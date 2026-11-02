"""Full before/after probes, followed by actual CLI and isolated web workers."""
from pathlib import Path
import hashlib
import importlib.util
import json
import os
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v21-final'
os.environ['VERAPDF_MOUNT_ROOT']=str(ROOT)
sys.path.insert(0,str(FROZEN))
import validate_eapdf as after
import validator_web as web
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v20/validate_eapdf.py')
before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
directory=FROZEN/'tests/fixtures/eapdf/validator-ambiguous-bytes'
cases=json.loads((directory/'expectations.json').read_text())['cases']
for case in cases:case['relative_file']='validator-ambiguous-bytes/'+case['file']
prior=next(c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-equivalence/expectations.json').read_text())['cases'] if c['file']=='isolated-ambiguous-size.pdf')
prior['relative_file']='validator-attachment-equivalence/'+prior['file'];cases.append(prior)
prior=next(c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-streams/expectations.json').read_text())['cases'] if c['file']=='ambiguous-records.pdf')
prior['relative_file']='validator-attachment-streams/'+prior['file'];cases.append(prior)
rows=json.loads((HERE/'pdf-results.json').read_text()) if '--resume' in sys.argv else []
if rows:
    assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in rows)
    assert all(any(c['file']==r['file'] and c['sha256']==r['sha256'] for c in cases) for r in rows)
    print('Reusing',len(rows),'completed checks on the same frozen inputs.',flush=True)


def record(path,result,label):
    output=HERE/'pdf-reports';output.mkdir(exist_ok=True)
    (output/(path.stem+'-'+label+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
    iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
    row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),entry=label,
             pdfa=result['pdfa']['status'],iso=iso['level'],profile_verified=iso['details'].get('profile_verified'),
             status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
    print(row,flush=True)
    assert row['pdfa']==row['iso']=='PASS' and row['profile_verified'],row
    rows.append(row)
    (HERE/'pdf-results.json').write_text(json.dumps(rows,indent=2)+'\n')
    return row


for case in cases:
    path=FROZEN/'tests/fixtures/eapdf'/case['relative_file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
    if not any(r['file']==path.name and r['entry']=='before' for r in rows):
        record(path,before.validate_one(str(path),write_html=False),'before')
    existing=next((r for r in rows if r['file']==path.name and r['entry']=='after'),None)
    row=existing or record(path,after.validate_one(str(path),write_html=False),'after')
    assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row

for case in cases:
    if case['file'] not in ('all-sizes-wrong.pdf','distinct-checksums-impossible.pdf','distinct-sizes-compatible.pdf','equivalent-control.pdf'):
        continue
    path=FROZEN/'tests/fixtures/eapdf'/case['relative_file']
    for entry in ('cli','web-worker'):
        if any(r['file']==path.name and r['entry']==entry for r in rows):
            continue
        if entry=='cli':
            result=subprocess.run([sys.executable,'-B','validate_eapdf.py',str(path),'--json','--html',str(HERE/'pdf-reports'/(path.stem+'.html'))],
                                  cwd=FROZEN,capture_output=True,text=True,timeout=240)
            assert result.returncode in (0,2),(result.returncode,result.stderr)
            (HERE/'pdf-reports'/(path.stem+'.stderr')).write_text(result.stderr)
            result=json.loads(result.stdout)
        else:
            result=web.run_pdf_validation(path.resolve())
        row=record(path,result,entry)
        assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row
assert len(rows)==32
print('Twelve before/after PDF probes and eight CLI/worker outcomes match.',flush=True)
