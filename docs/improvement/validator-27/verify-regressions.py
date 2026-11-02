"""Keep prior bytes/outcomes and verify independently reviewed filename cases."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v27'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v26/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
assert hashlib.sha256(Path(before.__file__).read_bytes()).digest() == hashlib.sha256((HERE / 'before-validate_eapdf.py').read_bytes()).digest()
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-26/custom-regressions.json').read_text())}
assert len(prior) == 224
rows = []
for manifest in sorted((SNAPSHOT / 'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path = manifest.parent / case['file']
        relative = str(path.relative_to(SNAPSHOT))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == case['sha256']
        row = dict(file=relative, sha256=digest)
        report = after.validate(str(path))
        row['after'] = dict(status=report.summary()['status'], failures=sorted({i['code'] for i in report.items if i['level'] == 'FAIL'}))
        assert row['after'] == dict(status=case['expected_status'], failures=sorted(set(case['expected_failures']) - {'ISO32005_UPSTREAM'})), row
        if relative in prior:
            assert digest == prior[relative]['sha256'] and row['after'] == prior[relative]['after'], row
            row['before'] = prior[relative]['after']
            row['before_evidence'] = 'Accepted V26 exact-byte outcome'
        else:
            previous = before.validate(str(path))
            row['before'] = dict(status=previous.summary()['status'], failures=sorted({i['code'] for i in previous.items if i['level'] == 'FAIL'}))
        rows.append(row)
        if len(rows) % 50 == 0:
            print('Verified', len(rows), 'custom outcomes', flush=True)
assert len(rows) == 232
assert sum(r['before'] != r['after'] for r in rows) == 6
(HERE / 'custom-regressions.json').write_text(json.dumps(rows, indent=2) + '\n')
print('232 outcomes match; 224 prior PDF hashes/outcomes unchanged; six reviewed filename corrections.', flush=True)
