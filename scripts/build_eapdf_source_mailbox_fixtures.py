"""Source address structure precedes display-name decoding and cleanup."""
import hashlib
import io
import json
from pathlib import Path
import re
from urllib.parse import quote
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data'
SEED = ROOT / 'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
META = 'http://www.pdfa.org/eapdf/ns/meta/'
FOAF = 'http://xmlns.com/foaf/0.1/'
qn = lambda namespace, name: '{' + namespace + '}' + name


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [(field.lower() + '-exact', field, '"a  b"@example.org', '"a  b"@example.org')
               for field in ('To', 'From', 'Cc', 'Bcc')]
    recipes += [(field.lower() + '-collapsed', field, '"a  b"@example.org', '"a b"@example.org')
                for field in ('To', 'From')]
    recipes += [('display-comma', 'From', '=?utf-8?q?Last=2C_First?= <person@example.org>', 'person@example.org'),
                ('literal-word-local', 'From', '=?utf-8?q?foo?=@example.org', '=?utf-8?q?foo?=@example.org'),
                ('bad-dot-domain', 'From', 'person@example.org', '=?utf-8?q?foo?=@example..org'),
                ('bad-double-at', 'From', 'person@example.org', '=?utf-8?q?foo?=@@example.org')]
    cases = []
    for name, field, original_value, xmp_mailbox in recipes:
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            original = source.EF.F.read_bytes()
            pattern = br'(?im)^' + field.encode() + br':[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*'
            header = (field + ': ' + original_value).encode()
            data, count = re.subn(pattern, lambda _: header, original, count=1)
            if not count:
                data = header + b'\r\n' + original
            old_checksum = hashlib.md5(original).hexdigest()
            source.EF.F.write(data)
            source.EF.F.Params.Size = len(data)
            source.EF.F.Params.CheckSum = pikepdf.String(hashlib.md5(data).digest())
            email.find(qn(META, 'SizeInBytes')).text = str(len(data))
            for asset in root.findall('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li')):
                checksum = asset.find(qn(META, 'Checksum'))
                if checksum is not None and checksum.text == old_checksum:
                    checksum.text = hashlib.md5(data).hexdigest()
                    asset.find(qn(META, 'SizeInBytes')).text = str(len(data))
            for prop in (field, 'Raw-' + field):
                for child in list(email):
                    if child.tag == qn(META, prop):
                        email.remove(child)
            if name != 'display-comma':
                ET.SubElement(email, qn(META, 'Raw-' + field)).text = original_value
            local, domain = xmp_mailbox.rsplit('@', 1)
            uri = 'mailto:' + quote(local, safe='') + '@' + domain
            prop = ET.SubElement(email, qn(META, field))
            item = ET.SubElement(ET.SubElement(prop, qn(RDF, 'Seq')), qn(RDF, 'li'))
            ET.SubElement(item, qn(RDF, 'Description'), {qn(FOAF, 'mbox'): uri})
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, namespace in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                      ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, namespace)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = ['MESSAGE_' + field.upper() + '_FOAF'] if name.endswith('-collapsed') or name.startswith('bad-') else []
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures,
                          original_field=field, original_value=original_value, mailbox_uri=uri))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF Table18 FOAF mailbox fields', 'RFC5321 section4.1.2',
                   'RFC5322 sections3.2.4/3.4.1', 'RFC2047 section5'],
        boundary='Quoted source mailbox data and display-name decoding; full SMTPUTF8, IDNA, obsolete/folded source grammar and raw-header fidelity normalization remain unverified.',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(), cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
