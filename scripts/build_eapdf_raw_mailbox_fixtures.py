"""Raw address data differs independently of the correct FOAF mailbox."""
import hashlib
import json
from pathlib import Path
import re

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-raw-mailbox-data'


def main():
    OUT.mkdir(exist_ok=True)
    recipes = [(field.lower() + '-collapsed', field) for field in ('To', 'From', 'Cc', 'Bcc')]
    recipes += [(name, 'From') for name in ('from-exact', 'from-qualified', 'from-outside-space', 'independent-foaf')]
    cases = []
    for name, field in recipes:
        seed = ROOT / 'tests/fixtures/eapdf/validator-source-mailbox-data' / (field.lower() + '-exact.pdf')
        with pikepdf.open(seed) as pdf:
            text = pdf.Root.Metadata.read_bytes().decode()
            pattern = r'<pdfmailmeta:Raw-' + field + r'>(.*?)</pdfmailmeta:Raw-' + field + '>'
            found = re.search(pattern, text, re.S)
            assert found and found.group(1) == '"a  b"@example.org'
            value = found.group(1)
            if name.endswith('-collapsed'):
                value = value.replace('a  b', 'a b')
            elif name == 'from-qualified':
                value = '<rdf:Description><rdf:value>' + value + '</rdf:value></rdf:Description>'
            elif name == 'from-outside-space':
                value = '  ' + value + '   '
            text = text[:found.start(1)] + value + text[found.end(1):]
            if name == 'independent-foaf':
                section = re.search(r'<pdfmailmeta:From>.*?</pdfmailmeta:From>', text, re.S)
                assert section and 'a%20%20b' in section.group(0)
                text = text[:section.start()] + section.group(0).replace('a%20%20b', 'a%20b') + text[section.end():]
            pdf.Root.Metadata.write(text.encode())
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['MESSAGE_RAW_' + field.upper() + '_XMP'] if name.endswith('-collapsed') else
                    ['MESSAGE_FROM_FOAF'] if name == 'independent-foaf' else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures,
                          seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest()))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF Table18 supplied raw address values reflect the original header; quoted mailbox data from RFC5321 section4.1.2 and RFC5322 section3.2.4.',
        boundary='Known ASCII mailbox changes; prior outside-whitespace policy and optional absence are retained. No complete raw-header byte-fidelity or unsupported source normalization certification.',
        cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
