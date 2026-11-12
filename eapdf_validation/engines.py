"""veraPDF/Docker and pinned ISO-profile adapters without rule-engine ownership.

Root-module facades supply current configuration and callbacks on each call;
patching the public validator functions continues to affect adapter execution.
"""
from dataclasses import dataclass
from pathlib import Path
import hashlib
import json
import os
import re
import uuid


@dataclass(frozen=True)
class EngineSettings:
    image: str
    container: str
    mount_root: Path


def _verapdf_default_result(path, status="SKIP", message="veraPDF validation was not run", extra=None, *, image):
    base={
        "file": str(path),
        "tool": "veraPDF CLI (Docker)",
        "image": image,
        "status": status,
        "message": message,
        "ran": False,
        "compliant": None,
        "profile_name": "",
        "statement": "",
        "job_end_status": "",
        "passed_rules": 0,
        "failed_rules": 0,
        "passed_checks": 0,
        "failed_checks": 0,
        "rule_summaries": [],
        "profile_allowed": None,
        "stderr_severity": "",
    }
    if extra:
        base.update(extra)
    return base


def _verapdf_stderr_severity(stderr):
    text=(stderr or "").strip()
    if not text:
        return ""
    severe_patterns=(
        r"(^|\b)SEVERE:",
        r"(^|\b)ERROR:",
        r"(^|\b)FATAL:",
        r"\b[A-Za-z0-9_.]*(Exception|Error)\b",
    )
    for pattern in severe_patterns:
        if re.search(pattern, text, flags=re.IGNORECASE | re.MULTILINE):
            return "ERROR"
    if re.search(r"(^|\b)WARNING:", text, flags=re.IGNORECASE | re.MULTILINE):
        return "WARN"
    return "INFO"


def _docker_work_path(pdf_path, *, mount_root):
    try:
        rel=pdf_path.resolve().relative_to(mount_root)
    except Exception:
        return None
    return f"/work/{rel.as_posix()}"


def ensure_verapdf_daemon(*, settings, executables, processes):
    docker=executables.which("docker")
    if not docker:
        return False, "Docker is not available on PATH."
    inspect_cmd=[docker, "inspect", "-f", "{{.State.Running}}", settings.container]
    try:
        proc=processes.run(inspect_cmd, capture_output=True, text=True, timeout=20, check=False)
        if proc.returncode == 0:
            if (proc.stdout or "").strip() == "true":
                return True, "running"
            started=processes.run([docker, "start", settings.container], capture_output=True, text=True, timeout=20, check=False)
            if started.returncode == 0:
                return True, "started"
            return False, (started.stderr or started.stdout or "Unable to start veraPDF daemon.").strip()
    except Exception as e:
        return False, str(e)

    run_cmd=[
        docker, "run", "-d",
        "--name", settings.container,
        "-v", f"{settings.mount_root}:/work",
        "--entrypoint", "sleep",
        settings.image,
        "infinity",
    ]
    try:
        proc=processes.run(run_cmd, capture_output=True, text=True, timeout=30, check=False)
        if proc.returncode == 0:
            return True, "created"
        return False, (proc.stderr or proc.stdout or "Unable to create veraPDF daemon.").strip()
    except Exception as e:
        return False, str(e)


def _verapdf_timeout(pdf_path, *, configured_timeout):
    if configured_timeout > 0:
        return configured_timeout
    try:
        size = Path(pdf_path).stat().st_size
    except OSError:
        size = 0
    return min(86400, max(600, 600 + size // (128 * 1024)))


def _verapdf_heap_mb():
    # The web parent divides its job allowance between Python and veraPDF.
    assigned = os.environ.get("EAPDF_VERAPDF_HEAP_MB", "")
    if assigned:
        return max(128, int(assigned))
    available = 1024**3
    try:
        available = int(re.search(r"MemAvailable:\s+(\d+)", Path("/proc/meminfo").read_text()).group(1)) * 1024
    except (OSError, AttributeError):
        pass
    for limit, used in (("/sys/fs/cgroup/memory.max", "/sys/fs/cgroup/memory.current"),
                        ("/sys/fs/cgroup/memory/memory.limit_in_bytes", "/sys/fs/cgroup/memory/memory.usage_in_bytes")):
        try:
            available = min(available, max(0, int(Path(limit).read_text()) - int(Path(used).read_text())))
        except (OSError, ValueError):
            pass
    return max(128, available // (4 * 1024**2))


def _verapdf_command(pdf_path, output_format, profile_path=None, *, settings, work_path, heap_for, timeout_for, ensure_daemon, executables):
    docker=executables.which("docker")
    if not docker:
        return None
    job_container=os.environ.get("VERAPDF_JOB_CONTAINER","")
    job_prefix=os.environ.get("VERAPDF_JOB_CONTAINER_PREFIX", "")
    if job_prefix and not job_container:
        if not re.fullmatch(r"eapdf-web-[0-9a-f]{32}", job_prefix): raise ValueError("Invalid job container prefix")
        job_container="eapdf-web-"+uuid.uuid4().hex
    heap_mb = heap_for()
    java_options = f"JAVA_OPTS=-Xmx{heap_mb}m -XX:ActiveProcessorCount=4"
    timeout = timeout_for(pdf_path)
    daemon_ok=False
    if not job_container:
        daemon_ok,_ = ensure_daemon()
    elif not re.fullmatch(r"eapdf-web-[0-9a-f]{32}",job_container):
        raise ValueError("Invalid job container identity")
    daemon_path = work_path(pdf_path)
    user_options=["--user",f"{os.getuid()}:{os.getgid()}"] if hasattr(os,"getuid") else []
    daemon_profile = work_path(profile_path) if profile_path else None
    if daemon_ok and daemon_path and (profile_path is None or daemon_profile):
        return [
            docker, "exec", *user_options, "-e", java_options, settings.container,
            "timeout", "-k", "5", str(timeout), "/opt/verapdf/verapdf",
            "--format", output_format,
            *(["--profile", daemon_profile] if profile_path else []),
            daemon_path,
        ]
    container_options=["--name",job_container] if job_container else []
    if job_prefix: container_options += ["--label", "eapdf.validation-job=" + job_prefix]
    return [
        docker,"run","--rm",*container_options,*user_options,
        "--memory",f"{heap_mb + max(256, heap_mb // 2)}m","--cpus","4","--pids-limit","128","--network","none",
        "-e",java_options,
        "-v",f"{pdf_path.parent}:/work:ro",
        *(["-v",f"{profile_path.parent}:/eapdf-profile:ro"] if profile_path else []),
        "--entrypoint","timeout",
        settings.image,
        "-k","5",str(timeout),"/opt/verapdf/verapdf",
        "--format",output_format,
        *(["--profile",f"/eapdf-profile/{profile_path.name}"] if profile_path else []),
        f"/work/{pdf_path.name}",
    ]


def run_verapdf(path, profile_path=None, expected_profile=None, *, allowed_profiles, command_for, default_result, severity_for, timeout_for, executables, processes):
    from validation_worker import docker_command
    docker=executables.which("docker")
    if not docker:
        return default_result(path, "SKIP", "veraPDF was skipped because Docker is not available on PATH.")
    pdf_path=Path(path).resolve()
    cmd=command_for(pdf_path, "json", profile_path) if profile_path else command_for(pdf_path, "json")
    if cmd is None:
        return default_result(path, "SKIP", "veraPDF was skipped because Docker is not available on PATH.")
    timeout = timeout_for(pdf_path)
    try:
        proc=processes.run(docker_command(cmd),capture_output=True,text=True,timeout=timeout + 10,check=False)
    except processes.TimeoutExpired:
        return default_result(path, "ERROR", f'veraPDF timed out after {timeout} seconds.')
    except Exception as e:
        return default_result(path, "ERROR", f"veraPDF could not be started: {e}")
    finally:
        job_container=cmd[cmd.index("--name")+1] if "--name" in cmd else ""
        if re.fullmatch(r"eapdf-web-[0-9a-f]{32}",job_container):
            try:
                processes.run(docker_command([docker,"rm","--force",job_container]),stdout=processes.DEVNULL,
                               stderr=processes.DEVNULL,timeout=10,check=False)
            except (OSError,processes.TimeoutExpired):
                pass

    stdout=(proc.stdout or "").strip()
    stderr=(proc.stderr or "").strip()
    stderr_severity=severity_for(stderr)
    if not stdout:
        message="veraPDF returned no JSON output."
        if stderr:
            message += f" stderr: {stderr}"
        return default_result(path, "ERROR", message, {"exit_code": proc.returncode, "stderr": stderr, "stderr_severity": stderr_severity})
    json_text=stdout
    if not json_text.startswith("{"):
        brace_index=json_text.find("{")
        if brace_index >= 0:
            json_text=json_text[brace_index:]
    try:
        payload=json.loads(json_text)
    except Exception as e:
        message=f"veraPDF returned unreadable JSON: {e}"
        if stderr:
            message += f" stderr: {stderr}"
        return default_result(path, "ERROR", message, {"exit_code": proc.returncode, "stdout": stdout[:4000], "stderr": stderr, "stderr_severity": stderr_severity})

    try:
        report=payload.get("report",{})
        jobs=report.get("jobs",[])
        job=jobs[0] if jobs else {}
        validations=job.get("validationResult",[])
        validation=validations[0] if validations else {}
        details=validation.get("details",{})
        compliant=validation.get("compliant")
        status="PASS" if compliant is True else ("FAIL" if compliant is False else "ERROR")
        message=validation.get("statement") or ("PDF/A validation passed." if compliant else "PDF/A validation failed.")
        result=default_result(path, status, message, {
            "ran": True,
            "compliant": compliant,
            "profile_name": validation.get("profileName",""),
            "statement": validation.get("statement",""),
            "job_end_status": validation.get("jobEndStatus",""),
            "passed_rules": details.get("passedRules",0),
            "failed_rules": details.get("failedRules",0),
            "passed_checks": details.get("passedChecks",0),
            "failed_checks": details.get("failedChecks",0),
            "rule_summaries": details.get("ruleSummaries",[]) or [],
            "exit_code": proc.returncode,
            "raw_json": payload,
            "stderr_severity": stderr_severity,
        })
        result["profile_allowed"]=(result["profile_name"] == expected_profile if expected_profile else
                                   result["profile_name"] in allowed_profiles) if result["profile_name"] else False
        if not result["profile_allowed"]:
            result["status"]="FAIL" if result["profile_name"] else "ERROR"
            result["message"]=(f'veraPDF did not use the required profile "{expected_profile}".' if expected_profile else
                                f'veraPDF validated against "{result["profile_name"]}", which is outside the EA-PDF-allowed PDF/A flavours.')
        if stderr_severity == "ERROR":
            if result["status"] != "FAIL": result["status"]="ERROR"
            if result.get("statement"):
                result["message"]=f'{result["statement"]} However, veraPDF also reported stderr errors.'
            else:
                result["message"]="veraPDF reported stderr errors while processing this file."
        if stderr:
            result["stderr"]=stderr
        if proc.returncode != 0:
            if result["status"] != "FAIL": result["status"]="ERROR"
            result["message"]="veraPDF did not complete successfully."
        return result
    except Exception as e:
        return default_result(path, "ERROR", f"veraPDF JSON did not match the expected report shape: {e}", {"exit_code": proc.returncode, "payload": payload})


def run_iso32005(path, *, root, pin_config, run_pdfa, default_result):
    """Run pinned upstream predicates; no local implementation of ISO inclusion rules."""
    pin=pin_config
    profile_path=root/pin["profile"]
    try:
        digest=hashlib.sha256(profile_path.read_bytes()).hexdigest()
        if digest != pin["profile_sha256"]:
            raise ValueError("ISO 32005 profile bytes differ from the reviewed profile")
    except (OSError,ValueError) as error:
        return default_result(path,"ERROR",str(error),{"profile_verified":False})
    result=run_pdfa(path,profile_path=profile_path,expected_profile=pin["profile_name"])
    result.update(profile_sha256=digest,selected_rules=pin["selected_rules"],profile_verified=False)
    try:
        upstream=result.get("raw_json",{}).get("report",{})
        jobs=upstream.get("jobs",[])
        versions={r["id"]:r["version"] for r in upstream.get("buildInformation",{}).get("releaseDetails",[])}
        normal=(len(jobs)==1 and len(jobs[0].get("validationResult",[]))==1
                and result.get("job_end_status")=="normal")
        counts=(type(result.get("passed_rules")) is int and type(result.get("failed_rules")) is int
                and result["passed_rules"]>=0 and result["failed_rules"]>=0
                and result["passed_rules"]+result["failed_rules"]==pin["selected_rules"])
        trusted=(normal and counts and result.get("status") in {"PASS","FAIL"}
                 and result.get("ran") is True and result.get("profile_allowed") is True
                 and result.get("profile_name")==pin["profile_name"]
                 and all(versions.get(k)==v for k,v in pin["reported_versions"].items())
                 and result.get("stderr_severity")!="ERROR" and result.get("exit_code") in (0,1))
        compliant=result.get("compliant")
        consistent=((compliant is True and result.get("failed_rules")==0 and result.get("exit_code")==0)
                    or (compliant is False and result.get("failed_rules",0)>0))
        if trusted and consistent:
            result.update(status="PASS" if compliant else "FAIL",profile_verified=True)
        else:
            result.update(status="ERROR",message="ISO 32005 execution could not be verified: "+result.get("message",""))
    except (TypeError,KeyError,AttributeError):
        result.update(status="ERROR",message="ISO 32005 report shape could not be verified")
    return result
