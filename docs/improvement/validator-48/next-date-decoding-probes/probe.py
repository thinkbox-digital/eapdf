"""Observe actual PDF date strings, preserving malformed encoded bytes."""
import hashlib,json,os,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT),GOMAXPROCS='2');sys.path.insert(0,str(ROOT/'.eapdf-verification-v48'));sys.path.insert(0,str(ROOT/'scripts'))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements
CASES={
 'invalid-utf8-date':b'\xef\xbb\xbf\xff',
 'invalid-utf8-date-tail':b'\xef\xbb\xbfD:20261021\xff',
 'valid-utf8-date':b'\xef\xbb\xbfD:20261021120000Z',
 'valid-utf16-date':b'\xfe\xff'+'D:20261021120000Z'.encode('utf-16-be'),
 'valid-ascii-date':b'D:20261021120000Z',
 'invalid-ascii-date':b'not-a-date',
 'truncated-utf16-date':b'\xfe\xff\x00D\x00:\x00',
 'fullwidth-digit-date':'D:２０２６１０２１'}
rows=[]
for name,value in CASES.items():
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
  message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
  message.A.P.append(pikepdf.Dictionary(N='Mail_Sent',V=pikepdf.String(value)))
  path=HERE/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
 if '--engines' in sys.argv:result=v.validate_one(str(path),write_html=False)
 else:
  report=v.validate(str(path));result=dict(summary=report.summary(),items=report.items)
 suffix='' if '--engines' in sys.argv else '-custom'
 (HERE/(name+suffix+'.json')).write_text(json.dumps(result,indent=2,default=str)+'\n')
 row=dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=result['summary']['status'],failures=sorted({i['code'] for i in result['items'] if i['level']=='FAIL'}))
 if '--engines' in sys.argv:row.update(pdfa=result['pdfa']['status'],iso=next(i['level'] for i in result['items'] if i['code']=='ISO32005_UPSTREAM'))
 rows.append(row);(HERE/('results.json' if '--engines' in sys.argv else 'custom-results.json')).write_text(json.dumps(rows,indent=2)+'\n');print(row,flush=True)
