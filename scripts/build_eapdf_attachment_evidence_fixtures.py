"""Exact filename and native/Alternative annotation evidence under Table 19/7.5/9.3."""
from pathlib import Path
import hashlib
import json
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as v


def main():
    output=ROOT/'tests/fixtures/eapdf/validator-attachment-evidence'
    output.mkdir(exist_ok=True)
    isolated=ROOT/'tests/fixtures/eapdf/validator-attachment-filenames/source-absent-original-no-checksum.pdf'
    retained=ROOT/'tests/fixtures/eapdf/validator-attachment-filenames/valid.pdf'
    cases=[]
    recipes=[('valid',[], 'PASS'),('exact-double-spaces',[], 'PASS'),('original-path',[], 'INCOMPLETE'),
             ('original-padding',[], 'INCOMPLETE'),('native-double-spaces',[], 'INCOMPLETE'),
             ('source-name-differs',['ATTACHMENT_XMP_MATCH'],'FAIL'),
             ('native-and-alternative',[],'PASS'),('alternative-annotation',['ATTACHMENT_ANNOTATIONS_PRESENT'],'FAIL')]
    for name,failures,status in recipes:
        source=retained if name=='source-name-differs' else isolated
        with pikepdf.open(source) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            row=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))[0]
            filename=row.find(v.qn(v.NS_PDFMAILMETA,'Filename'))
            annotation=next(a for a in v.collect_file_attachment_annotations(pdf) if a['file']['afrelationship']!='Source')
            spec=pdf.get_object(annotation['fs_objgen'])
            if name in ('exact-double-spaces','source-name-differs'):
                filename.text=filename.text.replace(' ','  ',1)
            elif name=='original-path':filename.text='unrelated-folder/'+filename.text
            elif name=='original-padding':filename.text='  '+filename.text+'  '
            if name in ('exact-double-spaces','native-double-spaces'):
                stored=filename.text if name=='exact-double-spaces' else str(spec.UF).replace(' ','  ',1)
                spec.F=pikepdf.String(stored);spec.UF=pikepdf.String(stored)
            if name in ('native-and-alternative','alternative-annotation'):
                native=pdf.get_object(annotation['file']['ef_objgen'])
                alternate_stream=pdf.make_stream(bytes(native.read_bytes()))
                for key,value in native.items():
                    if key not in ('/Length','/Filter','/DecodeParms','/AF'):
                        alternate_stream[key]=value
                alternate=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,
                    F=spec.F,UF=spec.UF,AFRelationship=pikepdf.Name.Alternative,
                    EF=pikepdf.Dictionary(F=alternate_stream,UF=alternate_stream)))
                native.AF=pikepdf.Array([alternate])
                if '/Names' not in pdf.Root:pdf.Root.Names=pikepdf.Dictionary()
                if '/EmbeddedFiles' not in pdf.Root.Names:
                    pdf.Root.Names.EmbeddedFiles=pdf.make_indirect(pikepdf.Dictionary(Names=pikepdf.Array()))
                tree=pikepdf.NameTree(pdf.Root.Names.EmbeddedFiles)
                tree['native-attachment']=spec
                tree['alternative-representation']=alternate
                if name=='alternative-annotation':
                    pdf.get_object(annotation['objgen']).FS=alternate
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_failures=failures,
            expected_status=status,required_unavailable_codes=['ATTACHMENT_XMP_MATCH','ATTACHMENT_ANNOTATIONS_PRESENT','VALIDATION_INCOMPLETE'] if status=='INCOMPLETE' else []))
    (output/'expectations.json').write_text(json.dumps(dict(authority=[
        'EA-PDF 9.8.4 Table 19 Filename: case-sensitive original filename text; PDF filename may differ.',
        'EA-PDF 7.5 printed pp44-45: AFRelationship Alternative identifies alternate representations.',
        'EA-PDF 9.3 printed p65: native attachment annotation in every applicable BodyRendering Content Set.'],
        sources={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (isolated,retained)},cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'filename/role evidence probes')


if __name__=='__main__':main()
