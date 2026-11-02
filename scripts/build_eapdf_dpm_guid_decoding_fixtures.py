"""Actual DPM GUID strings, unavailable owners and independent contradictions."""
import hashlib,json
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'tests/fixtures/eapdf/validator-dpm-guid-decoding'
SINGLE=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
MULTI=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-multi.pdf'
def identities(pdf):
 def walk(node):
  if node.get('/DPM',{}).get('/Mail_GUID') is not None:yield node
  for group in node.get('/DParts',[]):
   for child in group if isinstance(group,pikepdf.Array) else [group]:yield from walk(child)
 return list(walk(pdf.Root.DPartRoot.DPartRootNode))
def main():
 OUT.mkdir(parents=True,exist_ok=True);rows=[]
 for name in ('unreadable-utf8','unreadable-surrogate','valid-utf8','valid-utf16','empty','wrong-name','readable-mismatch','utf16-decoded-empty','unreadable-with-revision-failure','unreadable-with-other-owner-mismatch'):
  path=OUT/(name+'.pdf');seed=MULTI if name=='unreadable-with-other-owner-mismatch' else SINGLE
  with pikepdf.open(seed) as pdf:
   nodes=identities(pdf);node=nodes[0];guid=str(node.DPM.Mail_GUID)
   value=pikepdf.String(b'\xef\xbb\xbf\xff') if name.startswith('unreadable') else pikepdf.String(b'\xef\xbb\xbf'+guid.encode()) if name=='valid-utf8' else pikepdf.String(b'\xfe\xff'+guid.encode('utf-16be')) if name=='valid-utf16' else pikepdf.Name('/'+guid) if name=='wrong-name' else pikepdf.String('different-guid') if name=='readable-mismatch' else pikepdf.String(b'\xfe\xff\xd8\x00') if name=='utf16-decoded-empty' else pikepdf.String('')
   if name=='unreadable-surrogate':value=pikepdf.String(b'\xef\xbb\xbf\xed\xa0\x80')
   node.DPM.Mail_GUID=value
   if name=='unreadable-with-other-owner-mismatch':assert len(nodes)>1;nodes[1].DPM.Mail_GUID='different-guid'
   if name=='unreadable-with-revision-failure':
    data=pdf.Root.Metadata.read_bytes();changed=data.replace(b'<pdfmailid:rev>2024</pdfmailid:rev>',b'<pdfmailid:rev>2023</pdfmailid:rev>');assert changed!=data;pdf.Root.Metadata.write(changed)
   pdf.save(path,static_id=True,fix_metadata_version=False)
  failures=['EAPDF_REV'] if name=='unreadable-with-revision-failure' else ['DPM_MAIL_GUID_MATCH','MAIL_MESSAGE_GUID_MATCH_DPM_XMP'] if name in ('readable-mismatch','unreadable-with-other-owner-mismatch') else ['DPM_MAIL_GUID_PRESENT'] if name in ('empty','wrong-name','utf16-decoded-empty') else []
  if name in ('empty','wrong-name','utf16-decoded-empty','readable-mismatch'):failures=sorted(failures+['ATTACHMENT_ANNOTATIONS_PRESENT'])
  rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'INCOMPLETE' if name.startswith('unreadable') else 'PASS',expected_failures=failures))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table7 required GUID and precedence; actual independent counterparts; V39/V50 unavailable identity boundaries',boundary='Unreadable required identity is not absent, decoded-empty, a known mismatch or permission to choose a lower-priority identifier. Known readable contradictions remain failures.',cases=rows),indent=2)+'\n')
if __name__=='__main__':main()
