"""Keep prior bytes/outcomes and verify independently reviewed version and schema name cases."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v40'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v39/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
assert hashlib.sha256(Path(before.__file__).read_bytes()).digest() == hashlib.sha256((HERE / 'before-validate_eapdf.py').read_bytes()).digest()
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-39/custom-regressions.json').read_text())}
assert len(prior) == 367
checkpoint_path = HERE / 'custom-regressions-checkpoint.json'
checkpoint_key = hashlib.sha256((HERE / 'verification-inputs.json').read_bytes()).hexdigest()
checkpoint = json.loads(checkpoint_path.read_text()) if checkpoint_path.exists() else dict(inputs_sha256=checkpoint_key, rows=[])
assert checkpoint['inputs_sha256'] == checkpoint_key
cached = {row['file']: row for row in checkpoint['rows']}
rows = []
for manifest in sorted((SNAPSHOT / 'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path = manifest.parent / case['file']
        relative = str(path.relative_to(SNAPSHOT))
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        assert digest == case['sha256']
        row = dict(file=relative, sha256=digest)
        if relative in cached:
            assert cached[relative]['sha256'] == digest
            row = dict(cached[relative])
        else:
            report = after.validate(str(path))
            row['after'] = dict(status=report.summary()['status'], failures=sorted({i['code'] for i in report.items if i['level'] == 'FAIL'}))
            row['observed_levels'] = {code: sorted({item['level'] for item in report.items if item['code'] == code}) for code in sorted(report.seen_codes)}
        assert row['after'] == dict(status=case['expected_status'], failures=sorted(set(case['expected_failures']) - {'ISO32005_UPSTREAM'})), row
        if relative in prior:
            assert digest == prior[relative]['sha256'], row
            assert row['after'] == prior[relative]['after'], row
            row['before'] = prior[relative]['after']
            row['title_before'] = prior[relative]['observed_levels']['XMP_TITLE']
            row['before_evidence'] = 'Accepted V39 exact-byte outcome'
        else:
            previous = before.validate(str(path))
            row['title_before'] = sorted({i['level'] for i in previous.items if i['code'] == 'XMP_TITLE'})
            row['before'] = dict(status=previous.summary()['status'], failures=sorted({i['code'] for i in previous.items if i['level'] == 'FAIL'}))
        row['title_after'] = row['observed_levels']['XMP_TITLE']
        if 'expected_title' in case:
            assert row['title_after'] == [case['expected_title']], row
        rows.append(row)
        checkpoint['rows'] = rows + [entry for key, entry in cached.items() if key not in {r['file'] for r in rows}]
        checkpoint_path.write_text(json.dumps(checkpoint, indent=2) + '\n')
        if len(rows) % 50 == 0:
            print('Verified', len(rows), 'custom outcomes', flush=True)
assert len(rows) == 377
assert sum(r['before'] != r['after'] for r in rows) == 5
(HERE / 'custom-regressions.json').write_text(json.dumps(rows, indent=2) + '\n')
print('377 custom outcomes match; all 367 prior hashes/outcomes remain; five exact-name/ASCII encoding false passes now fail.', flush=True)
