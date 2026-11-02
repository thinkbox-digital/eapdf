"""Native viewer-mode controls for represented and unrelated original emails."""
import hashlib
import io
import json
from pathlib import Path
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_collection_child_status_fixtures import corrected_child
from build_eapdf_ambiguous_source_header_fixtures import add_source
from build_eapdf_empty_raw_fixtures import qn, META, RDF

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'tests/fixtures/eapdf/validator-profile-source-scope'
SEED=ROOT/'tests/fixtures/eapdf/validator-presence/_source-no-attachments.pdf'
DONOR=ROOT/'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
PLAIN=(b'From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n'
       b'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: No attachments\r\n\r\nPlain body.\r\n')


def main():
    OUT.mkdir(exist_ok=True)
    data,changes=corrected_child(SEED.read_bytes())
    cases=[]
    for name in ('plain-positive','extra-attached','extra-attached-reversed','extra-attached-s',
                 'wrong-page-mode','wrong-nfsm','extra-wrong-page-mode','extra-wrong-nfsm',
                 'ambiguous-mixed','ambiguous-mixed-attachments-mode','opaque-original',
                 'ambiguous-invalid-page-mode','ambiguous-invalid-nfsm'):
        with pikepdf.open(io.BytesIO(data)) as pdf,pikepdf.open(DONOR) as donor:
            packet=pdf.Root.Metadata.read_bytes()
            for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
            root=ET.fromstring(packet)
            email=root.find('.//'+qn(META,'email')+'/'+qn(RDF,'Bag')+'/'+qn(RDF,'li'))
            email.find(qn(META,'NumberAttachments')).text='0'
            if name.endswith('-s'):root.find('.//'+qn('http://www.pdfa.org/eapdf/ns/id/','conformance')).text='s'
            if root.find('.//'+qn(META,'assets')) is None:
                ET.SubElement(ET.SubElement(root[0],qn(META,'assets')),qn(RDF,'Bag'))
            pdf.Root.AF=pikepdf.Array()
            if '/Names' not in pdf.Root:pdf.Root.Names=pikepdf.Dictionary()
            pdf.Root.Names.EmbeddedFiles=pikepdf.Dictionary(Names=pikepdf.Array())
            source=next(spec for spec in donor.Root.AF if spec.AFRelationship==pikepdf.Name.Source)
            plain=PLAIN
            if name.startswith('ambiguous-'):
                attached=PLAIN.split(b'\r\n\r\n',1)[0]+(
                    b'\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=scope\r\n\r\n'
                    b'--scope\r\nContent-Type: text/plain\r\n\r\nPlain body.\r\n'
                    b'--scope\r\nContent-Type: application/octet-stream\r\n'
                    b'Content-Disposition: attachment; filename="extra.bin"\r\n\r\nDATA\r\n--scope--\r\n')
                plain+=b' '*(len(attached)-len(plain))
            email.find(qn(META,'SizeInBytes')).text=str(len(plain))
            if name!='opaque-original':add_source(pdf,root,source,plain,'represented.eml')
            if name.startswith('extra-'):add_source(pdf,root,source,source.EF.F.read_bytes(),'unrendered.eml')
            if name.startswith('ambiguous-'):add_source(pdf,root,source,attached,'candidate.eml')
            if name=='opaque-original':
                raw=(ROOT/'tests/fixtures/eapdf/validator-uninspectable-source/original.msg').read_bytes()
                add_source(pdf,root,source,raw,'unknown.msg','application/vnd.ms-outlook')
            if name.endswith('-reversed'):pdf.Root.AF=pikepdf.Array(list(reversed(list(pdf.Root.AF))))
            if name.endswith('wrong-page-mode') or name=='ambiguous-mixed-attachments-mode':pdf.Root.PageMode=pikepdf.Name.UseAttachments
            if name.endswith('wrong-nfsm') or name=='ambiguous-mixed-attachments-mode':pdf.Root.ViewerPreferences.NonFullScreenPageMode=pikepdf.Name.UseAttachments
            if name=='ambiguous-invalid-page-mode':pdf.Root.PageMode=pikepdf.Name.UseNone
            if name=='ambiguous-invalid-nfsm':pdf.Root.ViewerPreferences.NonFullScreenPageMode=pikepdf.Name.UseNone
            serialized=ET.tostring(root,encoding='utf-8')
            for prefix,uri in [('pdfmail','http://www.pdfa.org/eapdf/'),('pdfmailid','http://www.pdfa.org/eapdf/ns/id/'),('pdfmailmeta',META)]:
                if ('xmlns:'+prefix+'=').encode() not in serialized:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=OUT/(name+'.pdf');pdf.save(path,static_id=True,fix_metadata_version=False)
        failures=(['PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS'] if name.endswith('wrong-page-mode') else
                  ['PROFILE_NFSM_SINGLE_NO_ATTACHMENTS'] if name.endswith('wrong-nfsm') else [])
        if name=='ambiguous-invalid-page-mode':failures=['PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS','PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS']
        if name=='ambiguous-invalid-nfsm':failures=['PROFILE_NFSM_SINGLE_NO_ATTACHMENTS','PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS']
        if name=='opaque-original':failures=['MESSAGE_SUBJECT_XMP','MESSAGE_SIZE_XMP','MESSAGE_SENT_XMP','MESSAGE_FROM_FOAF','MESSAGE_TO_FOAF', 'MESSAGE_RAW_TO_XMP', 'MESSAGE_RAW_FROM_XMP']
        status='FAIL' if failures else 'INCOMPLETE' if name.startswith('ambiguous-') or name=='opaque-original' else 'PASS'
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_status=status,expected_failures=failures))
    (OUT/'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF single-message viewer-mode conditions describe the represented email; independent retained originals may contain unrendered emails.',
        boundary='Fixture-only marked-content operand correction uses the independent structure/page/MCID oracle. Original creator code and seed PDFs remain unchanged. Unknown source ownership does not establish attachment absence.',
        seed=str(SEED.relative_to(ROOT)),seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),operand_changes=changes,
        donor=str(DONOR.relative_to(ROOT)),donor_sha256=hashlib.sha256(DONOR.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')


if __name__=='__main__':main()
