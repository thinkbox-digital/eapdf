"""Compare complete custom outcomes and source media reporting on exact bytes."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v26'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v25/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
assert hashlib.sha256(Path(before.__file__).read_bytes()).digest() == hashlib.sha256((HERE / 'before-validate_eapdf.py').read_bytes()).digest()
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-25/custom-regressions.json').read_text())}
assert len(prior) == 215
rows = []
order = {'N/A': 0, 'PASS': 1, 'WARN': 2, 'FAIL': 3}
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
            row[label + '_media_rules'] = {code: max((i['level'] for i in report.items if i['code'] == code), key=order.get, default='N/A')
                                          for code in ('SOURCE_MEDIATYPE_PARAMETERS_MATCH', 'EMBEDDED_SUBTYPE_MATCH_XMP')}
        assert row['after'] == dict(status=case['expected_status'], failures=sorted(set(case['expected_failures']) - {'ISO32005_UPSTREAM'})), row
        assert row['before'] == row['after'], row
        if relative in prior:
            assert digest == prior[relative]['sha256'] and row['before'] == prior[relative]['after'], row
        if manifest.parent.name == 'validator-source-parameters':
            assert row['after_media_rules']['SOURCE_MEDIATYPE_PARAMETERS_MATCH'] == case['expected_parameter_level'], row
        rows.append(row)
        if len(rows) % 50 == 0:
            print('Compared', len(rows), 'custom outcomes/media fields', flush=True)
assert len(rows) == 224
(HERE / 'custom-regressions.json').write_text(json.dumps(rows, indent=2) + '\n')
changed = [r for r in rows if r['before_media_rules'] != r['after_media_rules']]
print('All 224 status/failure outcomes match; all 215 prior PDF hashes/outcomes unchanged;', len(changed), 'media-reporting changes.', flush=True)
