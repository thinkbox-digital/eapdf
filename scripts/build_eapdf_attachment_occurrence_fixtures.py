"""Table 19 source assignment with a removed and a retained same-name file."""
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
sys.path.insert(0,str(ROOT))
import validate_eapdf as v


def main():
    output=ROOT/'tests/fixtures/eapdf/validator-attachment-occurrences'
    output.mkdir(exist_ok=True)
    source=output/'_source.pdf'
    email=(b'From: Sender <sender@example.org>\r\nTo: Reader <reader@example.org>\r\n'
           b'Date: Tue, 20 Oct 2026 12:00:00 +0000\r\nSubject: Repeated attachment names\r\n'
           b'Message-ID: <occurrences@example.org>\r\nMIME-Version: 1.0\r\n'
           b'Content-Type: multipart/mixed; boundary=parts\r\n\r\n'
           b'--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody.\r\n'
           b'--parts\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=same.txt\r\n'
           b'Content-Transfer-Encoding: base64\r\n\r\nYQ==\r\n'
           b'--parts\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=same.txt\r\n'
           b'Content-Transfer-Encoding: base64\r\n\r\nbG9uZ2Vy\r\n--parts--\r\n')
    (output/'source.eml').write_bytes(email)
    if not source.exists():
        jar=Path(os.environ.get('EAPDF_GENERATOR_CLASSPATH',str(ROOT/'target'/('emailtopdfa-'+(ROOT/'VERSION').read_text().strip()+'.jar'))))
        with tempfile.TemporaryDirectory(prefix='eapdf-v18-') as directory:
            work=Path(directory)
            result=subprocess.run(['java','-Xmx512m','-cp',str(jar),'eapdf.EmailToPDFA',
                '--out',str(work/'out'),'--debug-dir',str(work/'scratch'),str(output/'source.eml')],
                cwd=ROOT,capture_output=True,text=True,timeout=180)
            (output/'generation.log').write_text(result.stdout+result.stderr)
            assert result.returncode==0,result.stderr
            paths=list((work/'out').glob('*.pdf'));assert len(paths)==1,paths
            source.write_bytes(paths[0].read_bytes())
    recipes=[('control',False,False),('placeholder-first',True,False),
             ('retained-first',True,False),('placeholder-empty-checksum',True,False),
             ('retained-first-empty-checksum',True,False),
             ('wrong-retained-size',True,True)]
    cases=[]
    for name,placeholder,wrong_size in recipes:
        with pikepdf.open(source) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            assert len(bag)==2
            retained=next(row for row in bag if row.find(v.qn(v.NS_PDFMAILMETA,'CheckSum')).text.lower()==v.md5hex(b'a'))
            omitted=next(row for row in bag if row is not retained)
            if placeholder:
                omitted.find(v.qn(v.NS_PDFMAILMETA,'SizeInBytes')).text='0'
                checksum=omitted.find(v.qn(v.NS_PDFMAILMETA,'CheckSum'))
                if name.endswith('empty-checksum'):checksum.text=v.md5hex(b'')
                else:omitted.remove(checksum)
                files=v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf))
                native=next(file for file in files if file['afrelationship']=='Data' and file['md5']==v.md5hex(b'longer'))
                spec=pdf.get_object(native['objgen'])
                stream=spec.EF.F
                stream.write(b'')
                stream.Params.Size=0
                stream.Params.CheckSum=pikepdf.String(bytes.fromhex(v.md5hex(b'')))
                bag[:]=[retained,omitted] if name.startswith('retained-first') else [omitted,retained]
            if wrong_size:retained.find(v.qn(v.NS_PDFMAILMETA,'SizeInBytes')).text='2'
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        failures=['ATTACHMENT_ANNOTATIONS_PRESENT','ATTACHMENT_XMP_MATCH'] if wrong_size else []
        status='FAIL' if failures else 'INCOMPLETE' if name in ('placeholder-first','retained-first') else 'PASS'
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_failures=failures,expected_status=status))
    (output/'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.8.4: attachments are an unordered set',
                   'Table 19: CheckSum is optional; SizeInBytes can be zero for removed attachments'],
        scope='Source field consistency; matching does not prove unique attachment occurrence identity or a complete original inventory.',
        source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'attachment occurrence probes')


if __name__=='__main__':main()
