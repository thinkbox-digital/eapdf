"""Observe stream substitutions and unavailable key decoding on retained independent bytes."""
import hashlib,json,os,sys,traceback
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v37'))
import validate_eapdf as v
rows=[]
for name in ('root-stream','root-node-stream','leaf-stream','dpm-key-unreadable','dpm-nested-key-unreadable'):
 with pikepdf.Pdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  root,node,nodes=v.get_dpart_nodes(pdf);leaf=nodes[-1]['node']
  if name in ('root-stream','root-node-stream','leaf-stream'):
   original=root if name=='root-stream' else node if name=='root-node-stream' else leaf
   replacement=pdf.make_stream(b'policy')
   for key,value in original.items():replacement[key]=value
   if name=='root-stream':pdf.Root.DPartRoot=replacement;node.Parent=replacement
   elif name=='root-node-stream':root.DPartRootNode=replacement;leaf.Parent=replacement
   else:node.DParts=pikepdf.Array([pikepdf.Array([replacement])]);pdf.pages[0].obj.DPart=replacement
  else:
   bad=pikepdf.Object.parse(b'<< /#ff (value) >>')
   if name=='dpm-key-unreadable':
    for key,value in leaf.DPM.items():bad[key]=value
    leaf.DPM=bad
   else:leaf.DPM.VendorPolicy=bad
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 try:
  result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
  row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 except Exception as error:
  (HERE/(name+'-error.log')).write_text(traceback.format_exc());row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),exception=type(error).__name__)
 rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
