"""Same payload/metadata comparisons across distinct XMP attachment owners."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from xml.etree import ElementTree as ET

import pikepdf

ROOT=Path(__file__).resolve().parents[1]
sys.path[:0]=[str(ROOT),str(ROOT/'scripts')]
import validate_eapdf as v
from build_eapdf_attachment_filename_fixtures import omit_source


def main():
    output=ROOT/'tests/fixtures/eapdf/validator-attachment-equivalence';output.mkdir(exist_ok=True)
    source=output/'_source.pdf'
    for index in (1,2):
        raw=(f'From: Sender <sender@example.org>\r\nTo: Reader <reader@example.org>\r\n'
             f'Date: Tue, 20 Oct 2026 12:0{index}:00 +0000\r\nSubject: Owner {index}\r\n'
             f'Message-ID: <equivalent-{index}@example.org>\r\nMIME-Version: 1.0\r\n'
             'Content-Type: multipart/mixed; boundary=parts\r\n\r\n'
             '--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody.\r\n'
             '--parts\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=same.txt\r\n'
             'Content-Transfer-Encoding: base64\r\n\r\ncGF5bG9hZA==\r\n--parts--\r\n').encode()
        (output/f'source-{index}.eml').write_bytes(raw)
    if not source.exists():
        jar=os.environ.get('EAPDF_GENERATOR_CLASSPATH',str(ROOT/'target'/('emailtopdfa-'+(ROOT/'VERSION').read_text().strip()+'.jar')))
        with tempfile.TemporaryDirectory(prefix='eapdf-v19-') as directory:
            work=Path(directory);inputs=work/'inputs';inputs.mkdir()
            for p in output.glob('*.eml'):(inputs/p.name).write_bytes(p.read_bytes())
            result=subprocess.run(['java','-Xmx512m','-cp',jar,'eapdf.EmailToPDFA','--out',str(work/'out'),
                '--debug-dir',str(work/'scratch'),'--archive-profile','m','--archive-output',str(work/'out/archive.pdf'),str(inputs)],
                cwd=ROOT,capture_output=True,text=True,timeout=180)
            (output/'generation.log').write_text(result.stdout+result.stderr)
            assert result.returncode==0,result.stdout+result.stderr
            source.write_bytes((work/'out/archive.pdf').read_bytes())
    recipes=[('two-owners',[],'PASS'),('two-owners-reversed',[],'PASS'),
             ('isolated-equivalent',[],'PASS'),('isolated-wrong-size',['ATTACHMENT_XMP_MATCH'],'FAIL'),
             ('isolated-wrong-media',['EMBEDDED_SUBTYPE_MATCH_XMP'],'FAIL'),
             ('isolated-ambiguous-size',[],'INCOMPLETE'),('isolated-ambiguous-media',[],'INCOMPLETE'),
             ('isolated-distinct-payloads',[],'INCOMPLETE')]
    cases=[]
    for name,failures,status in recipes:
        with pikepdf.open(source) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            assert len(bag)==2
            if name=='two-owners-reversed':bag[:]=list(reversed(list(bag)))
            if name.startswith('isolated-'):
                omit_source(pdf,root)
                root.find('.//'+v.qn(v.NS_PDFMAILID,'conformance')).text='mi'
                for row in bag:row.remove(row.find(v.qn(v.NS_PDFMAILMETA,'CheckSum')))
                files=v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf))
                native=[f for f in files if f['afrelationship']=='Data' and f['md5']==v.md5hex(b'payload')]
                assert len(native)==2,len(native)
                for f in native:
                    spec=pdf.get_object(f['objgen']);spec.F=pikepdf.String('same.txt');spec.UF=pikepdf.String('same.txt')
                for item in v.get_dpart_nodes(pdf)[2]:
                    if v.safe_name(item['dpm'].get('Mail_ContentSetType'))=='BodyRendering':
                        item['node'].DPM.Mail_ContentSetType=pikepdf.Name.EmailHeaderRendering
                for obj in pdf.objects:
                    if isinstance(obj,pikepdf.Dictionary) and str(obj.get('/S')) in ('/Mail_ContentSet','/Mail_ContentGroup'):
                        for prop in obj.A.P:
                            if str(prop.N)=='Mail_ContentSetType' and str(prop.V)=='BodyRendering':
                                prop.V=pikepdf.String('EmailHeaderRendering')
                if name.endswith('wrong-size'):
                    for row in bag:row.find(v.qn(v.NS_PDFMAILMETA,'SizeInBytes')).text='8'
                if name.endswith('wrong-media'):
                    for row in bag:row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text='application/pdf'
                if name.endswith('ambiguous-size'):bag[1].find(v.qn(v.NS_PDFMAILMETA,'SizeInBytes')).text='8'
                if name.endswith('ambiguous-media'):bag[1].find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text='application/pdf'
                if name.endswith('distinct-payloads'):
                    stream=pdf.get_object(native[1]['objgen']).EF.F
                    stream.write(b'another');stream.Params.Size=7
                    stream.Params.CheckSum=pikepdf.String(hashlib.md5(b'another').digest())
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_failures=failures,expected_status=status))
    (output/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 7.5: filenames need not be unique and annotations can share embedded streams',
                   'EA-PDF 9.3: owning-email body annotation requirements remain separate',
                   'EA-PDF 9.8.4 Table 19: stored size/checksum/media consistency'],
        boundary='Equivalent metadata permits the same comparisons for all candidates. No unique owner or occurrence is inferred.',
        source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'equivalent attachment comparison probes')


if __name__=='__main__':main()
