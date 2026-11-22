#!/usr/bin/env python3
"""Verify or reproduce the bundled java-libpst bounds patch with JDK 21."""

import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LOCK_PATH = ROOT / "third_party/java-libpst/lock.json"
SOURCE = "com/pff/PSTNodeInputStream.java"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def archive(path, files):
    with zipfile.ZipFile(path, "w") as output:
        for name in sorted(files):
            entry = zipfile.ZipInfo(name, (2026, 11, 22, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            output.writestr(entry, files[name], zipfile.ZIP_DEFLATED, 9)


def contents(path):
    with zipfile.ZipFile(path) as source:
        return {entry.filename: source.read(entry) for entry in source.infolist() if not entry.is_dir()}


def build(lock, destination, local_inputs=None):
    with tempfile.TemporaryDirectory(prefix="eapdf-libpst-build-") as work:
        work = Path(work)
        for artifact in lock["inputs"]:
            name = artifact["name"]
            if local_inputs is None:
                with urlopen(artifact["url"], timeout=60) as response:
                    data = response.read()
            else:
                data = (local_inputs / name).read_bytes()
            if digest(data) != artifact["sha256"]:
                raise SystemExit("Upstream checksum mismatch: " + name)
            (work / name).write_bytes(data)
        upstream = "java-libpst-0.9.3"
        version = lock["version"]
        binary = contents(work / (upstream + ".jar"))
        sources = contents(work / (upstream + "-sources.jar"))
        source = work / SOURCE
        source.parent.mkdir(parents=True)
        source.write_bytes(sources[SOURCE].replace(b"\r\n", b"\n"))
        subprocess.run(["git", "apply", str(ROOT / lock["patch"]["path"])], cwd=work, check=True)
        classes = work / "classes"
        subprocess.run(["javac", "--release", "8", "-g", "-encoding", "UTF-8", "-cp",
                        str(work / (upstream + ".jar")), "-d", str(classes), str(source)], check=True)
        for compiled in classes.rglob("*.class"):
            binary[compiled.relative_to(classes).as_posix()] = compiled.read_bytes()
        sources[SOURCE] = source.read_bytes()
        pom = (work / (upstream + ".pom")).read_bytes().replace(
            b"<version>0.9.3</version>", b"<version>" + version.encode() + b"</version>", 1)
        for files in (binary, sources):
            for name in list(files):
                if name.endswith("/pom.xml"):
                    files[name] = pom
                elif name.endswith("/pom.properties"):
                    files[name] = f"groupId=com.pff\nartifactId=java-libpst\nversion={version}\n".encode()
        prefix = f"java-libpst-{version}"
        archive(destination / (prefix + ".jar"), binary)
        archive(destination / (prefix + "-sources.jar"), sources)
        (destination / (prefix + ".pom")).write_bytes(pom)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rebuild", action="store_true")
    parser.add_argument("--local-inputs", type=Path, help="Directory holding the pinned upstream artifacts")
    args = parser.parse_args()
    lock = json.loads(LOCK_PATH.read_text())
    for item in [lock["patch"], *lock["artifacts"]]:
        if digest((ROOT / item["path"]).read_bytes()) != item["sha256"]:
            raise SystemExit("Checksum mismatch: " + item["path"])
    if args.rebuild:
        with tempfile.TemporaryDirectory(prefix="eapdf-libpst-verify-") as temporary:
            destination = Path(temporary)
            build(lock, destination, args.local_inputs)
            for item in lock["artifacts"]:
                if digest((destination / Path(item["path"]).name).read_bytes()) != item["sha256"]:
                    raise SystemExit("Rebuild mismatch: " + item["path"])
    print("java-libpst bounds patch and artifacts verified.")


if __name__ == "__main__":
    main()
