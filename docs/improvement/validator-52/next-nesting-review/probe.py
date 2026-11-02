"""Retain ancestry controls without resolving the open collection contradiction."""
import hashlib,json,os,shutil,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v52'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
SOURCES={
 'nested-multi':HERE/'baselines/valid-multi.pdf',
 'nested-subtype':HERE/'baselines/valid-subtype.pdf',
 'orphan-front-noncollection':ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf',
 'orphan-empty-self':ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf',
 'orphan-unreadable-self':ROOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf',
 'orphan-front-collection':ROOT/'tests/fixtures/eapdf/validator-booleans/collection-false.pdf',
 'orphan-other-collection':ROOT/'tests/fixtures/eapdf/validator-booleans/collection-false.pdf',
 'nested-single':ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'}
rows=[]
for name,source in SOURCES.items():
 path=HERE/(name+'.pdf')
 if name in ('orphan-empty-self','orphan-unreadable-self','orphan-other-collection'):
  with pikepdf.open(source) as pdf:
   target=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_ContentSet') and any(str(p.N)=='Mail_ContentSetType' and str(p.V)=='FrontMatter' for p in e.A.P))
   prop=next(p for p in target.A.P if str(p.N)=='Mail_ContentSetType')
   prop.V='' if name=='orphan-empty-self' else 'Other' if name=='orphan-other-collection' else pikepdf.String(b'\xef\xbb\xbf\xff')
   pdf.save(path,static_id=True,fix_metadata_version=False)
 else:shutil.copyfile(source,path)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 suffix='' if '--engines' in sys.argv else '-custom'
 (HERE/(name+suffix+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}),parent_levels=sorted({i['level'] for i in result['items'] if i['code']=='MAIL_CONTENTSET_NESTED_UNDER_MESSAGE'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
