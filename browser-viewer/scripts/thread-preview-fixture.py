"""Isolate metadata threading cases without running or changing the Creator."""
from pathlib import Path
import sys,io,hashlib
import pikepdf
from lxml import etree as ET
ns={'rdf':'http://www.w3.org/1999/02/22-rdf-syntax-ns#','meta':'http://www.pdfa.org/eapdf/ns/meta/'}
def change(pdf,start=0):
    xml=ET.fromstring(pdf.Root.Metadata.read_bytes())
    records=xml.xpath('//meta:email/rdf:Bag/rdf:li',namespaces=ns)
    for i,container in enumerate(records):
        resource=container.find('rdf:Description',ns)
        if resource is None:resource=container
        index=start+i
        for old in resource.findall('meta:messageid',ns):resource.remove(old)
        for key,value in [('Message-ID',f'<preview{index}@test>'),('Subject',f'Preview thread {index}')]:
            node=resource.find('meta:'+key,ns)
            if node is None:node=ET.SubElement(resource,'{'+ns['meta']+'}'+key)
            node.text=value
        for key in ['References','In-Reply-To']:
            for node in resource.findall('meta:'+key,ns):resource.remove(node)
            if index:
                node=ET.SubElement(resource,'{'+ns['meta']+'}'+key);bag=ET.SubElement(node,'{'+ns['rdf']+'}Bag');ET.SubElement(bag,'{'+ns['rdf']+'}li').text=f'<preview{index-1}@test>'
    pdf.Root.Metadata.write(ET.tostring(xml))
work=Path(sys.argv[1])
for profile in ['m','mi']:
    with pikepdf.open(f'../examples/pst/pst_quality-attached-message_1{profile}.pdf') as pdf:
        change(pdf);pdf.save(work/f'preview_1{profile}.pdf')
for profile in ['c','ci']:
    with pikepdf.open(f'../examples/eml/eml_Agenda_and_timeline_slides_archive_1{profile}.pdf') as pdf:
        change(pdf);index=0
        for obj in pdf.objects:
            if isinstance(obj,pikepdf.Dictionary) and obj.get('/Type')==pikepdf.Name('/Filespec') and str(obj.get('/UF','')).endswith('.pdf'):
                stream=obj.EF.get('/UF',obj.EF.get('/F'))
                with pikepdf.open(io.BytesIO(stream.read_bytes())) as child:
                    change(child,index);data=io.BytesIO();child.save(data)
                stream.write(data.getvalue());stream.Params=pikepdf.Dictionary(Size=len(data.getvalue()),CheckSum=pikepdf.String(hashlib.md5(data.getvalue()).digest()));index+=1
        pdf.save(work/f'preview_1{profile}.pdf')
