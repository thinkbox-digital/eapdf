"""Table 19 supplied Mail_GUID retains its text type when Message-ID is present."""
from pathlib import Path
import hashlib
import json
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    output = ROOT / 'tests/fixtures/eapdf/validator-attachment-identity'
    output.mkdir(exist_ok=True)
    source = ROOT / 'tests/fixtures/eapdf/validator-attachment-streams/valid.pdf'
    cases = []
    for form in ('normal', 'absent', 'qualified', 'attribute', 'uri', 'array', 'structure', 'foreign-extension'):
        with pikepdf.open(source) as pdf:
            root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
            for prefix, uri in namespaces.items():
                ET.register_namespace(prefix, uri)
            record = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'attachments') + '/' + v.qn(v.NS_RDF, 'Bag'))[0]
            guid = record.find(v.qn(v.NS_PDFMAILMETA, 'Mail_GUID'))
            identity = guid.text
            message = v.flatten_email_records(root)[0]['messageid']
            ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, 'Message-ID')).text = message
            guid.text = None
            if form == 'normal':
                guid.text = identity
            elif form == 'absent':
                record.remove(guid)
            elif form == 'qualified':
                guid.set(v.qn(v.NS_RDF, 'parseType'), 'Resource')
                ET.SubElement(guid, v.qn(v.NS_RDF, 'value')).text = identity
                ET.SubElement(guid, v.qn(v.NS_XMP, 'Label')).text = 'Qualified identity'
            elif form == 'attribute':
                record.remove(guid)
                # Property attributes belong on an RDF node, not on a property
                # element that also has rdf:parseType="Resource".
                del record.attrib[v.qn(v.NS_RDF, 'parseType')]
                description = ET.Element(v.qn(v.NS_RDF, 'Description'),
                                         {v.qn(v.NS_PDFMAILMETA, 'Mail_GUID'): identity})
                for child in list(record):
                    record.remove(child)
                    description.append(child)
                record.append(description)
            elif form == 'uri':
                guid.set(v.qn(v.NS_RDF, 'resource'), identity)
            elif form == 'array':
                ET.SubElement(ET.SubElement(guid, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = identity
            elif form == 'structure':
                guid.set(v.qn(v.NS_RDF, 'parseType'), 'Resource')
                ET.SubElement(guid, v.qn(v.NS_XMP, 'Label')).text = identity
            elif form == 'foreign-extension':
                record.remove(guid)
                ET.SubElement(record, '{https://example.org/vendor/}Mail_GUID', {v.qn(v.NS_RDF, 'resource'): identity})
            packet = ET.tostring(root, encoding='utf-8')
            for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                if ('xmlns:' + prefix + '=').encode() not in packet:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (form + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        failures = ['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID'] if form in ('uri', 'array', 'structure') else []
        cases.append({'file': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
                      'expected_failures': failures, 'expected_status': 'FAIL' if failures else 'PASS',
                      'required_unavailable_codes': []})
    (output / 'expectations.json').write_text(json.dumps({'authority': 'EA-PDF 9.8.4 Table 19: Mail_GUID text string, conditional presence; supplied fields retain declared types.',
        'source': str(source.relative_to(ROOT)), 'source_sha256': hashlib.sha256(source.read_bytes()).hexdigest(),
        'cases': cases}, indent=2)+'\n')
    print('Wrote', len(cases), 'attachment identity probes')


if __name__ == '__main__':
    main()
