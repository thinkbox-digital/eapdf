"""Compare final Java/Python outcomes on small positive and negative PDFs."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from hashlib import sha256
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator


def levels(items, codes):
    rank = {"N/A": 0, "PASS": 1, "WARN": 2, "FAIL": 3}
    result = {}
    for item in items:
        if item["code"] in codes and rank[item["level"]] > rank.get(result.get(item["code"]), -1):
            result[item["code"]] = item["level"]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("cases", type=Path)
    parser.add_argument("jar", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    cases = json.loads(args.cases.read_text())
    codes = set(json.loads((ROOT / "validator_rule_text.json").read_text()))
    rulebook_sha256 = sha256((ROOT / "validator_rule_text.json").read_bytes()).hexdigest()

    def run(case):
        name = case["case"]
        source = Path(case["path"])
        digest = sha256(source.read_bytes()).hexdigest()
        started = time.monotonic()
        java_path = args.output / (name + ".java.json")
        command = ["java", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-cp", str(args.jar),
                   "eapdf.JavaValidation", str(source), str(java_path)]
        with (args.output / (name + ".java.log")).open("w") as log:
            java_run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        java = json.loads(java_path.read_text())
        with tempfile.TemporaryDirectory(prefix="rule-refinement-parity-", dir=ROOT / ".generator_web_tmp") as folder:
            staged = Path(folder) / source.name
            shutil.copy2(source, staged)
            assert sha256(staged.read_bytes()).hexdigest() == digest
            python = validator.validate_one(str(staged), html_path=args.output / (name + ".python.html"))
            python["tested_sha256"] = digest
            (args.output / (name + ".python.json")).write_text(json.dumps(python, indent=2) + "\n")
        assert sha256(source.read_bytes()).hexdigest() == digest
        java_levels = levels(java["items"], codes)
        python_levels = levels(python["items"], codes)
        differences = [{"code": code, "java": java_levels.get(code), "python": python_levels.get(code)}
                       for code in sorted(codes) if java_levels.get(code) != python_levels.get(code)]
        failures = {code for code, level in java_levels.items() if level == "FAIL"}
        expected_target = case.get("expected_failure_rule")
        result = {"case": name, "path": str(source), "sha256": digest,
                  "expected_status": case["expected_status"], "java_status": java["summary"]["status"],
                  "python_status": python["summary"]["status"], "python_pdfa": python["pdfa"]["status"],
                  "java_pdfa": java["pdfa"]["status"],
                  "java_exit": java_run.returncode, "rule_count": len(codes), "differences": differences,
                  "failed_codes": sorted(failures), "expected_failure_rule": expected_target,
                  "target_failed": expected_target in failures if expected_target else None,
                  "seconds": round(time.monotonic() - started, 3)}
        result["independent_rule_results"] = [
            {"code": code, "allowed": allowed, "java": java_levels.get(code), "python": python_levels.get(code)}
            for code, allowed in case.get("expected_rule_results", {}).items()]
        result["rulebook_hashes_match"] = (java["summary"].get("rulebook_sha256")
            == python["summary"].get("rulebook_sha256") == rulebook_sha256)
        result["checks_pass"] = (not differences and len(java_levels) == len(python_levels) == len(codes)
            and result["java_status"] == result["python_status"] == case["expected_status"]
            and result["python_pdfa"] == result["java_pdfa"] == "PASS"
            and result["rulebook_hashes_match"] and (not expected_target or result["target_failed"])
            and all(row["java"] in row["allowed"] and row["python"] in row["allowed"]
                    for row in result["independent_rule_results"]))
        print(json.dumps(result), flush=True)
        return result

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(run, cases))
    receipt = {"status": "PASS" if all(row["checks_pass"] for row in results) else "FAIL",
               "java_jar_sha256": sha256(args.jar.read_bytes()).hexdigest(),
               "python_sha256": sha256((ROOT / "validate_eapdf.py").read_bytes()).hexdigest(),
               "rules_sha256": sha256((ROOT / "validator_rule_text.json").read_bytes()).hexdigest(),
               "results": results}
    (args.output / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    if receipt["status"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
