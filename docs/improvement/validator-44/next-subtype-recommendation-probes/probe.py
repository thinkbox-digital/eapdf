"""SHOULD recommendation applicability is tied to occurrence of Mail_ContentSet."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v44'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
CODE='MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'
rows=[]
for name in ('all-subtypes-present','body-subtype-omitted','body-subtype-and-self-type-omitted',
 'frontmatter-subtype-omitted','all-subtypes-omitted','all-body-properties-omitted','empty-subtype-present','unresolved-content-attributes'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf') as pdf:
  sets=[e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_ContentSet')]
  bodies=[e for e in sets if any(str(p.N)=='Mail_ContentSetType' and str(p.V)=='BodyRendering' for p in e.A.P)]
  front=next(e for e in sets if any(str(p.N)=='Mail_ContentSetType' and str(p.V)=='FrontMatter' for p in e.A.P))
  if name in ('body-subtype-omitted','body-subtype-and-self-type-omitted'):
   names={'Mail_Subtype','Mail_ContentSetType'} if name.endswith('and-self-type-omitted') else {'Mail_Subtype'}
   bodies[0].A.P=pikepdf.Array([p for p in bodies[0].A.P if str(p.N) not in names])
  elif name=='frontmatter-subtype-omitted':front.A.P=pikepdf.Array([p for p in front.A.P if str(p.N)!='Mail_Subtype'])
  elif name in ('all-subtypes-omitted','all-body-properties-omitted'):
   for e in sets:
    names={'Mail_Subtype','Mail_ContentSetType'} if name=='all-body-properties-omitted' and e in bodies else {'Mail_Subtype'}
    e.A.P=pikepdf.Array([p for p in e.A.P if str(p.N) not in names])
  elif name=='empty-subtype-present':next(p for p in bodies[0].A.P if str(p.N)=='Mail_Subtype').V=''
  elif name=='unresolved-content-attributes':bodies[0].A.O=pikepdf.String('UserProperties')
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],recommendation=sorted({i['level'] for i in result['items'] if i['code']==CODE}),failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
