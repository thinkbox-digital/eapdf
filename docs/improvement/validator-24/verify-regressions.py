"""Verify only independently proved operand/child failures are added."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v24'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v23/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-23/custom-regressions.json').read_text())}
oracle = {r['file']: r for r in json.loads((HERE / 'retained-operand-review.json').read_text())}
rows = []
for manifest in sorted((SNAPSHOT / 'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path = manifest.parent / case['file']
        relative = str(path.relative_to(SNAPSHOT))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == case['sha256']
        row = dict(file=relative, sha256=digest)
        for label, engine in [('before', before), ('after', after)]:
            report = engine.validate(str(path))
            row[label] = dict(status=report.summary()['status'], failures=sorted({i['code'] for i in report.items if i['level'] == 'FAIL'}))
        # The inclusion-rule counterexample declares an ISO failure for the
        # complete pipeline; this custom-only gate does not run that engine.
        custom_failures = sorted(set(case['expected_failures']) - {'ISO32005_UPSTREAM'})
        assert row['after'] == dict(status=case['expected_status'], failures=custom_failures), row
        if relative in prior:
            assert digest == prior[relative]['sha256'] == oracle[relative]['sha256']
            assert row['before'] == prior[relative]['after'], row
            extra = ({'CUSTOM_TAG_OPERAND_MATCH'} if oracle[relative]['mismatches'] else set())
            extra |= {'COLLECTION_CHILD_VALIDATION'} if oracle[relative]['children'] else set()
            assert set(row['after']['failures']) == set(row['before']['failures']) | extra, row
        rows.append(row)
        if len(rows) % 50 == 0:
            print('Compared', len(rows), 'cases', flush=True)
assert len(rows) == 207
(HERE / 'custom-regressions.json').write_text(json.dumps(rows, indent=2) + '\n')
print('All 207 exact outcomes match; 202 prior PDF hashes unchanged.', flush=True)
