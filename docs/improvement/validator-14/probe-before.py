"""Independent frozen-V13 probes for native consistency without BodyRendering."""
from pathlib import Path
from xml.etree import ElementTree as ET
import json
import sys
import pikepdf

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'.eapdf-verification-v13-final'))
import validate_eapdf as v
HERE=Path(__file__).resolve().parent
OUT=HERE/'before-probes'
OUT.mkdir(exist_ok=True)
for name, field, value in [('control',None,None),('size','SizeInBytes','1'),
                         ('checksum','CheckSum','0'*32),('media','Content-Type','application/pdf')]:
 with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-attachment-filenames/source-absent-original-no-checksum.pdf') as pdf:
  root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
  for prefix,uri in namespaces.items(): ET.register_namespace(prefix,uri)
  for item in v.get_dpart_nodes(pdf)[2]:
   if v.safe_name(item['dpm'].get('Mail_ContentSetType'))=='BodyRendering':
    item['node'].DPM.Mail_ContentSetType=pikepdf.Name.EmailHeaderRendering
  for obj in pdf.objects:
   if isinstance(obj,pikepdf.Dictionary) and str(obj.get('/S'))=='/Mail_ContentSet':
    for prop in obj.A.P:
     if str(prop.N)=='Mail_ContentSetType': prop.V=pikepdf.String('EmailHeaderRendering')
  row=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')).find(v.qn(v.NS_RDF,'Bag'))[0]
  if field:
   node=row.find(v.qn(v.NS_PDFMAILMETA,field))
   if node is None: node=ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,field))
   node.text=value
  packet=ET.tostring(root,encoding='utf-8')
  for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
   if ('xmlns:'+prefix+'=').encode() not in packet: root.set('xmlns:'+prefix,uri)
  pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
  path=OUT/(name+'.pdf')
  pdf.save(path,fix_metadata_version=False)
 result=v.validate_one(str(path),write_html=False)
 (OUT/(name+'.json')).write_text(json.dumps(result,indent=2,default=str))
 print(name,result['pdfa']['status'],result['summary']['status'],[(r['code'],r['level']) for r in result['items'] if r['level']=='FAIL' or r['code'] in ('ATTACHMENT_XMP_MATCH','EMBEDDED_SUBTYPE_MATCH_XMP','ATTACHMENT_ANNOTATIONS_PRESENT')],flush=True)
