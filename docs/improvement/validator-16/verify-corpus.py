"""Complete frozen V16 validation of the eight probe PDFs."""
from concurrent.futures import ProcessPoolExecutor,as_completed
from pathlib import Path
import hashlib
import importlib.util
import json

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
OUT=HERE/'corpus'
OUT.mkdir(exist_ok=True)


def check(case):
    spec=importlib.util.spec_from_file_location('after',ROOT/'.eapdf-verification-v16/validate_eapdf.py')
    engine=importlib.util.module_from_spec(spec);spec.loader.exec_module(engine)
    path=ROOT/'.eapdf-verification-v16/tests/fixtures/eapdf/validator-attachment-evidence'/case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
    result=engine.validate_one(str(path),write_html=False)
    (OUT/(path.name+'.json')).write_text(json.dumps(result,indent=2,default=str))
    iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
    return dict(file=case['file'],sha256=case['sha256'],pdfa=result['pdfa']['status'],iso=iso['level'],
                profile_verified=iso.get('details',{}).get('profile_verified'),status=result['summary']['status'],
                failures=sorted({item['code'] for item in result['items'] if item['level']=='FAIL'}),
                expected_status=case['expected_status'],expected_failures=case['expected_failures'],
                unavailable_match=all(any(item['code']==code and item['level']=='WARN' for item in result['items'])
                                      for code in case['required_unavailable_codes']))


if __name__=='__main__':
    rows=[]
    cases=json.loads((ROOT/'.eapdf-verification-v16/tests/fixtures/eapdf/validator-attachment-evidence/expectations.json').read_text())['cases']
    with ProcessPoolExecutor(max_workers=2) as pool:
        for future in as_completed([pool.submit(check,case) for case in cases]):
            row=future.result();rows.append(row);print(row,flush=True)
    rows.sort(key=lambda row:row['file'])
    (HERE/'corpus-results.json').write_text(json.dumps(rows,indent=2)+'\n')
    assert all(row['pdfa']==row['iso']=='PASS' and row['profile_verified'] for row in rows)
    assert all(row['status']==row['expected_status'] and row['failures']==row['expected_failures'] and row['unavailable_match'] for row in rows)
    print('All eight frozen complete-pipeline outcomes match',flush=True)
