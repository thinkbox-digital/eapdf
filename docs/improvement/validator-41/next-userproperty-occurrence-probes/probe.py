"""Observe repeated active property names without inventing array-order priority."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v41'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements,guid_property
rows=[]
for name in ('duplicate-guid-uniform','duplicate-guid-wrong-first','duplicate-guid-wrong-last','duplicate-guid-all-wrong'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf') as pdf:
  messages=[e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message')]
  prop=guid_property(messages[0]);actual=prop.V;foreign=guid_property(messages[1]).V
  duplicate=pikepdf.Dictionary(N='Mail_GUID',V=actual if name=='duplicate-guid-uniform' else foreign)
  if name=='duplicate-guid-all-wrong':prop.V=foreign
  if name=='duplicate-guid-wrong-last':messages[0].A.P.append(duplicate)
  else:messages[0].A.P.insert(0,duplicate)
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
