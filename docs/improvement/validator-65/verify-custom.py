"""Inherit unchanged V64 receipts by hash and execute twelve new controls."""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v65'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as validator

read = lambda p: json.loads(p.read_text())
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
current = read(HERE / 'verification-inputs.json')
prior = read(ROOT / 'docs/improvement/validator-64/verification-inputs.json')
for name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py',
             'config/verapdf/ISO-32005.xml', 'config/verapdf/effective-profile-review.json'):
    if name in prior:
        assert current[name] == prior[name] == sha(SNAPSHOT / name)
rows = []
for row in read(ROOT / 'docs/improvement/validator-64/custom-regressions.json'):
    assert sha(SNAPSHOT / row['file']) == row['sha256']
    rows.append(dict(row, before=row['after'], inherited_from='validator-64/custom-regressions.json'))
assert len(rows) == 591
directory = SNAPSHOT / 'tests/fixtures/eapdf/validator-coverage-branches'
for case in read(directory / 'expectations.json')['cases']:
    path = directory / case['file']
    assert sha(path) == case['sha256']
    report = validator.validate(str(path))
    result = dict(status=report.summary()['status'], failures=sorted({
        item['code'] for item in report.items if item['level'] == 'FAIL'}))
    assert result == dict(status=case['expected_status'], failures=case['expected_failures'])
    levels = {}
    for item in report.items:
        levels.setdefault(item['code'], set()).add(item['level'])
    for code, expected in case['expected_levels'].items():
        assert expected in levels.get(code, set())
        assert expected == 'FAIL' or 'FAIL' not in levels[code]
    rows.append(dict(file=str(path.relative_to(SNAPSHOT)), sha256=case['sha256'],
                     before=result, after=result, observed_levels={k: sorted(v) for k, v in levels.items()}))
assert len(rows) == 603
(HERE / 'custom-regressions.json').write_text(json.dumps(rows, indent=2) + '\n')
print('591 unchanged-runtime V64 receipts verified by hash; twelve new custom controls match.')
