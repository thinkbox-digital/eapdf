"""Inspect declared owner contradictions independently of annotation geometry."""
import hashlib,json,re,sys
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT));import validate_eapdf as v
seed=ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid/readable-guid.pdf'
rows=[]
for name in ('unknown-owner-guid','duplicate-owner-guid','conflicting-owner-aliases'):
 path=HERE/(name+'.pdf')
 with pikepdf.open(seed) as pdf:
  text=pdf.Root.Metadata.read_bytes().decode('utf-8')
  root,_,_=v.parse_xmp(text.encode(),v.Report(str(seed)))
  records=v.flatten_email_records(root);original=records[0]['Mail_GUID']
  if name=='unknown-owner-guid':
   # Change the GUID only inside the single attachment record.
   text,count=re.subn(r'(<pdfmailmeta:attachments>.*?<pdfmailmeta:Mail_GUID>).*?(</pdfmailmeta:Mail_GUID>)',lambda m:m.group(1)+'unknown-owner'+m.group(2),text,flags=re.S);assert count==1
  elif name=='duplicate-owner-guid':
   email=re.search(r'(<pdfmailmeta:email>\s*<rdf:Bag>)(.*?)(</rdf:Bag>\s*</pdfmailmeta:email>)',text,re.S);assert email
   # Duplicate email XMP record; leave original body and its native annotation intact.
   text=text[:email.start(2)]+email.group(2)*2+text[email.end(2):]
  else:
   text,count=re.subn(r'(<pdfmailmeta:email>\s*<rdf:Bag>\s*<rdf:li[^>]*>)',lambda m:m.group(1)+'<pdfmailmeta:Message-ID>different-owner</pdfmailmeta:Message-ID>',text);assert count==1
  pdf.Root.Metadata.write(text.encode());pdf.save(path,static_id=True,fix_metadata_version=False)
 report=v.validate(str(path))
 rows.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),status=report.summary()['status'],failures=[i for i in report.items if i['level']=='FAIL'],annotation=[i for i in report.items if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT']))
 print(name,report.summary()['status'],[(i['code'],i['level']) for i in report.items if i['level']=='FAIL' or i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'],flush=True)
(HERE/'results.json').write_text(json.dumps(rows,indent=2,default=str)+'\n')
