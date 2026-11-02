"""Preserve every unrelated DPart parent and page reference during stream substitution."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v37'))
import validate_eapdf as v
rows=[]
for name in ('root-node-stream-preserved','leaf-stream-preserved'):
 with pikepdf.Pdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  root,node,nodes=v.get_dpart_nodes(pdf)
  item=nodes[0] if name.startswith('root-node') else nodes[-1];original=item['node'];identity=v.objgen(original)
  replacement=pdf.make_stream(b'policy')
  for key,value in original.items():replacement[key]=value
  if item['parent'] is None:root.DPartRootNode=replacement
  else:
   parent=item['parent'];parent.DParts=pikepdf.Array([pikepdf.Array([replacement if v.objgen(child)==identity else child for child in group]) for group in parent.DParts])
  for child in nodes:
   if child['parent'] is not None and v.objgen(child['parent'])==identity:child['node'].Parent=replacement
  for page in pdf.pages:
   if v.objgen(page.obj.get('/DPart'))==identity:page.obj.DPart=replacement
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'repaired-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
