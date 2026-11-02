"""Decode only actual Text strings; malformed N data must not abort validation."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v45'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements,guid_property
rows=[]
for name in ('name-object-as-property-name','undecodable-name-object','undecodable-text-string',
 'utf16-encoded-known-name','empty-ascii-extension','baseline-ascii-name','array-as-property-name','dictionary-as-property-name'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  prop=guid_property(message)
  if name=='empty-ascii-extension':message.A.P.append(pikepdf.Dictionary(N='',V='Unicode value résumé'))
  elif name!='baseline-ascii-name':
   prop.N=(pikepdf.Name('/Mail_GUID') if name=='name-object-as-property-name' else
     pikepdf.Object.parse(b'/Vendor#FF') if name=='undecodable-name-object' else
     pikepdf.String(b'\xef\xbb\xbf\xff') if name=='undecodable-text-string' else
     pikepdf.String(b'\xfe\xff'+'Mail_GUID'.encode('utf-16be')) if name=='utf16-encoded-known-name' else
     pikepdf.Array([pikepdf.Object.parse(b'/Vendor#FF')]) if name=='array-as-property-name' else
     pikepdf.Dictionary(Raw=pikepdf.Object.parse(b'/Vendor#FF')))
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 (HERE/(name+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
