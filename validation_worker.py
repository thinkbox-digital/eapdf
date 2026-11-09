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


def available_memory_bytes():
    """Available host/container memory before applying a job share or override."""
    available = None
    try:
        for line in Path('/proc/meminfo').read_text().splitlines():
            if line.startswith('MemAvailable:'):
                available = int(line.split()[1]) * 1024
                break
    except OSError:
        pass
    if available is None:
        try:
            available = os.sysconf('SC_AVPHYS_PAGES') * os.sysconf('SC_PAGE_SIZE')
        except (ValueError, OSError, AttributeError):
            raise RuntimeError('Cannot determine available validation memory; set VALIDATOR_JOB_MEMORY_MB.')
    for limit, used in (('/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/memory.current'),
                        ('/sys/fs/cgroup/memory/memory.limit_in_bytes', '/sys/fs/cgroup/memory/memory.usage_in_bytes')):
        try:
            available = min(available, max(0, int(Path(limit).read_text()) - int(Path(used).read_text())))
        except (OSError, ValueError):
            pass
    return max(1, available)


def memory_allowance_bytes(workers=1):
    configured = os.environ.get('VALIDATOR_JOB_MEMORY_MB', '').strip()
    return max(1, int(configured)) * 1024**2 if configured else max(1, available_memory_bytes() // (2 * max(1, workers)))


def disk_allowances(root, workers=1):
    share = max(1, shutil.disk_usage(root).free // (2 * max(1, workers)))
    return (max(1, int(os.environ.get('VALIDATOR_MAX_WORK_FILE_BYTES', max(1, share // 2)))),
            max(1, int(os.environ.get('VALIDATOR_MAX_SCRATCH_BYTES', share))))


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
        return _run_cli(script, arguments)
    except (OSError, ValueError, RuntimeError, MemoryError) as error:
        return 2, b"", b"", "Validation could not complete: " + type(error).__name__ + ": " + str(error)


def _run_cli(script, arguments):
    try:
        import resource
    except ImportError:
        return 2, b"", b"", "Native worker resource limits are unavailable on this platform."
    timeout = max(1, int(os.environ.get("VALIDATOR_JOB_TIMEOUT", "600") or "600"))
    memory = max(1, memory_allowance_bytes() // 1024**2)
    scratch_root = Path(__file__).resolve().parent / ".generator_web_tmp"
    scratch_root.mkdir(exist_ok=True, mode=0o700)
    maximum, scratch_maximum = disk_allowances(scratch_root)
    identity = "eapdf-web-" + uuid.uuid4().hex
    environment = dict(os.environ, EAPDF_VALIDATION_WORKER="1", VERAPDF_JOB_CONTAINER_PREFIX=identity,
                       EAPDF_VERAPDF_HEAP_MB=str(max(128, memory // 2)), VALIDATOR_JOB_MEMORY_MB=str(memory))
    bootstrap = (
        "import os,resource,runpy,sys; "
        "memory=int(sys.argv[1]);maximum=int(sys.argv[2]); "
        "hard=max(memory,int(os.environ.get('VALIDATOR_DOCKER_CLIENT_MEMORY_MB','4096'))*1024**2); "
        "resource.setrlimit(resource.RLIMIT_AS,(memory,hard)); "
        "resource.setrlimit(resource.RLIMIT_FSIZE,(maximum,maximum)); "
        "soft,hard=resource.getrlimit(resource.RLIMIT_NOFILE); "
        "requested=max(1,int(os.environ.get('VALIDATOR_MAX_OPEN_FILES',str(soft if hard==resource.RLIM_INFINITY else hard)))); "
        "limit=requested if hard==resource.RLIM_INFINITY else min(requested,hard); "
        "resource.setrlimit(resource.RLIMIT_NOFILE,(limit,limit)); "
        "resource.setrlimit(resource.RLIMIT_CORE,(0,0)); "
        "sys.argv=sys.argv[3:];runpy.run_path(sys.argv[0],run_name='__main__')"
    )
    process = None
    with tempfile.TemporaryDirectory(prefix="eapdf-python-validator-", dir=scratch_root) as directory:
        root = Path(directory)
        environment.update(TMPDIR=directory, VALIDATOR_CHILD_TEMP_ROOT=str(root / "children"))
        reason = None
        def over_budget():
            size = 0
            for path in root.rglob("*"):
                try:
                    if path.is_symlink() or not path.is_file(): continue
                    size += path.stat().st_size
                except FileNotFoundError:
                    continue
                if size > scratch_maximum:
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
                output_limit = min(maximum, memory * 1024**2 // 4)
                stdout.seek(0); output = stdout.read(min(os.fstat(stdout.fileno()).st_size, output_limit + 1))
                stderr.seek(0); errors = stderr.read(min(os.fstat(stderr.fileno()).st_size, output_limit + 1))
                if len(output) > output_limit or len(errors) > output_limit:
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
