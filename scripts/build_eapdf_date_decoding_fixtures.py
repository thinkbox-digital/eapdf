"""Retain malformed/valid encoded PDF dates without changing date grammar."""
import hashlib,json
from pathlib import Path
import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-date-decoding'
SEED=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
CASES={
 'invalid-utf8-date':b'\xef\xbb\xbf\xff',
 'invalid-utf8-date-tail':b'\xef\xbb\xbfD:20261021\xff',
 'valid-utf8-date':b'\xef\xbb\xbfD:20261021120000Z',
 'valid-utf16-date':b'\xfe\xff'+'D:20261021120000Z'.encode('utf-16-be'),
 'valid-ascii-date':b'D:20261021120000Z',
 'invalid-ascii-date':b'not-a-date',
 'truncated-utf16-date':b'\xfe\xff\x00D\x00:\x00',
 'fullwidth-digit-date':'D:２０２６１０２１'}

def main():
 OUT.mkdir(parents=True,exist_ok=True);rows=[]
 for name,value in CASES.items():
  with pikepdf.open(SEED) as pdf:
   message=next(e for e in structure_elements(pdf) if e.get('/S')==pikepdf.Name('/Mail_Message'))
   message.A.P.append(pikepdf.Dictionary(N='Mail_Sent',V=pikepdf.String(value)))
   path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
  legal=name.startswith('valid-')
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='PASS' if legal else 'FAIL',expected_failures=[] if legal else ['USERPROPS_TYPE']))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA Table12 Mail_Sent PDF date; ISO32000-2:2020 7.9.4 processed date grammar and 7.9.2.2 Unicode text encoding',source_files={str(SEED.relative_to(ROOT)):hashlib.sha256(SEED.read_bytes()).hexdigest()},boundary='Decoder rejection fails the date predicate. Keep valid Unicode dates and existing grammar/calendar constraints. Other string semantics and complete text encoding validation remain separate.',cases=rows),indent=2)+'\n')
 print('Built eight encoded date controls.')
if __name__=='__main__':main()
