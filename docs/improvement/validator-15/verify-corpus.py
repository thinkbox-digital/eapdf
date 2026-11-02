"""Complete current and prior validation of the eight identical identity probe PDFs."""
from concurrent.futures import ProcessPoolExecutor, as_completed
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
OUT=HERE/'corpus'
OUT.mkdir(exist_ok=True)


def check(case):
    path=ROOT/'.eapdf-verification-v15/tests/fixtures/eapdf/validator-attachment-identity'/case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
    row=dict(file=case['file'],sha256=case['sha256'],expected_failures=case['expected_failures'],expected_status=case['expected_status'])
    for label,snapshot in [('before','.eapdf-verification-v14-accepted'),('after','.eapdf-verification-v15')]:
        spec=importlib.util.spec_from_file_location(label,ROOT/snapshot/'validate_eapdf.py')
        engine=importlib.util.module_from_spec(spec); spec.loader.exec_module(engine)
        result=engine.validate_one(str(path),write_html=False)
        (OUT/(label+'--'+case['file']+'.json')).write_text(json.dumps(result,indent=2,default=str))
        iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
        row[label]=dict(pdfa=result['pdfa']['status'],status=result['summary']['status'],iso=iso['level'],
                        profile_verified=iso.get('details',{}).get('profile_verified'),
                        failures=sorted({item['code'] for item in result['items'] if item['level']=='FAIL'}))
    return row


if __name__=='__main__':
    cases=json.loads((ROOT/'.eapdf-verification-v15/tests/fixtures/eapdf/validator-attachment-identity/expectations.json').read_text())['cases']
    rows=[]
    with ProcessPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(check,case) for case in cases]):
            row=future.result();rows.append(row);print(row,flush=True)
    rows.sort(key=lambda row:row['file'])
    (HERE/'corpus-results.json').write_text(json.dumps(rows,indent=2)+'\n')
    assert len(rows)==8
    assert all(row['before']['status']=='PASS' and row['before']['failures']==[] for row in rows)
    assert all(row['after']['failures']==row['expected_failures'] and row['after']['status']==row['expected_status'] for row in rows)
    assert all(row[label]['pdfa']==row[label]['iso']=='PASS' and row[label]['profile_verified'] for row in rows for label in ('before','after'))
    print('All eight identical-byte before/after cases meet exact expectations',flush=True)
