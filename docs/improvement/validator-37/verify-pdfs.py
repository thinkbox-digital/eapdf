"""Optional DPart controls, real entry points and twelve fresh retained profile diagnoses."""
import hashlib,importlib.util,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v37'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(SNAPSHOT))
import validate_eapdf as after
import validator_web as web
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v36/validate_eapdf.py')
before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
assert Path(before.__file__).read_bytes()==(HERE/'before-validate_eapdf.py').read_bytes()
directory=SNAPSHOT/'tests/fixtures/eapdf/validator-dpart-metadata'
cases=json.loads((directory/'expectations.json').read_text())['cases']
OUT=HERE/'pdf-reports';OUT.mkdir(exist_ok=True)
rows=json.loads((HERE/'pdf-results.json').read_text()) if '--resume' in sys.argv else []
old={Path(r['file']).stem:r for r in json.loads((ROOT/'docs/improvement/validator-36/next-dpart-metadata-probes/results.json').read_text())}
assert len(old)==10

def record(path,result,label,key=None,reused=None):
    key=key or path.stem
    (OUT/(key+'-'+label+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
    iso=next(i for i in result['items'] if i['code']=='ISO32005_UPSTREAM')
    row=dict(file=key,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),entry=label,status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=iso['level'],profile_verified=(iso.get('details') or {}).get('profile_verified'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
    if reused:row['reused_before_report']=reused
    rows.append(row);(HERE/'pdf-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
    assert row['pdfa']==row['iso']=='PASS' and row['profile_verified'],row
    return row

def existing(path,label,key=None):
    row=next((r for r in rows if r['file']==(key or path.stem) and r['entry']==label),None)
    if row:assert row['sha256']==hashlib.sha256(path.read_bytes()).hexdigest()
    return row

for case in cases:
    path=directory/case['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
    if not existing(path,'before'):
        previous=old.get(path.stem)
        if previous:
            assert previous['sha256']==case['sha256']
            saved=ROOT/'docs/improvement/validator-36/next-dpart-metadata-probes'/path.with_suffix('.json').name
            record(path,json.loads(saved.read_text()),'before',reused=str(saved.relative_to(ROOT)))
        else:record(path,before.validate_one(str(path),write_html=False),'before')
    row=existing(path,'after') or record(path,after.validate_one(str(path),write_html=False),'after')
    assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row

for name,entry in [('metadata-stream.pdf','cli'),('dpm-key-nmtoken.pdf','web-worker')]:
    path=directory/name;case=next(c for c in cases if c['file']==name)
    if existing(path,entry):continue
    if entry=='cli':
        proc=subprocess.run([sys.executable,'-B','validate_eapdf.py',str(path),'--json','--html',str(OUT/(path.stem+'.html'))],cwd=SNAPSHOT,capture_output=True,text=True,timeout=240)
        (OUT/(path.stem+'.stderr')).write_text(proc.stderr);assert proc.returncode==2,(proc.returncode,proc.stderr)
        result=json.loads(proc.stdout)
    else:result=web.run_pdf_validation(path.resolve())
    row=record(path,result,entry);assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row

# Exercise all six original profiles in both normal/accessibility modes on exact retained bytes.
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())
prior={r['profile']:r for r in json.loads((ROOT/'docs/improvement/validator-35/retained-pdfa4-preconditions.json').read_text())['rows']}
for profile in diagnosis['profiles']:
    key=profile['mode']+'-'+profile['profile']
    original=json.loads((ROOT/profile['report']).read_text());path=Path(original['summary']['file'])
    assert hashlib.sha256(path.read_bytes()).hexdigest()==prior[key]['sha256']
    row=existing(path,'retained-profile',key) or record(path,after.validate_one(str(path),write_html=False),'retained-profile',key)
    assert row['status']=='FAIL' and row['failures']==profile['failures'],row
assert len(rows)==46
print('Sixteen exact-byte DPart controls, two entry points and twelve fresh retained profile diagnoses match; ten exact V36 before-runs reused.',flush=True)
