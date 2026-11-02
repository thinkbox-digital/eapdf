"""Retain GUID uncertainty, duplicate occurrences and definite mismatches."""
import hashlib,json
from pathlib import Path
import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements,guid_property
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-guid-decoding'
SEED=ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf'
def main():
 OUT.mkdir(parents=True,exist_ok=True)
 rows=[]
 for name in ('unreadable-guid','unreadable-guid-before-valid','unreadable-guid-after-valid','unreadable-guid-and-wrong',
              'valid-utf8-guid','valid-utf16-guid','empty-utf8-guid','wrong-name-guid'):
  with pikepdf.open(SEED) as pdf:
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
   path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
  incomplete=name in ('unreadable-guid','unreadable-guid-before-valid','unreadable-guid-after-valid')
  legal=name in ('valid-utf8-guid','valid-utf16-guid')
  failures=[] if incomplete or legal else ['USERPROPS_TYPE'] if name=='wrong-name-guid' else ['MAIL_MESSAGE_GUID_MATCH_DPM_XMP'] if name=='unreadable-guid-and-wrong' else ['MAIL_MESSAGE_GUID_ATTRIBUTE','MAIL_MESSAGE_GUID_MATCH_DPM_XMP']
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='INCOMPLETE' if incomplete else 'PASS' if legal else 'FAIL',expected_failures=failures))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA Table13 required Mail_GUID and correspondence; Table12 string; V39/V42 owner and occurrence review',source_files={str(SEED.relative_to(ROOT)):hashlib.sha256(SEED.read_bytes()).hexdigest()},boundary='Unreadable GUID identity remains unavailable, never invented or treated as a definite mismatch. Readable companion presence and definite mismatches remain independent. No new UUID or encoding prohibition is introduced.',cases=rows),indent=2)+'\n')
 print('Built eight GUID decoding controls.')

if __name__=='__main__':main()
