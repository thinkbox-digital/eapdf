"""Native byte contradictions that do not require choosing an attachment owner."""
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
    output=ROOT/'tests/fixtures/eapdf/validator-ambiguous-bytes';output.mkdir(exist_ok=True)
    inputs=ROOT/'tests/fixtures/eapdf/validator-attachment-equivalence'
    recipes=[
        ('all-sizes-wrong','isolated-equivalent','FAIL'),
        ('one-size-wrong','isolated-ambiguous-size','FAIL'),
        ('distinct-sizes-impossible','isolated-distinct-payloads','FAIL'),
        ('distinct-sizes-compatible','isolated-distinct-payloads','INCOMPLETE'),
        ('all-checksums-wrong','isolated-equivalent','FAIL'),
        ('distinct-checksums-impossible','isolated-distinct-payloads','FAIL'),
        ('some-checksums-compatible','isolated-distinct-payloads','INCOMPLETE'),
        ('checksum-absent','isolated-distinct-payloads','INCOMPLETE'),
        ('placeholder-compatible','isolated-distinct-payloads','INCOMPLETE'),
        ('equivalent-control','isolated-equivalent','PASS'),
    ]
    cases=[]
    for name,seed,status in recipes:
        with pikepdf.open(inputs/(seed+'.pdf')) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            assert len(bag)==2
            native=[f for f in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf))
                    if f['afrelationship']=='Data' and f['ufilename']=='same.txt']
            assert len(native)==2
            def sizes(values):
                for row,value in zip(bag,values):row.find(v.qn(v.NS_PDFMAILMETA,'SizeInBytes')).text=value
            def checksums(values):
                for row,value in zip(bag,values):
                    ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,'CheckSum')).text=value
            def payload(index,data):
                stream=pdf.get_object(native[index]['objgen']).EF.F
                stream.write(data);stream.Params.Size=len(data)
                stream.Params.CheckSum=pikepdf.String(hashlib.md5(data).digest())
            if name=='all-sizes-wrong':sizes(('8','9'))
            if name.startswith('distinct-sizes-'):
                payload(1,b'longerer')
                sizes(('9','9') if name.endswith('impossible') else ('7','8'))
            if name=='all-checksums-wrong':checksums(('0'*32,'1'*32))
            if name=='distinct-checksums-impossible':checksums(('0'*32,'0'*32))
            if name=='some-checksums-compatible':checksums((v.md5hex(b'payload'),v.md5hex(b'payload')))
            if name=='placeholder-compatible':
                payload(0,b'');sizes(('0','0'))
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status=status,expected_failures=['ATTACHMENT_XMP_MATCH'] if status=='FAIL' else []))
    (output/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 7.5 printed p44: filenames may repeat and native streams may be shared',
                   'EA-PDF 9.8.4 Table 19 printed pp89-90: stored SizeInBytes and optional CheckSum'],
        boundary='Only a field contradicted by every observed possible native payload fails. Association and media ambiguity remain unavailable.',
        seeds={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(inputs.glob('isolated-*.pdf'))},
        cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'ambiguous native-byte probes')


if __name__=='__main__':main()
