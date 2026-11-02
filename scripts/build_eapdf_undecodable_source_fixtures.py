"""Unknown source octets stay unavailable while readable violations survive."""
import hashlib
import io
import json
from pathlib import Path
import re
from xml.etree import ElementTree as ET
import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-undecodable-source'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
FOAF = 'http://xmlns.com/foaf/0.1/'


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [(field.lower() + '-unknown', field) for field in ('From', 'To', 'Cc', 'Bcc')]
    recipes += [('subject-unknown', 'Subject'), ('reply-unknown', 'In-Reply-To'),
                ('identifier-unknown', 'Message-ID'), ('independent-mismatch', 'Subject'),
                ('bad-xmp-mailbox', 'From'), ('known-utf8', 'Subject')]
    cases = []
    for name, field in recipes:
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            raw = source.EF.F.read_bytes()
            value = 'café'.encode() if name == 'known-utf8' else (b'<bad\xff@example.org>' if field in ('Message-ID', 'In-Reply-To') else b'j\xffrg@example.org')
            header = field.encode() + b': ' + value
            pattern = br'(?im)^' + field.encode() + br':[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*'
            data, count = re.subn(pattern, lambda _: header, raw, count=1)
            if not count:
                data = header + b'\r\n' + raw
            update_source(pdf, root, email, data)
            if field in ('From', 'To', 'Cc', 'Bcc'):
                for prop in (field, 'Raw-' + field):
                    for child in list(email):
                        if child.tag == qn(META, prop):
                            email.remove(child)
                ET.SubElement(email, qn(META, 'Raw-' + field)).text = 'guessed@example.org'
                prop = ET.SubElement(email, qn(META, field))
                item = ET.SubElement(ET.SubElement(prop, qn(RDF, 'Seq')), qn(RDF, 'li'))
                ET.SubElement(item, qn(RDF, 'Description'), {qn(FOAF, 'mbox'): 'mailto:' + ('bad..address' if name == 'bad-xmp-mailbox' else 'guessed') + '@example.org'})
            elif field == 'In-Reply-To':
                prop = ET.SubElement(email, qn(META, field))
                ET.SubElement(ET.SubElement(prop, qn(RDF, 'Bag')), qn(RDF, 'li')).text = '<guessed@example.org>'
            elif field == 'Subject':
                email.find(qn(META, 'Subject')).text = 'café' if name == 'known-utf8' else 'Guessed subject'
            if name == 'independent-mismatch':
                prop = email.find(qn(META, 'From'))
                assert prop is not None
                email.remove(prop)
                prop = ET.SubElement(email, qn(META, 'From'))
                item = ET.SubElement(ET.SubElement(prop, qn(RDF, 'Seq')), qn(RDF, 'li'))
                ET.SubElement(item, qn(RDF, 'Description'), {qn(FOAF, 'mbox'): 'mailto:different@example.org'})
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = ['MESSAGE_FROM_FOAF'] if name in ('independent-mismatch', 'bad-xmp-mailbox') else []
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS' if name == 'known-utf8' else 'INCOMPLETE', expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF Table18 original header meaning; unavailable original decoding cannot prove a text mismatch. Supplied FOAF and independently readable source predicates remain mandatory.',
        boundary='Undecodable octets are retained internally and escaped for diagnostics; no charset guessing or general malformed encoded-word repair certification.',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(), cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
