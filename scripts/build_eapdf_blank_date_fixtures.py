"""Original Date presence is distinct from normalized text truthiness."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-blank-source-date'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'


def main():
    OUT.mkdir(exist_ok=True)
    cases = []
    for name in ('blank-no-raw', 'blank-empty-raw', 'blank-qualified-raw', 'blank-attribute-raw',
                 'whitespace-no-raw', 'whitespace-empty-raw', 'absent-no-raw', 'invalid-no-raw'):
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            replacement = (b'' if name == 'absent-no-raw' else b'Date: not a date\r\n' if name == 'invalid-no-raw'
                           else b'Date:    \r\n' if name.startswith('whitespace') else b'Date: \r\n')
            data, count = re.subn(br'(?im)^Date:[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*\r?\n', replacement,
                                  source.EF.F.read_bytes(), count=1)
            assert count == 1
            update_source(pdf, root, email, data)
            if name == 'blank-attribute-raw':
                email.attrib.pop(qn(RDF, 'parseType'), None)
                description = ET.Element(qn(RDF, 'Description'), {qn(META, 'Raw-Sent'): ''})
                for child in list(email):
                    email.remove(child)
                    description.append(child)
                email.append(description)
            elif not name.endswith('no-raw'):
                prop = ET.SubElement(email, qn(META, 'Raw-Sent'))
                if name == 'blank-qualified-raw':
                    ET.SubElement(ET.SubElement(prop, qn(RDF, 'Description')), qn(RDF, 'value')).text = ''
                else:
                    prop.text = ''
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = ['MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'] if name in ('blank-no-raw', 'whitespace-no-raw', 'invalid-no-raw') else []
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF Table18: a supplied blank original Date value cannot be a valid xsd:dateTime, so Raw-Sent is required and may preserve the blank as present empty text.',
        boundary='A physically absent Date header is distinguished from an explicit blank/whitespace value. This is not a complete date parser or normalization-policy certification.',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(), cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
