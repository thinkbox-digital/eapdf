"""Count equality is insufficient evidence that every DPart Content Set is tagged."""
import hashlib,json,os,shutil,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v53'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-multi.pdf'
rows=[]
for name in ('all-covered','split-tag-same-content-set','balanced-count-missing-front',
             'empty-tag-declared-front-page','empty-tag-without-page','fewer-tags-missing-front'):
 path=HERE/(name+'.pdf')
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
 with pikepdf.open(path) as pdf:
  elems=[];v.walk_struct_elems(pdf.Root.StructTreeRoot.K,elems);sets=[e for e in elems if e['type']=='Mail_ContentSet']
  _,_,nodes=v.get_dpart_nodes(pdf);page_map=v.page_objgen_map(pdf)
  connections=v.structure_content_dpm_assessments(pdf,sets,nodes)
  native=dict(custom_sets=len(sets),dpart_leaves=[dict(objgen=n['objgen'],range=v.page_range_from_dpart(n,page_map)[:2]) for n in nodes if not n['child_count']],connections=connections)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 suffix='' if '--engines' in sys.argv else '-custom'
 (HERE/(name+suffix+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 (HERE/(name+'-native.json')).write_text(json.dumps(native,indent=2)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}),coverage=next(i['level'] for i in result['items'] if i['code']=='CONTENT_SET_MAIL_CONTENTSET_TAGGED'))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
