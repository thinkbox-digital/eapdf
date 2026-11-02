#!/usr/bin/env python3
"""Development-only full comparison against the current Python validator."""
import sys
sys.dont_write_bytecode = True

import argparse
import concurrent.futures
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
PINS = {'validate_eapdf.py': '4c70d558b8484bba7ecf59461915e5b3279c28163bd583ddbc13b422c1358d2d', 'validator_rule_text.json': '36f7731d64f56e902d729ea8481a60413af609bc624c7e232f8b20f7e5db47d7', 'config/verapdf/ISO-32005.xml': 'a2181a59dcaef27d20cc3078aa8b63e63fe528908a69f985a0b56b714c62d7a1', 'config/verapdf/ISO-32005.json': '6bdd077d49c5ac1d86cc769e9a4dae2e1049ee8796fa82a7cb2ae33c230e7ea2'}
ORDER = {"N/A": 0, "PASS": 1, "WARN": 2, "FAIL": 3}


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    with gzip.open(path, "rt", encoding="utf-8") as stream:
        return json.load(stream)


def python_case(row, output, staging):
    target = Path(output) / (row["id"] + ".python.json.gz")
    if target.exists():
        return row["id"]
    os.environ["VALIDATOR_CHILD_TEMP_ROOT"] = staging
    spec = importlib.util.spec_from_file_location("comparison_frozen_validator", ROOT / "validate_eapdf.py")
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    context = validator.ChildValidationContext(include_pdfa=True)
    try:
        result = validator.validate_one(row["absolute_path"], write_html=False, child_context=context)
        result["comparison_children"] = context.cache
    except Exception as error:
        result = {"comparison_error": repr(error)}
    temporary = target.with_suffix(f".{os.getpid()}.part")
    with gzip.open(temporary, "wt", encoding="utf-8", compresslevel=2) as stream:
        json.dump(result, stream, ensure_ascii=True)
    temporary.replace(target)
    return row["id"]


def vector(result, rules):
    levels = {code: "MISSING" for code in rules}
    for item in result.get("items", []):
        code, level = item["code"], item["level"]
        if code in levels and ORDER[level] > ORDER.get(levels[code], -1):
            levels[code] = level
    return levels


def summarize(inputs, output, jar):
    rules = json.loads((ROOT / "validator_rule_text.json").read_text())
    rows, frequencies = [], {}
    for row in inputs:
        paths = [output / (row["id"] + "." + engine + ".json.gz") for engine in ("python", "java")]
        if not all(path.exists() for path in paths):
            rows.append({"path": row["path"], "missing_output": True})
            continue
        old, new = map(read, paths)
        old_vector, new_vector = vector(old, rules), vector(new, rules)
        differences = {code: {"python": old_vector[code], "java": new_vector[code]}
                       for code in rules if old_vector[code] != new_vector[code]}
        for code in differences:
            frequencies[code] = frequencies.get(code, 0) + 1
        rows.append({"id": row["id"], "path": row["path"], "sha256": row["sha256"],
                     "python_status": old.get("summary", {}).get("status"),
                     "java_status": new.get("summary", {}).get("status"),
                     "java_port_complete": new.get("summary", {}).get("java_port_complete", False),
                     "python_pdfa": old.get("pdfa", {}).get("status"),
                     "java_pdfa": new.get("pdfa", {}).get("status"),
                     "differences": differences,
                     "python_error": old.get("comparison_error"), "java_error": new.get("comparison_error"),
                     "python_child_hashes": sorted(old.get("comparison_children", {})),
                     "java_child_hashes": sorted(new.get("comparison_children", {}))})
    result = {"scope": "Every saved tests/fixtures/eapdf PDF, eight example PDFs and the two protected reference PDFs; complete real upstream runs, no mocks.",
              "rules": len(rules), "cases": len(rows), "jar_sha256": sha(jar), "frozen_hashes": PINS,
              "port_guard_retained": any(not row.get("java_port_complete", False) for row in rows), "differences_by_rule": dict(sorted(frequencies.items(), key=lambda item: (-item[1], item[0]))),
              "status_matches": sum(r.get("python_status") == r.get("java_status") for r in rows),
              "pdfa_matches": sum(r.get("python_pdfa") == r.get("java_pdfa") for r in rows),
              "rows": rows}
    (output / "comparison.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("/tmp/eapdf-validator-compare"))
    parser.add_argument("--jar", type=Path, default=ROOT / "dist/eapdf-validator.jar")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--summarize-only", action="store_true")
    parser.add_argument("--python-only", action="store_true")
    parser.add_argument("--java-only", action="store_true")
    parser.add_argument("--python-start", type=int, default=0)
    args = parser.parse_args()
    if args.python_only and args.java_only:
        parser.error("Choose only one engine-only mode")
    for name, expected in PINS.items():
        if sha(ROOT / name) != expected:
            raise RuntimeError("Pinned validator input changed: " + name)
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    paths = sorted((ROOT / "tests/fixtures/eapdf").rglob("*.pdf"))
    paths += sorted((ROOT / "examples").glob("sample_*.pdf"))
    paths += [ROOT / "output/Agenda and timeline slides_000001.pdf", ROOT / "output/bfo.pdf"]
    inputs = [{"id": hashlib.sha256(str(path.relative_to(ROOT)).encode()).hexdigest()[:20],
               "path": str(path.relative_to(ROOT)), "absolute_path": str(path), "sha256": sha(path)}
              for path in paths if path.is_file()]
    manifest = output / "inputs.json"
    if manifest.exists() and json.loads(manifest.read_text()) != inputs:
        raise RuntimeError("Input corpus changed; choose a fresh output directory")
    manifest.write_text(json.dumps(inputs, indent=2) + "\n")
    if not args.summarize_only:
        engine_record = {"jar_sha256": sha(args.jar), "frozen_hashes": PINS}
        engine_path = output / "engines.json"
        if engine_path.exists() and json.loads(engine_path.read_text()) != engine_record:
            raise RuntimeError("Engine changed; choose a fresh output directory to avoid stale cached results")
        engine_path.write_text(json.dumps(engine_record, indent=2) + "\n")
        classes = output / "runner-classes"; classes.mkdir(exist_ok=True)
        java = Path(shutil.which("java")).resolve()
        javac = java.with_name("javac")
        subprocess.run([str(javac), "-cp", str(args.jar), "-d", str(classes),
                        str(ROOT / "validator-desktop/src/test/java/eapdf/ValidatorComparisonRunner.java")], check=True)
        shared = ROOT / ".generator_web_tmp"
        shared.mkdir(exist_ok=True)
        staging = tempfile.mkdtemp(prefix="java-comparison-", dir=shared)
        native, logs = [], []
        try:
            for index in range(0 if args.python_only else args.jobs):
                log = (output / f"java-{index}.log").open("w"); logs.append(log)
                native.append(subprocess.Popen([str(java), "-Xmx512m", "-cp", str(classes) + os.pathsep + str(args.jar),
                    "eapdf.ValidatorComparisonRunner", str(manifest), str(output), str(index), str(args.jobs)], stdout=log, stderr=subprocess.STDOUT))
            started = time.monotonic()
            with concurrent.futures.ProcessPoolExecutor(max_workers=args.jobs) as executor:
                futures = {executor.submit(python_case, row, str(output), staging) for row in ([] if args.java_only else inputs[args.python_start:])}
                while futures:
                    done, futures = concurrent.futures.wait(futures, timeout=10, return_when=concurrent.futures.FIRST_COMPLETED)
                    for future in done:
                        future.result()
                    if time.monotonic() - started >= 10:
                        print(f"Python {len(list(output.glob('*.python.json.gz')))}/{len(inputs)}; Java {len(list(output.glob('*.java.json.gz')))}/{len(inputs)}", flush=True)
                        started = time.monotonic()
            for process in native:
                if process.wait() != 0:
                    raise RuntimeError("Java comparison worker failed; inspect its log and resume")
        finally:
            for process in native:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait()
            for log in logs:
                log.close()
            shutil.rmtree(staging)
    result = summarize(inputs, output, args.jar)
    print(json.dumps({key: value for key, value in result.items() if key not in {"rows", "frozen_hashes"}}, indent=2))


if __name__ == "__main__":
    main()
