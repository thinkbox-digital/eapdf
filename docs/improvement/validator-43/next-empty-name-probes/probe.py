"""Review actual empty PDF Name objects separately from missing keys/media syntax."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v43'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
rows=[]
for name in ('empty-extension-type','empty-optional-subtype','empty-required-subtype'):
 seed=ROOT/('tests/fixtures/eapdf/validator-content-group-types/opaque-group-types.pdf' if name=='empty-extension-type' else 'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf')
 with pikepdf.open(seed) as pdf:
  elements=structure_elements(pdf);_,_,nodes=v.get_dpart_nodes(pdf)
  if name=='empty-extension-type':
   for item in nodes:
    if not item['child_count'] and str(item['dpm'].get('Mail_ContentSetType'))=='/ Vendor  rendering ':
     item['node'].DPM.Mail_ContentSetType=pikepdf.Name('/')
   for e in elements:
    if e.get('/S') in (pikepdf.Name('/Mail_ContentGroup'),pikepdf.Name('/Mail_ContentSet')):
     for p in e.A.P:
      if str(p.N)=='Mail_ContentSetType' and str(p.V)==' Vendor  rendering ':p.V=''
  else:
   wanted='FrontMatter' if name=='empty-optional-subtype' else 'BodyRendering'
   item=next(i for i in nodes if not i['child_count'] and i['dpm'].get('Mail_ContentSetType')==pikepdf.Name('/'+wanted))
   item['node'].DPM.Mail_Subtype=pikepdf.Name('/')
   e=next(e for e in elements if e.get('/S')==pikepdf.Name('/Mail_ContentSet') and e.get('/Pg') is not None and e.Pg.objgen==item['start'].objgen)
   next(p for p in e.A.P if str(p.N)=='Mail_Subtype').V=''
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False) if '--engines' in sys.argv else None
 if result is None:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
