"""A supplied empty GUID is not omission when optional Message-ID is present."""
import hashlib,json,re,sys
from html import escape
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT));import validate_eapdf as v
SEED=ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid/readable-guid.pdf'
rows=[]
for name in ('empty-element','empty-attribute','qualified-empty','absent-guid','matching-guid','uri-guid','empty-outside','empty-header-only'):
 p=HERE/(name+'.pdf')
 with pikepdf.open(SEED) as pdf:
  text=pdf.Root.Metadata.read_bytes().decode();x,_,_=v.parse_xmp(text.encode(),v.Report(str(SEED)));owner=v.flatten_email_records(x)[0]
  section=re.search(r'<pdfmailmeta:attachments>.*?</pdfmailmeta:attachments>',text,re.S);assert section
  part=section.group(0);assert '<pdfmailmeta:Message-ID>' not in part
  part,count=re.subn(r'(</rdf:li>\s*</rdf:Bag>\s*</pdfmailmeta:attachments>)',lambda m:'<pdfmailmeta:Message-ID>'+escape(v.first_present(owner,'Message-ID','messageid'))+'</pdfmailmeta:Message-ID>'+m.group(1),part);assert count==1
  if name!='matching-guid':
   replacement='' if name in ('absent-guid','empty-attribute') else '<pdfmailmeta:Mail_GUID rdf:resource="urn:wrong-type"/>' if name=='uri-guid' else '<pdfmailmeta:Mail_GUID><rdf:Description><rdf:value></rdf:value></rdf:Description></pdfmailmeta:Mail_GUID>' if name=='qualified-empty' else '<pdfmailmeta:Mail_GUID></pdfmailmeta:Mail_GUID>'
   part,count=re.subn(r'<pdfmailmeta:Mail_GUID>.*?</pdfmailmeta:Mail_GUID>',replacement,part,flags=re.S);assert count==1
  if name=='empty-attribute':
   part,count=re.subn(r'<rdf:li[^>]*>', '<rdf:li><rdf:Description pdfmailmeta:Mail_GUID="">', part, count=1);assert count==1
   part=part.replace('</rdf:li>','</rdf:Description></rdf:li>',1)
  text=text[:section.start()]+part+text[section.end():];pdf.Root.Metadata.write(text.encode())
  if name=='empty-outside':
   for page in pdf.pages:
    for a in page.obj.get('/Annots',[]):
     if a.get('/Subtype')==pikepdf.Name.FileAttachment:a.Rect=[10000,10000,10010,10010]
  if name=='empty-header-only':
   for node in v.get_dpart_nodes(pdf)[2]:
    if node['dpm'].get('Mail_ContentSetType')==pikepdf.Name.BodyRendering:node['node'].DPM.Mail_ContentSetType=pikepdf.Name.EmailHeaderRendering
  pdf.save(p,static_id=True,fix_metadata_version=False)
 failures=[] if name in ('absent-guid','matching-guid') else ['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID']
 if name=='empty-outside':failures=sorted(failures+['ATTACHMENT_ANNOTATIONS_PRESENT'])
 rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'PASS',expected_failures=failures))
(HERE/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table19 p90: supplied GUID reflects containing email generated GUID; optional omission does not permit a supplied nonmatching value. Empty element, attribute and qualified simple text retain presence.',cases=rows),indent=2)+'\n')
