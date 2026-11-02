"""Observe unreadable GUID values without inventing identity or ownership."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v49'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements,guid_property
rows=[]
for name in ('unreadable-guid','unreadable-guid-before-valid','unreadable-guid-after-valid','unreadable-guid-and-wrong',
             'valid-utf8-guid','valid-utf16-guid','empty-utf8-guid','wrong-name-guid'):
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  prop=guid_property(message);original=str(prop.V);bad=pikepdf.String(b'\xef\xbb\xbf\xff')
  if name=='unreadable-guid':prop.V=bad
  elif name=='unreadable-guid-before-valid':message.A.P.insert(0,pikepdf.Dictionary(N='Mail_GUID',V=bad))
  elif name=='unreadable-guid-after-valid':message.A.P.append(pikepdf.Dictionary(N='Mail_GUID',V=bad))
  elif name=='unreadable-guid-and-wrong':
   prop.V=bad;message.A.P.append(pikepdf.Dictionary(N='Mail_GUID',V='known-wrong-guid'))
  elif name=='valid-utf8-guid':prop.V=pikepdf.String(b'\xef\xbb\xbf'+original.encode())
  elif name=='valid-utf16-guid':prop.V=pikepdf.String(b'\xfe\xff'+original.encode('utf-16-be'))
  elif name=='empty-utf8-guid':prop.V=pikepdf.String(b'\xef\xbb\xbf')
  else:prop.V=pikepdf.Name('/WrongType')
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 suffix='' if '--engines' in sys.argv else '-custom'
 (HERE/(name+suffix+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
