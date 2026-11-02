"""Complete collection validation: actual parent/child engines and entry points."""
import hashlib,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v76'));import validate_eapdf as v;import validator_web as web
OUT=HERE/'pdf-reports';OUT.mkdir(exist_ok=True)
rows=json.loads((HERE/'pdf-results.json').read_text()) if (HERE/'pdf-results.json').exists() else []
context=v.ChildValidationContext(include_pdfa=True)
def record(path,result,entry,key=None):
 key=key or path.stem
 (OUT/(key+'-'+entry+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 iso=next(i for i in result['items'] if i['code']=='ISO32005_UPSTREAM')
 children=[i for i in result['items'] if i['code']=='COLLECTION_CHILD_VALIDATION' and (i.get('details') or {}).get('sha256')]
 child_statuses=[dict(level=i['level'],status=i['details']['status'],pdfa=i['details']['pdfa_status'],sha256=i['details']['sha256']) for i in children]
 row=dict(file=str(path.relative_to(ROOT)),key=key,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),entry=entry,status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=iso['level'],profile_verified=(iso.get('details') or {}).get('profile_verified'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}),children=child_statuses)
 rows.append(row);(HERE/'pdf-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
 return row
folder=ROOT/'tests/fixtures/eapdf/validator-collection-child-status'
for case in json.loads((folder/'expectations.json').read_text())['cases']:
 path=folder/case['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
 if any(row['key']==path.stem and row['entry']=='after' for row in rows):continue
 row=record(path,v.validate_one(str(path),write_html=False,child_context=context),'after')
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified'],row
 assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'],row
 assert row['children'],row
 if path.stem.endswith('-valid'):assert all(c['level']==c['status']==c['pdfa']=='PASS' for c in row['children']),row
 elif path.stem.endswith('-incomplete'):assert any(c['level']=='WARN' and c['status']=='INCOMPLETE' and c['pdfa']=='PASS' for c in row['children']),row
 else:assert any(c['level']=='FAIL' and c['status']=='FAIL' for c in row['children']),row
for name,entry in [('normal-1c-incomplete','cli'),('accessibility-1ci-valid','web-worker')]:
 path=folder/(name+'.pdf')
 if any(row['key']==name and row['entry']==entry for row in rows):continue
 if entry=='cli':
  process=subprocess.run([sys.executable,'-B',str(ROOT/'.eapdf-verification-v76/validate_eapdf.py'),str(path),'--json','--no-html'],cwd=ROOT,capture_output=True,text=True,timeout=300)
  assert process.returncode==2,(process.returncode,process.stderr);result=json.loads(process.stdout)
 else:result=web.run_pdf_validation(path.resolve())
 row=record(path,result,entry);assert row['pdfa']==row['iso']=='PASS' and row['profile_verified'],row
 assert row['status']==('INCOMPLETE' if entry=='cli' else 'PASS') and not row['failures'],row
assert len(rows)==12
print('Ten complete collection controls, unavailable CLI and positive worker matched.',flush=True)
