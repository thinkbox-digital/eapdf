"""Retain real engine reports and assert detected profiles and negative rules."""
import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault('VERAPDF_MOUNT_ROOT', str(ROOT))
os.environ.setdefault('GOMAXPROCS', '2')
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

FIXTURES = ROOT / 'tests/fixtures/eapdf/upstream-profile-controls'
OUT = ROOT / 'docs/improvement/upstream-profile-execution'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    results = []
    errors = []
    for case in json.loads((FIXTURES / 'cases.json').read_text())['cases']:
        path = FIXTURES / case['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
        report = v.run_verapdf(str(path))
        (OUT / (path.stem + '.json')).write_text(json.dumps(report, indent=2, default=str) + '\n')
        failures = [r for r in report.get('rule_summaries', []) if r.get('status') == 'failed']
        row = dict(case, actual=report['status'], actual_profile=report.get('profile_name'),
                   ran=report.get('ran'), engine_version=v.verapdf_reported_version(report), failed_rules=failures)
        results.append(row)
        (OUT / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
        print(path.name, row['actual_profile'], row['actual'], flush=True)
        if not (row['actual'] == case['expected_pdfa'] and row['actual_profile'] == case['profile']
                and row['ran'] and row['engine_version'] == '1.31.18'):
            errors.append(row)
        if case['expected_pdfa'] == 'FAIL' and not any(
                r.get('clause') == case['expected_failure_clause'] and r.get('testNumber') == 1
                and r.get('object') == 'PDAction' for r in failures):
            errors.append(dict(missing_expected_predicate=case))
    assert not errors, json.dumps(errors, indent=2)
    assert set(r['actual_profile'] for r in results) == v.ALLOWED_VERAPDF_PROFILES


if __name__ == '__main__':
    main()
