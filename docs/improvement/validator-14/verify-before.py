"""Exact final V14 probe bytes validated by unchanged frozen V13."""
from pathlib import Path
import hashlib
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'.eapdf-verification-v13-final'))
import validate_eapdf as v
HERE=Path(__file__).resolve().parent
OUT=HERE/'before-reports'
OUT.mkdir(exist_ok=True)
directory=ROOT/'.eapdf-verification-v14/tests/fixtures/eapdf/validator-attachment-streams'
rows=[]
for case in json.loads((directory/'expectations.json').read_text())['cases']:
    path=directory/case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
    result=v.validate_one(str(path),write_html=False)
    (OUT/(path.name+'.json')).write_text(json.dumps(result,indent=2,default=str))
    iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
    row=dict(file=path.name,sha256=case['sha256'],pdfa=result['pdfa']['status'],status=result['summary']['status'],
             iso=iso['level'],failures=sorted({item['code'] for item in result['items'] if item['level']=='FAIL'}))
    rows.append(row)
    print(row,flush=True)
(HERE/'before-results.json').write_text(json.dumps(rows,indent=2)+'\n')
assert all(row['pdfa']==row['iso']=='PASS' for row in rows)
