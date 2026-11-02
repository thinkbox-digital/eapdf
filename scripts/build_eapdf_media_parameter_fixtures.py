"""Retain parameter text despite MIME recovery and whitespace cleanup."""
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
    output=ROOT/'tests/fixtures/eapdf/validator-media-parameters';output.mkdir(exist_ok=True)
    isolated=ROOT/'tests/fixtures/eapdf/validator-attachment-streams/valid.pdf'
    quoted=output/'_quoted-source.pdf'
    raw=(b'From: Sender <sender@example.org>\r\nTo: Reader <reader@example.org>\r\n'
         b'Date: Tue, 20 Oct 2026 12:00:00 +0000\r\nSubject: Original media parameters\r\n'
         b'Message-ID: <parameters@example.org>\r\nMIME-Version: 1.0\r\n'
         b'Content-Type: multipart/mixed; boundary=parts\r\n\r\n'
         b'--parts\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody.\r\n'
         b'--parts\r\nContent-Type: text/plain; name="A  B.txt"; note=reserve0123456789\r\n'
         b'Content-Disposition: attachment; filename=file.txt\r\n'
         b'Content-Transfer-Encoding: base64\r\n\r\ncGF5bG9hZA==\r\n--parts--\r\n')
    (output/'source.eml').write_bytes(raw)
    if not quoted.exists():
        jar=os.environ.get('EAPDF_GENERATOR_CLASSPATH',str(ROOT/'target'/('emailtopdfa-'+(ROOT/'VERSION').read_text().strip()+'.jar')))
        with tempfile.TemporaryDirectory(prefix='eapdf-v20-') as directory:
            work=Path(directory)
            result=subprocess.run(['java','-Xmx512m','-cp',jar,'eapdf.EmailToPDFA','--out',str(work/'out'),
                '--debug-dir',str(work/'scratch'),str(output/'source.eml')],cwd=ROOT,capture_output=True,text=True,timeout=180)
            (output/'generation.log').write_text(result.stdout+result.stderr)
            assert result.returncode==0,result.stdout+result.stderr
            files=list((work/'out').glob('*.pdf'));assert len(files)==1,files
            quoted.write_bytes(files[0].read_bytes())
    bad_media=['EMBEDDED_SUBTYPE_MATCH_XMP','SOURCE_MEDIATYPE_PARAMETERS_MATCH']
    source_media=['ATTACHMENT_XMP_CONTENTTYPE_MATCH']
    recipes=[('control',[]),('extra-empty-parameter',bad_media),('nameless-parameter',bad_media),
             ('unquoted-space',bad_media),('malformed-preserved',[]),('legal-extended-parameter',[]),
             ('quoted-source',[]),('quoted-source-cleaned',source_media),
             ('malformed-source-preserved',[]),('malformed-source-repaired',source_media)]
    cases=[]
    for name,failures in recipes:
        retained=name.startswith(('quoted-source','malformed-source'))
        with pikepdf.open(quoted if retained else isolated) as pdf:
            root,namespaces,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in namespaces.items():ET.register_namespace(prefix,uri)
            row=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))[0]
            node=row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type'))
            native=next(f for f in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf)) if f['afrelationship']=='Data')
            stream=pdf.get_object(native['ef_objgen'])
            bare=native['subtype'];params=native['media_type_parameters'];canonical=bare+';'+params
            if name=='extra-empty-parameter':node.text=bare+'; ;'+params
            elif name=='nameless-parameter':node.text=canonical+'; =ignored'
            elif name=='unquoted-space':node.text=bare+'; name='+row.find(v.qn(v.NS_PDFMAILMETA,'Filename')).text
            elif name=='malformed-preserved':
                node.text=canonical+'; =ignored';stream.Mail_MediaTypeParameters=pikepdf.String(params+'; =ignored')
            elif name=='legal-extended-parameter':
                node.text=bare+"; name*=UTF-8''r%C3%A9sum%C3%A9.txt"
                stream.Mail_MediaTypeParameters=pikepdf.String('name="résumé.txt"')
            elif name=='quoted-source-cleaned':
                node.text=node.text.replace('A  B','A B')
                stream.Mail_MediaTypeParameters=pikepdf.String(params.replace('A  B','A B'))
            elif name.startswith('malformed-source'):
                source=v.get_af_specs(pdf)
                source=next(f for f in source if f['afrelationship']=='Source')
                old=b'note=reserve0123456789';new=b'=ignored'+b' '*(len(old)-len(b'=ignored'))
                assert source['bytes'].count(old)==1
                changed=source['bytes'].replace(old,new)
                assert len(changed)==source['size']
                source_stream=pdf.get_object(source['ef_objgen']);source_stream.write(changed)
                source_stream.Params.Size=len(changed)
                source_stream.Params.CheckSum=pikepdf.String(hashlib.md5(changed).digest())
                asset=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'assets')+'/'+v.qn(v.NS_RDF,'Bag'))[0]
                asset.find(v.qn(v.NS_PDFMAILMETA,'Checksum')).text=v.md5hex(changed)
                message=v.BytesParser(policy=v.policy.default).parsebytes(changed)
                content_type=v.summarize_email_attachments(message,changed)[0]['content_type']
                node.text=content_type if name.endswith('preserved') else 'text/plain; name="A B.txt"'
                stream.Mail_MediaTypeParameters=pikepdf.String(node.text.split(';',1)[1])
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_failures=failures,expected_status='FAIL' if failures else 'PASS'))
    (output/'expectations.json').write_text(json.dumps(dict(authority=[
        'EA-PDF 9.4, printed pp66-67: embedded media parameters and required reconstruction algorithm.',
        'EA-PDF 9.8.4 Table 19: preserve the original attachment Content-Type text, including errors.'],
        boundary='Malformed source text remains permitted. Parser repair cannot establish equality with different text.',
        retained_source_sha256=hashlib.sha256(quoted.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'media-parameter probes')


if __name__=='__main__':main()
