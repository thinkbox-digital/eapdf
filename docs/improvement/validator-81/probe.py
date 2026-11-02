"""Original nested email bytes must not be excluded by a top-level-only proof."""
import sys,io,json,hashlib
from pathlib import Path
from xml.etree import ElementTree as ET
ROOT=Path(__file__).resolve().parents[3]
sys.path[:0]=[str(ROOT),str(ROOT/'scripts')]
import pikepdf,validate_eapdf as v
from build_eapdf_empty_raw_fixtures import update_source,qn,META,RDF
seed=ROOT/'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
with pikepdf.open(seed) as pdf:
 packet=pdf.Root.Metadata.read_bytes()
 for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
 root=ET.fromstring(packet);email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
 spec=next(spec for spec in pdf.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
 child=spec.EF.F.read_bytes()
 outer=(b'From: outer@example.org\r\nTo: archive@example.org\r\nSubject: Outer container\r\n'
        b'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nMessage-ID: <outer@example.org>\r\n'
        b'MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=scope\r\n\r\n'
        b'--scope\r\nContent-Type: text/plain\r\n\r\nOuter message.\r\n'
        b'--scope\r\nContent-Type: message/rfc822\r\nContent-Disposition: attachment; filename="original.eml"\r\n\r\n'
        +child+b'\r\n--scope--\r\n')
 update_source(pdf,root,email,outer);spec.EF.UF=spec.EF.F
 email.find(qn(META,'SizeInBytes')).text=str(len(child))
 for asset in root.findall('.//'+qn(META,'assets')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li')):
  if asset.find(qn(META,'Checksum')).text==hashlib.md5(outer).hexdigest():
   number=asset.find(qn(META,'NumberMessages'))
   if number is None:number=ET.SubElement(asset,qn(META,'NumberMessages'))
   number.text='2'
 serialized=ET.tostring(root,encoding='utf-8')
 for prefix,uri in [('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',META)]:
  if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
 pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
 path=Path('/tmp/eapdf-nested-original-scope.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
report=v.validate(str(path))
out=dict(file=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),summary=report.summary(),findings=[i for i in report.items if i['level'] in ('WARN','FAIL')])
(Path(__file__).resolve().parent/'before-probe.json').write_text(json.dumps(out,indent=2,default=str)+'\n')
print(report.summary()['status'],sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
