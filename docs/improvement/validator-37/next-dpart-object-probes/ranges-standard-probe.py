"""Observe page coverage multiplicity and depth-first leaf ordering."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v37'))
import validate_eapdf as v
rows=[]
for name in ('standard-ordered-split','standard-reversed-leaves','standard-overlapping-ranges','standard-overlap-one-page'):
 with pikepdf.Pdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/standard-only.pdf') as pdf:
  root,node,nodes=v.get_dpart_nodes(pdf);leaf=nodes[-1]['node'];parent=nodes[-1]['parent']
  second=pdf.make_indirect(pikepdf.Dictionary({key:value for key,value in leaf.items()}));second.Start=pdf.pages[1].obj
  if '/End' in second:del second.End
  if not name.endswith('overlapping-ranges') and '/End' in leaf:del leaf.End
  if name.endswith('overlap-one-page'):second.Start=pdf.pages[0].obj;second.End=pdf.pages[1].obj
  parent.DParts=pikepdf.Array([pikepdf.Array([second,leaf] if name.endswith('reversed-leaves') else [leaf,second])])
  pdf.pages[0].obj.DPart=leaf;pdf.pages[1].obj.DPart=second
  with pdf.open_outline() as outline:outline.root.append(pikepdf.OutlineItem('Second content set',1))
  annotations=[annot for page in pdf.pages for annot in page.obj.get('/Annots',[]) if annot.get('/Subtype')==pikepdf.Name('/FileAttachment')]
  for page in pdf.pages:
   if not any(annot.get('/Subtype')==pikepdf.Name('/FileAttachment') for annot in page.obj.get('/Annots',[])):
    copied=pdf.make_indirect(pikepdf.Dictionary({key:value for key,value in annotations[0].items() if key!='/StructParent'}));copied.P=page.obj
    if '/Annots' not in page.obj:page.obj.Annots=pikepdf.Array()
    page.obj.Annots.append(copied)
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'ranges-standard-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
