"""Actual PDF/A/ISO execution, exact-byte before/after custom checks and entry points."""
import hashlib,importlib.util,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT))
import validate_eapdf as v
import validator_web as web
spec=importlib.util.spec_from_file_location('v60',ROOT/'.eapdf-verification-v60/validate_eapdf.py');before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
OUT=HERE/'pdf-reports';OUT.mkdir(exist_ok=True)
rows=[]
def record(path,result,entry,key=None):
 key=key or path.stem
 (OUT/(key+'-'+entry+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 iso=next(i for i in result['items'] if i['code']=='ISO32005_UPSTREAM')
 row=dict(file=str(path.relative_to(ROOT)),key=key,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),entry=entry,status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=iso['level'],profile_verified=(iso.get('details') or {}).get('profile_verified'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 rows.append(row);(HERE/'pdf-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
 return row
for directory in ('validator-leaf-identities','validator-local-decisions'):
 d=ROOT/'tests/fixtures/eapdf'/directory
 for case in json.loads((d/'expectations.json').read_text())['cases']:
  path=d/case['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
  old=before.validate(str(path))
  (OUT/(path.stem+'-before-custom.json')).write_text(json.dumps(dict(summary=old.summary(),items=old.items),indent=2,default=str)+'\n')
  row=record(path,v.validate_one(str(path),write_html=False),'after')
  assert row['pdfa']=='PASS',row
  assert row['iso']=='PASS' and row['profile_verified'],row
  assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row
for name,entry in [('contents-missing','cli'),('reply-bag','web-worker')]:
 path=ROOT/'tests/fixtures/eapdf/validator-local-decisions'/(name+'.pdf')
 if entry=='cli':
  p=subprocess.run([sys.executable,'-B','validate_eapdf.py',str(path),'--json'],cwd=ROOT,capture_output=True,text=True,timeout=240)
  assert p.returncode==2,(p.returncode,p.stderr);result=json.loads(p.stdout)
 else:result=web.run_pdf_validation(path.resolve())
 row=record(path,result,entry)
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
 assert row['failures']==(['ANNOTATION_CONTENTS_REQUIRED'] if entry=='cli' else [])
prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-60/pdf-results.json').read_text()) if r['entry']=='retained-profile'}
for p in json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']:
 key=p['mode']+'-'+p['profile'];original=json.loads((ROOT/p['report']).read_text());path=Path(original['summary']['file'])
 assert hashlib.sha256(path.read_bytes()).hexdigest()==prior[key]['sha256']
 row=record(path,v.validate_one(str(path),write_html=False),'retained-profile',key)
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
 assert row['status']=='FAIL' and row['failures']==prior[key]['failures'],row
print('18 controls, CLI, worker and twelve retained creator diagnoses matched.',flush=True)
