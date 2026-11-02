"""Single-property controls for user-authorized local interpretations."""
import hashlib,json,sys,re
from pathlib import Path
from xml.etree import ElementTree as ET
import pikepdf
ROOT=Path(__file__).resolve().parents[4];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT));import validate_eapdf as v
SEED=ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
ANNOT=ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid/readable-guid.pdf'
rows=[]
def save(name,source,mutate,failures=(),status=None):
 p=HERE/(name+'.pdf')
 with pikepdf.open(source) as pdf:
  mutate(pdf);pdf.save(p,static_id=True,fix_metadata_version=False)
 rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),expected_status=status or ('FAIL' if failures else 'PASS'),expected_failures=list(failures)))
def annotate(pdf,value):
 for page in pdf.pages:
  for a in page.obj.get('/Annots',[]):
   if a.get('/Subtype')==pikepdf.Name.FileAttachment:
    if value is None:
     if '/Contents' in a:del a['/Contents']
    else:a.Contents=value
    return
 raise AssertionError('No attachment annotation')
for name,value,status in [('missing',None,'FAIL'),('name',pikepdf.Name.Desc,'FAIL'),('empty',pikepdf.String(''),'PASS'),('unicode',pikepdf.String('添付資料'),'PASS'),('unreadable',pikepdf.String(b'\xef\xbb\xbf\xff'),'INCOMPLETE')]:
 save('contents-'+name,ANNOT,lambda pdf,value=value:annotate(pdf,value),['ANNOTATION_CONTENTS_REQUIRED'] if status=='FAIL' else [],status)
def reply(pdf,kind):
 text=pdf.Root.Metadata.read_bytes().decode('utf-8')
 text=re.sub(r'<pdfmailmeta:In-Reply-To>.*?</pdfmailmeta:In-Reply-To>','',text,flags=re.S)
 text,count=re.subn(r'(</rdf:li>\s*</rdf:Bag>\s*</pdfmailmeta:email>)',
     lambda m:'<pdfmailmeta:In-Reply-To><rdf:'+kind+'/></pdfmailmeta:In-Reply-To>'+m.group(1),text)
 assert count==1
 pdf.Root.Metadata.write(text.encode('utf-8'))
for kind in ('Bag','Seq','Alt'):
 save('reply-'+kind.lower(),SEED,lambda pdf,kind=kind:reply(pdf,kind),[] if kind=='Bag' else ['MESSAGE_INREPLYTO_XMP'])
(HERE/'expectations.json').write_text(json.dumps(dict(authority='User-authorized project interpretations 2026-10-21; EA-PDF7.5 and Table18. Contents uses PDF text, not nonempty text. Unordered reply IDs use Bag.',cases=rows),indent=2)+'\n')
