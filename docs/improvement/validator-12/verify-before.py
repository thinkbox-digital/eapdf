"""Validate final retained V12 bytes with the unchanged frozen V11 engine."""
from pathlib import Path
import hashlib
import json
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / '.eapdf-verification-v11'))
import validate_eapdf as v

directory = ROOT / 'tests/fixtures/eapdf/validator-asset-ownership'
output = Path(__file__).with_name('before-reports')
output.mkdir(exist_ok=True)
rows = []
for case in json.loads((directory / 'expectations.json').read_text())['cases']:
    path = directory / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    result = v.validate_one(str(path), write_html=False)
    (output / (path.name + '.json')).write_text(json.dumps(result, indent=2, default=str))
    row = dict(file=path.name, sha256=case['sha256'], pdfa=result['pdfa']['status'],
               status=result['summary']['status'],
               failures=sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'}))
    rows.append(row)
    print(row, flush=True)
Path(__file__).with_name('before-final-fixtures.json').write_text(json.dumps(rows, indent=2) + '\n')
