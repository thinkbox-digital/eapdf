"""Unreadable connected group types and subtypes versus known contradictions."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v50'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
SEEDS=dict(group=ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf',subtype=ROOT/'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf')
rows=[]
for family in ('group','subtype'):
 for mode in ('unreadable','unreadable-and-wrong','valid-utf8','valid-utf16'):
  name=family+'-'+mode
  with pikepdf.open(SEEDS[family]) as pdf:
   elems=structure_elements(pdf)
   key='Mail_ContentSetType' if family=='group' else 'Mail_Subtype'
   if family=='group':target=next(e for e in elems if e.get('/S')==pikepdf.Name('/Mail_ContentGroup'))
   else:target=next(e for e in elems if e.get('/S')==pikepdf.Name('/Mail_ContentSet') and any(str(p.N)=='Mail_ContentSetType' and str(p.V)=='FrontMatter' for p in e.A.P))
   prop=next(p for p in target.A.P if str(p.N)==key);original=str(prop.V)
   if mode.startswith('unreadable'):
    prop.V=pikepdf.String(b'\xef\xbb\xbf\xff')
    if mode.endswith('wrong'):target.A.P.append(pikepdf.Dictionary(N=key,V='FrontMatter' if family=='group' else 'application/octet-stream'))
   elif mode=='valid-utf8':prop.V=pikepdf.String(b'\xef\xbb\xbf'+original.encode())
   else:prop.V=pikepdf.String(b'\xfe\xff'+original.encode('utf-16-be'))
   path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
  if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
  else:
   report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
  suffix='' if '--engines' in sys.argv else '-custom'
  (HERE/(name+suffix+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
  row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
  if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
  rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
