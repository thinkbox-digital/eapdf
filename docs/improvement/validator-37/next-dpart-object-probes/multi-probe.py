"""Use retained multi-message bytes with only independently proved operand repairs."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v37'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from review_eapdf_operand_corpus import inspect
records=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']
profile=next(r for r in records if r['mode']=='normal' and r['profile']=='1m');source=Path(json.loads((ROOT/profile['report']).read_text())['summary']['file'])
rows=[]
for name in ('multi-ordered','multi-reversed','multi-overlapping'):
 with pikepdf.Pdf.open(source) as pdf:
  expected={(r['page'],r['mcid']):r['expected'] for r in inspect(pdf)}
  for number,page in enumerate(pdf.pages,1):
   instructions=[]
   for instruction in pikepdf.parse_content_stream(page):
    if str(instruction.operator)=='BDC':
     tag,props=instruction.operands;resolved=page.Resources.Properties[str(props)] if isinstance(props,pikepdf.Name) else props
     value=expected.get((number,resolved.get('/MCID')))
     if value:instruction=pikepdf.ContentStreamInstruction([pikepdf.Name('/'+value),props],instruction.operator)
    instructions.append(instruction)
   page.Contents=pdf.make_stream(pikepdf.unparse_content_stream(instructions))
  assert not inspect(pdf)
  root,node,nodes=v.get_dpart_nodes(pdf);leaves=[x for x in nodes if x['child_count']==0]
  if name=='multi-reversed':node.DParts=pikepdf.Array([pikepdf.Array(list(reversed(list(group)))) for group in reversed(list(node.DParts))])
  elif name=='multi-overlapping':leaves[0]['node'].End=leaves[1]['node'].get('/End',leaves[1]['node'].Start)
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}));rows.append(row);(HERE/'multi-results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
(HERE/'multi-source.json').write_text(json.dumps(dict(source=str(source.relative_to(ROOT)),sha256=hashlib.sha256(source.read_bytes()).hexdigest(),boundary='Original retained creator output unchanged. Fixture copies repair only independently proved page/MCID operand defects before DPart range/order mutations.'),indent=2)+'\n')
