"""Supplied empty raw text, optional absence and genuinely empty originals."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-empty-raw-text'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
qn = lambda namespace, name: '{' + namespace + '}' + name


def update_source(pdf, root, email, data):
    source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
    old = hashlib.md5(source.EF.F.read_bytes()).hexdigest()
    source.EF.F.write(data)
    source.EF.F.Params.Size = len(data)
    source.EF.F.Params.CheckSum = pikepdf.String(hashlib.md5(data).digest())
    email.find(qn(META, 'SizeInBytes')).text = str(len(data))
    for asset in root.findall('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li')):
        checksum = asset.find(qn(META, 'Checksum'))
        if checksum is not None and checksum.text == old:
            checksum.text = hashlib.md5(data).hexdigest()
            asset.find(qn(META, 'SizeInBytes')).text = str(len(data))


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [(field.lower() + '-empty', field) for field in ('To', 'From', 'Cc', 'Bcc', 'Sent')]
    recipes += [(name, field) for name, field in [('from-qualified-empty', 'From'),
        ('sent-attribute-empty', 'Sent'), ('from-omitted', 'From'), ('from-original-empty', 'From'),
        ('sent-invalid-date-empty', 'Sent')]]
    cases = []
    for name, field in recipes:
        seed = (ROOT / 'tests/fixtures/eapdf/validator-coverage-branches/invalid-date-raw.pdf' if name == 'sent-invalid-date-empty' else
                ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data' / ((field.lower() if field != 'Sent' else 'from') + '-exact.pdf'))
        with pikepdf.open(seed) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            prop = email.find(qn(META, 'Raw-' + field))
            if prop is not None:
                email.remove(prop)
            if name == 'sent-attribute-empty':
                email.attrib.pop(qn(RDF, 'parseType'), None)
                description = ET.Element(qn(RDF, 'Description'), {qn(META, 'Raw-Sent'): ''})
                for child in list(email):
                    email.remove(child)
                    description.append(child)
                email.append(description)
            elif name != 'from-omitted':
                prop = ET.SubElement(email, qn(META, 'Raw-' + field))
                if name == 'from-qualified-empty':
                    ET.SubElement(ET.SubElement(prop, qn(RDF, 'Description')), qn(RDF, 'value')).text = ''
                else:
                    prop.text = ''
            if name == 'from-original-empty':
                source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
                data, count = re.subn(br'(?im)^From:[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*', b'From: ', source.EF.F.read_bytes(), count=1)
                assert count == 1
                update_source(pdf, root, email, data)
                email.remove(email.find(qn(META, 'From')))
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, namespace in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                      ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, namespace)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        code = 'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID' if field == 'Sent' else 'MESSAGE_RAW_' + field.upper() + '_XMP'
        failures = [] if name in ('from-omitted', 'from-original-empty') else [code]
        levels = ({'MESSAGE_RAW_SENT_OMIT_IF_VALID': 'WARN'} if field == 'Sent' and name != 'sent-invalid-date-empty' else
                  {'MESSAGE_RAW_FROM_XMP': 'N/A'} if name == 'from-omitted' else {})
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures,
                          expected_levels=levels, seed=str(seed.relative_to(ROOT)),
                          seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest()))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF Table18 raw text types, conditional Raw-Sent, and original-header value meaning. Optional raw address fields have no ordinary SHOULD presence requirement.',
        boundary='Empty type remains legal; matched source fidelity and physical presence are independent. Original source decoding and complete normalization remain bounded.',
        cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
