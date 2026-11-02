"""Real engine checks for the five newly reached prior range violations."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v38'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after

spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v37/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
rows = json.loads((HERE / 'prior-pdf-results.json').read_text()) if (HERE / 'prior-pdf-results.json').exists() else []
OUT = HERE / 'prior-pdf-reports'
OUT.mkdir(exist_ok=True)
for case in json.loads((HERE / 'prior-range-review.json').read_text())['rows']:
    path = SNAPSHOT / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    for entry, runtime in [('before', before), ('after', after)]:
        existing = next((r for r in rows if r['file'] == case['file'] and r['entry'] == entry), None)
        if existing:
            assert existing['sha256'] == case['sha256'] and existing['failures'] == case[entry]['failures']
            assert existing['status'] == 'FAIL' and existing['pdfa'] == 'PASS' and existing['iso'] == 'N/A'
            assert (OUT / (path.stem + '-' + entry + '.json')).is_file()
            continue
        result = runtime.validate_one(str(path), write_html=False)
        (OUT / (path.stem + '-' + entry + '.json')).write_text(json.dumps(result, indent=2, default=str) + '\n')
        iso = next(i for i in result['items'] if i['code'] == 'ISO32005_UPSTREAM')
        row = dict(file=case['file'], sha256=case['sha256'], entry=entry,
                   status=result['summary']['status'], pdfa=result['pdfa']['status'], iso=iso['level'],
                   failures=sorted({i['code'] for i in result['items'] if i['level'] == 'FAIL'}))
        rows.append(row)
        (HERE / 'prior-pdf-results.json').write_text(json.dumps(rows, indent=2) + '\n')
        print(row, flush=True)
        assert row['status'] == 'FAIL' and row['failures'] == case[entry]['failures'], row
        assert row['pdfa'] == 'PASS' and row['iso'] == 'N/A', row
assert len(rows) == 10
print('Five exact-byte retained negative controls gain only proved PAGE_DPART_PRESENT failures; PDF/A passes and ISO32005 is inapplicable to their unstructured outer PDFs.')
