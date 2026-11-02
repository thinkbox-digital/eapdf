"""Table 18 schema probes with no retained source email available to the validator."""
import hashlib
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    source = ROOT / 'examples/eml/eml_Agenda_and_timeline_slides_1si.pdf'
    output = ROOT / 'tests/fixtures/eapdf/validator-email-schema'
    output.mkdir(parents=True, exist_ok=True)
    recipes = [('valid', None, None, None, []),
        ('size-uri', 'SizeInBytes', 'uri', '123', ['MESSAGE_SIZE_XMP']),
        ('count-negative', 'NumberAttachments', 'text', '-1', ['MESSAGE_ATTACHMENT_COUNT_XMP']),
        ('subject-uri', 'Subject', 'uri', 'urn:subject', ['MESSAGE_SUBJECT_XMP']),
        ('raw-to-array', 'Raw-To', 'array', 'original', ['MESSAGE_RAW_TO_XMP']),
        ('sent-uri', 'Sent', 'uri', '2026-10-19T12:30:00Z', ['MESSAGE_SENT_FORMAT']),
        ('sent-no-timezone', 'Sent', 'text', '2026-10-19T12:30:00', []),
        ('sent-extended-year', 'Sent', 'text', '10000-01-01T00:00:00Z', []),
        ('sent-impossible-date', 'Sent', 'text', '2026-02-29T12:30:00Z', ['MESSAGE_SENT_FORMAT']),
        ('size-absent', 'SizeInBytes', 'remove', None, []),
        ('size-long-zero-padding', 'SizeInBytes', 'text', '0' * 5000 + '12', []),
        ('subject-empty', 'Subject', 'text', '', [])]
    cases = []
    for name, field, kind, value, failures in recipes:
        with pikepdf.Pdf.open(source) as pdf:
            assert not any(f['afrelationship'] == 'Source' for f in v.get_af_specs(pdf))
            if field:
                root, namespaces, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
                for prefix, uri in namespaces.items():
                    ET.register_namespace(prefix, uri)
                parent = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'email')).find('.//' + v.qn(v.NS_RDF, 'li'))
                node = parent.find(v.qn(v.NS_PDFMAILMETA, field))
                if node is None:
                    node = ET.SubElement(parent, v.qn(v.NS_PDFMAILMETA, field))
                node.clear()
                if kind == 'uri':
                    node.set(v.qn(v.NS_RDF, 'resource'), value)
                elif kind == 'array':
                    ET.SubElement(ET.SubElement(node, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = value
                elif kind == 'remove':
                    parent.remove(node)
                else:
                    node.text = value
                packet = ET.tostring(root, encoding='utf-8')
                for prefix, uri in (('pdfmail', v.NS_PDFMAIL), ('pdfmailid', v.NS_PDFMAILID), ('pdfmailmeta', v.NS_PDFMAILMETA)):
                    if ('xmlns:' + prefix + '=').encode() not in packet:
                        root.set('xmlns:' + prefix, uri)
                pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = output / (name + '.pdf')
            pdf.save(path, fix_metadata_version=False)
        cases.append(dict(file=path.name, expected_failures=failures, sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.8.3 Table 18', 'XML Schema 1.0 Part 2 sections 3.2.7 and 3.3.20'],
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        source_availability='Isolated source-omitting profile; schema validity does not depend on source comparison',
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'isolated-email probes to', output)


if __name__ == '__main__':
    main()
