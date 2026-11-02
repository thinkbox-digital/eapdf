"""Native before controls for optional present DPM MessageID decoding."""
import hashlib,json
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-messageid-decoding'
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'

def identities(pdf):
 def walk(node):
  if node.get('/DPM',{}).get('/Mail_GUID') is not None:yield node
  for group in node.get('/DParts',[]):
   for child in group if isinstance(group,pikepdf.Array) else [group]:yield from walk(child)
 return list(walk(pdf.Root.DPartRoot.DPartRootNode))

def main():
 OUT.mkdir(parents=True,exist_ok=True);rows=[]
 for name in ('unreadable-utf8','unreadable-surrogate','valid-utf8','valid-utf16','readable-mismatch','wrong-name','absent-optional','unreadable-with-revision-failure'):
  path=OUT/(name+'.pdf')
  with pikepdf.open(SEED) as pdf:
   node=identities(pdf)[0];mid=str(node.DPM.Mail_MessageID)
   if name=='absent-optional':del node.DPM.Mail_MessageID
   else:
    node.DPM.Mail_MessageID=pikepdf.String(b'\xef\xbb\xbf\xff') if name.startswith('unreadable') else pikepdf.String(b'\xef\xbb\xbf'+mid.encode()) if name=='valid-utf8' else pikepdf.String(b'\xfe\xff'+mid.encode('utf-16be')) if name=='valid-utf16' else pikepdf.Name('/'+mid) if name=='wrong-name' else pikepdf.String('different-id')
    if name=='unreadable-surrogate':node.DPM.Mail_MessageID=pikepdf.String(b'\xef\xbb\xbf\xed\xa0\x80')
   if name=='unreadable-with-revision-failure':
    data=pdf.Root.Metadata.read_bytes();changed=data.replace(b'<pdfmailid:rev>2024</pdfmailid:rev>',b'<pdfmailid:rev>2023</pdfmailid:rev>');assert changed!=data;pdf.Root.Metadata.write(changed)
   pdf.save(path,static_id=True,fix_metadata_version=False)
  level='WARN' if name.startswith('unreadable') else 'FAIL' if name in ('readable-mismatch','wrong-name') else 'N/A' if name=='absent-optional' else 'PASS'
  failures=['EAPDF_REV'] if name=='unreadable-with-revision-failure' else ['DPM_MAIL_MESSAGEID_MATCH'] if level=='FAIL' else []
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'INCOMPLETE' if level=='WARN' else 'PASS',expected_failures=failures,expected_messageid=level))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table7 and following matching clause, printedp70',boundary='Optional absence differs from unavailable supplied text. Current readable string comparison policy remains unchanged.',source_files={str(SEED.relative_to(ROOT)):hashlib.sha256(SEED.read_bytes()).hexdigest()},cases=rows),indent=2)+'\n')
if __name__=='__main__':main()
