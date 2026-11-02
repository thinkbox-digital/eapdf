"""Disposable controller for the Python command-line validator (no shell)."""
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import uuid


def docker_client_memory_mb():
    return max(128, int(os.environ.get("VALIDATOR_DOCKER_CLIENT_MEMORY_MB", "4096") or "4096"))


def docker_command(command):
    """Give only the trusted Docker executable its own bounded address allowance.

    Python retains its soft parser limit; the controller supplies enough hard
    headroom for this exec. No preexec_fn is used in threaded validation paths.
    """
    if os.environ.get("EAPDF_VALIDATION_WORKER") != "1" or os.name != "posix":
        return command
    bootstrap = (
        "import os,resource,sys; "
        "requested=int(sys.argv[1]);soft,hard=resource.getrlimit(resource.RLIMIT_AS); "
        "limit=requested if hard==resource.RLIM_INFINITY else min(requested,hard); "
        "resource.setrlimit(resource.RLIMIT_AS,(limit,hard)); "
        "os.execvp(sys.argv[2],sys.argv[2:])"
    )
    return [sys.executable, "-B", "-c", bootstrap, str(docker_client_memory_mb() * 1024**2), *command]


def run_cli(script, arguments):
    try:
        import resource
    except ImportError:
        return 2, b"", b"", "Native worker resource limits are unavailable on this platform."
    timeout = max(1, int(os.environ.get("VALIDATOR_JOB_TIMEOUT", "600") or "600"))
    memory = max(128, int(os.environ.get("VALIDATOR_JOB_MEMORY_MB", "512") or "512"))
    maximum = max(1, int(os.environ.get("VALIDATOR_MAX_WORK_FILE_BYTES", str(64 * 1024**2))))
    scratch_maximum = max(1, int(os.environ.get("VALIDATOR_MAX_SCRATCH_BYTES", str(512 * 1024**2))))
    identity = "eapdf-web-" + uuid.uuid4().hex
    environment = dict(os.environ, EAPDF_VALIDATION_WORKER="1", VERAPDF_JOB_CONTAINER_PREFIX=identity,
                       EAPDF_VERAPDF_HEAP_MB=str(max(128, memory // 2)))
    bootstrap = (
        "import os,resource,runpy,sys; "
        "memory=int(sys.argv[1]);maximum=int(sys.argv[2]); "
        "hard=max(memory,int(os.environ.get('VALIDATOR_DOCKER_CLIENT_MEMORY_MB','4096'))*1024**2); "
        "resource.setrlimit(resource.RLIMIT_AS,(memory,hard)); "
        "resource.setrlimit(resource.RLIMIT_FSIZE,(maximum,maximum)); "
        "resource.setrlimit(resource.RLIMIT_NOFILE,(256,256)); "
        "resource.setrlimit(resource.RLIMIT_CORE,(0,0)); "
        "sys.argv=sys.argv[3:];runpy.run_path(sys.argv[0],run_name='__main__')"
    )
    process = None
    with tempfile.TemporaryDirectory(prefix="eapdf-python-validator-", dir="/tmp") as directory:
        root = Path(directory)
        environment.update(TMPDIR=directory, VALIDATOR_CHILD_TEMP_ROOT=str(root / "children"))
        reason = None
        def over_budget():
            size = count = 0
            for path in root.rglob("*"):
                try:
                    if path.is_symlink() or not path.is_file(): continue
                    size += path.stat().st_size; count += 1
                except FileNotFoundError:
                    continue
                if size > scratch_maximum or count > 10000:
                    return True
            return False
        try:
            with (root / "stdout").open("w+b") as stdout, (root / "stderr").open("w+b") as stderr:
                process = subprocess.Popen([sys.executable, "-B", "-c", bootstrap,
                    str(memory * 1024**2), str(maximum), str(script), *arguments], env=environment,
                    stdout=stdout, stderr=stderr, start_new_session=True)
                deadline = time.monotonic() + timeout
                while process.poll() is None:
                    if time.monotonic() > deadline:
                        reason = "Validation exceeded its time limit."; break
                    if over_budget(): reason = "Validation exceeded its scratch/report budget."
                    if reason: break
                    try: process.wait(timeout=0.2)
                    except subprocess.TimeoutExpired: pass
                if reason:
                    try: os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                code = process.wait()
                if reason is None and over_budget():
                    reason = "Validation exceeded its scratch/report budget."
                stdout.seek(0); output = stdout.read(maximum + 1)
                stderr.seek(0); errors = stderr.read(maximum + 1)
                if len(output) > maximum or len(errors) > maximum:
                    reason = "Validation exceeded its output budget."
                if code not in (0, 2) or not output:
                    reason = reason or f"Validation worker stopped before completion (exit {code})."
                return code, output if reason is None else b"", errors[:maximum], reason
        finally:
            if process is not None and process.poll() is None:
                try: os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                process.wait()
            docker = shutil.which("docker")
            if docker:
                try:
                    result = subprocess.run([docker, "ps", "-aq", "--filter", "label=eapdf.validation-job=" + identity], capture_output=True, text=True, timeout=10)
                    identifiers = result.stdout.split()
                    if identifiers: subprocess.run([docker, "rm", "--force", *identifiers], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
                except (OSError, subprocess.TimeoutExpired): pass
