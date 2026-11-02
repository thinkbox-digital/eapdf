"""Actual tag connections versus compensating counts and unknown placeholders."""
import hashlib,json,shutil
from pathlib import Path
import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-contentset-coverage'
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-multi.pdf'
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 rows=[]
 for name in ('all-covered','split-tag-same-content-set','balanced-count-missing-front',
              'empty-tag-declared-front-page','empty-tag-without-page','fewer-tags-missing-front'):
  path=OUT/(name+'.pdf')
  if name=='all-covered':shutil.copyfile(SEED,path)
  else:
   with pikepdf.open(SEED) as pdf:
    elems=structure_elements(pdf)
    sets=[e for e in elems if e.get('/S')==pikepdf.Name('/Mail_ContentSet')]
    def self_type(e):return next(str(p.V) for p in e.A.P if str(p.N)=='Mail_ContentSetType')
    front=next(e for e in sets if self_type(e)=='FrontMatter')
    body=next(e for e in sets if self_type(e)=='BodyRendering')
    if name!='split-tag-same-content-set':
     front.S=pikepdf.Name.Art
     if '/NS' in front:del front.NS
     if '/A' in front:del front.A
     contents=front.Pg.get('/Contents')
     for stream in contents if isinstance(contents,pikepdf.Array) else [contents]:
      stream.write(stream.read_bytes().replace(b'/Mail_ContentSet',b'/Art'))
    if name in ('split-tag-same-content-set','balanced-count-missing-front'):
     kids=list(body.K);assert len(kids)>1
     new=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/Mail_ContentSet'),P=body.P,Pg=body.Pg,K=pikepdf.Array(kids[1:]),NS=body.NS,A=body.A))
     body.K=pikepdf.Array(kids[:1])
     for child in kids[1:]:child.P=new
    elif name.startswith('empty-tag-'):
     new=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/Mail_ContentSet'),P=body.P,NS=body.NS))
     if name=='empty-tag-declared-front-page':new.Pg=front.Pg
    else:new=None
    if new is not None:
     siblings=body.P.K;siblings=list(siblings) if isinstance(siblings,pikepdf.Array) else [siblings]
     index=next(i for i,e in enumerate(siblings) if e.objgen==body.objgen);siblings.insert(index+1,new);body.P.K=pikepdf.Array(siblings)
    pdf.save(path,static_id=True,fix_metadata_version=False)
  positive=name in ('all-covered','split-tag-same-content-set')
  unknown=name.startswith('empty-tag-')
  failures=[] if positive else ['MAIL_CONTENTSET_ART_ONLY','STRUCTURE_CORRESPONDENCE'] if unknown else ['CONTENT_SET_MAIL_CONTENTSET_TAGGED','MAIL_CONTENTSET_ART_ONLY']
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='PASS' if positive else 'FAIL',expected_failures=failures,expected_coverage='PASS' if positive else 'WARN' if unknown else 'FAIL'))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA9.7 printed p76 conditional custom-tag block: every actual Content Set needs Mail_ContentSet tagging; V39/V42 actual reference evidence',source_files={str(SEED.relative_to(ROOT)):hashlib.sha256(SEED.read_bytes()).hexdigest()},boundary='Count equality does not establish coverage. Missing known counterparts fail; unresolved counterparts and invalid correspondence remain unavailable. Extra tag count is not itself prohibited. Existing Art-only count and empty-tag structure failures retain separate scope; complete Art/tagging semantics are not certified.',cases=rows),indent=2)+'\n')
 print('Built six actual Content Set coverage controls.')

if __name__=='__main__':main()
