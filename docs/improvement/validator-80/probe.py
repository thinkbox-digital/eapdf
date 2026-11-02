import sys,io
from pathlib import Path
from xml.etree import ElementTree as ET
sys.path[:0]=[str(Path.cwd()),str(Path.cwd()/'scripts')]
import pikepdf,validate_eapdf as v
from build_eapdf_collection_child_status_fixtures import corrected_child
from build_eapdf_ambiguous_source_header_fixtures import add_source
seed=Path('tests/fixtures/eapdf/validator-presence/_source-no-attachments.pdf')
data,_=corrected_child(seed.read_bytes())
with pikepdf.open(io.BytesIO(data)) as pdf,pikepdf.open('tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf') as donor:
 packet=pdf.Root.Metadata.read_bytes()
 for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
 root=ET.fromstring(packet);email=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'email')+'/'+v.qn(v.NS_RDF,'Bag')+'/'+v.qn(v.NS_RDF,'li'))
 raw=b'From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: No attachments\r\n\r\nPlain body.\r\n'
 email.find(v.qn(v.NS_PDFMAILMETA,'SizeInBytes')).text=str(len(raw))
 number=email.find(v.qn(v.NS_PDFMAILMETA,'NumberAttachments'))
 if number is not None:number.text='0'
 for name in ('assets',):
  if root.find('.//'+v.qn(v.NS_PDFMAILMETA,name)) is None:
   ET.SubElement(ET.SubElement(root[0],v.qn(v.NS_PDFMAILMETA,name)),v.qn(v.NS_RDF,'Bag'))
 pdf.Root.AF=pikepdf.Array()
 if '/Names' not in pdf.Root:pdf.Root.Names=pikepdf.Dictionary()
 pdf.Root.Names.EmbeddedFiles=pikepdf.Dictionary(Names=pikepdf.Array())
 donor_source=next(s for s in donor.Root.AF if s.AFRelationship==pikepdf.Name.Source)
 add_source(pdf,root,donor_source,raw,'represented.eml')
 for extra in (False,True):
  if extra:add_source(pdf,root,donor_source,donor_source.EF.F.read_bytes(),'unrendered.eml')
  serialized=ET.tostring(root,encoding='utf-8')
  for prefix,uri in [('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)]:
   if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
  pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
  path=Path('/tmp/eapdf-profile-'+('extra' if extra else 'positive')+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
  report=v.validate(str(path));print(path,report.summary()['status'],sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
  print([(i['code'],i['details']) for i in report.items if i['code']=='MESSAGE_SOURCE_MATCH' and i['level']=='PASS'])
