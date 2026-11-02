"""Table19 owner GUID controls keep actual annotations independent of metadata errors."""
import hashlib,json,re,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT));import validate_eapdf as v
SEED=ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid/readable-guid.pdf'
rows=[]
for name in ('known-owner','unknown-owner','unknown-owner-optional-mid','unknown-owner-header-only','unknown-owner-case','unknown-owner-spaces','known-owner-outside','wrong-guid-type'):
 p=HERE/(name+'.pdf')
 with pikepdf.open(SEED) as pdf:
  text=pdf.Root.Metadata.read_bytes().decode('utf-8');root,_,_=v.parse_xmp(text.encode(),v.Report(str(SEED)))
  owner=v.flatten_email_records(root)[0];guid=owner['Mail_GUID']
  if name.startswith('unknown-owner') or name=='wrong-guid-type':
   replacement='unknown-owner' if name not in ('unknown-owner-case','unknown-owner-spaces','wrong-guid-type') else guid.upper() if name.endswith('case') else ' '+guid+' ' if name.endswith('spaces') else ''
   def change(m):
    if name=='wrong-guid-type':return m.group(1)+'<pdfmailmeta:Mail_GUID rdf:resource="urn:wrong-type"/>'+m.group(3)
    return m.group(1)+'<pdfmailmeta:Mail_GUID>'+replacement+'</pdfmailmeta:Mail_GUID>'+m.group(3)
   text,count=re.subn(r'(<pdfmailmeta:attachments>.*?)<pdfmailmeta:Mail_GUID>(.*?)</pdfmailmeta:Mail_GUID>(.*?</pdfmailmeta:attachments>)',change,text,flags=re.S);assert count==1
   if name=='unknown-owner-optional-mid':
    mid=v.first_present(owner,'Message-ID','messageid')
    text,count=re.subn(r'(</rdf:li>\s*</rdf:Bag>\s*</pdfmailmeta:attachments>)',lambda m:'<pdfmailmeta:Message-ID>'+mid+'</pdfmailmeta:Message-ID>'+m.group(1),text);assert count==1
   pdf.Root.Metadata.write(text.encode())
  if name=='unknown-owner-header-only':
   for node in v.get_dpart_nodes(pdf)[2]:
    if node['dpm'].get('Mail_ContentSetType')==pikepdf.Name.BodyRendering:node['node'].DPM.Mail_ContentSetType=pikepdf.Name.EmailHeaderRendering
  if name=='known-owner-outside':
   for page in pdf.pages:
    for a in page.obj.get('/Annots',[]):
     if a.get('/Subtype')==pikepdf.Name.FileAttachment:a.Rect=[10000,10000,10010,10010]
  pdf.save(p,static_id=True,fix_metadata_version=False)
 failure=[] if name=='known-owner' else ['ATTACHMENT_ANNOTATIONS_PRESENT'] if name=='known-owner-outside' else ['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID']
 annotation='FAIL' if name=='known-owner-outside' else 'N/A' if name=='unknown-owner-header-only' else 'WARN' if name.startswith('unknown-owner') else 'PASS'
 rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),expected_status='FAIL' if failure else 'PASS',expected_failures=failure,expected_annotation=annotation))
(HERE/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table19 p90: supplied Mail_GUID reflects containing email GUID; section9.3 body annotation applicability requires an established owner.',cases=rows),indent=2)+'\n')
