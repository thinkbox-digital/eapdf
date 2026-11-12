"""Filesystem-backed web job capabilities, expiry and admission slots.

Authentication and policy configuration stay in validator_web; no private paths,
registries or deadline variables are duplicated here.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass
from pathlib import Path
from typing import Callable
import json
import os
import re
import shutil
import tempfile
import time


@dataclass(frozen=True)
class JobSettings:
    root: Path
    output_root: Path
    retention_seconds: int
    request_timeout: int
    capacity: int
    deadline: ContextVar
    managed_path: Callable


def managed_path(path, settings):
    resolved = Path(path).resolve()
    if any(resolved.is_relative_to((settings.root / directory).resolve()) for directory in (".validator_web_tmp", ".generator_web_tmp")):
        return resolved
    if resolved.is_relative_to(settings.output_root.resolve()) and ".web-jobs" in resolved.relative_to(settings.output_root.resolve()).parts:
        return resolved
    return None


class CapabilityRegistry:
    def __init__(self, kind, settings):
        self.kind = kind
        self._settings = settings

    def __setitem__(self, token, path):
        settings = self._settings()
        directory = settings.root / ".validator_web_tmp" / "registry"
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        if not re.fullmatch(r"[0-9a-f]{32}", token):
            raise ValueError("Invalid result identity")
        lifetime = settings.retention_seconds + settings.request_timeout if self.kind == "job" else settings.retention_seconds
        record = {"path": str(Path(path).resolve()), "kind": self.kind,
                  "expires": time.time() + lifetime}
        destination = directory / (token + ".json")
        with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as temporary:
            json.dump(record, temporary)
        os.replace(temporary.name, destination)

    def get(self, token, default=None):
        settings = self._settings()
        if not re.fullmatch(r"[0-9a-f]{32}", token):
            return default
        try:
            record = json.loads((settings.root / ".validator_web_tmp" / "registry" / (token + ".json")).read_text())
            if record["kind"] == self.kind and record["expires"] > time.time():
                return Path(record["path"])
        except (OSError, ValueError, KeyError, TypeError):
            pass
        return default


def cleanup_expired_jobs(settings):
    directory = settings.root / ".validator_web_tmp" / "registry"
    for entry in directory.glob("*.json"):
        try:
            record = json.loads(entry.read_text())
            if record["expires"] > time.time():
                continue
            path = settings.managed_path(record["path"])
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
def job_slot(settings):
    if os.name != "posix":
        raise ValueError("Bounded web jobs require POSIX file locking on this deployment.")
    import fcntl
    directory = settings.root / ".validator_web_tmp" / "slots"
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    for index in range(settings.capacity):
        lock = (directory / str(index)).open("a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            continue
        try:
            deadline_token = settings.deadline.set(time.monotonic() + settings.request_timeout)
            try:
                yield
            finally:
                settings.deadline.reset(deadline_token)
        finally:
            lock.close()
        return
    raise JobCapacityError("All job slots are busy. Please try again shortly.")
