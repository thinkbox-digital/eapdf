"""Direct UTF-8 source headers and exact XMP counterparts in message/global."""
import hashlib
import io
import json
from pathlib import Path
import re
from urllib.parse import quote
from xml.etree import ElementTree as ET

import pikepdf
from build_eapdf_empty_raw_fixtures import update_source, qn, META, RDF

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-utf8-source-headers'
SEED = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
FOAF = 'http://xmlns.com/foaf/0.1/'


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [(field.lower() + '-utf8', field, 'Jörg <jörg@example.org>', 'jörg@example.org')
               for field in ('From', 'To', 'Cc', 'Bcc')]
    recipes += [('subject-utf8', 'Subject', 'Résumé café', None),
                ('quoted-utf8', 'From', '"jö  rg"@example.org', '"jö  rg"@example.org'),
                ('domain-utf8', 'From', 'user@bücher.example', 'user@bücher.example'),
                ('different-local', 'From', 'Jörg <jörg@example.org>', 'Jörg@example.org'),
                ('global-no-eml-name', 'From', 'Jörg <jörg@example.org>', 'jörg@example.org'),
                ('global-no-eml-wrong', 'From', 'Jörg <jörg@example.org>', 'Jörg@example.org')]
    cases = []
    for name, field, original_value, mailbox in recipes:
        with pikepdf.open(SEED) as pdf:
            packet = pdf.Root.Metadata.read_bytes()
            for _, (prefix, uri) in ET.iterparse(io.BytesIO(packet), events=['start-ns']):
                ET.register_namespace(prefix, uri)
            root = ET.fromstring(packet)
            email = root.find('.//' + qn(META, 'email') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li'))
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            raw = source.EF.F.read_bytes()
            pattern = br'(?im)^' + field.encode() + br':[^\r\n]*(?:\r?\n[ \t][^\r\n]*)*'
            header = (field + ': ' + original_value).encode('utf-8')
            data, count = re.subn(pattern, lambda _: header, raw, count=1)
            if not count:
                data = header + b'\r\n' + raw
            update_source(pdf, root, email, data)
            source.EF.F.Subtype = pikepdf.Name('/message/global')
            checksum = hashlib.md5(data).hexdigest()
            for asset in root.findall('.//' + qn(META, 'assets') + '/' + qn(RDF, 'Bag') + '/' + qn(RDF, 'li')):
                if asset.find(qn(META, 'Checksum')).text == checksum:
                    asset.find(qn(META, 'ContentType')).text = 'message/global'
                    if name.startswith('global-no-eml'):
                        asset.find(qn(META, 'Filename')).text = 'original.data'
            if name.startswith('global-no-eml'):
                source.F = source.UF = 'original.data'
                def rename_key(node):
                    names = node.get('/Names', [])
                    for index in range(0, len(names), 2):
                        if names[index + 1].objgen == source.objgen:
                            names[index] = pikepdf.String('original.data')
                    for child in node.get('/Kids', []):
                        rename_key(child)
                rename_key(pdf.Root.Names.EmbeddedFiles)
            for prop in (field, 'Raw-' + field):
                for child in list(email):
                    if child.tag == qn(META, prop):
                        email.remove(child)
            if field == 'Subject':
                ET.SubElement(email, qn(META, 'Subject')).text = original_value
            else:
                ET.SubElement(email, qn(META, 'Raw-' + field)).text = original_value
                local, domain = mailbox.rsplit('@', 1)
                uri = 'mailto:' + quote(local, safe='') + '@' + quote(domain, safe='.')
                prop = ET.SubElement(email, qn(META, field))
                item = ET.SubElement(ET.SubElement(prop, qn(RDF, 'Seq')), qn(RDF, 'li'))
                ET.SubElement(item, qn(RDF, 'Description'), {qn(FOAF, 'mbox'): uri})
            serialized = ET.tostring(root, encoding='utf-8')
            for prefix, uri in [('pdfmail', 'http://www.pdfa.org/eapdf/'),
                                ('pdfmailid', 'http://www.pdfa.org/eapdf/ns/id/'), ('pdfmailmeta', META)]:
                if ('xmlns:' + prefix + '=').encode() not in serialized:
                    root.set('xmlns:' + prefix, uri)
            pdf.Root.Metadata.write(ET.tostring(root, encoding='utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = ['MESSAGE_FROM_FOAF'] if name in ('different-local', 'global-no-eml-wrong') else []
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF Table18 original header and FOAF field values', 'RFC6532 sections3.1/3.2/3.7', 'RFC6531 section3.3'],
        boundary='Lossless restoration of valid UTF-8 bytes, not complete internationalization, IDNA, legacy charset detection or malformed-source handling.',
        seed=str(SEED.relative_to(ROOT)), seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(), cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
