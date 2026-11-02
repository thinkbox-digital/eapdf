"""Table 19 optional GUID, XMP owner lookup and optional Table 7 DPM Message-ID."""
from pathlib import Path
import hashlib
import json
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
import validate_eapdf as v
from build_eapdf_attachment_filename_fixtures import omit_source


def main():
    output=ROOT/'tests/fixtures/eapdf/validator-attachment-owners'
    source=output/'_source.pdf';assert source.exists()
    recipes=[('valid-guid',[]),('message-id-only',[]),('optional-dpm-id-omitted',[]),
             ('message-id-only-native-alias',[]),('wrong-owner',['ATTACHMENT_ANNOTATIONS_PRESENT','ATTACHMENT_XMP_MATCH','MESSAGE_ATTACHMENT_COUNT_XMP']),
             ('guid-primary-wrong-id',['ATTACHMENT_XMP_MATCH']),
             ('source-omitted-message-id',[]),('source-omitted-missing-count',['MESSAGE_ATTACHMENT_COUNT_XMP']),
             ('source-omitted-zero-count',['MESSAGE_ATTACHMENT_COUNT_XMP'])]
    cases=[]
    for name,failures in recipes:
        with pikepdf.open(source) as pdf:
            root,ns,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            for prefix,uri in ns.items():ET.register_namespace(prefix,uri)
            records=v.flatten_email_records(root)
            row=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))[0]
            guid=row.find(v.qn(v.NS_PDFMAILMETA,'Mail_GUID'))
            owner=next(record for record in records if record['Mail_GUID']==guid.text)
            other=next(record for record in records if record['Mail_GUID']!=guid.text)
            assert owner['messageid']=='<Owner@example.org>' and other['messageid']=='<owner@example.org>'
            if name!='valid-guid':
                ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,'Message-ID')).text=other['messageid'] if name in ('wrong-owner','guid-primary-wrong-id') else owner['messageid']
            if name not in ('valid-guid','guid-primary-wrong-id'):row.remove(guid)
            if name=='optional-dpm-id-omitted':
                for item in v.get_dpart_nodes(pdf)[2]:
                    if 'Mail_MessageID' in item['dpm']:del item['node'].DPM.Mail_MessageID
            if name.startswith('source-omitted-'):
                omit_source(pdf,root)
                root.find('.//'+v.qn(v.NS_PDFMAILID,'conformance')).text='mi'
                email_bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'email')+'/'+v.qn(v.NS_RDF,'Bag'))
                owning_email=next(item for item in email_bag if item.find(v.qn(v.NS_PDFMAILMETA,'Mail_GUID')).text==owner['Mail_GUID'])
                count=owning_email.find(v.qn(v.NS_PDFMAILMETA,'NumberAttachments'))
                if name=='source-omitted-missing-count':owning_email.remove(count)
                elif name=='source-omitted-zero-count':count.text='0'
            if name=='message-id-only-native-alias':
                checksum=row.find(v.qn(v.NS_PDFMAILMETA,'CheckSum'))
                if checksum is not None:row.remove(checksum)
                native=next(annotation for annotation in v.collect_file_attachment_annotations(pdf) if annotation['file']['afrelationship']=='Data')
                spec=pdf.get_object(native['fs_objgen']);spec.F=pikepdf.String('stored-safe.txt');spec.UF=pikepdf.String('stored-safe.txt')
            packet=ET.tostring(root,encoding='utf-8')
            for prefix,uri in (('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)):
                if ('xmlns:'+prefix+'=').encode() not in packet:root.set('xmlns:'+prefix,uri)
            pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'))
            path=output/(name+'.pdf');pdf.save(path,fix_metadata_version=False,deterministic_id=True)
        cases.append(dict(file=path.name,sha256=hashlib.sha256(path.read_bytes()).hexdigest(),expected_failures=failures,
            expected_status='FAIL' if failures else 'PASS',required_unavailable_codes=[]))
    (output/'expectations.json').write_text(json.dumps(dict(authority=[
        'EA-PDF Table 19: Mail_GUID identifies containing email and is optional when Message-ID is supplied.',
        'EA-PDF Tables 7/18: email GUID mapping remains available when optional DPM Mail_MessageID is omitted.',
        'EA-PDF 9.3: attachment annotation belongs to the owning email body, not a different email with a similar identifier.'],
        supporting_identifier_reference='https://www.rfc-editor.org/rfc/rfc5256.html',
        reference_boundary='The IMAP threading comparison statement supports preserving identifier case; no IMAP requirements or full RFC normalization are imposed on EA-PDF.',
        source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),cases=cases),indent=2)+'\n')
    print('Wrote',len(cases),'attachment owner probes')


if __name__=='__main__':main()
