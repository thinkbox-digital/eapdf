"""Observe actual Content Set structure ownership independently of broad tag counts."""
import hashlib,json,os,sys,traceback
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v38'))
import validate_eapdf as v
rows=[]
for name in ('empty-set-substitute','empty-set-unrelated-range','swapped-message-dpm-guids','swapped-structure-guids'):
 seed=ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf' if name.startswith('swapped') else ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
 with pikepdf.open(seed) as pdf:
  roots=pdf.Root.StructTreeRoot.K;pending=list(roots) if isinstance(roots,pikepdf.Array) else [roots];sets=[];messages=[]
  while pending:
   e=pending.pop()
   if not isinstance(e,pikepdf.Dictionary):continue
   if e.get('/S')==pikepdf.Name('/Mail_ContentSet'):sets.append(e)
   if e.get('/S')==pikepdf.Name('/Mail_Message'):messages.append(e)
   k=e.get('/K',[]);pending.extend(list(k) if isinstance(k,pikepdf.Array) else [k])
  if name.startswith('empty-set'):
   original=sets[0];namespace=original.NS
   fake=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/Mail_ContentSet'),P=original.P,NS=namespace,A=original.A,K=pikepdf.Array()))
   if name=='empty-set-unrelated-range':fake.Pg=pdf.pages[-1].obj
   mapped=namespace.RoleMapNS['/Mail_ContentSet'];original.S=mapped[0];original.NS=mapped[1]
   siblings=original.P.K;original.P.K=pikepdf.Array(list(siblings)+[fake] if isinstance(siblings,pikepdf.Array) else [siblings,fake])
  elif name=='swapped-message-dpm-guids':
   _,_,nodes=v.get_dpart_nodes(pdf);targets=[x['node'].DPM for x in nodes if x['child_count'] and '/Mail_GUID' in x['node'].get('/DPM',{})];assert len(targets)==2
   a,b=targets[0].Mail_GUID,targets[1].Mail_GUID;targets[0].Mail_GUID=b;targets[1].Mail_GUID=a
  else:
   def prop(e):return next(p for p in e.A.P if str(p.N)=='Mail_GUID')
   assert len(messages)==2;a,b=prop(messages[0]).V,prop(messages[1]).V;prop(messages[0]).V=b;prop(messages[1]).V=a
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 try:
  result=v.validate_one(str(path),write_html=False);(HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
  row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 except Exception as error:
  (HERE/(name+'-error.log')).write_text(traceback.format_exc());row=dict(file=str(path.relative_to(ROOT)),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),exception=type(error).__name__)
 rows.append(row);(HERE/'results.json').write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
