"""Before-fix subtype formatting probes; internal declared correspondence only."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2')
sys.path.insert(0,str(ROOT/'.eapdf-verification-v42'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
CASES={
'matching-quoted-spaces':('application/pdf; x-eapdf="a  b"','application/pdf; x-eapdf="a  b"'),
'changed-quoted-spaces':('application/pdf; x-eapdf="a b"','application/pdf; x-eapdf="a  b"'),
'equivalent-type-case':('application/pdf','APPLICATION/PDF'),
'equivalent-quoted-token':('application/pdf; x-eapdf=ok','application/pdf; x-eapdf="ok"'),
'equivalent-parameter-order':('application/pdf; x-eapdf=one; y-eapdf=two','application/pdf; y-eapdf=two; x-eapdf=one'),
'changed-parameter-case':('application/pdf; x-eapdf=Ab','application/pdf; x-eapdf=ab'),
'matching-layout-spaces':('application/pdf;  x-eapdf=ok','application/pdf;  x-eapdf=ok'),
'matching-baseline':('application/pdf','application/pdf')}
rows=[]
for name,(dpm,value) in CASES.items():
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf') as pdf:
  for e in structure_elements(pdf):
   if e.get('/S')==pikepdf.Name('/Mail_ContentSet') and any(str(p.N)=='Mail_ContentSetType' and str(p.V)=='FrontMatter' for p in e.A.P):
    next(p for p in e.A.P if str(p.N)=='Mail_Subtype').V=value
  _,_,nodes=v.get_dpart_nodes(pdf)
  for item in nodes:
   if not item['child_count'] and item['dpm'].get('Mail_ContentSetType')==pikepdf.Name.FrontMatter:
    item['node'].DPM.Mail_Subtype=pikepdf.Name('/'+dpm)
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:
  result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),dpm=dpm,value=value,status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:
  row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
