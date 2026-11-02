"""A group's declared type must correspond to its own connected Content Sets."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v40'))
import validate_eapdf as v
rows=[]
for name in ('matching-group','foreign-group-type'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf') as pdf:
  pending=[pdf.Root.StructTreeRoot.K]
  while pending:
   element=pending.pop()
   if not isinstance(element,pikepdf.Dictionary):continue
   if element.get('/S')==pikepdf.Name('/Mail_Message'):message=element;break
   children=element.get('/K',[]);pending.extend(list(children) if isinstance(children,pikepdf.Array) else [children])
  namespace=message.NS;legacy=namespace.RoleMapNS['/Mail_ContentSet'][1]
  namespace.RoleMapNS['/Mail_ContentGroup']=pikepdf.Array([pikepdf.Name.Part,legacy]);pdf.Root.StructTreeRoot.RoleMap['/Mail_ContentGroup']=pikepdf.Name.Part
  old=message.K;group=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/Mail_ContentGroup'),P=message,NS=namespace,K=old,A=pikepdf.Dictionary(O=pikepdf.Name.UserProperties,P=pikepdf.Array([pikepdf.Dictionary(N='Mail_ContentSetType',V='BodyRendering' if name=='matching-group' else 'FrontMatter')]))))
  for child in old if isinstance(old,pikepdf.Array) else [old]:
   if isinstance(child,pikepdf.Dictionary) and '/S' in child:child.P=group
  message.K=group
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
