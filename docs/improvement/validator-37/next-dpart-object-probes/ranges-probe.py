"""Observe page coverage multiplicity and depth-first leaf ordering."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v37'))
import validate_eapdf as v
rows=[]
for name in ('ordered-split','reversed-leaves','overlapping-ranges','overlap-one-page'):
 with pikepdf.Pdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  root,node,nodes=v.get_dpart_nodes(pdf);leaf=nodes[-1]['node'];parent=nodes[-1]['parent']
  second=pdf.make_indirect(pikepdf.Dictionary({key:value for key,value in leaf.items()}));second.Start=pdf.pages[1].obj
  if '/End' in second:del second.End
  if name!='overlapping-ranges' and '/End' in leaf:del leaf.End
  if name=='overlap-one-page':second.Start=pdf.pages[0].obj;second.End=pdf.pages[1].obj
  parent.DParts=pikepdf.Array([pikepdf.Array([second,leaf] if name=='reversed-leaves' else [leaf,second])])
  pdf.pages[0].obj.DPart=leaf;pdf.pages[1].obj.DPart=second
  with pdf.open_outline() as outline:outline.root.append(pikepdf.OutlineItem('Second content set',1))
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'ranges-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
