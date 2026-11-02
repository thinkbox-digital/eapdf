"""Physical witnesses for Art content counterparts, without count equivalence."""
import hashlib,json,shutil
from pathlib import Path
import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-art-usage'
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-multi.pdf'

def main():
 OUT.mkdir(parents=True,exist_ok=True); cases=[]
 for name in ('all-custom','mixed-explicit-implicit','all-standard','extra-standard-article','cross-content-sets','empty-standard-article'):
  path=OUT/(name+'.pdf')
  if name=='all-custom':shutil.copyfile(SEED,path)
  elif name=='mixed-explicit-implicit':shutil.copyfile(ROOT/'tests/fixtures/eapdf/validator-contentset-coverage/fewer-tags-missing-front.pdf',path)
  else:
   with pikepdf.open(SEED) as pdf:
    mapping={str(k)[1:]:str(value)[1:] for k,value in pdf.Root.StructTreeRoot.RoleMap.items() if str(k).startswith('/Mail_')}
    for elem in structure_elements(pdf):
     key=str(elem.get('/S',''))[1:]
     if key in mapping:
      elem.S=pikepdf.Name('/'+mapping[key])
      for entry in ('/NS','/A'):
       if entry in elem:del elem[entry]
    for page in pdf.pages:
     streams=page.Contents if isinstance(page.Contents,pikepdf.Array) else [page.Contents]
     for stream in streams:
      data=stream.read_bytes()
      for old,new in mapping.items():data=data.replace(('/'+old).encode(),('/'+new).encode())
      stream.write(data)
    arts=[e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name.Art]
    front=next(e for e in arts if e.Pg.objgen==pdf.pages[0].obj.objgen)
    body=next(e for e in arts if e.Pg.objgen==pdf.pages[1].obj.objgen and isinstance(e.get('/K'),pikepdf.Array) and len(e.K)>1)
    if name=='extra-standard-article':
     kids=list(body.K); new=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name.Art,P=body.P,Pg=body.Pg,K=pikepdf.Array(kids[1:])))
     body.K=pikepdf.Array(kids[:1])
     for child in kids[1:]:child.P=new
    elif name=='cross-content-sets':
     kids=list(body.K);moved=kids.pop();body.K=pikepdf.Array(kids)
     old=front.K;front.K=pikepdf.Array((list(old) if isinstance(old,pikepdf.Array) else [old])+[moved]);moved.P=front
     new=None
    elif name=='empty-standard-article':
     new=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name.Art,P=body.P,Pg=front.Pg))
    else:new=None
    if new is not None:
     old=body.P.K;siblings=list(old) if isinstance(old,pikepdf.Array) else [old];siblings.append(new);body.P.K=pikepdf.Array(siblings)
    pdf.save(path,static_id=True,fix_metadata_version=False)
  failures={'mixed-explicit-implicit':['CONTENT_SET_MAIL_CONTENTSET_TAGGED'],'cross-content-sets':['MAIL_CONTENTSET_ART_ONLY']}.get(name,[])
  cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'INCOMPLETE' if name in ('all-standard','extra-standard-article','empty-standard-article') else 'PASS',expected_failures=failures,expected_art='FAIL' if name=='cross-content-sets' else 'WARN' if name=='empty-standard-article' else 'PASS'))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 9.7 printedpp29,77; Art includes implicit Mail_ContentSet and must represent Content Sets only',boundary='Actual counterpart evidence; no tag-count equivalence, complete logical encapsulation or broad role-map resolution is certified.',cases=cases),indent=2)+'\n')
 print('Built six Art counterpart controls.')
if __name__=='__main__':main()
