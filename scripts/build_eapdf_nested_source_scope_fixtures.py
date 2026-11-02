"""Known nested originals invalidate a proof based only on outer email headers."""
import hashlib
import io
import json
from email import policy
from email.parser import BytesParser
from pathlib import Path
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_empty_raw_fixtures import update_source,qn,META,RDF
from build_eapdf_ambiguous_source_header_fixtures import set_agent

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-nested-source-scope'
SEED=ROOT/'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
PLAIN=ROOT/'tests/fixtures/eapdf/validator-profile-source-scope/plain-positive.pdf'


def wrap(child,media='message/rfc822',filename='original.eml',headers=None):
    headers=headers or (b'From: outer@example.org\r\nTo: archive@example.org\r\nSubject: Outer container\r\n'
                        b'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nMessage-ID: <outer@example.org>\r\n')
    return headers+(b'MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=scope\r\n\r\n'
        b'--scope\r\nContent-Type: text/plain\r\n\r\nOuter message.\r\n--scope\r\nContent-Type: '+media.encode()+
        b'\r\nContent-Disposition: attachment; filename="'+filename.encode()+b'"\r\n\r\n'+child+b'\r\n--scope--\r\n')


def main():
    OUT.mkdir(exist_ok=True);cases=[]
    for name in ('nested-rfc822','nested-global','nested-eml-octet','nested-msg','tentative-outer','mbox-envelope',
                 'outer-consistent','ordinary-binary-wrong-to'):
        seed=PLAIN if name=='outer-consistent' else SEED
        with pikepdf.open(seed) as pdf:
            packet=pdf.Root.Metadata.read_bytes()
            for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
            root=ET.fromstring(packet);email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
            source=next(spec for spec in pdf.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
            child=source.EF.F.read_bytes();headers=None
            if name in ('tentative-outer','outer-consistent'):
                parsed=BytesParser(policy=policy.default).parsebytes(child)
                headers=b''.join((field+': '+str(parsed[field])+'\r\n').encode('utf-8')
                                 for field in ('From','To','Subject','Date','Message-ID') if parsed[field] is not None)
            if name=='ordinary-binary-wrong-to':set_agent(email,'To','wrong@example.org')
            else:
                payload=(ROOT/'tests/fixtures/eapdf/validator-uninspectable-source/original.msg').read_bytes() if name=='nested-msg' else child
                media='message/global' if name=='nested-global' else 'application/octet-stream' if name in ('nested-eml-octet','nested-msg') else 'message/rfc822'
                filename='original.msg' if name=='nested-msg' else 'original.data' if name=='nested-global' else 'original.eml'
                outer=wrap(payload,media,filename,headers)
                if name=='mbox-envelope':
                    outer=(b'From outer@example.org Tue Jan 28 21:20:54 2003\n'+
                           wrap(b'Other data','application/octet-stream','data.bin')+
                           b'\nFrom child@example.org Thu Sep 30 13:24:56 2010\n'+child)
                update_source(pdf,root,email,outer);source.EF.UF=source.EF.F
                if name!='outer-consistent':email.find(qn(META,'SizeInBytes')).text=str(len(child))
                else:
                    email.find(qn(META,'NumberAttachments')).text='1'
                    pdf.Root.PageMode=pikepdf.Name.UseAttachments
                    pdf.Root.ViewerPreferences.NonFullScreenPageMode=pikepdf.Name.UseAttachments
                for asset in root.findall('.//'+qn(META,'assets')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li')):
                    if asset.find(qn(META,'Checksum')).text==hashlib.md5(outer).hexdigest():
                        number=asset.find(qn(META,'NumberMessages'))
                        if number is None:number=ET.SubElement(asset,qn(META,'NumberMessages'))
                        number.text='2'
            serialized=ET.tostring(root,encoding='utf-8')
            for prefix,uri in [('pdfmail','http://www.pdfa.org/eapdf/'),('pdfmailid','http://www.pdfa.org/eapdf/ns/id/'),('pdfmailmeta',META)]:
                if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        failures=['MESSAGE_TO_FOAF'] if name=='ordinary-binary-wrong-to' else []
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS' if name=='outer-consistent' else 'INCOMPLETE',
                          expected_failures=failures,seed=str(seed.relative_to(ROOT)),seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest()))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 7.2: retained originals can contain additional unrendered email data. A top-level-only inspection cannot contradict every original when known nested mail candidates or mailbox envelopes remain uninspected.',
        boundary='No nested ownership assignment, reserialization, full mailbox enumeration or arbitrary archive-content discovery is claimed. A consistent outer original and ordinary non-mail attachments retain independent checks.',
        cases=cases),indent=2)+'\n')


if __name__=='__main__':main()
