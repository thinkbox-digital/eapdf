#!/usr/bin/env python3

import html
import hashlib
import ipaddress
import io
import importlib.util
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import uuid
import tempfile
import time
from contextlib import contextmanager
from contextvars import ContextVar
from collections import OrderedDict
from pathlib import Path, PurePosixPath, PureWindowsPath

from flask import Flask, Response, request, send_file, url_for, has_request_context
from werkzeug.middleware.proxy_fix import ProxyFix
from werkzeug.exceptions import HTTPException
from werkzeug.security import check_password_hash
from urllib.parse import urlsplit
from rule_documentation import specification_context_html
from validation_worker import available_memory_bytes, disk_allowances


ROOT = Path(__file__).resolve().parent
VALIDATOR_PATH = ROOT / "validate_eapdf.py"
RULE_TEXT_PATH = ROOT / "validator_rule_text.json"
VERSION_PATH = ROOT / "VERSION"
WEB_TMP_ROOT = ROOT / ".validator_web_tmp"
GENERATOR_TMP_ROOT = ROOT / ".generator_web_tmp"
OUT_PDF_ROOT = ROOT / "out"

def workload_seconds(size, configured, minimum=600):
    return max(1, configured) if configured else min(86400, max(minimum, 600 + size // (128 * 1024)))


def input_bytes(path):
    path = Path(path)
    if path.is_file():
        return path.stat().st_size
    if path.is_dir():
        return sum(p.stat().st_size for p in path.rglob("*") if p.is_file())
    return 0


RETENTION_SECONDS = max(60, int(os.environ.get("VALIDATOR_RETENTION_SECONDS", "86400")))
MAX_CONCURRENT_JOBS = max(1, int(os.environ.get("VALIDATOR_MAX_CONCURRENT_JOBS", "2")))
MAX_GENERATOR_UPLOAD_FILES = max(1, int(os.environ.get("GENERATOR_MAX_UPLOAD_FILES", "100")))
MAX_UPLOAD_FILES = int(os.environ.get("VALIDATOR_MAX_UPLOAD_FILES", "3"))
_max_content_length_raw = os.environ.get("VALIDATOR_MAX_CONTENT_LENGTH", "").strip()
MAX_CONTENT_LENGTH = max(1, int(_max_content_length_raw)) if _max_content_length_raw else min(64 * 1024**2, shutil.disk_usage(ROOT).free // 8)
VALIDATION_TIMEOUT = int(os.environ.get("VALIDATOR_JOB_TIMEOUT", "0"))
REQUEST_TIMEOUT = max(1, int(os.environ.get("VALIDATOR_REQUEST_TIMEOUT", "86400")))
JOB_DEADLINE = ContextVar("validator_job_deadline", default=None)
VALIDATION_MEMORY_MB = int(os.environ.get("VALIDATOR_JOB_MEMORY_MB", "0"))
GENERATION_TIMEOUT = int(os.environ.get("GENERATOR_JOB_TIMEOUT", "0"))
GENERATION_MEMORY_MB = int(os.environ.get("GENERATOR_JOB_MEMORY_MB", "0"))
MAX_WORK_FILE_BYTES, MAX_JOB_WORK_BYTES = disk_allowances(ROOT, MAX_CONCURRENT_JOBS)
MAX_LOG_BYTES = 4 * 1024 * 1024
VALIDATION_WORKER_SCRIPT = """
import runpy
import sys
import os
os.environ["EAPDF_VALIDATION_WORKER"] = "1"
if sys.platform != "win32":
    import resource
    limit = int(sys.argv[4]) * 1024 * 1024
    hard_limit = max(limit, int(os.environ.get("VALIDATOR_DOCKER_CLIENT_MEMORY_MB", "4096")) * 1024**2)
    resource.setrlimit(resource.RLIMIT_AS, (limit, hard_limit))
    file_limit = int(sys.argv[5])
    resource.setrlimit(resource.RLIMIT_FSIZE, (file_limit, file_limit))
    inherited_soft, inherited_hard = resource.getrlimit(resource.RLIMIT_NOFILE)
    requested = max(1, int(os.environ.get("VALIDATOR_MAX_OPEN_FILES", str(inherited_soft if inherited_hard == resource.RLIM_INFINITY else inherited_hard))))
    descriptor_limit = requested if inherited_hard == resource.RLIM_INFINITY else min(requested, inherited_hard)
    resource.setrlimit(resource.RLIMIT_NOFILE, (descriptor_limit, descriptor_limit))
from contextlib import redirect_stdout
from pathlib import Path
report_json = Path(sys.argv[3] + ".json")
sys.argv = [sys.argv[1], sys.argv[2], "--json", "--html", sys.argv[3]]
with report_json.open("w") as output, redirect_stdout(output):
    runpy.run_path(sys.argv[0], run_name="__main__")
"""


def managed_path(path):
    resolved = Path(path).resolve()
    if any(resolved.is_relative_to((ROOT / directory).resolve()) for directory in (".validator_web_tmp", ".generator_web_tmp")):
        return resolved
    if resolved.is_relative_to(OUT_PDF_ROOT.resolve()) and ".web-jobs" in resolved.relative_to(OUT_PDF_ROOT.resolve()).parts:
        return resolved
    return None


class CapabilityRegistry:
    def __init__(self, kind):
        self.kind = kind

    def __setitem__(self, token, path):
        directory = ROOT / ".validator_web_tmp" / "registry"
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not re.fullmatch(r"[0-9a-f]{32}", token):
            raise ValueError("Invalid result identity")
        lifetime = RETENTION_SECONDS + REQUEST_TIMEOUT if self.kind == "job" else RETENTION_SECONDS
        record = {"path": str(Path(path).resolve()), "kind": self.kind,
                  "expires": time.time() + lifetime}
        destination = directory / (token + ".json")
        with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as temporary:
            json.dump(record, temporary)
        os.replace(temporary.name, destination)

    def get(self, token, default=None):
        if not re.fullmatch(r"[0-9a-f]{32}", token):
            return default
        try:
            record = json.loads((ROOT / ".validator_web_tmp" / "registry" / (token + ".json")).read_text())
            if record["kind"] == self.kind and record["expires"] > time.time():
                return Path(record["path"])
        except (OSError, ValueError, KeyError, TypeError):
            pass
        return default


GENERATED_FILE_MAP = CapabilityRegistry("generated")
VALIDATION_REPORT_MAP = CapabilityRegistry("report")
WEB_JOB_MAP = CapabilityRegistry("job")


def cleanup_expired_jobs():
    directory = ROOT / ".validator_web_tmp" / "registry"
    for entry in directory.glob("*.json"):
        try:
            record = json.loads(entry.read_text())
            if record["expires"] > time.time():
                continue
            path = managed_path(record["path"])
            if path is not None:
                if record["kind"] == "job" and path.is_dir():
                    shutil.rmtree(path)
                elif path.is_file():
                    path.unlink()
            entry.unlink(missing_ok=True)
        except (OSError, ValueError, KeyError, TypeError):
            continue


class JobCapacityError(ValueError):
    pass


@contextmanager
def job_slot():
    if os.name != "posix":
        raise ValueError("Bounded web jobs require POSIX file locking on this deployment.")
    import fcntl
    directory = ROOT / ".validator_web_tmp" / "slots"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for index in range(MAX_CONCURRENT_JOBS):
        lock = (directory / str(index)).open("a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            continue
        try:
            deadline_token = JOB_DEADLINE.set(time.monotonic() + REQUEST_TIMEOUT)
            try:
                yield
            finally:
                JOB_DEADLINE.reset(deadline_token)
        finally:
            lock.close()
        return
    raise JobCapacityError("All job slots are busy. Please try again shortly.")


def application_url(endpoint, **values):
    if has_request_context():
        return url_for(endpoint, **values)
    with app.test_request_context():
        return url_for(endpoint, **values)


def read_app_version():
    try:
        value = VERSION_PATH.read_text(encoding="utf-8").strip()
        return value or "dev"
    except Exception:
        return "dev"


APP_VERSION = read_app_version()


def resolve_generator_jar():
    candidates = [
        ROOT / "dist" / f"emailtopdfa-{APP_VERSION}.jar",
        ROOT / "target" / f"emailtopdfa-{APP_VERSION}.jar",
    ]
    available = [path for path in candidates if path.is_file()]
    if available:
        return max(available, key=lambda path: path.stat().st_mtime)
    built_jars = sorted(
        [
            path for path in (ROOT / "target").glob("emailtopdfa-*.jar")
            if not path.name.endswith("-gui.jar")
        ],
        key=lambda p: p.stat().st_mtime,
        reverse=True,
    ) if (ROOT / "target").exists() else []
    if built_jars:
        return built_jars[0]
    return candidates[0]


JAR_PATH = resolve_generator_jar()


def load_validator():
    spec = importlib.util.spec_from_file_location("validate_eapdf", VALIDATOR_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_rule_text():
    if not RULE_TEXT_PATH.exists():
        return {}
    try:
        return json.loads(RULE_TEXT_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {}


VALIDATOR = None
VALIDATOR_MTIME_NS = None
VALIDATOR_RULE_TEXT_MTIME_NS = None
RULE_TEXT = load_rule_text()
RULE_TEXT_MTIME_NS = RULE_TEXT_PATH.stat().st_mtime_ns if RULE_TEXT_PATH.exists() else None
app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = MAX_CONTENT_LENGTH
class TrustedProxyHeaders:
    """Forwarded headers are meaningful only from explicitly configured peers."""
    def __init__(self, application):
        self.application = application
        self.forwarded = ProxyFix(application, x_for=1, x_proto=1, x_host=1, x_port=1, x_prefix=1)

    def __call__(self, environ, start_response):
        configured = os.environ.get("VALIDATOR_TRUSTED_PROXIES", "").split(",")
        try:
            peer = ipaddress.ip_address(environ.get("REMOTE_ADDR", ""))
            trusted = any(peer in ipaddress.ip_network(value.strip(), strict=False)
                          for value in configured if value.strip())
        except ValueError:
            trusted = False
        return (self.forwarded if trusted else self.application)(environ, start_response)


app.wsgi_app = TrustedProxyHeaders(app.wsgi_app)
try:
    VALIDATOR = load_validator()
    VALIDATOR_MTIME_NS = VALIDATOR_PATH.stat().st_mtime_ns
    VALIDATOR_RULE_TEXT_MTIME_NS = RULE_TEXT_PATH.stat().st_mtime_ns if RULE_TEXT_PATH.exists() else None
except Exception:
    pass


def get_validator():
    global VALIDATOR, VALIDATOR_MTIME_NS, VALIDATOR_RULE_TEXT_MTIME_NS
    current_mtime_ns = VALIDATOR_PATH.stat().st_mtime_ns
    current_rule_mtime_ns = RULE_TEXT_PATH.stat().st_mtime_ns if RULE_TEXT_PATH.exists() else None
    if (VALIDATOR is None or VALIDATOR_MTIME_NS != current_mtime_ns
            or VALIDATOR_RULE_TEXT_MTIME_NS != current_rule_mtime_ns):
        VALIDATOR = load_validator()
        VALIDATOR_MTIME_NS = current_mtime_ns
        VALIDATOR_RULE_TEXT_MTIME_NS = current_rule_mtime_ns
    return VALIDATOR


def get_rule_text():
    global RULE_TEXT, RULE_TEXT_MTIME_NS
    current_mtime_ns = RULE_TEXT_PATH.stat().st_mtime_ns if RULE_TEXT_PATH.exists() else None
    if RULE_TEXT_MTIME_NS != current_mtime_ns:
        RULE_TEXT = load_rule_text()
        RULE_TEXT_MTIME_NS = current_mtime_ns
    return RULE_TEXT


def all_rule_codes():
    validator_codes = list(get_validator().ALL_RULE_CODES)
    rule_text_codes = list(get_rule_text().keys())
    return list(dict.fromkeys(rule_text_codes + validator_codes))


LEVEL_RANK = {
    "FAIL": 4,
    "WARN": 3,
    "PASS": 2,
    "N/A": 1,
}


def collapse_items(items):
    validator = get_validator()
    rows = OrderedDict()
    for code in all_rule_codes():
        rows[code] = {
            "code": code,
            "level": "N/A",
            "message": "Rule not applicable for this PDF",
            "details": None,
            "spec_refs": validator.spec_references_for(code),
        }

    reported = set()
    for item in items:
        code = item["code"]
        row = rows.setdefault(
            code,
            {
                "code": code,
                "level": item["level"],
                "message": item["message"],
                "details": item.get("details"),
                "spec_refs": validator.spec_references_for(code),
            },
        )
        row.setdefault("occurrences", []).append({
            "level": item["level"], "message": item["message"], "details": item.get("details")})
        if code not in reported or LEVEL_RANK[item["level"]] > LEVEL_RANK[row["level"]]:
            row["level"] = item["level"]
            row["message"] = item["message"]
            row["details"] = item.get("details")
        elif LEVEL_RANK[item["level"]] == LEVEL_RANK[row["level"]]:
            existing = row["message"]
            if item["message"] != existing:
                row["message"] = existing + " | " + item["message"]
            if row["details"] is None:
                row["details"] = item.get("details")
        reported.add(code)
    for code, row in rows.items():
        if code not in reported:
            row.update(level="WARN", message="No validation result was provided for this rule",
                       details={"state":"unverified","reason":"Non-applicability has not been established."})
    return list(rows.values())


def format_details(details):
    if details is None:
        return ""
    return json.dumps(details, ensure_ascii=False, indent=2, sort_keys=True)


def render_value(value):
    if value is None:
        return ""
    return html.escape(str(value))


def render_nested_value(value):
    if isinstance(value, dict):
        if not value:
            return "<div class='detail-value-empty'></div>"
        return (
            "<div class='detail-nested'>"
            + "".join(
                "<div class='detail-nested-row'>"
                f"<div class='detail-nested-key'>{html.escape(str(k))}</div>"
                f"<div class='detail-nested-value'>{render_nested_value(v)}</div>"
                "</div>"
                for k, v in value.items()
            )
            + "</div>"
        )
    if isinstance(value, list):
        if not value:
            return "<div class='detail-value-empty'></div>"
        if all(not isinstance(item, (dict, list)) for item in value):
            return "<div class='detail-list'>" + "<br>".join(render_value(item) for item in value) + "</div>"
        return (
            "<div class='detail-nested'>"
            + "".join(
                "<div class='detail-nested-row'>"
                f"<div class='detail-nested-key'>{idx + 1}</div>"
                f"<div class='detail-nested-value'>{render_nested_value(item)}</div>"
                "</div>"
                for idx, item in enumerate(value)
            )
            + "</div>"
        )
    return render_value(value)


def render_emphasized_text(text):
    escaped = html.escape(text)
    return re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", escaped)


class SafeTemplateDict(dict):
    def __missing__(self, key):
        return ""


def humanize_field_name(name):
    raw_name = str(name).strip()
    if "_" in raw_name:
        return raw_name.upper()
    text = raw_name.replace("-", " ").strip()
    acronyms = {"PDF", "XMP", "AF", "DPM", "RFC822", "URI", "GUID", "MD5", "FOAF", "N/A"}
    words = []
    for raw in text.split():
        upper = raw.upper()
        if upper in acronyms:
            words.append(upper)
        elif raw.isupper() and len(raw) <= 5:
            words.append(raw)
        else:
            words.append(raw[:1].upper() + raw[1:])
    return " ".join(words)


def render_detail_rows(data):
    rows = []
    for key, value in data.items():
        rows.append(
            "<div class='detail-row'>"
            f"<div class='detail-inline'><span class='detail-key-inline'>{html.escape(str(key))}:</span> <span class='detail-value-inline'>{render_nested_value(value)}</span></div>"
            "</div>"
        )
    return "".join(rows)


def render_details(details, level=None, code=None):
    if details is None:
        return "<div class='detail-empty'></div>"
    if not isinstance(details, dict):
        return (
            "<div class='detail-group'>"
            "<div class='detail-label'>Observed</div>"
            f"<div class='detail-rows'>{render_detail_rows(OrderedDict([('value', details)]))}</div>"
            "</div>"
        )

    observed = OrderedDict()
    expected = OrderedDict()
    context = OrderedDict()
    why_not_applied = ""

    if level in (None, "N/A") and set(details.keys()) == {"reason"} and isinstance(details.get("reason"), str):
        reason_text = details["reason"].strip()
        if "Context:" in reason_text:
            why_not_applied, raw_context = reason_text.split("Context:", 1)
            why_not_applied = why_not_applied.strip()
            for piece in raw_context.split(";"):
                piece = piece.strip().rstrip(".")
                if not piece:
                    continue
                if "=" in piece:
                    key, value = piece.split("=", 1)
                    context[key.strip()] = value.strip()
                else:
                    context[piece] = ""
        else:
            why_not_applied = reason_text

        if why_not_applied:
            lowered = why_not_applied.lower()
            if lowered.startswith("this rule was not applied because"):
                why_not_applied = "Not applied because" + why_not_applied[len("This rule was not applied because"):]
            elif not lowered.startswith("not applied because"):
                why_not_applied = f"Not applied because {why_not_applied[:1].lower()}{why_not_applied[1:]}" if len(why_not_applied) > 1 else "Not applied because this rule did not apply to this PDF."

    expected_keys = {"expected", "allowed", "allowed_tags", "expected_prefix"}
    context_predicates = (
        lambda key: key.startswith("source_"),
        lambda key: key.startswith("heuristic_"),
        lambda key: key.endswith("_count"),
        lambda key: key.endswith("_emails"),
        lambda key: key.endswith("_attachments"),
    )

    # Namespace rows need a literal split between observed bindings and required bindings.
    if "expected_prefix" in details:
        if key := details.get("prefix"):
            observed["prefix"] = key
        if "actual_prefixes" in details:
            observed["actual_prefixes"] = details.get("actual_prefixes")
        if "uri" in details:
            target = observed if ("prefix" in details or "actual_prefixes" in details) else expected
            target["required_uri" if target is expected else "uri"] = details["uri"]
        expected["required_prefix"] = details["expected_prefix"]

    for key, value in details.items():
        if key == "reason" and why_not_applied:
            continue
        if key in {"prefix", "actual_prefixes", "uri", "expected_prefix"} and "expected_prefix" in details:
            continue
        if key in expected_keys or key.startswith("expected_") or key.startswith("required_"):
            expected[key] = value
        elif any(test(key) for test in context_predicates):
            context[key] = value
        else:
            label = {"pages": "PDF page numbers (1-based)", "count": "Affected page count"}.get(key, key) if code == "PAGE_DPART_PRESENT" else key
            observed[label] = value

    if not expected and code and level in {"FAIL", "WARN"}:
        inferred = infer_expected_value({"code": code, "level": level, "details": details}, details, {})
        if inferred not in (None, ""):
            expected["value"] = inferred

    parts = []
    if why_not_applied:
        parts.append(
            "<div class='detail-group'>"
            "<div class='detail-label'>Why Not Applied</div>"
            f"<div class='detail-rows'>{render_detail_rows(OrderedDict([('explanation', why_not_applied)]))}</div>"
            "</div>"
        )
    if observed or level == "FAIL":
        observed_html = render_detail_rows(observed) if observed else "<div class='detail-value-empty'></div>"
        parts.append(
            "<div class='detail-group'>"
            "<div class='detail-label'>Observed</div>"
            f"<div class='detail-rows'>{observed_html}</div>"
            "</div>"
        )
    if expected:
        parts.append(
            "<div class='detail-group'>"
            "<div class='detail-label'>Expected</div>"
            f"<div class='detail-rows'>{render_detail_rows(expected)}</div>"
            "</div>"
        )
    if context and code != "OPEN":
        parts.append(
            "<div class='detail-group'>"
            f"<div class='detail-label'>{'Context Flags' if why_not_applied else 'Comparison Context'}</div>"
            f"<div class='detail-rows'>{render_detail_rows(context)}</div>"
            "</div>"
        )
    if not parts:
        parts.append(
            "<div class='detail-group'>"
            "<div class='detail-label'>Observed</div>"
            f"<div class='detail-rows'>{render_detail_rows(OrderedDict([('value', format_details(details))]))}</div>"
            "</div>"
        )
    return "".join(parts)


def normalize_plain_language_context(row):
    details = row.get("details")
    context = {"field": humanize_field_name(row.get("code", ""))}

    if details is not None and not isinstance(details, dict):
        if isinstance(details, list):
            if details and all(isinstance(item, dict) for item in details):
                parts = []
                for item in details:
                    bits = [f"{k}={v}" for k, v in item.items()]
                    parts.append(", ".join(bits))
                context["actual"] = "; ".join(parts)
            else:
                context["actual"] = ", ".join(str(item) for item in details)
        else:
            context["actual"] = str(details)

    if isinstance(details, dict):
        reason = details.get("reason")
        if isinstance(reason, str) and reason.strip():
            explanation = reason.split("Context:", 1)[0].strip().rstrip(".")
            lower = explanation.lower()
            if lower.startswith("not applied because "):
                explanation = explanation[len("Not applied because "):]
            context["explanation"] = explanation

        observed_candidates = []
        expected_candidates = []

        for key, value in details.items():
            if key == "reason":
                continue
            if key in {"expected", "expected_prefix", "required_prefix", "required_uri", "allowed", "allowed_tags"}:
                expected_candidates.append((key, value))
                continue
            if key.startswith("source_") or key.startswith("heuristic_") or key.endswith("_count") or key.endswith("_emails") or key.endswith("_attachments"):
                continue
            observed_candidates.append((key, value))

        for key in ("actual", "value", "PageMode", "NonFullScreenPageMode", "prefix", "catalog_version", "header_version", "part", "conformance"):
            if key in details and details[key] not in (None, "", []):
                value = details[key]
                if isinstance(value, list):
                    value = ", ".join(str(item) for item in value)
                context.setdefault("actual", value)
                break

        if "actual" not in context and observed_candidates:
            key, value = observed_candidates[0]
            if isinstance(value, list):
                value = ", ".join(str(item) for item in value)
            elif isinstance(value, dict):
                value = ", ".join(f"{k}={v}" for k, v in value.items())
            elif value == "":
                value = "missing"
            context["actual"] = value if value not in (None, []) else ""

        for key in ("expected", "expected_prefix", "required_prefix"):
            if key in details and details[key] not in (None, "", []):
                context.setdefault("expected", details[key])
                break

        if "uri" in details and details["uri"] not in (None, "", []):
            context["uri"] = details["uri"]
        if "required_uri" in details and details["required_uri"] not in (None, "", []):
            context["required_uri"] = details["required_uri"]
        elif "uri" in details and "expected_prefix" in details and details["uri"] not in (None, "", []):
            context["required_uri"] = details["uri"]
        if "pages" in details:
            pages = details["pages"]
            if isinstance(pages, list):
                context["pages"] = ", ".join(str(page) for page in pages)
            elif pages not in (None, ""):
                context["pages"] = str(pages)
        if "count" in details and details["count"] not in (None, ""):
            context["count"] = details["count"]
        for key in ("count", "profile", "source_email_has_attachments", "Filename", "Message-ID", "Mail_GUID", "ContentType", "Content-Type", "SizeInBytes", "NumberAttachments"):
            if key in details and details[key] not in (None, "", []):
                context[key] = details[key]

        field_key = ""
        for key, value in observed_candidates:
            if key in {"value", "actual", "pages", "count"}:
                continue
            field_key = key
            break
        if not field_key and expected_candidates:
            field_key = expected_candidates[0][0]
        if field_key:
            context["field"] = humanize_field_name(field_key)

    context.setdefault("message", row.get("message", ""))
    context.setdefault("level", row.get("level", ""))
    if "actual" not in context or context["actual"] in (None, ""):
        message = row.get("message", "").lower()
        if "missing" in message:
            context["actual"] = "missing"
        elif "not present" in message:
            context["actual"] = "missing"
        elif row.get("level") == "PASS":
            context["actual"] = "present"
        elif row.get("level") == "WARN":
            context["actual"] = "present but needs review"
        else:
            context["actual"] = "not valid"
    if context.get("actual") in {"present", "not valid", "present but needs review"}:
        msg = row.get("message", "")
        m = re.search(r"\bis ([^.,]+)", msg)
        if m:
            context["actual"] = m.group(1).strip()
    inferred_expected = infer_expected_value(row, details if isinstance(details, dict) else {}, context)
    if inferred_expected not in (None, ""):
        context["expected"] = inferred_expected
    elif "expected" not in context or context["expected"] in (None, ""):
        if context.get("required_prefix"):
            context["expected"] = context["required_prefix"]
        elif context.get("required_uri"):
            context["expected"] = context["required_uri"]
        else:
            context["expected"] = "the required value"
    synthetic_unquoted = {
        "missing",
        "present",
        "present but needs review",
        "not valid",
        "the required value",
    }
    for key, value in list(context.items()):
        if key in {"field", "message", "level", "explanation"}:
            continue
        if value in (None, ""):
            continue
        if str(value) in synthetic_unquoted:
            context[key] = str(value)
        else:
            context[key] = f'"{value}"'
    return context


def infer_expected_value(row, details, context):
    code = row.get("code", "")
    profile = details.get("profile")
    allowed = details.get("allowed")
    if isinstance(allowed, (list, tuple)) and allowed:
        return " or ".join(str(value) for value in allowed)

    if code in {"PDF_HEADER_VERSION_READ", "PDF_EFFECTIVE_VERSION", "PDF_CATALOG_VERSION"}:
        return "1.7 or 2.0"
    if code == "PDFA_PART":
        return "3 or 4"
    if code == "PDFA_CONFORMANCE":
        part = str(details.get("part", "")).strip()
        if part == "3":
            return "PDF/A-3a or PDF/A-3u"
        if part == "4":
            return "PDF/A-4, PDF/A-4f, or PDF/A-4e"
        return "a supported EA-PDF PDF/A conformance"
    if code == "OUTPUT_INTENT":
        return "/S = /GTS_PDFA1"
    if code == "DOCINFO_PRESENT":
        part = str(details.get("part", "")).strip()
        if part == "3":
            return "a Document Info dictionary"
    if code == "DOCINFO_KEYWORDS" or code == "XMP_KEYWORDS":
        return "a keyword value containing EA-PDF"
    if code in {"DOCINFO_TITLE_MATCH", "DOCINFO_PRODUCER_MATCH", "DOCINFO_AUTHOR_MATCH"}:
        return "a value matching the corresponding XMP property"
    if code == "CATALOG_PIECEINFO":
        return "PieceInfo absent"
    if code in {"PAGEMODE_PRESENT", "NONFULLSCREENPAGEMODE_PRESENT", "PROFILE_PAGEMODE", "PROFILE_NFSM"}:
        if profile in ("m", "mi"):
            return "UseOutlines"
        if profile in ("c", "ci"):
            return "UseAttachments"
        if details.get("expected") not in (None, ""):
            return details.get("expected")
    if code == "VIEWER_PREFS":
        return "a ViewerPreferences dictionary"
    if code == "DISPLAY_DOCTITLE":
        return "true"
    if code == "AF_RULE":
        if profile and str(profile).endswith("i"):
            return "catalog AF omitted or limited to isolated-profile usage"
        return "a catalog AF array with at least one entry"
    if code == "SOURCE_AF":
        return "at least one Source AF file" if not str(profile).endswith("i") else None
    if code == "SOURCE_IN_ISOLATED":
        return None  # Source presence alone cannot decide isolated provenance.
    if code == "OUTLINES_PRESENT":
        return "an Outlines entry in the document catalog"
    if code == "OUTLINE_ITEMS_PRESENT":
        return "at least one outline item"
    if code == "PROFILE_COLLECTION":
        return "a Collection dictionary"
    if code == "COLLECTION_REQUIRED":
        return "a Collection dictionary"
    if code == "COLLECTION_FORBIDDEN":
        return "Collection absent"
    if code == "COLLECTION_EXTENSIONS":
        return "Adobe Extension Level 5 metadata for PDF 1.7 collections"
    if code == "COLLECTION_D_FORBIDDEN":
        return "Collection.D absent"
    if code == "COLLECTION_VIEW_FORBIDDEN":
        return "a Collection.View value other than H"
    if code == "COLLECTION_NAVIGATOR_FORBIDDEN":
        return "Navigator without SWF or APIVersion"
    if code == "COLLECTION_SCHEMA_PRESENT":
        return "a Collection schema dictionary"
    if code == "COLLECTION_SCHEMA_FIELD":
        field_name = details.get("field")
        subtype = details.get("expected_subtype")
        if field_name and subtype:
            return f"Collection schema field {field_name} with subtype {subtype}"
    if code == "COLLECTION_SCHEMA_E_FALSE":
        return "E = false when the Collection custom field declares E"
    if code == "DPM_ROOTNODE_PRESENT":
        return "a DPartRootNode dictionary"
    if code == "DPM_KEY_PREFIX":
        return "EA-PDF-defined DPM keys use the registered /Mail_ prefix; unrelated extension keys may remain"
    if code == "DPM_MAIL_GUID_PRESENT":
        return "a nonempty Mail_GUID PDF text string"
    if code == "DPM_MAIL_GUID_MATCH":
        return "a Mail_GUID value matching the XMP message identifier"
    if code == "DPM_EMAIL_CONTENT_SET_OWNERSHIP":
        return "DPart ancestry matching independently established email-content ownership"
    if code == "DPM_MAIL_MESSAGEID_MATCH":
        return "a Mail_MessageID value matching the XMP message identifier"
    if code == "DPM_LEAF_REQUIRED":
        return "a DPart leaf node for each required content set"
    if code == "DPM_CONTENTSETTYPE_PRESENT":
        return "a Mail_ContentSetType PDF name"
    if code == "DPM_DESC_REQUIRED":
        return "a Mail_Desc text string"
    if code == "DPM_SUBTYPE_REQUIRED":
        return "Mail_Subtype containing the rendered source media type and its parameters"
    if code == "DPM_ATTACHMENTLIST_FORBIDDEN":
        return "Mail_ContentSetType other than AttachmentList in c/ci collections"
    if code == "STRUCTTREE_PRESENT":
        return "a StructTreeRoot"
    if code == "STRUCTTREE_ROOT_DOCUMENT":
        return "one root structure element whose resolved standard role is Document"
    if code == "ROLEMAPNS_PRESENT":
        return "a RoleMapNS dictionary"
    if code == "MAIL_MESSAGE_COUNT":
        return "the profile-appropriate number of Mail_Message elements"
    if code == "MAIL_MESSAGE_CHILDREN":
        return "required Mail_Message child elements"
    if code == "MAIL_CONTENTSET_COUNT":
        return "the profile-appropriate number of Mail_ContentSet elements"
    if code == "MAIL_CONTENTGROUP_CHILDREN":
        return "required Mail_ContentGroup child elements"
    if code == "MAIL_FIELD_CHILDREN":
        return "required Mail_Field child elements"
    if code == "MAIL_FIELDVALUE_WITH_NAME":
        return "each Mail_FieldValue to have a matching field name"
    if code == "MAIL_TAG_PROFILE_USE":
        return "Mail_* tags used only in the profiles that allow them"
    if code == "USERPROPS_NAME_ASCII":
        return "an ASCII UserProperties Name"
    if code == "USERPROPS_TYPE":
        return details.get("expected")
    if code == "USERPROPS_TAG_SCOPE":
        allowed_tags = details.get("allowed_tags")
        if isinstance(allowed_tags, (list, tuple)) and allowed_tags:
            return "use only on " + ", ".join(str(tag) for tag in allowed_tags)
    if code == "XMP_PRESENT":
        return "an XMP metadata packet"
    if code == "XMP_NS_PREFIX":
        if details.get("expected_prefix") and details.get("uri"):
            return f"namespace {details['uri']} with prefix {details['expected_prefix']}"
    if code == "XMP_CREATOR":
        return "a populated first dc:creator name"
    if code == "XMP_CREATOR_SEMANTICS":
        return "creator attribution supported by independent provenance"
    if code == "XMP_PRODUCER":
        return "pdf:Producer present"
    if code == "XMP_PRODUCER_SEMANTICS":
        return "attribution to the PDF creation tool supported by independent provenance"
    if code == "XMP_CREATEDATE":
        return "xmp:CreateDate present"
    if code == "XMP_CREATEDATE_SEMANTICS":
        return "resource creation time supported by independent provenance"
    if code == "XMP_TITLE":
        return "dc:title present"
    if code == "EAPDF_VERSION":
        return "1"
    if code == "EAPDF_REV":
        return "2024"
    if code == "EAPDF_CONFORMANCE":
        return "1s, 1si, 1m, 1mi, 1c, or 1ci"
    if code == "ASSET_XMP":
        return "an XMP asset record"
    if code == "ASSET_XMP_FIELDS":
        return "required asset XMP fields"
    if code == "ASSET_SIZE_TYPE":
        return "an integer SizeInBytes value"
    if code == "ASSET_NUMBERMESSAGES_TYPE":
        return "a nonnegative integer NumberMessages value"
    if code == "ASSET_NUMBERMESSAGES_MATCH_RENDERED":
        return details.get("rendered_messages", "the independently established rendered-message count for this source asset")
    if code == "ASSET_CHECKSUM_FORM":
        return "a 32-character ASCII hexadecimal MD5 checksum (upper or lower case)"
    if code == "ASSET_CONTENTTYPE":
        return "source-asset ContentType text containing its media type and required parameters"
    if code == "ASSET_CONTENTTYPE_MATCH":
        return "the media type and parameters of the independently associated embedded Source"
    if code == "ATTACHMENT_XMP_CONTENTTYPE_MATCH":
        return "the Content-Type of the independently associated original attachment, including original parameters"
    if code == "ASSET_CONTENTTYPE_GENERIC":
        return "a specific non-generic MIME type"
    if code == "ASSET_MATCH":
        return "an asset record matching an embedded file"
    if code == "ASSET_SIZE_MATCH":
        return "SizeInBytes matching the embedded file size"
    if code == "MESSAGE_SENT_FORMAT":
        return "an ISO 8601 date-time value"
    if code in {"MESSAGE_TO_FOAF", "MESSAGE_FROM_FOAF", "MESSAGE_CC_FOAF", "MESSAGE_BCC_FOAF"}:
        return "a foaf:Agent structure"
    if code in {"MESSAGE_TO_XMP", "MESSAGE_RAW_TO_XMP", "MESSAGE_FROM_XMP", "MESSAGE_RAW_FROM_XMP", "MESSAGE_SENT_XMP", "MESSAGE_RAW_SENT_XMP", "MESSAGE_SUBJECT_XMP", "MESSAGE_ID_XMP", "MAIL_GUID_XMP", "MESSAGE_SIZE_XMP", "MESSAGE_ATTACHMENT_COUNT_XMP", "MESSAGE_CC_XMP", "MESSAGE_RAW_CC_XMP", "MESSAGE_BCC_XMP", "MESSAGE_RAW_BCC_XMP", "MESSAGE_INREPLYTO_XMP"}:
        return "the field presence, value type and representation described by this rule"
    if code == "ATTACHMENT_XMP_CONTAINER":
        return "a well-formed XMP attachment-record container when supplied"
    if code == "ATTACHMENT_XMP_FIELDS":
        return "required attachment XMP fields"
    if code == "ATTACHMENT_XMP_SIZE_TYPE":
        return "an integer attachment size"
    if code == "ATTACHMENT_XMP_IDENTIFIER":
        return "an attachment identifier value"
    if code == "ATTACHMENT_XMP_CHECKSUM_FORM":
        return "a 32-character ASCII hexadecimal MD5 checksum (upper or lower case)"
    if code == "PAGE_COUNT":
        return "at least one page"
    if details.get("expected_subtype") not in (None, ""):
        return details["expected_subtype"]
    if context.get("required_prefix"):
        return context["required_prefix"]
    if context.get("required_uri"):
        return context["required_uri"]
    return None


def render_plain_language_from_template(rule_text, row):
    template_map = (rule_text or {}).get("plain_language_template") or {}
    template = template_map.get(row.get("level"))
    if not template:
        return ""
    context = normalize_plain_language_context(row)
    try:
        return template.format_map(SafeTemplateDict(context)).strip()
    except Exception:
        return template


def restate_spec_excerpt_plainly(text):
    if not text:
        return ""
    text = re.sub(r"\*\*(.+?)\*\*", r"\1", text).strip()
    text = re.sub(r"\s+", " ", text)
    text = text.replace(" SHALL NOT ", " must not ")
    text = text.replace(" shall not ", " must not ")
    text = text.replace(" SHALL ", " must ")
    text = text.replace(" shall ", " must ")
    text = text.replace(" SHOULD NOT ", " should not ")
    text = text.replace(" should not ", " should not ")
    text = text.replace(" SHOULD ", " should ")
    text = text.replace(" should ", " should ")
    text = text.replace(" MAY NOT ", " may not ")
    text = text.replace(" may not ", " may not ")
    text = text.replace(" MAY ", " may ")
    text = text.replace(" may ", " may ")
    if text and text[-1] not in ".!?":
        text += "."
    return text


def render_rule_explanation(row, refs=None):
    return ""


def render_spec_refs(refs, row):
    if row.get("catalogue_notice"):
        return "<p>" + html.escape(row["catalogue_notice"]) + "</p>"
    rule_text = get_rule_text().get(row.get("code", ""), {})
    if not refs and rule_text:
        refs = [{
            "spec": rule_text.get("spec_section", "EA-PDF 1.0"),
            "excerpt": rule_text.get("spec_excerpt", ""),
        }]
    if not refs:
        return ""
    parts = []
    for ref in refs:
        spec_basis, validator_note = split_spec_excerpt(ref["excerpt"])
        if rule_text.get("spec_excerpt"):
            spec_basis = rule_text["spec_excerpt"]
        spec_label = normalize_spec_label(rule_text.get("spec_section", ref["spec"]))
        validator_note_html = (
            f"<div class='spec-note'><div class='spec-label'>Validator Note</div>{render_emphasized_text(validator_note)}</div>"
            if validator_note
            else ""
        )
        parts.append(
            "<div class='spec-ref'>"
            f"<div class='spec-meta'><strong>{html.escape(spec_label)}</strong></div>"
            "<div class='spec-label'>Reviewed summary — paraphrase, not a quotation</div>"
            f"<div class='spec-excerpt'>{render_emphasized_text(spec_basis)}</div>"
            f"{specification_context_html(rule_text)}"
            f"{render_rule_explanation(row, [ref])}"
            f"{validator_note_html}"
            "</div>"
        )
    return "".join(parts)


def split_spec_excerpt(text):
    markers = [
        "For validator design, this means:",
        "The summary notes ",
        "These findings document ",
        "The extracted page is truncated",
        "For a first validator pass,",
    ]
    for marker in markers:
        idx = text.find(marker)
        if idx != -1:
            spec_basis = text[:idx].strip()
            validator_note = text[idx:].strip()
            return spec_basis, validator_note
    return text, ""


def normalize_spec_label(label):
    prefix = "EA-PDF 1.0 sections "
    if not label.startswith(prefix):
        return label
    raw_sections = [part.strip() for part in label[len(prefix):].split(",")]

    def sort_key(section):
        try:
            major, minor = section.split(".", 1)
            return (int(major), int(minor))
        except Exception:
            return (9999, 9999)

    ordered = sorted(raw_sections, key=sort_key)
    return prefix + ", ".join(ordered)


def render_file_cell(row):
    occurrences = row.get("occurrences") or [row]
    finding_details = "".join(
        "<div class='finding-occurrence'>"
        + f"<p><strong>{html.escape(item['level'])}:</strong> {html.escape(item['message'])}</p>"
        + render_details(item.get("details"), item["level"], row["code"])
        + "</div>" for item in occurrences)
    return (
        f"<div class='file-cell' data-level='{html.escape(row['level'])}'>"
        f"<div class='status {row['level'].lower().replace('/', '-').replace(' ', '-')}'>{html.escape(row['level'])}</div>"
        f"<div class='reason'>{html.escape(row['message'])}</div>"
        f"<details class='detail-block'><summary>Finding</summary><div class='detail-resizer'>{finding_details}</div></details>"
        f"<details class='detail-block'><summary>Specification</summary>{render_spec_refs(row['spec_refs'], row)}</details>"
        "</div>"
    )


def effective_eapdf_status(summary, pdfa):
    base = summary.get("status", summary.get("eapdf_status", "INCOMPLETE"))
    if base == "FAIL":
        return "FAIL"
    if (pdfa or {}).get("status") == "FAIL":
        return "FAIL"
    if (pdfa or {}).get("status") != "PASS":
        return "INCOMPLETE"
    if not pdfa.get("ran") or pdfa.get("compliant") is not True or pdfa.get("profile_allowed") is not True:
        return "INCOMPLETE"
    return base


def effective_eapdf_fail_count(summary, pdfa):
    fail_count = int(summary.get("rule_fail", 0) or 0)
    if (pdfa or {}).get("status") == "FAIL":
        return max(1, fail_count)
    return fail_count


def register_validation_report(path):
    if not path:
        return None
    report_path = Path(path)
    if not report_path.is_absolute():
        report_path = (ROOT / report_path).resolve()
    else:
        report_path = report_path.resolve()
    if not report_path.is_file() or managed_path(report_path) is None:
        return None
    file_id = uuid.uuid4().hex
    VALIDATION_REPORT_MAP[file_id] = report_path
    return application_url("validation_report_file", report_id=file_id)


def attach_validation_report_link(result):
    link = register_validation_report(result.get("html_output"))
    if link:
        result["validation_report_link"] = link
    return result


def render_summary_strip(results):
    chips = []
    for index, result in enumerate(results, start=1):
        summary = result["summary"]
        pdfa = result.get("pdfa", {})
        display_name = result.get("display_name") or Path(summary["file"]).name
        verification_spec = summary.get("verification_spec", "")
        pdfa_status = pdfa.get("status", "SKIP")
        eapdf_status = effective_eapdf_status(summary, pdfa)
        eapdf_fail_count = effective_eapdf_fail_count(summary, pdfa)
        meta_html = (
            f"<div class='summary-chip-meta'>Verifying against EA-PDF {html.escape(verification_spec)}</div>"
            if verification_spec else ""
        )
        meta_html += "<div class='summary-chip-meta'>" + "Automated checks only; not EA-PDF certification" + "</div>"
        eapdf_counts = (
            f"<span>Fail {eapdf_fail_count}</span><span>Warn {summary['rule_warn']}</span>"
            f"<span>Pass {summary['rule_pass']}</span><span>N/A {summary['rule_na']}</span>"
        )
        report_link = result.get("validation_report_link")
        report_html = (
            f"<div class='summary-chip-meta'><a class='report-link' href='{html.escape(report_link)}' target='_blank' rel='noopener'>Open validation report</a></div>"
            if report_link else ""
        )
        chips.append(
            "<div class='summary-chip'>"
            f"<div class='summary-chip-label'>File {index}</div>"
            f"<div class='summary-chip-name'>{html.escape(display_name)}</div>"
            f"{meta_html}"
            f"{report_html}"
            f"<div class='summary-chip-line'>"
            f"<span class='summary-status {pdfa_status.lower()}'>{html.escape(pdfa_status)}</span>"
            f"<span class='summary-line-label'>PDF/A</span>"
            f"<span>{html.escape(pdfa.get('profile_name', '') or 'No PDF/A profile')}</span>"
            "</div>"
            f"<div class='summary-chip-line'>"
            f"<span class='summary-status {eapdf_status.lower()}'>{html.escape(eapdf_status)}</span>"
            f"<span class='summary-line-label'>EA-PDF</span>"
            f"{eapdf_counts}"
            "</div>"
            f"<div class='summary-chip-pdfa'>{html.escape(pdfa.get('message', ''))}</div>"
            "</div>"
        )
    return "<section class='summary-strip'>" + "".join(chips) + "</section>"


def bounded_subprocess(command, timeout, cwd=None, env=None, logs=None, work_roots=()):
    deadline = JOB_DEADLINE.get()
    if deadline is not None:
        timeout = min(timeout, deadline - time.monotonic())
    if timeout <= 0:
        raise ValueError("Job exceeded its time limit.")
    finishes = time.monotonic() + timeout
    roots = tuple(Path(p) for p in work_roots) + ((Path(logs),) if logs is not None else ())
    def over_budget():
        size = 0
        for root in roots:
            if not root.exists(): continue
            for path in root.rglob("*"):
                try:
                    if path.is_symlink() or not path.is_file(): continue
                    size += path.stat().st_size
                except FileNotFoundError:
                    continue
                except OSError:
                    return True
                if size > MAX_JOB_WORK_BYTES: return True
        return False
    if logs is not None: Path(logs).mkdir(parents=True, exist_ok=True)
    with (Path(logs) / "conversion.log").open("w+b") if logs is not None else tempfile.TemporaryFile() as stdout_file, \
            (Path(logs) / "conversion-errors.log").open("w+b") if logs is not None else tempfile.TemporaryFile() as stderr_file:
        with subprocess.Popen(command, cwd=cwd or ROOT, env=env, stdout=stdout_file, stderr=stderr_file,
                              start_new_session=True) as process:
            failure = None
            while process.poll() is None:
                try:
                    process.communicate(timeout=min(1, max(0.01, finishes - time.monotonic())))
                except subprocess.TimeoutExpired:
                    if time.monotonic() >= finishes:
                        failure = "Job exceeded its time limit."
                    if over_budget(): failure = "Job exceeded its aggregate scratch/report budget."
                if failure: break
            if failure is None and over_budget(): failure = "Job exceeded its aggregate scratch/report budget."
            if failure:
                if sys.platform == "win32":
                    process.kill()
                else:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.communicate()
                raise ValueError(failure) from None
        stdout_file.seek(0)
        stderr_file.seek(0)
        stdout = stdout_file.read(MAX_LOG_BYTES + 1)
        stderr = stderr_file.read(MAX_LOG_BYTES + 1)
        if len(stdout) > MAX_LOG_BYTES or len(stderr) > MAX_LOG_BYTES:
            if logs is None: raise ValueError("Job exceeded its output limit.")
            # Full conversion logs remain on disk. The last lines include output paths and totals.
            for stream, name in ((stdout_file, "stdout"), (stderr_file, "stderr")):
                stream.seek(0, 2); stream.seek(max(0, stream.tell() - MAX_LOG_BYTES))
                tail = stream.read()
                if name == "stdout": stdout = tail
                else: stderr = tail
        return process.returncode, stdout.decode("utf-8", "replace"), stderr.decode("utf-8", "replace")


def run_command(cmd, cwd=None):
    worker = (
        "import os,sys,resource; "
        "memory=int(sys.argv[1])*1024*1024; "
        "resource.setrlimit(resource.RLIMIT_AS,(memory,memory)); "
        f"resource.setrlimit(resource.RLIMIT_FSIZE,({MAX_WORK_FILE_BYTES},{MAX_WORK_FILE_BYTES})); "
        "os.execvp(sys.argv[2],sys.argv[2:])"
    )
    memory = GENERATION_MEMORY_MB or max(512, available_memory_bytes() // (2 * MAX_CONCURRENT_JOBS * 1024**2))
    timeout = workload_seconds(input_bytes(cmd[-1]), GENERATION_TIMEOUT, minimum=1800)
    environment = dict(os.environ)
    environment.setdefault("EAPDF_WORKER_HEAP_BYTES", str(max(256, memory - 512) * 1024**2))
    environment.setdefault("EAPDF_MAX_TEMP_BYTES", str(MAX_JOB_WORK_BYTES))
    environment.setdefault("EAPDF_MAX_OUTPUT_BYTES", str(MAX_WORK_FILE_BYTES))
    logs = Path(cmd[cmd.index("--out") + 1]) if "--out" in cmd else None
    executed = list(cmd)
    scratch = None
    try:
        if logs is not None and Path(cmd[0]).name.lower() in ("java", "java.exe"):
            logs.mkdir(parents=True, exist_ok=True)
            scratch = Path(tempfile.mkdtemp(prefix=".worker-scratch-", dir=logs.resolve()))
            executed = [arg for arg in executed if not arg.startswith("-Djava.io.tmpdir=")]
            executed.insert(1, "-Djava.io.tmpdir=" + str(scratch))
        code, stdout, stderr = bounded_subprocess(
            [sys.executable, "-c", worker, str(memory + 4096 + (MAX_JOB_WORK_BYTES + 1024**2 - 1) // 1024**2), *executed], timeout, cwd, env=environment, logs=logs)
    finally:
        if scratch is not None: shutil.rmtree(scratch)
    return {
        "cmd": cmd,
        "code": code,
        "stdout": stdout,
        "stderr": stderr,
    }


def parse_generated_paths(command_result):
    paths = []
    for line in (command_result.get("stdout") or "").splitlines():
        line = line.strip()
        if line.startswith("wrote: "):
            paths.append(Path(line[len("wrote: "):].strip()))
        elif line.startswith("wrote pdf: "):
            paths.append(Path(line[len("wrote pdf: "):].strip()))
        elif line.startswith("wrote archive: "):
            paths.append(Path(line[len("wrote archive: "):].strip()))
    deduped = []
    seen = set()
    for path in paths:
        resolved = (ROOT / path).resolve() if not path.is_absolute() else path.resolve()
        if resolved not in seen:
            seen.add(resolved)
            deduped.append(resolved)
    return deduped


def summarize_generated_files(paths):
    results = []
    for path in paths:
        path = path.resolve()
        if not path.is_file() or path.suffix.lower() != ".pdf" or not path.is_relative_to(OUT_PDF_ROOT.resolve()):
            continue
        result = attach_validation_report_link(run_pdf_validation(path))
        display_name = path.name
        result["display_name"] = display_name
        result["summary"]["display_name"] = display_name
        file_id = uuid.uuid4().hex
        GENERATED_FILE_MAP[file_id] = path
        results.append(
            {
                "name": display_name,
                "path": path,
                "link": application_url("generated_file", file_id=file_id),
                "validation": result,
            }
        )
    return results


def format_command(cmd):
    return " ".join(shlex_quote(part) for part in cmd)


def shlex_quote(value):
    text = str(value)
    if re.fullmatch(r"[-_./:@A-Za-z0-9]+", text):
        return text
    return "'" + text.replace("'", "'\"'\"'") + "'"


def generation_profile_args(profile, archive_output=None):
    if profile == "1s":
        return []
    if profile == "1si":
        return ["--omit-source"]
    if profile == "1m":
        args = ["--archive-profile", "m"]
        if archive_output:
            args.extend(["--archive-output", archive_output])
        return args
    if profile == "1mi":
        args = ["--archive-profile", "mi"]
        if archive_output:
            args.extend(["--archive-output", archive_output])
        return args
    if profile == "1c":
        args = ["--archive-profile", "c"]
        if archive_output:
            args.extend(["--archive-output", archive_output])
        return args
    if profile == "1ci":
        args = ["--archive-profile", "ci"]
        if archive_output:
            args.extend(["--archive-output", archive_output])
        return args
    raise ValueError(f"Unsupported profile: {profile}")


def infer_archive_output_name(profile, upload_names):
    archive_suffix = f"_{profile}.pdf"
    cleaned = [name.replace("\\", "/") for name in upload_names if name]
    if not cleaned:
        return f"archive{archive_suffix}"

    if len(cleaned) == 1:
        stem = Path(cleaned[0]).stem
        return Common_safe_filename(stem) + archive_suffix

    first_parts = [Path(name).parts for name in cleaned]
    top_levels = {parts[0] for parts in first_parts if len(parts) > 1}
    if len(top_levels) == 1:
        return Common_safe_filename(next(iter(top_levels))) + archive_suffix

    return f"archive{archive_suffix}"


def Common_safe_filename(name):
    text = str(name or "").strip()
    if not text:
        return "archive"
    text = re.sub(r"[^A-Za-z0-9._ -]+", "_", text)
    text = re.sub(r"\s+", "_", text)
    text = re.sub(r"_+", "_", text).strip("._ ")
    return text or "archive"


def list_project_output_directories():
    dirs = ["out"]
    if OUT_PDF_ROOT.is_dir():
        for path in sorted(OUT_PDF_ROOT.iterdir()):
            if path.is_dir() and not path.is_symlink() and not path.name.startswith("."):
                dirs.append(path.relative_to(ROOT).as_posix())
    return dirs


def staged_upload_path(stage_dir, upload_name):
    raw_name = str(upload_name or "").replace("\\", "/")
    relative = PurePosixPath(raw_name)
    if (not raw_name or "\x00" in raw_name or relative.is_absolute()
            or PureWindowsPath(raw_name).drive or ".." in relative.parts):
        raise ValueError("Upload filenames must be relative paths without parent-directory components.")
    if not relative.name or relative.name in {".", ".."} or ":" in raw_name:
        raise ValueError("Invalid upload filename.")
    stage_root = stage_dir.resolve()
    target = stage_root.joinpath(*relative.parts)
    if not target.resolve().is_relative_to(stage_root):
        raise ValueError("Upload filename escapes the staging directory.")
    return target


def handle_local_generation():
    uploads = [f for f in request.files.getlist("inputs") if getattr(f, "filename", "")]
    uploads.extend(f for f in request.files.getlist("input_folder") if getattr(f, "filename", ""))
    if not uploads:
        raise ValueError("Select at least one .eml, .msg, .pst, or .mbox file, or choose a folder containing them.")
    if len(uploads) > MAX_GENERATOR_UPLOAD_FILES:
        raise ValueError(f"You can convert at most {MAX_GENERATOR_UPLOAD_FILES} input files at a time.")

    profile = request.form.get("profile", "1s")
    has_pst_input = any(Path(upload.filename).suffix.lower() == ".pst" for upload in uploads)
    if has_pst_input and profile in {"1s", "1si"}:
        raise ValueError("PST input requires archive profile 1m, 1mi, 1c, or 1ci.")
    archive_output_name = request.form.get("archive_output", "").strip()
    output_dir_raw = request.form.get("output_dir", "").strip()
    if output_dir_raw:
        output_dir = (ROOT / output_dir_raw).resolve()
    else:
        output_dir = OUT_PDF_ROOT
    output_root = OUT_PDF_ROOT.resolve()
    if not output_root.is_relative_to(ROOT.resolve()) or not output_dir.resolve().is_relative_to(output_root):
        raise ValueError("Output directory must stay within this project's out directory.")
    if archive_output_name:
        if (PurePosixPath(archive_output_name).name != archive_output_name
                or PureWindowsPath(archive_output_name).name != archive_output_name
                or ":" in archive_output_name or "\x00" in archive_output_name
                or Path(archive_output_name).suffix.lower() != ".pdf"):
            raise ValueError("Archive output must be a PDF filename without a directory path.")
    elif profile in {"1m", "1mi", "1c", "1ci"}:
        archive_output_name = infer_archive_output_name(profile, [upload.filename for upload in uploads])
    request_id = uuid.uuid4().hex
    output_dir = output_dir / ".web-jobs" / request_id
    output_dir.mkdir(parents=True, mode=0o700)
    WEB_JOB_MAP[request_id] = output_dir
    stage_dir = GENERATOR_TMP_ROOT / request_id
    stage_dir.mkdir(parents=True, mode=0o700)
    WEB_JOB_MAP[uuid.uuid4().hex] = stage_dir

    try:
        for upload in uploads:
            target = staged_upload_path(stage_dir, upload.filename)
            filename = target.name
            suffix = target.suffix.lower()
            if suffix not in {".eml", ".msg", ".pst", ".mbox"}:
                raise ValueError(f"{filename} is not a supported input type.")
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as destination:
                upload.save(destination)

        cmd = ["java", "-XX:ActiveProcessorCount=4",
               "-jar", str(JAR_PATH), "--jobs", "4", "--out", str(output_dir)]
        archive_output_path = str((output_dir / archive_output_name).resolve()) if archive_output_name else None
        if archive_output_path and not Path(archive_output_path).is_relative_to(output_root):
            raise ValueError("Archive output must stay within this project's out directory.")
        cmd.extend(generation_profile_args(profile, archive_output=archive_output_path))
        cmd.append(str(stage_dir))
        command_result = run_command(cmd, cwd=ROOT)
        generated_paths = [path for path in parse_generated_paths(command_result) if path.is_relative_to(output_dir.resolve())]
        generated = summarize_generated_files(generated_paths)
        return {
            "title": "Local Generation",
            "profile": profile,
            "output_dir": str(output_dir),
            "command_result": command_result,
            "generated": generated,
        }
    finally:
        shutil.rmtree(stage_dir)


def render_generated_cards(generated):
    if not generated:
        return "<div class='gen-note'>No output files were reported by the generator.</div>"
    cards = []
    for item in generated:
        summary = item["validation"]["summary"]
        pdfa = item["validation"].get("pdfa", {})
        eapdf_status = effective_eapdf_status(summary, pdfa)
        eapdf_fail_count = effective_eapdf_fail_count(summary, pdfa)
        report_html = (
            f"<div class='generated-meta'><a class='report-link' href='{html.escape(item['validation'].get('validation_report_link', ''))}' target='_blank' rel='noopener'>Open validation report</a></div>"
            if item["validation"].get("validation_report_link") else ""
        )
        cards.append(
            "<div class='generated-card'>"
            f"<div class='generated-name'><a href='{html.escape(item['link'])}'>{html.escape(item['name'])}</a></div>"
            f"<div class='generated-meta'>EA-PDF {html.escape(summary.get('verification_spec', ''))}</div>"
            f"{report_html}"
            "<div class='generated-line'>"
            f"<span class='summary-status {html.escape((pdfa.get('status') or 'skip').lower())}'>{html.escape(pdfa.get('status', 'SKIP'))}</span>"
            f"<span>PDF/A {html.escape(pdfa.get('profile_name', '') or 'No profile')}</span>"
            "</div>"
            "<div class='generated-line'>"
            f"<span class='summary-status {html.escape(eapdf_status.lower())}'>{html.escape(eapdf_status)}</span>"
            f"<span>EA-PDF Fail {eapdf_fail_count}</span>"
            f"<span>Warn {summary['rule_warn']}</span>"
            f"<span>Pass {summary['rule_pass']}</span>"
            f"<span>N/A {summary['rule_na']}</span>"
            "</div>"
            "</div>"
        )
    return "<div class='generated-grid'>" + "".join(cards) + "</div>"


def render_generation_result(result):
    if not result:
        return ""
    cmd = result["command_result"]
    status_class = "pass" if cmd["code"] == 0 else "fail"
    generated_html = render_generated_cards(result["generated"])
    stdout = html.escape(cmd.get("stdout", "").strip() or "(no stdout)")
    stderr = html.escape(cmd.get("stderr", "").strip() or "(no stderr)")
    return (
        "<section class='gen-result'>"
        f"<div class='gen-result-head'><span class='summary-status {status_class}'>{'PASS' if cmd['code'] == 0 else 'FAIL'}</span>"
        f"<span>{html.escape(result['title'])} using EA-PDF {html.escape(result['profile']).upper()}</span>"
        f"<span class='version-chip'>Version {html.escape(APP_VERSION)}</span></div>"
        f"<div class='gen-command'><strong>Command:</strong> <code>{html.escape(format_command(cmd['cmd']))}</code></div>"
        f"<div class='gen-command'><strong>Output Directory:</strong> <code>{html.escape(result.get('output_dir', str(OUT_PDF_ROOT)))}</code></div>"
        f"{generated_html}"
        "<div class='gen-log-grid'>"
        f"<details class='detail-block' open><summary>Process Output</summary><div class='detail-resizer'><pre>{stdout}</pre></div></details>"
        f"<details class='detail-block'><summary>Process Errors</summary><div class='detail-resizer'><pre>{stderr}</pre></div></details>"
        "</div>"
        "</section>"
    )


def render_generate_page(local_result=None, local_error=None):
    local_error_html = f"<div class='error'>{html.escape(local_error)}</div>" if local_error else ""
    local_result_html = render_generation_result(local_result)
    output_buttons = "".join(
        f"<button type='button' class='dir-choice{' active' if path == 'out' else ''}' data-dir='{html.escape(path)}'>{html.escape(path)}</button>"
        for path in list_project_output_directories()
    )
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>EA-PDF Generator {html.escape(APP_VERSION)}</title>
  <style>
    :root {{
      --bg: #f4efe6;
      --panel: #fffdf8;
      --border: #d8c8b0;
      --text: #1f1d1a;
      --muted: #675b4f;
      --accent: #0f6b8f;
      --fail: #912018;
      --warn: #8a6300;
      --pass: #1f6f43;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: Georgia, "Times New Roman", serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(15,107,143,0.10), transparent 28rem),
        linear-gradient(180deg, #f8f4ec 0%, var(--bg) 100%);
    }}
    .page {{
      max-width: 1880px;
      margin: 0 auto;
      padding: 0.4rem 0.24rem 1rem;
      display: grid;
      gap: 0.75rem;
    }}
    .hero, .panel, .gen-result {{
      background: rgba(255,255,255,0.8);
      border: 1px solid var(--border);
      border-radius: 18px;
      padding: 0.75rem 0.9rem;
      box-shadow: 0 12px 34px rgba(82, 66, 47, 0.08);
      backdrop-filter: blur(8px);
    }}
    .hero {{
      position: relative;
    }}
    h1 {{
      margin: 0 0 0.12rem;
      font-size: clamp(1.05rem, 3vw, 2.45rem);
      line-height: 1.08;
    }}
    .page-link {{
      position: absolute;
      top: 0.8rem;
      right: 0.9rem;
      font-size: 0.76rem;
      font-weight: 700;
      color: var(--accent);
      text-decoration: none;
      letter-spacing: 0.04em;
    }}
    .lede {{
      margin: 0;
      color: var(--muted);
      max-width: 64rem;
    }}
    .panel h2 {{
      margin: 0 0 0.22rem;
      font-size: 1.15rem;
    }}
    .panel p {{
      margin: 0 0 0.6rem;
      color: var(--muted);
      line-height: 1.3;
    }}
    form {{
      display: grid;
      gap: 0.8rem;
    }}
    .field-grid {{
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.7rem;
    }}
    .field {{
      display: grid;
      gap: 0.18rem;
    }}
    label {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }}
    input[type=file], input[type=text], input[type=number], select {{
      width: 100%;
      border: 1px solid #ccb89e;
      border-radius: 10px;
      background: rgba(255,255,255,0.92);
      padding: 0.45rem 0.5rem;
      font: inherit;
      color: var(--text);
    }}
    .checkbox {{
      display: flex;
      align-items: center;
      gap: 0.45rem;
      font-size: 0.9rem;
      color: var(--text);
    }}
    .checkbox input {{
      margin: 0;
    }}
    .dropzone {{
      border: 2px dashed var(--accent);
      border-radius: 14px;
      padding: 0.7rem;
      background: #fcfffe;
    }}
    .section-title {{
      font-size: 0.74rem;
      text-transform: uppercase;
      letter-spacing: 0.1em;
      color: var(--muted);
      font-weight: 700;
      margin-bottom: 0.2rem;
    }}
    .picker-row {{
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.7rem;
      align-items: start;
    }}
    .picker-card {{
      border: 1px solid #e1d5c5;
      border-radius: 14px;
      background: rgba(255,255,255,0.78);
      padding: 0.7rem;
      display: grid;
      grid-auto-rows: max-content;
      gap: 0.35rem;
      align-content: start;
      align-self: start;
      height: auto;
      min-height: 0;
    }}
    .picker-button {{
      display: inline-flex;
      align-items: center;
      justify-content: center;
      border: 0;
      border-radius: 999px;
      background: var(--accent);
      color: white;
      padding: 0.62rem 1rem;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
      text-decoration: none;
      width: fit-content;
      max-width: 100%;
      min-height: 0;
      height: auto;
      white-space: nowrap;
      align-self: start;
      flex: 0 0 auto;
    }}
    .picker-card > .picker-button {{
      align-self: start;
      justify-self: start;
    }}
    .hidden-file-input {{
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
      border: 0;
    }}
    .picker-status {{
      font-size: 0.82rem;
      color: var(--muted);
      line-height: 1.25;
      min-height: 2.4em;
    }}
    .dir-picker-value {{
      font-size: 0.84rem;
      color: var(--text);
      font-weight: 700;
      word-break: break-word;
    }}
    .dir-picker-panel {{
      display: none;
      position: absolute;
      top: calc(100% + 0.45rem);
      left: 0;
      right: 0;
      z-index: 10;
      border: 1px solid #e1d5c5;
      border-radius: 12px;
      background: rgba(255,255,255,0.9);
      padding: 0.55rem;
      gap: 0.45rem;
      box-shadow: 0 16px 28px rgba(82, 66, 47, 0.12);
    }}
    .dir-picker-panel.open {{
      display: grid;
    }}
    .dir-choice-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 0.45rem;
    }}
    .dir-choice {{
      border: 1px solid #ccb89e;
      border-radius: 10px;
      background: rgba(255,255,255,0.96);
      color: var(--text);
      padding: 0.42rem 0.52rem;
      font: inherit;
      font-size: 0.82rem;
      text-align: left;
      cursor: pointer;
      width: 100%;
    }}
    .dir-choice.active {{
      border-color: var(--accent);
      box-shadow: inset 0 0 0 1px var(--accent);
      background: #eef7fb;
    }}
    .output-picker-card {{
      position: relative;
    }}
    .generate-actions {{
      display: flex;
      justify-content: flex-start;
    }}
    .hint {{
      margin: 0.1rem 0 0;
      color: var(--muted);
      font-size: 0.84rem;
    }}
    .action-button {{
      justify-self: start;
      border: 0;
      border-radius: 999px;
      background: var(--accent);
      color: white;
      padding: 0.62rem 1rem;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }}
    .error {{
      margin-top: 0.2rem;
      background: #fff2f0;
      color: var(--fail);
      border: 1px solid #efc1bb;
      border-radius: 12px;
      padding: 0.65rem 0.8rem;
    }}
    .gen-result {{
      display: grid;
      gap: 0.55rem;
    }}
    .gen-result-head {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.55rem;
      align-items: center;
      font-size: 0.92rem;
      font-weight: 700;
    }}
    .summary-status.fail {{ color: var(--fail); font-weight: 700; }}
    .summary-status.pass {{ color: var(--pass); font-weight: 700; }}
    .summary-status.warn, .summary-status.incomplete {{ color: var(--warn); font-weight: 700; }}
    .gen-command {{
      font-size: 0.82rem;
      color: var(--muted);
      word-break: break-word;
    }}
    .generated-grid {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 0.55rem;
    }}
    .generated-card {{
      border: 1px solid #e1d5c5;
      border-radius: 12px;
      background: rgba(255,255,255,0.86);
      padding: 0.55rem 0.65rem;
      display: grid;
      gap: 0.25rem;
    }}
    .generated-name a {{
      color: var(--text);
      text-decoration: none;
      font-weight: 700;
    }}
    .generated-meta {{
      font-size: 0.7rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }}
    .generated-line {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem 0.5rem;
      font-size: 0.8rem;
    }}
    .gen-log-grid {{
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.65rem;
    }}
    .detail-block {{
      border: 1px solid #e6dac9;
      border-radius: 8px;
      background: #fdfaf5;
      overflow: hidden;
    }}
    .detail-block summary {{
      cursor: pointer;
      list-style: none;
      padding: 0.26rem 0.38rem;
      background: #f6eee1;
      font-weight: 700;
      font-size: 0.72rem;
    }}
    .detail-resizer {{
      max-height: 22rem;
      overflow: auto;
    }}
    pre, code {{
      font-family: "SFMono-Regular", Consolas, monospace;
    }}
    pre {{
      margin: 0;
      padding: 0.5rem 0.55rem;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 0.74rem;
      line-height: 1.2;
    }}
    .gen-note {{
      color: var(--muted);
      font-size: 0.84rem;
    }}
    .version-chip {{
      display: inline-flex;
      align-items: center;
      padding: 0.16rem 0.5rem;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: rgba(15,107,143,0.08);
      color: var(--muted);
      font-size: 0.8rem;
      margin-left: 0.4rem;
      vertical-align: middle;
    }}
    @media (max-width: 980px) {{
      .gen-log-grid, .field-grid, .picker-row {{
        grid-template-columns: 1fr;
      }}
    }}
  </style>
</head>
<body>
  <main class="page">
    <section class="hero">
      <a class="page-link" href="{html.escape(application_url('index'))}">Open Validator</a>
      <h1>EA-PDF Generator <span class="version-chip">Version {html.escape(APP_VERSION)}</span></h1>
      <p class="lede">Generate EA-PDF files from .eml, .msg, .pst, or .mbox files using the current tracked release.</p>
      <p class="hint">Web results expire after {RETENTION_SECONDS / 3600:g} hours. Download files you want to keep.</p>
    </section>

    <section class="panel">
      <h2>Local Generation</h2>
      <p>Use file selection for specific items or choose a whole folder. Archive profiles combine the selected messages into one output PDF.</p>
      <form method="post" action="{html.escape(application_url('generate_local_route'))}" enctype="multipart/form-data">
        <div>
          <div class="section-title">Convert</div>
          <div class="picker-row">
            <div class="picker-card">
              <label for="inputs" class="picker-button">Convert File</label>
              <input id="inputs" class="hidden-file-input" type="file" name="inputs" accept=".eml,.msg,.pst,.mbox,message/rfc822,application/vnd.ms-outlook" multiple>
              <div class="picker-status" id="inputs-status">No files selected.</div>
            </div>
            <div class="picker-card">
              <label for="input_folder" class="picker-button">Convert Directory</label>
              <input id="input_folder" class="hidden-file-input" type="file" name="input_folder" webkitdirectory directory multiple>
              <div class="picker-status" id="folder-status">No directory selected.</div>
            </div>
            <div class="picker-card output-picker-card">
              <input id="output_dir" name="output_dir" type="hidden" value="out">
              <button type="button" class="picker-button" id="output_dir_button">Output Directory</button>
              <div class="dir-picker-value" id="output_dir_value">out</div>
              <div class="dir-picker-panel" id="dir_picker_panel">
                <div class="dir-choice-grid">
                  {output_buttons}
                </div>
              </div>
              <div class="hint">Project folders only. Default is <code>out</code>.</div>
            </div>
          </div>
        </div>
        <div>
          <div class="section-title">Profile</div>
          <div class="field-grid">
            <div class="field">
              <label for="profile">EA-PDF Profile</label>
              <select id="profile" name="profile">
                <option value="1s">1s</option>
                <option value="1si">1si</option>
                <option value="1m">1m</option>
                <option value="1mi">1mi</option>
                <option value="1c">1c</option>
                <option value="1ci">1ci</option>
              </select>
            </div>
            <div class="field">
              <label for="archive_output">Archive Output Name</label>
              <input id="archive_output" name="archive_output" type="text" placeholder="Leave blank to derive from input name">
            </div>
            <div class="field">
              <label>&nbsp;</label>
              <div class="hint">Use <code>1s</code> or <code>1si</code> for single-message output. Use <code>1m</code> or <code>1mi</code> to combine selected messages into one multi-message archive PDF. Use <code>1c</code> or <code>1ci</code> to create a collection container with child EA-PDF files.</div>
            </div>
          </div>
        </div>
        <div>
          <div class="section-title">Generate</div>
          <div class="generate-actions">
            <button type="submit" class="action-button">Generate EA-PDF</button>
          </div>
        </div>
      </form>
      {local_error_html}
    </section>

    {local_result_html}
  </main>
  <script>
    (function () {{
      const inputs = document.getElementById('inputs');
      const folder = document.getElementById('input_folder');
      const inputsStatus = document.getElementById('inputs-status');
      const folderStatus = document.getElementById('folder-status');
      const profileSelect = document.getElementById('profile');
      const singleMessageProfiles = ['1s', '1si'];
      const defaultArchiveProfile = '1m';
      function selectedFilesContainPst() {{
        const fileLists = [];
        if (inputs && inputs.files) fileLists.push(Array.from(inputs.files));
        if (folder && folder.files) fileLists.push(Array.from(folder.files));
        return fileLists.some(function (files) {{
          return files.some(function (file) {{
            return (file.name || '').toLowerCase().endsWith('.pst');
          }});
        }});
      }}
      function updateProfileAvailability() {{
        if (!profileSelect) {{
          return;
        }}
        const pstSelected = selectedFilesContainPst();
        Array.from(profileSelect.options).forEach(function (option) {{
          if (!singleMessageProfiles.includes(option.value)) {{
            return;
          }}
          option.disabled = pstSelected;
        }});
        if (pstSelected && singleMessageProfiles.includes(profileSelect.value)) {{
          profileSelect.value = defaultArchiveProfile;
        }}
      }}
      if (inputs && inputsStatus) {{
        inputs.addEventListener('change', function () {{
          const count = inputs.files ? inputs.files.length : 0;
          inputsStatus.textContent = count ? `${{count}} file${{count === 1 ? '' : 's'}} selected.` : 'No files selected.';
          updateProfileAvailability();
        }});
      }}
      if (folder && folderStatus) {{
        folder.addEventListener('change', function () {{
          const count = folder.files ? folder.files.length : 0;
          if (!count) {{
            folderStatus.textContent = 'No directory selected.';
            updateProfileAvailability();
            return;
          }}
          const first = folder.files[0];
          const rel = first.webkitRelativePath || '';
          const top = rel ? rel.split('/')[0] : 'Selected folder';
          folderStatus.textContent = `${{top}} selected with ${{count}} item${{count === 1 ? '' : 's'}}.`;
          updateProfileAvailability();
        }});
      }}
      const dirInput = document.getElementById('output_dir');
      const dirButton = document.getElementById('output_dir_button');
      const dirValue = document.getElementById('output_dir_value');
      const dirPanel = document.getElementById('dir_picker_panel');
      const dirChoices = Array.from(document.querySelectorAll('.dir-choice'));
      if (dirButton && dirPanel && dirInput && dirValue) {{
        dirButton.addEventListener('click', function () {{
          dirPanel.classList.toggle('open');
        }});
        dirChoices.forEach(function (choice) {{
          choice.addEventListener('click', function () {{
            const value = choice.dataset.dir || 'out';
            dirInput.value = value;
            dirValue.textContent = value;
            dirChoices.forEach(function (item) {{ item.classList.remove('active'); }});
            choice.classList.add('active');
            dirPanel.classList.remove('open');
          }});
        }});
      }}
      updateProfileAvailability();
    }})();
  </script>
</body>
</html>"""

def render_pdfa_details(pdfa):
    rule_summaries = pdfa.get("rule_summaries") or []
    stderr = pdfa.get("stderr", "")
    stderr_severity = (pdfa.get("stderr_severity") or "").upper()
    blocks = []

    if rule_summaries:
        items = []
        for summary in rule_summaries:
            clause = summary.get("clause", "")
            specification = summary.get("specification", "")
            description = summary.get("description", "")
            failed_checks = summary.get("failedChecks", 0)
            checks = summary.get("checks") or []
            check_items = []
            for check in checks[:8]:
                message = check.get("errorMessage", "")
                context = check.get("context", "")
                parts = []
                if message:
                    parts.append(f"<div class='pdfa-check-message'>{html.escape(message)}</div>")
                if context:
                    parts.append(f"<div class='pdfa-check-context'>{html.escape(context)}</div>")
                check_items.append("<li>" + "".join(parts) + "</li>")
            more_checks = ""
            if len(checks) > 8:
                more_checks = f"<div class='pdfa-more-checks'>Showing 8 of {len(checks)} failed checks.</div>"
            items.append(
                "<div class='pdfa-rule-summary'>"
                f"<div class='pdfa-rule-head'>{html.escape(specification)} clause {html.escape(str(clause))}</div>"
                f"<div class='pdfa-rule-desc'>{html.escape(description)}</div>"
                f"<div class='pdfa-rule-meta'>Failed checks: {failed_checks}</div>"
                + (f"<ul class='pdfa-check-list'>{''.join(check_items)}</ul>{more_checks}" if check_items else "")
                + "</div>"
            )
        blocks.append(
            "<div class='pdfa-detail-note'><strong>Failed PDF/A rule summaries</strong></div>"
            "<div class='detail-resizer pdfa-detail-resizer'>"
            + "".join(items)
            + "</div>"
        )
    else:
        status = pdfa.get("status", "")
        if status == "PASS":
            blocks.append(
                "<div class='pdfa-detail-note'>veraPDF found no validation errors in this file.</div>"
            )
            if stderr and stderr_severity == "WARN":
                blocks.append(
                    "<div class='pdfa-detail-note'>veraPDF also emitted stderr warnings.</div>"
                )
        elif pdfa.get("message"):
            blocks.append(
                f"<div class='pdfa-detail-note'>{html.escape(pdfa.get('message', ''))}</div>"
            )
        else:
            blocks.append(
                "<div class='pdfa-detail-note'>No per-rule PDF/A detail was reported by veraPDF for this result.</div>"
            )

    if stderr:
        blocks.append(
            "<details class='detail-block pdfa-detail-block'><summary>veraPDF stderr</summary>"
            f"<div class='detail-resizer pdfa-detail-resizer'><pre>{html.escape(stderr)}</pre></div>"
            "</details>"
        )
    return "".join(blocks)


def render_pdfa_table(results):
    rows = [
        ("Status", lambda pdfa: pdfa.get("status", "")),
        ("Profile", lambda pdfa: pdfa.get("profile_name", "") or "No PDF/A profile"),
        ("Message", lambda pdfa: pdfa.get("message", "")),
        ("Passed Rules", lambda pdfa: str(pdfa.get("passed_rules", 0))),
        ("Failed Rules", lambda pdfa: str(pdfa.get("failed_rules", 0))),
        ("Passed Checks", lambda pdfa: str(pdfa.get("passed_checks", 0))),
        ("Failed Checks", lambda pdfa: str(pdfa.get("failed_checks", 0))),
    ]
    head_cells = []
    for index, result in enumerate(results, start=1):
        display_name = result.get("display_name") or Path(result["summary"]["file"]).name
        verification_spec = result["summary"].get("verification_spec", "")
        meta_html = (
            f"<div class='file-head-meta'>EA-PDF {html.escape(verification_spec)}</div>"
            if verification_spec else ""
        )
        report_link = result.get("validation_report_link")
        report_html = (
            f"<div class='file-head-meta'><a class='report-link' href='{html.escape(report_link)}' target='_blank' rel='noopener'>Open validation report</a></div>"
            if report_link else ""
        )
        head_cells.append(
            "<th class='pdfa-head'>"
            f"<div class='file-head-label'>File {index}</div>"
            f"<div class='file-head-name'>{html.escape(display_name)}</div>"
            f"{meta_html}"
            f"{report_html}"
            "</th>"
        )
    body = []
    for label, getter in rows:
        cells = []
        for result in results:
            pdfa = result.get("pdfa", {})
            value = getter(pdfa)
            cells.append(f"<td class='pdfa-cell'>{html.escape(value)}</td>")
        body.append(
            "<tr>"
            f"<th class='pdfa-row-label'>{html.escape(label)}</th>"
            f"{''.join(cells)}"
            "</tr>"
        )
    detail_cells = []
    for result in results:
        pdfa = result.get("pdfa", {})
        detail_cells.append(f"<td class='pdfa-cell pdfa-detail-cell'>{render_pdfa_details(pdfa)}</td>")
    body.append(
        "<tr>"
        "<th class='pdfa-row-label'>Details</th>"
        f"{''.join(detail_cells)}"
        "</tr>"
    )
    iso_cells = []
    for result in results:
        upstream = [row for row in collapse_items(result.get("items", [])) if row["code"] == "ISO32005_UPSTREAM"]
        value = render_file_cell(upstream[0]) if upstream else "Not run"
        iso_cells.append(f"<td class='pdfa-cell'>{value}</td>")
    body.append("<tr><th class='pdfa-row-label'>ISO/TS 32005</th>" + "".join(iso_cells) + "</tr>")
    return (
        "<section class='table-shell pdfa-shell'>"
        "<div class='pdfa-title'>PDF/A Results</div>"
        "<table class='compare-table pdfa-table'>"
        f"<thead><tr><th class='pdfa-corner'></th>{''.join(head_cells)}</tr></thead>"
        f"<tbody>{''.join(body)}</tbody>"
        "</table>"
        "</section>"
    )


def render_comparison_table(results):
    validator = get_validator()
    current_codes = all_rule_codes()
    historical_codes = list(dict.fromkeys(item["code"] for result in results for item in result["items"]
        if item["code"] not in current_codes and item["code"] not in {"PDFA_UPSTREAM", "ISO32005_UPSTREAM"}))
    rule_codes = current_codes + historical_codes
    collapsed_results = [collapse_items(result["items"]) for result in results]
    row_maps = [{row["code"]: row for row in rows} for rows in collapsed_results]
    current_hash = hashlib.sha256(RULE_TEXT_PATH.read_bytes()).hexdigest()
    for result, rows in zip(results, row_maps):
        reported_hash = result["summary"].get("rulebook_sha256")
        if reported_hash != current_hash:
            identity = "This report used a different rule catalogue." if reported_hash else "This report does not identify its rule catalogue."
            notice = (identity + " Its original findings are retained, "
                      "but the current specification text cannot be attributed to them. Rerun validation "
                      "to obtain findings under the current rules.")
            for row in rows.values():
                row["catalogue_notice"] = notice
        for code in historical_codes:
            row = rows.setdefault(code, {"code":code, "level":"WARN",
                "message":"No result for this historical rule was supplied in this report.",
                "details":None, "spec_refs":[]})
            row["catalogue_notice"] = "This rule code is absent from the current catalogue. Its original finding is shown without assigning a current rule number or specification meaning."
    body = []
    col_count = len(results)
    for index, code in enumerate(rule_codes, start=1):
        # Upstream-only checks are displayed in the upstream results section.
        covered_attr = "false"
        covered_badge = ""
        index_label = str(index) + "." if code in current_codes else "Historical / unmapped rule"
        title = (
            "<div class='rule-title-cell'>"
            f"<div class='requirement-index'>{html.escape(index_label)}</div>"
            f"<div class='rule-title-line'><code>{html.escape(code)}</code>{covered_badge}</div>"
            "</div>"
        )
        body.append(
            f"<tr class='rule-title-row' data-rule-code='{html.escape(code)}' data-pdfa-covered='{covered_attr}'>"
            f"<td class='rule-title-col' colspan='{col_count}'>{title}</td>"
            "</tr>"
        )
        cols = []
        row_levels = []
        for file_index, row_map in enumerate(row_maps, start=1):
            row = row_map[code]
            row_levels.append(f"data-file{file_index}-level='{html.escape(row['level'])}'")
            cols.append(f"<td class='file-col'>{render_file_cell(row)}</td>")
        body.append(
            f"<tr class='rule-row' data-rule-code='{html.escape(code)}' data-pdfa-covered='{covered_attr}' {' '.join(row_levels)}>"
            + "".join(cols)
            + "</tr>"
        )

    head_cells = []
    for index, result in enumerate(results, start=1):
        name = result.get("display_name") or Path(result["summary"]["file"]).name
        verification_spec = result["summary"].get("verification_spec", "")
        meta_html = (
            f"<div class='file-head-meta'>EA-PDF {html.escape(verification_spec)}</div>"
            if verification_spec else ""
        )
        report_link = result.get("validation_report_link")
        report_html = (
            f"<div class='file-head-meta'><a class='report-link' href='{html.escape(report_link)}' target='_blank' rel='noopener'>Open validation report</a></div>"
            if report_link else ""
        )
        head_cells.append(
            "<th class='file-head'>"
            f"<div class='file-head-label'>File {index}</div>"
            f"<div class='file-head-name'>{html.escape(name)}</div>"
            f"{meta_html}"
            f"{report_html}"
            "<label class='file-filter-label'>"
            "<span>Filter</span>"
            f"<select class='file-filter' data-file-index='{index}'>"
            "<option value='ALL'>All</option>"
            "<option value='FAIL'>Fail</option>"
            "<option value='WARN'>Warn</option>"
            "<option value='PASS'>Pass</option>"
            "<option value='N/A'>N/A</option>"
            "</select>"
            "</label>"
            "</th>"
        )

    return (
        "<section class='table-shell'>"
        "<table class='compare-table'>"
        "<thead>"
        f"<tr class='header-row'>{''.join(head_cells)}</tr>"
        "</thead>"
        f"<tbody>{''.join(body)}</tbody>"
        "</table>"
        "</section>"
    )


def render_page(results=None, error=None):
    results = results or []
    results_html = ""
    if results:
        results_html = render_summary_strip(results) + render_pdfa_table(results) + render_comparison_table(results)
    error_html = f"<div class='error'>{html.escape(error)}</div>" if error else ""
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>EA-PDF Validator {html.escape(APP_VERSION)}</title>
  <style>
    :root {{
      --bg: #f4efe6;
      --panel: #fffdf8;
      --border: #d8c8b0;
      --text: #1f1d1a;
      --muted: #675b4f;
      --accent: #0f6b8f;
      --fail: #912018;
      --warn: #8a6300;
      --pass: #1f6f43;
      --na: #546275;
    }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0;
      font-family: Georgia, "Times New Roman", serif;
      color: var(--text);
      background:
        radial-gradient(circle at top left, rgba(15,107,143,0.10), transparent 28rem),
        linear-gradient(180deg, #f8f4ec 0%, var(--bg) 100%);
    }}
    .page {{
      max-width: 1940px;
      margin: 0 auto;
      padding: 0.35rem 0.2rem 1rem 0.08rem;
    }}
    .hero {{
      position: relative;
      background: rgba(255,255,255,0.72);
      border: 1px solid var(--border);
      border-radius: 20px;
      padding: 0.65rem 0.8rem;
      box-shadow: 0 12px 40px rgba(82, 66, 47, 0.08);
      backdrop-filter: blur(8px);
    }}
    h1 {{
      margin: 0 0 0.15rem;
      font-size: clamp(0.95rem, 3vw, 2.35rem);
      line-height: 1.05;
    }}
    .page-link {{
      position: absolute;
      top: 0.72rem;
      right: 0.85rem;
      font-size: 0.76rem;
      font-weight: 700;
      color: var(--accent);
      text-decoration: none;
      letter-spacing: 0.04em;
    }}
    .lede {{
      margin: 0 0 0.4rem;
      color: var(--muted);
      max-width: 58rem;
    }}
    form {{
      display: grid;
      gap: 0.45rem;
      margin-top: 0.35rem;
    }}
    .upload-box {{
      border: 2px dashed var(--accent);
      border-radius: 16px;
      padding: 0.55rem 0.7rem;
      background: #fcfffe;
    }}
    input[type=file] {{
      width: 100%;
      font: inherit;
    }}
    .hint {{
      margin: 0.12rem 0 0;
      color: var(--muted);
      font-size: 0.88rem;
    }}
    .action-button {{
      justify-self: start;
      border: 0;
      border-radius: 999px;
      background: var(--accent);
      color: white;
      padding: 0.6rem 0.95rem;
      font: inherit;
      font-weight: 700;
      cursor: pointer;
    }}
    .error {{
      margin-top: 0.45rem;
      background: #fff2f0;
      color: var(--fail);
      border: 1px solid #efc1bb;
      border-radius: 12px;
      padding: 0.65rem 0.8rem;
    }}
    .results {{
      display: grid;
      gap: 0.7rem;
      margin-top: 0.45rem;
    }}
    .summary-strip {{
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 0.6rem;
    }}
    .summary-chip {{
      border: 1px solid var(--border);
      border-radius: 16px;
      background: rgba(255,255,255,0.8);
      padding: 0.65rem 0.8rem;
      box-shadow: 0 10px 28px rgba(82, 66, 47, 0.06);
    }}
    .summary-chip-label {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
    }}
    .summary-chip-name {{
      margin-top: 0.15rem;
      font-weight: 700;
      word-break: break-word;
      font-size: 0.92rem;
    }}
    .summary-chip-meta {{
      margin-top: 0.14rem;
      font-size: 0.72rem;
      color: var(--muted);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }}
    .summary-chip-line {{
      display: flex;
      flex-wrap: wrap;
      gap: 0.35rem 0.55rem;
      margin-top: 0.35rem;
      font-size: 0.82rem;
    }}
    .summary-line-label {{
      font-size: 0.72rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
      font-weight: 700;
    }}
    .summary-chip-pdfa {{
      margin-top: 0.3rem;
      font-size: 0.78rem;
      color: var(--muted);
      line-height: 1.2;
    }}
    .summary-status.fail {{ color: var(--fail); font-weight: 700; }}
    .summary-status.pass {{ color: var(--pass); font-weight: 700; }}
    .summary-status.warn {{ color: var(--warn); font-weight: 700; }}
    .summary-status.na {{ color: var(--na); font-weight: 700; }}
    .summary-status.skip {{ color: var(--muted); font-weight: 700; }}
    .summary-status.error {{ color: var(--fail); font-weight: 700; }}
    .pdfa-shell {{
      margin-bottom: 0.6rem;
    }}
    .pdfa-title {{
      padding: 0.45rem 0.55rem 0.15rem;
      font-size: 0.9rem;
      font-weight: 700;
    }}
    .table-controls {{
      padding: 0.38rem 0.45rem 0;
      background: rgba(255,255,255,0.72);
    }}
    .coverage-toggle {{
      display: inline-flex;
      align-items: center;
      gap: 0.42rem;
      font-size: 0.78rem;
      color: var(--muted);
      font-weight: 700;
    }}
    .coverage-toggle input {{
      margin: 0;
    }}
    .table-shell {{
      border: 1px solid var(--border);
      border-radius: 14px;
      background: rgba(255,255,255,0.84);
      box-shadow: 0 12px 34px rgba(82, 66, 47, 0.08);
      overflow: visible;
    }}
    .pdfa-row-label,
    .pdfa-cell,
    .pdfa-head,
    .pdfa-corner {{
      padding: 0.35rem 0.45rem !important;
      font-size: 0.78rem;
      line-height: 1.2;
      word-break: break-word;
      white-space: normal;
      background: rgba(255,255,255,0.92);
    }}
    .pdfa-detail-cell {{
      vertical-align: top;
    }}
    .pdfa-detail-note {{
      padding: 0.35rem 0.45rem;
      font-size: 0.76rem;
      line-height: 1.25;
      color: var(--muted);
    }}
    .pdfa-detail-block {{
      margin: 0.28rem 0.35rem;
    }}
    .pdfa-detail-resizer {{
      min-height: 2.6rem;
      max-height: 18rem;
    }}
    .pdfa-rule-summary {{
      padding: 0.35rem 0.42rem;
      border-bottom: 1px dashed #d8c8b0;
      font-size: 0.74rem;
      line-height: 1.24;
    }}
    .pdfa-rule-summary:last-child {{
      border-bottom: 0;
    }}
    .pdfa-rule-head {{
      font-weight: 700;
      margin-bottom: 0.12rem;
    }}
    .pdfa-rule-desc {{
      margin-bottom: 0.16rem;
    }}
    .pdfa-rule-meta {{
      color: var(--muted);
      margin-bottom: 0.16rem;
      font-size: 0.7rem;
    }}
    .pdfa-check-list {{
      margin: 0;
      padding-left: 1rem;
    }}
    .pdfa-check-list li {{
      margin: 0 0 0.2rem;
    }}
    .pdfa-check-message {{
      font-size: 0.72rem;
    }}
    .pdfa-check-context {{
      color: var(--muted);
      font-size: 0.68rem;
      font-family: "SFMono-Regular", Consolas, monospace;
      word-break: break-word;
    }}
    .pdfa-more-checks {{
      margin-top: 0.18rem;
      color: var(--muted);
      font-size: 0.68rem;
    }}
    .pdfa-row-label {{
      width: 11rem;
      background: #faf5ec;
      font-weight: 700;
    }}
    .pdfa-corner {{
      background: linear-gradient(180deg, #f8f1e3 0%, #efe3ce 100%);
      width: 11rem;
    }}
    .compare-table {{
      width: 100%;
      border-collapse: collapse;
      table-layout: fixed;
    }}
    .compare-table th,
    .compare-table td {{
      padding: 0;
      border: 1px solid #e1d5c5;
      vertical-align: top;
      text-align: left;
      background: rgba(255,255,255,0.92);
    }}
    .compare-table thead th {{
      position: sticky;
      top: 0;
      z-index: 3;
      background: linear-gradient(180deg, #f8f1e3 0%, #efe3ce 100%);
      box-shadow: inset 0 -1px 0 #d9c7ae;
    }}
    .compare-table .file-head {{
      padding: 0.45rem 0.45rem;
      width: 33.333%;
    }}
    .file-head-label {{
      font-size: 0.68rem;
      text-transform: uppercase;
      letter-spacing: 0.08em;
      color: var(--muted);
    }}
    .file-head-name {{
      margin-top: 0.15rem;
      font-weight: 700;
      word-break: break-word;
      font-size: 0.82rem;
    }}
    .file-head-meta {{
      margin-top: 0.1rem;
      font-size: 0.66rem;
      color: var(--muted);
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.05em;
    }}
    .file-filter-label {{
      display: grid;
      gap: 0.18rem;
      margin-top: 0.35rem;
      font-size: 0.68rem;
      color: var(--muted);
    }}
    .file-filter-label span {{
      text-transform: uppercase;
      letter-spacing: 0.08em;
    }}
    .file-filter {{
      width: 100%;
      border: 1px solid #ccb89e;
      border-radius: 8px;
      background: rgba(255,255,255,0.92);
      padding: 0.18rem 0.28rem;
      font: inherit;
      font-size: 0.72rem;
      color: var(--text);
    }}
    .rule-title-col {{
      background: #faf5ec;
      padding: 0;
    }}
    .rule-title-cell {{
      padding: 0.32rem 0.4rem 0.3rem;
    }}
    .rule-title-line {{
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.35rem;
    }}
    .requirement-index {{
      font-size: 0.68rem;
      font-weight: 700;
      color: var(--muted);
      margin-bottom: 0.12rem;
    }}
    .rule-badge {{
      display: inline-block;
      border: 1px solid #ccb89e;
      border-radius: 999px;
      background: #f6eee1;
      color: #7a684f;
      font-size: 0.58rem;
      font-weight: 700;
      letter-spacing: 0.08em;
      text-transform: uppercase;
      padding: 0.08rem 0.28rem;
    }}
    .file-col {{
      width: 33.333%;
    }}
    .file-cell {{
      display: grid;
      gap: 0.28rem;
      padding: 0.35rem 0.35rem;
    }}
    .status {{
      display: inline-block;
      font-weight: 700;
      white-space: nowrap;
      padding: 0.12rem 0.34rem;
      border-radius: 999px;
      font-size: 0.72rem;
      border: 1px solid currentColor;
    }}
    .status.fail {{ color: var(--fail); background: #fff2f0; }}
    .status.warn {{ color: var(--warn); background: #fff8de; }}
    .status.pass {{ color: var(--pass); background: #edf9f1; }}
    .status.n-a {{ color: var(--na); background: #eef2f7; }}
    .reason {{
      font-size: 0.74rem;
      line-height: 1.2;
    }}
    .detail-block {{
      border: 1px solid #e6dac9;
      border-radius: 8px;
      background: #fdfaf5;
      overflow: hidden;
    }}
    .detail-block summary {{
      cursor: pointer;
      list-style: none;
      padding: 0.22rem 0.34rem;
      background: #f6eee1;
      font-weight: 700;
      font-size: 0.68rem;
    }}
    .detail-block summary::-webkit-details-marker {{
      display: none;
    }}
    .detail-resizer {{
      resize: vertical;
      overflow: auto;
      min-height: 3.6rem;
      max-height: 24rem;
    }}
    .detail-group + .detail-group {{
      border-top: 1px dashed #d8c8b0;
    }}
    .detail-label {{
      padding: 0.26rem 0.34rem 0.08rem;
      font-size: 0.58rem;
      font-weight: 700;
      letter-spacing: 0.12em;
      color: #7b6a56;
      text-transform: uppercase;
    }}
    .detail-rows {{
      padding: 0 0.34rem 0.22rem;
      display: grid;
      gap: 0.14rem;
    }}
    .detail-row {{
      display: block;
      border: 1px solid #eadfce;
      border-radius: 8px;
      background: rgba(255,255,255,0.45);
      padding: 0.12rem 0.24rem 0.14rem;
    }}
    .detail-inline {{
      color: #1f1d1a;
      word-break: break-word;
      white-space: pre-wrap;
      font-family: "SFMono-Regular", Consolas, monospace;
      font-size: 0.72rem;
      line-height: 1.08;
    }}
    .detail-key-inline {{
      color: #806f5b;
      font-weight: 700;
      text-transform: uppercase;
      letter-spacing: 0.04em;
    }}
    .detail-value-inline {{
      font-weight: 400;
    }}
    .detail-empty {{
      min-height: 0.4rem;
    }}
    pre {{
      margin: 0;
      padding: 0.4rem 0.45rem;
      white-space: pre-wrap;
      word-break: break-word;
      font-size: 0.7rem;
      font-family: "SFMono-Regular", Consolas, monospace;
      min-height: 4.25rem;
      height: 100%;
    }}
    code {{
      font-size: 0.72rem;
      font-family: "SFMono-Regular", Consolas, monospace;
      word-break: break-word;
    }}
    .spec-ref {{
      padding: 0.3rem 0.34rem;
    }}
    .spec-ref + .spec-ref {{
      border-top: 1px dashed #d8c8b0;
    }}
    .spec-meta {{
      font-size: 0.64rem;
      color: var(--muted);
      margin-bottom: 0.14rem;
    }}
    .spec-excerpt {{
      font-size: 0.7rem;
      line-height: 1.12;
    }}
    .spec-note {{
      margin-top: 0.32rem;
      padding-top: 0.26rem;
      border-top: 1px dashed #d8c8b0;
      font-size: 0.68rem;
      line-height: 1.12;
      color: #5f564b;
    }}
    .spec-summary-source {{
      margin-top: 0.45rem;
      padding-top: 0.38rem;
      border-top: 1px dashed #d8c8b0;
      font-size: 0.72rem;
      line-height: 1.18;
      color: #6a6156;
    }}
    .spec-label {{
      margin-bottom: 0.12rem;
      font-size: 0.58rem;
      font-weight: 700;
      letter-spacing: 0.12em;
      text-transform: uppercase;
      color: #7b6a56;
    }}
    .version-chip {{
      display: inline-flex;
      align-items: center;
      padding: 0.16rem 0.5rem;
      border: 1px solid var(--border);
      border-radius: 999px;
      background: rgba(15,107,143,0.08);
      color: var(--muted);
      font-size: 0.8rem;
      margin-left: 0.4rem;
      vertical-align: middle;
    }}
    @media (max-width: 960px) {{
      .page {{
        padding-inline: 0.08rem;
      }}
    }}
  </style>
</head>
<body>
  <main class="page">
    <section class="hero">
      <a class="page-link" href="{html.escape(application_url('generate_index'))}">Open Generator</a>
      <h1>EA-PDF Validation Workbench <span class="version-chip">Version {html.escape(APP_VERSION)}</span></h1>
      <p class="lede">Upload up to three PDF files and validate each one with a two-pass process: veraPDF first for PDF/A conformance, then EA-PDF-specific rules.</p>
      <p class="hint">Web results expire after {RETENTION_SECONDS / 3600:g} hours. Reports can contain private email information.</p>
      <form method="post" action="{html.escape(application_url('validate_route'))}" enctype="multipart/form-data">
        <div class="upload-box">
          <input type="file" name="pdfs" accept="application/pdf,.pdf" multiple required>
          <p class="hint">Choose 1 to 3 PDF files. Each file gets its own full rule table. The filename banner row stays pinned while you scroll.</p>
        </div>
        <button type="submit">Validate PDFs</button>
      </form>
      {error_html}
    </section>
    <section class="results">{results_html}</section>
  </main>
  <script>
    (function () {{
      const filters = Array.from(document.querySelectorAll('.file-filter'));
      const rows = Array.from(document.querySelectorAll('.rule-row'));
      const showPdfaCovered = document.getElementById('show-pdfa-covered');
      if (!filters.length || !rows.length) return;

      function applyFilters() {{
        const activeFilters = filters.filter((filter) => filter.value !== 'ALL');
        const activeIndex = activeFilters.length ? activeFilters[0].dataset.fileIndex : null;
        const includePdfaCovered = showPdfaCovered ? showPdfaCovered.checked : false;

        filters.forEach((filter) => {{
          if (!activeIndex || filter.dataset.fileIndex === activeIndex) {{
            filter.disabled = false;
          }} else {{
            filter.disabled = true;
          }}
        }});

        rows.forEach((row) => {{
          let visible = true;
          if (!includePdfaCovered && row.dataset.pdfaCovered === 'true') {{
            visible = false;
          }}
          for (const filter of filters) {{
            if (filter.disabled) continue;
            const selected = filter.value;
            if (selected === 'ALL') continue;
            const idx = filter.dataset.fileIndex;
            const actual = row.dataset['file' + idx + 'Level'];
            if (actual !== selected) {{
              visible = false;
              break;
            }}
          }}
          row.style.display = visible ? '' : 'none';
          const titleRow = document.querySelector(`.rule-title-row[data-rule-code="${{row.dataset.ruleCode}}"]`);
          if (titleRow) titleRow.style.display = visible ? '' : 'none';
        }});
      }}

      filters.forEach((filter) => filter.addEventListener('change', applyFilters));
      if (showPdfaCovered) showPdfaCovered.addEventListener('change', applyFilters);
      applyFilters();
    }})();
  </script>
</body>
</html>
"""


def html_response(content, status=200):
    return Response(content, status=status, mimetype="text/html")


def incomplete_web_validation(path, report_path, reason):
    item = {"level": "WARN", "code": "VALIDATION_INCOMPLETE", "message": reason,
            "details": {"assessment": "unavailable"}}
    summary = {"file": str(path), "status": "INCOMPLETE", "pass": 0, "fail": 0, "warn": 1, "na": 0,
               "rule_pass": 0, "rule_fail": 0, "rule_warn": 1, "rule_na": max(0, len(RULE_TEXT) - 1),
               "rule_total": len(RULE_TEXT), "validation_scope": "Automated checks; validation did not finish"}
    pdfa = {"status": "ERROR", "ran": False, "compliant": False, "profile_allowed": False,
            "message": reason, "profile_name": ""}
    result = {"summary": summary, "pdfa": pdfa, "items": [item]}
    report_path.write_text("<!doctype html><meta charset='utf-8'><title>Validation incomplete</title>"
                          "<h1>Validation incomplete</h1><p>" + html.escape(str(path.name)) + "</p><p>"
                          + html.escape(reason) + "</p><p>No complete compliance verdict was produced.</p>", encoding="utf-8")
    report_path.with_name(report_path.name + ".json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    result["html_output"] = str(report_path)
    return result


def run_pdf_validation(path):
    job_id = uuid.uuid4().hex
    report_directory = ROOT / ".validator_web_tmp" / "reports" / job_id
    report_directory.mkdir(parents=True, mode=0o700)
    WEB_JOB_MAP[job_id] = report_directory
    report_path = report_directory / (path.name + ".validation.html")
    memory_mb = VALIDATION_MEMORY_MB or max(256, available_memory_bytes() // (2 * MAX_CONCURRENT_JOBS * 1024**2))
    command = [sys.executable, "-B", "-c", VALIDATION_WORKER_SCRIPT,
               str(VALIDATOR_PATH), str(path), str(report_path),
               str(memory_mb),
               str(MAX_WORK_FILE_BYTES)]
    container_name = "eapdf-web-" + job_id
    environment = dict(os.environ, VERAPDF_JOB_CONTAINER=container_name,
                       VALIDATOR_JOB_MEMORY_MB=str(memory_mb), TMPDIR=str(report_directory),
                       EAPDF_VERAPDF_HEAP_MB=str(max(128, memory_mb // 2)),
                       VALIDATOR_CHILD_TEMP_ROOT=str(report_directory / "children"))
    try:
        returncode, stdout, stderr = bounded_subprocess(command, workload_seconds(input_bytes(path), VALIDATION_TIMEOUT), env=environment, work_roots=(report_directory,))
    except (ValueError, OSError) as error:
        return incomplete_web_validation(path, report_path, str(error))
    finally:
        docker = shutil.which("docker")
        if docker:
            try:
                subprocess.run([docker, "rm", "--force", container_name], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=10, check=False)
            except (OSError, subprocess.TimeoutExpired):
                pass
    if returncode not in {0, 2}:
        return incomplete_web_validation(path, report_path, "PDF validation worker failed or exceeded its memory limit.")
    try:
        report_json = report_path.with_name(report_path.name + ".json")
        with report_json.open() if report_json.is_file() else io.StringIO(stdout) as report_input:
            result = json.load(report_input)
        if not all(key in result for key in ("summary", "pdfa", "items")):
            raise ValueError("Missing validation result fields")
    except (ValueError, TypeError):
        return incomplete_web_validation(path, report_path, "PDF validation worker did not return a complete result.")
    result["html_output"] = str(report_path) if report_path.is_file() else None
    return result


def handle_uploads():
    uploads = [f for f in request.files.getlist("pdfs") if getattr(f, "filename", "")]
    if not uploads:
        raise ValueError("Select at least one PDF file.")
    if len(uploads) > MAX_UPLOAD_FILES:
        raise ValueError(f"You can validate at most {MAX_UPLOAD_FILES} PDF files at a time.")

    results = []
    WEB_TMP_ROOT.mkdir(parents=True, exist_ok=True)
    job_id = uuid.uuid4().hex
    stage_dir = WEB_TMP_ROOT / job_id
    stage_dir.mkdir(mode=0o700)
    WEB_JOB_MAP[job_id] = stage_dir
    try:
        for index, upload in enumerate(uploads, start=1):
            filename = Path(upload.filename).name
            if Path(filename).suffix.lower() != ".pdf":
                raise ValueError(f"{filename} is not a PDF file.")
            target = stage_dir / f"{index:02d}_{filename}"
            upload.save(target)
            result = attach_validation_report_link(run_pdf_validation(target))
            result["display_name"] = filename
            result["summary"]["display_name"] = filename
            if "pdfa" in result and isinstance(result["pdfa"], dict):
                result["pdfa"]["display_name"] = filename
            results.append(result)
    finally:
        shutil.rmtree(stage_dir)
    return results


@app.get("/")
def index():
    return html_response(render_page())


@app.get("/generate")
def generate_index():
    return html_response(render_generate_page())


@app.post("/")
def validate_route():
    try:
        with job_slot():
            results = handle_uploads()
        return html_response(render_page(results=results))
    except JobCapacityError as exc:
        return html_response(render_page(error=str(exc)), status=429)
    except ValueError as exc:
        return html_response(render_page(error=str(exc)), status=400)
    except HTTPException:
        raise
    except Exception as exc:
        return html_response(render_page(error=f"Validation failed: {exc}"), status=500)


@app.post("/generate/local")
def generate_local_route():
    try:
        with job_slot():
            result = handle_local_generation()
        status = 200 if result["command_result"]["code"] == 0 else 500
        return html_response(render_generate_page(local_result=result), status=status)
    except JobCapacityError as exc:
        return html_response(render_generate_page(local_error=str(exc)), status=429)
    except ValueError as exc:
        return html_response(render_generate_page(local_error=str(exc)), status=400)
    except HTTPException:
        raise
    except Exception as exc:
        return html_response(render_generate_page(local_error=f"Generation failed: {exc}"), status=500)


@app.get("/generated/<path:file_id>")
def generated_file(file_id):
    path = GENERATED_FILE_MAP.get(file_id) if re.fullmatch(r"[0-9a-f]{32}", file_id) else None
    if path is not None:
        path = Path(path).resolve()
    if path is None or not path.is_file() or path.suffix.lower() != ".pdf" or not path.is_relative_to(OUT_PDF_ROOT.resolve()):
        return html_response("Generated file not found.", status=404)
    return send_file(path, mimetype="application/pdf", as_attachment=False)


@app.get("/validation-report/<report_id>")
def validation_report_file(report_id):
    path = VALIDATION_REPORT_MAP.get(report_id)
    if not path or not Path(path).is_file() or managed_path(path) is None:
        return html_response(render_page(error="Validation report not found."), status=404)
    return send_file(path, mimetype="text/html", as_attachment=False)

@app.before_request
def expire_web_results():
    try:
        remote = not ipaddress.ip_address(request.remote_addr or "").is_loopback
    except ValueError:
        remote = True
    required = remote or os.environ.get("VALIDATOR_REQUIRE_AUTH", "").lower() in ("1", "true", "yes")
    if remote and not request.is_secure:
        return Response("HTTPS is required for remote access.\n", status=503)
    authentication_file = os.environ.get("VALIDATOR_AUTH_FILE", "")
    if required or authentication_file:
        try:
            path = Path(authentication_file)
            if not authentication_file or not path.is_file() or path.stat().st_size > 65536:
                raise ValueError("Authentication file unavailable")
            users = json.loads(path.read_text())
            if not isinstance(users, dict) or not users or any(
                    not isinstance(user, str) or not user or not isinstance(encoded, str)
                    or not encoded.startswith(("scrypt:", "pbkdf2:")) for user, encoded in users.items()):
                raise ValueError("Authentication users unavailable")
        except (OSError, ValueError):
            return Response("Authentication is not configured.\n", status=503)
        authorization = request.authorization
        encoded = users.get(authorization.username, "") if authorization and authorization.type == "basic" else ""
        try:
            authenticated = bool(encoded and encoded.startswith(("scrypt:", "pbkdf2:"))
                                 and check_password_hash(encoded, authorization.password or ""))
        except (ValueError, TypeError):
            authenticated = False
        if not authenticated:
            return Response("Authentication required.\n", status=401,
                            headers={"WWW-Authenticate": 'Basic realm="EA-PDF", charset="UTF-8"'})
    allowed = os.environ.get("VALIDATOR_ALLOWED_HOSTS", "localhost,127.0.0.1,::1")
    if (urlsplit("//" + request.host).hostname or "").lower() not in {host.strip().lower() for host in allowed.split(",")}:
        return Response("Host not allowed.\n", status=400)
    if request.method not in ("GET", "HEAD", "OPTIONS"):
        origin = request.headers.get("Origin") or request.headers.get("Referer")
        try:
            cross_origin = bool(origin and (urlsplit(origin).scheme, urlsplit(origin).netloc) != (request.scheme, request.host))
        except ValueError:
            cross_origin = True
        if cross_origin or request.headers.get("Sec-Fetch-Site") == "cross-site":
            return Response("Cross-origin submission rejected.\n", status=403)
    cleanup_expired_jobs()

@app.errorhandler(413)
def file_too_large(_exc):
    return html_response(render_page(error="Upload too large."), status=413)


@app.after_request
def add_security_headers(resp):
    resp.headers["Cache-Control"] = "no-store"
    resp.headers["X-Content-Type-Options"] = "nosniff"
    resp.headers["X-Frame-Options"] = "SAMEORIGIN"
    resp.headers["Referrer-Policy"] = "strict-origin-when-cross-origin"
    resp.headers["Content-Security-Policy"] = (
        "default-src 'self'; "
        "script-src 'self' 'unsafe-inline'; "
        "style-src 'self' 'unsafe-inline'; "
        "img-src 'self' data:; "
        "font-src 'self' data:; "
        "form-action 'self'; "
        "frame-ancestors 'self'; "
        "base-uri 'self'"
    )
    return resp


def main():
    host = os.environ.get("HOST", "127.0.0.1")
    port = int(os.environ.get("PORT", "8766"))
    print(f"EA-PDF validator web app running at http://{host}:{port}")
    app.run(host=host, port=port)


if __name__ == "__main__":
    main()
