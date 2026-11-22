"""Locate the creator progress report for a specific process log."""
from pathlib import Path


def creator_progress(log):
    with log.open(errors="replace") as lines:
        for line in lines:
            if line.startswith("Progress: "):
                return Path(line.removeprefix("Progress: ").rstrip("\r\n"))
    raise FileNotFoundError(f"No creator progress report named in {log}")
