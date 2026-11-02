"""Known typed unique parent MessageID bridges an attachment GUID to its body."""
import hashlib,json,shutil
from pathlib import Path
import pikepdf
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid'
GUID=ROOT/'tests/fixtures/eapdf/validator-dpm-guid-decoding'
def main():
 OUT.mkdir(parents=True,exist_ok=True);rows=[]
 mapping={'empty-guid':'empty','name-guid':'wrong-name','decoded-empty-guid':'utf16-decoded-empty','readable-guid':'valid-utf8','unreadable-guid':'unreadable-utf8','conflicting-guid':'readable-mismatch'}
 for name in ('empty-guid','name-guid','decoded-empty-guid','integer-guid','readable-guid','unreadable-guid','conflicting-guid','outside-annotations'):
  p=OUT/(name+'.pdf')
  if name in mapping:shutil.copyfile(GUID/(mapping[name]+'.pdf'),p)
  else:
   with pikepdf.open(GUID/'empty.pdf') as pdf:
    def walk(node):
     yield node
     for group in node.get('/DParts',[]):
      for child in group if isinstance(group,pikepdf.Array) else [group]:yield from walk(child)
    owner=next(n for n in walk(pdf.Root.DPartRoot.DPartRootNode) if n.get('/DPM',{}).get('/Mail_GUID') is not None)
    if name=='integer-guid':owner.DPM.Mail_GUID=42
    else:
     for page in pdf.pages:
      box=page.obj.get('/CropBox',page.obj.get('/MediaBox'))
      for annot in page.obj.get('/Annots',[]):
       if annot.get('/Subtype')==pikepdf.Name.FileAttachment:
        x,y=float(box[0])-1000,float(box[1])-1000;annot.Rect=pikepdf.Array([x,y,x+10,y+10])
    pdf.save(p,static_id=True,fix_metadata_version=False)
  failures=[] if name in ('readable-guid','unreadable-guid') else ['ATTACHMENT_ANNOTATIONS_PRESENT','DPM_MAIL_GUID_MATCH','MAIL_MESSAGE_GUID_MATCH_DPM_XMP'] if name=='conflicting-guid' else ['ATTACHMENT_ANNOTATIONS_PRESENT','DPM_MAIL_GUID_PRESENT'] if name=='outside-annotations' else ['DPM_MAIL_GUID_PRESENT']
  rows.append(dict(file=p.name,sha256=hashlib.sha256(p.read_bytes()).hexdigest(),expected_status='FAIL' if failures else 'INCOMPLETE' if name=='unreadable-guid' else 'PASS',expected_failures=failures,expected_annotation='WARN' if name=='unreadable-guid' else 'FAIL' if name in ('conflicting-guid','outside-annotations') else 'PASS'))
 (OUT/'expectations.json').write_text(json.dumps(dict(authority='EA-PDF1.0 Table7 GUID precedence and optional MessageID; section9.3 p65 attachment body ownership',boundary='A normal text MessageID unique across email records supplies the missing body identity field of a uniquely GUID-bound XMP attachment owner. Unknown, conflicting, ambiguous and typed-invalid identities do not justify a new match.',cases=rows),indent=2)+'\n')
if __name__=='__main__':main()
