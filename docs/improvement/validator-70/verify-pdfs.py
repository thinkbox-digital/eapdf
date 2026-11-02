"""Actual PDF/A/ISO execution, exact-byte before/after custom checks and entry points."""
import hashlib,importlib.util,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v70'))
import validate_eapdf as v
import validator_web as web
spec=importlib.util.spec_from_file_location('v69',ROOT/'.eapdf-verification-v69/validate_eapdf.py');before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
OUT=HERE/'pdf-reports';OUT.mkdir(exist_ok=True)
rows=json.loads((HERE/'pdf-results.json').read_text()) if (HERE/'pdf-results.json').exists() else []
def record(path,result,entry,key=None):
 key=key or path.stem
 (OUT/(key+'-'+entry+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 iso=next(i for i in result['items'] if i['code']=='ISO32005_UPSTREAM')
 row=dict(file=str(path.relative_to(ROOT)),key=key,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),entry=entry,status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=iso['level'],profile_verified=(iso.get('details') or {}).get('profile_verified'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 rows.append(row);(HERE/'pdf-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
 return row
for directory in ('validator-empty-raw-text',):
 d=ROOT/'tests/fixtures/eapdf'/directory
 for case in json.loads((d/'expectations.json').read_text())['cases']:
  path=d/case['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
  if any(row['key']==path.stem and row['entry']=='after' for row in rows):continue
  old=before.validate(str(path))
  (OUT/(path.stem+'-before-custom.json')).write_text(json.dumps(dict(summary=old.summary(),items=old.items),indent=2,default=str)+'\n')
  row=record(path,v.validate_one(str(path),write_html=False),'after')
  assert row['pdfa']=='PASS',row
  assert row['iso']==case.get('expected_iso','PASS'),row
  if row['iso']=='PASS':assert row['profile_verified'],row
  assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row
  for code,level in case.get('expected_levels',{}).items():
   levels={item['level'] for item in json.loads((OUT/(path.stem+'-after.json')).read_text())['items'] if item['code']==code}
   assert level in levels and ('FAIL' not in levels if level!='FAIL' else True),(case,code,levels)
for name,entry in [('from-empty','cli'),('from-original-empty','web-worker')]:
 path=ROOT/'tests/fixtures/eapdf/validator-empty-raw-text'/(name+'.pdf')
 if any(row['key']==name and row['entry']==entry for row in rows):continue
 if entry=='cli':
  p=subprocess.run([sys.executable,'-B',str(ROOT/'.eapdf-verification-v70/validate_eapdf.py'),str(path),'--json'],cwd=ROOT,capture_output=True,text=True,timeout=240)
  assert p.returncode==2,(p.returncode,p.stderr);result=json.loads(p.stdout)
 else:result=web.run_pdf_validation(path.resolve())
 row=record(path,result,entry)
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
 assert row['failures']==(['MESSAGE_RAW_FROM_XMP'] if entry=='cli' else [])
prior={r.get('key',r['file']):r for r in json.loads((ROOT/'docs/improvement/validator-69/pdf-results.json').read_text()) if r['entry']=='retained-profile'}
for p in json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']:
 key=p['mode']+'-'+p['profile'];original=json.loads((ROOT/p['report']).read_text());path=Path(original['summary']['file'])
 assert hashlib.sha256(path.read_bytes()).hexdigest()==prior[key]['sha256']
 if any(row['key']==key and row['entry']=='retained-profile' for row in rows):continue
 row=record(path,v.validate_one(str(path),write_html=False),'retained-profile',key)
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
 assert row['status']=='FAIL' and row['failures']==prior[key]['failures'],row
assert len(rows)==24
print('Ten controls, CLI, worker and twelve retained creator diagnoses matched.',flush=True)
