"""Declared parameters without a native association remain unavailable evidence."""
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
    output=ROOT/'tests/fixtures/eapdf/validator-unmatched-parameters';output.mkdir(exist_ok=True)
    seed=ROOT/'tests/fixtures/eapdf/validator-attachment-equivalence/isolated-equivalent.pdf'
    media='EMBEDDED_SUBTYPE_MATCH_XMP';params='SOURCE_MEDIATYPE_PARAMETERS_MATCH'
    recipes=[('unknown-alias-parameters','INCOMPLETE',[],'WARN'),
             ('unknown-alias-malformed','INCOMPLETE',[],'WARN'),
             ('mixed-verified-and-unknown','INCOMPLETE',[],'WARN'),
             ('mixed-failure-and-unknown','FAIL',[media,params],'FAIL'),
             ('checksum-known-alias','PASS',[],'PASS'),
             ('no-parameters-control','INCOMPLETE',[],'N/A'),
             ('alternative-only-control','PASS',[],'N/A'),
             ('mixed-bare-and-unknown-parameters','INCOMPLETE',[],'WARN')]
    cases=[]
    for name,status,failures,level in recipes:
        with pikepdf.open(seed) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            files=[f for f in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf))
                   if f['afrelationship']=='Data' and f['ufilename']=='same.txt']
            assert len(bag)==len(files)==2
            for index,(row,fileinfo) in enumerate(zip(bag,files)):
                spec=pdf.get_object(fileinfo['objgen']);stream=spec.EF.F
                spec.F=pikepdf.String(f'stored-{index}.txt');spec.UF=spec.F
                row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text='text/plain; note=x'
                stream.Mail_MediaTypeParameters=pikepdf.String('note=x')
                if name=='unknown-alias-malformed':
                    row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text='text/plain; note=x; =ignored'
                    stream.Mail_MediaTypeParameters=pikepdf.String(' note=x; =ignored')
                if name.startswith('mixed-'):
                    if index==0:
                        spec.F=pikepdf.String('same.txt');spec.UF=spec.F
                        if name=='mixed-failure-and-unknown':stream.Mail_MediaTypeParameters=pikepdf.String('note=y')
                    else:row.find(v.qn(v.NS_PDFMAILMETA,'Filename')).text='missing.txt'
                if name=='checksum-known-alias':ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,'CheckSum')).text=fileinfo['md5']
                if name=='no-parameters-control' or (name=='mixed-bare-and-unknown-parameters' and index==0):
                    row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text='text/plain'
                    del stream.Mail_MediaTypeParameters
                if name=='alternative-only-control':spec.AFRelationship=pikepdf.Name.Alternative
            if name=='alternative-only-control':
                # The archive also embeds its index workbook. Exclude that
                # supplementary file too, so this control has no native files.
                for fileinfo in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf)):
                    pdf.get_object(fileinfo['objgen']).AFRelationship=pikepdf.Name.Alternative
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,
                          expected_failures=sorted(failures),expected_parameter_level=level))
    (output/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.4 pp66-67: reconstructed native media/parameters match equivalent XMP',
                   'EA-PDF Table 19 pp89-90: original Filename can differ from native name and Content-Type retains parameters/errors',
                   'EA-PDF 7.5 p44: native streams can be shared; removed attachments are not universally required as separate copies'],
        boundary='Report unavailable declared parameters only when native files exist but no association is established. Do not invent a native copy requirement.',
        seed=str(seed.relative_to(ROOT)),seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'unmatched-parameter probes')


if __name__=='__main__':main()
