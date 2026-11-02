"""Build all format/profile examples in parallel and validate with the Java engine."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
from xml.etree import ElementTree as ET
from zipfile import ZipFile

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
SAMPLE_INPUTS = {
    "eml": ("in/Agenda and timeline slides.eml", "in/eml_01_403.eml"),
    "msg": ("in/TxtSampleEmailWithAttachment.msg",
            "in/test.MSG"),
    "pst": ("in/quality-attached-message.pst",),
    "mbox": ("in/enron-sample.mbox",),
}
PROFILES = ("1s", "1si", "1m", "1mi", "1c", "1ci")


def sample_stem(kind, inputs, profile):
    stem = re.sub(r"[^\w.-]+", "_", Path(inputs[0]).stem).strip("._") or "input"
    while len(stem.encode("utf-8")) > 120:
        stem = stem[:-1]
    group = "_archive" if len(inputs) > 1 and profile not in ("1s", "1si") else ""
    return f"{kind}_{stem}{group}_{profile}"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def validator_jar():
    return Path(os.environ.get("EAPDF_VALIDATOR_JAR", ROOT / "dist/eapdf-validator.jar")).resolve()


def parallel_settings():
    cpus = len(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else (os.cpu_count() or 1)
    available_mb = 2048
    try:
        line = next(line for line in Path("/proc/meminfo").read_text().splitlines()
                    if line.startswith("MemAvailable:"))
        available_mb = int(line.split()[1]) // 1024
        limit = Path("/sys/fs/cgroup/memory.max")
        current = Path("/sys/fs/cgroup/memory.current")
        if limit.is_file() and current.is_file() and limit.read_text().strip() != "max":
            available_mb = min(available_mb, max(0, int(limit.read_text()) - int(current.read_text())) // (1024 * 1024))
    except (OSError, ValueError, StopIteration):
        pass
    # Leave room for the desktop and other work. The creator budgets roughly
    # one GiB per rendering worker under its existing default message limits.
    budget_mb = max(512, available_mb // 2)
    requested_jobs = int(os.environ.get("EAPDF_SAMPLE_JOBS", "4"))
    requested_workers = int(os.environ.get("EAPDF_SAMPLE_WORKERS", "2"))
    if requested_jobs < 1 or requested_workers < 1:
        raise ValueError("EAPDF_SAMPLE_JOBS and EAPDF_SAMPLE_WORKERS must be positive")
    jobs = min(requested_jobs, cpus, max(1, budget_mb // 1024))
    heap_mb = 512 if jobs == 1 else 1024 * jobs
    workers = min(requested_workers, len(SAMPLE_INPUTS), max(1, cpus // jobs),
                  max(1, budget_mb // (heap_mb + 512)))
    return jobs, heap_mb, workers


def run_java_validation(pdf, report_path):
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.unlink(missing_ok=True)
    start = time.perf_counter()
    with report_path.with_suffix(".java.log").open("w") as log:
        process = subprocess.run(["java", "-Xmx512m", "-cp", str(validator_jar()),
                                  "eapdf.JavaValidation", str(pdf), str(report_path)],
                                 cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=900)
    if process.returncode not in (0, 2) or not report_path.is_file():
        raise RuntimeError(f"Java validation did not produce a result for {pdf}; see {report_path.with_suffix('.java.log')}")
    result = json.loads(report_path.read_text())
    if (result["summary"]["status"] == "PASS") != (process.returncode == 0):
        raise RuntimeError(f"Java validation exit status disagrees with its report for {pdf}")
    result["sample_execution"] = {"validator": "Java", "seconds": round(time.perf_counter() - start, 3)}
    report_path.write_text(json.dumps(result, indent=2) + "\n")
    return result


def validate_sample(pdf, profile, reports, input_type=None):
    return run_java_validation(pdf, reports / f"{pdf.stem}.json")


def require_sample_validation(profile, result):
    if result["summary"].get("verification_spec") != profile:
        raise RuntimeError(f"{profile} output declares a different EA-PDF profile")
    failures = [item for item in result["items"] if item["level"] == "FAIL"]
    incomplete = any(item["code"] == "VALIDATION_INCOMPLETE" and item["level"] == "WARN"
                     for item in result["items"])
    iso = [item for item in result["items"] if item["code"] == "ISO32005_UPSTREAM"]
    if (result["summary"]["status"] != "PASS" or failures or incomplete or result["pdfa"]["status"] != "PASS"
            or not iso or any(item["level"] != "PASS" for item in iso)):
        raise RuntimeError(f"{profile} did not complete PDF/A, EA-PDF and selected ISO checks successfully")


def embedded_members(document):
    names = document.Root.get("/Names", {}).get("/EmbeddedFiles")
    return list(pikepdf.NameTree(names).items()) if names is not None else []


def extract_single_message(collection, destination, profile):
    """Copy an actual message child unchanged; never relabel a mailbox PDF."""
    with pikepdf.Pdf.open(collection) as document:
        for name, spec in sorted(embedded_members(document), key=lambda item: item[0]):
            if spec.get("/AFRelationship") != pikepdf.Name.Data:
                continue
            ef = spec.get("/EF", {})
            stream = ef.get("/UF", ef.get("/F"))
            if not isinstance(stream, pikepdf.Stream):
                continue
            data = stream.read_bytes()
            if not data.startswith(b"%PDF-"):
                continue
            with pikepdf.Pdf.open(io.BytesIO(data)) as child:
                root = ET.fromstring(child.Root.Metadata.read_bytes())
                field = "{http://www.pdfa.org/eapdf/ns/id/}conformance"
                values = [element.text for element in root.iter(field)]
                values.extend(element.attrib[field] for element in root.iter() if field in element.attrib)
                if values != [profile[1:]]:
                    continue
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
            return {"collection": collection.name, "member": name,
                    "member_sha256": hashlib.sha256(data).hexdigest()}
    raise RuntimeError(f"No {profile} message child found in {collection.name}")


def publish(outputs):
    for source, destination in outputs:
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".sample-",
                                         suffix=destination.suffix, delete=False) as stream:
            temporary = Path(stream.name)
            try:
                with source.open("rb") as input_file:
                    shutil.copyfileobj(input_file, stream)
                stream.flush()
                os.fsync(stream.fileno())
                stream.close()
                temporary.replace(destination)
            finally:
                temporary.unlink(missing_ok=True)


def build_format(kind, inputs, workspace, reports, version, classpath, jobs, heap_mb, workers):
    outputs, records, sidecars, paths = [], [], [], {}
    kind_reports = reports / kind
    kind_reports.mkdir(parents=True, exist_ok=True)
    input_directory = workspace / kind / "inputs"
    input_directory.mkdir(parents=True)
    for source in inputs:
        shutil.copyfile(ROOT / source, input_directory / Path(source).name)
    mailbox = kind in ("pst", "mbox")
    order = ("1m", "1mi", "1c", "1ci", "1s", "1si") if mailbox else PROFILES
    for profile in order:
        destination = workspace / kind / profile
        output_stem = sample_stem(kind, inputs, profile)
        pdf = destination / f"{output_stem}.pdf"
        origin = None
        selected_inputs = inputs if profile not in ("1s", "1si") else inputs[:1]
        if mailbox and profile in ("1s", "1si"):
            origin = extract_single_message(paths["1c" if profile == "1s" else "1ci"], pdf, profile)
        else:
            command = ["java", "-Djava.awt.headless=true", f"-Xmx{heap_mb}m",
                       f"-Deapdf.workerHeapBytes={heap_mb * 1024**2}",
                       f"-Deapdf.workerAddressBytes={(heap_mb + 4096) * 1024**2}", "-cp", classpath,
                       "eapdf.EmailToPDFA", "--jobs", str(jobs), "--out", str(destination)]
            if profile in ("1m", "1mi", "1c", "1ci"):
                command.extend(["--archive-profile", profile[1:], "--archive-output", str(pdf)])
            elif profile == "1si":
                command.append("--omit-source")
            command.append(str(input_directory if len(selected_inputs) > 1 else ROOT / selected_inputs[0]))
            process = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=600)
            (kind_reports / f"{output_stem}.log").write_text(process.stdout + process.stderr)
            if process.returncode != 0:
                raise RuntimeError(f"{kind}/{profile} generation failed; see {kind_reports}")
            if not pdf.exists():
                generated = list(destination.glob("*.pdf"))
                if len(generated) != 1:
                    raise RuntimeError(f"Expected one {kind}/{profile} PDF, got {generated}")
                pdf = generated[0]
        with pikepdf.Pdf.open(pdf) as document:
            expected = f"<xmp:CreatorTool>EmailToPDFA {version}</xmp:CreatorTool>".encode()
            if expected not in document.Root.Metadata.read_bytes():
                raise RuntimeError(f"{kind}/{profile} was generated by a different release than {version}")
            page_count = len(document.pages)
        result = validate_sample(pdf, profile, kind_reports, kind)
        try:
            require_sample_validation(profile, result)
        except RuntimeError as error:
            raise RuntimeError(f"{kind}/{error}; see {kind_reports}") from error
        paths[profile] = pdf
        outputs.append((pdf, ROOT / "examples" / kind / f"{output_stem}.pdf"))
        records.append({"profile": profile, "file": f"{output_stem}.pdf", "sha256": digest(pdf),
                        "pages": page_count, "inputs": list(selected_inputs), "extracted_from": origin,
                        "pdfa": result["pdfa"]["status"], "eapdf": result["summary"]["status"],
                        "selected_iso_structure": "PASS", "validation_seconds": result["sample_execution"]["seconds"]})
        alias = ROOT / "output" / "eml_sample_1s.pdf"
        if kind == "eml" and profile == "1s" and (alias.is_file() or (ROOT / "output/sample_1s.pdf").is_file()):
            outputs.append((pdf, alias))
        for suffix in ("_premis.xml", "_index.xlsx", "_finding_aid.pdf"):
            sidecar = pdf.with_name(pdf.stem + suffix)
            if not sidecar.is_file():
                continue
            check = {"file": f"{output_stem}{suffix}", "sha256": digest(sidecar)}
            if suffix == "_finding_aid.pdf":
                with pikepdf.Pdf.open(sidecar) as document:
                    if (embedded_members(document) or document.Root.get("/AF")
                            or any(isinstance(obj, pikepdf.Stream) and obj.get("/Type") == pikepdf.Name.EmbeddedFile
                                   for obj in document.objects)):
                        raise RuntimeError(f"{kind}/{profile} finding-aid sidecar must not embed files")
                # A finding aid is a plain PDF/A sidecar, not an EA-PDF. Only its
                # real PDF/A engine result is applicable to this check.
                pdfa = run_java_validation(sidecar, kind_reports / f"{output_stem}_finding_aid.java.json")["pdfa"]
                (kind_reports / f"{output_stem}_finding_aid-pdfa.json").write_text(json.dumps(pdfa, indent=2) + "\n")
                if pdfa["status"] != "PASS":
                    raise RuntimeError(f"{kind}/{profile} finding-aid sidecar failed PDF/A; see {kind_reports}")
                check["pdfa"] = "PASS"
            if suffix == "_index.xlsx":
                with ZipFile(sidecar) as workbook:
                    if "xl/workbook.xml" not in workbook.namelist():
                        raise RuntimeError(f"{kind}/{profile} index is not an XLSX workbook")
                check["xlsx_package"] = "PASS"
            sidecars.append(check)
            outputs.append((sidecar, ROOT / "examples" / kind / f"{output_stem}{suffix}"))
        print(f"{kind}/{profile}: Java PDF/A PASS; overall PASS", flush=True)
    manifest = workspace / kind / "manifest.json"
    manifest.write_text(json.dumps({"input_type": kind, "creator_version": version,
        "filename_policy": "Lower-case input type, input filename stem, _archive for multiple inputs, and EA-PDF profile. Single-message mailbox examples contain one extracted child.",
        "creator_sha256": digest(Path(classpath)) if Path(classpath).is_file() else None,
        "validator_sha256": digest(validator_jar()), "rulebook_sha256": digest(ROOT / "validator_rule_text.json"),
        "configuration_sha256": digest(Path(os.environ.get("EAPDF_CONFIG", ROOT / "config/eapdf_config.json"))),
        "sources": {source: digest(ROOT / source) for source in inputs},
        "single_message_policy": "First matching message child, sorted by collection name, copied unchanged from the corresponding collection." if mailbox else "First listed input file.",
        "archive_policy": "Every message in all listed inputs.",
        "validation": "Standalone Java validator, bundled real PDF/A and selected ISO structure checks, including collection children; default validator limits unchanged.",
        "parallelism": {"creator_jobs": jobs, "creator_heap_mb": heap_mb, "concurrent_formats": workers},
        "samples": sorted(records, key=lambda row: PROFILES.index(row["profile"])), "sidecars": sidecars}, indent=2) + "\n")
    outputs.append((manifest, ROOT / "examples" / kind / "manifest.json"))
    return outputs


def main():
    version = (ROOT / "VERSION").read_text().strip()
    classpath = os.environ.get("EAPDF_GENERATOR_CLASSPATH", str(ROOT / "target" / f"emailtopdfa-{version}.jar"))
    reports = Path(os.environ.get("EAPDF_SAMPLE_REPORT_DIR") or tempfile.mkdtemp(prefix="eapdf-sample-reports-"))
    reports.mkdir(parents=True, exist_ok=True)
    with ZipFile(validator_jar()) as jar:
        if jar.read("validator/validator_rule_text.json") != (ROOT / "validator_rule_text.json").read_bytes():
            raise RuntimeError("Rebuild the standalone Java validator: its bundled rules differ from the current JSON")
    jobs, heap_mb, workers = parallel_settings()
    print(f"Using Java validation; {workers} concurrent formats; {jobs} creator workers with {heap_mb} MB heap each process.", flush=True)
    source_hashes = {source: digest(ROOT / source) for inputs in SAMPLE_INPUTS.values() for source in inputs}
    with tempfile.TemporaryDirectory(prefix="eapdf-samples-", dir="/tmp") as directory:
        workspace = Path(directory)
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(build_format, kind, inputs, workspace, reports, version, classpath,
                                   jobs, heap_mb, workers) for kind, inputs in SAMPLE_INPUTS.items()]
            outputs = [item for future in futures for item in future.result()]
        if source_hashes != {source: digest(ROOT / source) for source in source_hashes}:
            raise RuntimeError("An original input changed during regeneration; examples were not published")
        # Publish only after the complete format/profile matrix passes.
        publish(outputs)
        published = {destination for _, destination in outputs}
        for kind in (None, *SAMPLE_INPUTS):
            folder = ROOT / "examples" if kind is None else ROOT / "examples" / kind
            for profile in PROFILES:
                for suffix in (".pdf", "_premis.xml", "_index.xlsx", "_finding_aid.pdf"):
                    stems = [f"sample_{profile}"] if kind is None else [f"sample_{profile}", f"{kind}_sample_{profile}"]
                    for stem in stems:
                        old = folder / (stem + suffix)
                        if old not in published:
                            old.unlink(missing_ok=True)
        if ROOT / "output/eml_sample_1s.pdf" in published:
            (ROOT / "output/sample_1s.pdf").unlink(missing_ok=True)
    print(f"Updated {len(SAMPLE_INPUTS) * len(PROFILES)} email samples for {version}; reports: {reports}")


if __name__ == "__main__":
    main()
