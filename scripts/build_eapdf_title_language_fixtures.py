"""Independent title-language controls, with explicit recommendation expectations."""
import hashlib
import json
from pathlib import Path
import re
import pikepdf
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-title-language'
SEED = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    default = '<rdf:li xml:lang="x-default">Default title</rdf:li>'
    localized = '<rdf:li xml:lang="fr">Titre</rdf:li>'
    alt = lambda items: '<dc:title><rdf:Alt>' + items + '</rdf:Alt></dc:title>'
    recipes = [
        ('default-first', alt(default + localized), 'PASS'),
        ('default-later', alt(localized + default), 'PASS'),
        ('localized-only', alt(localized), 'WARN'),
        ('empty-default', alt('<rdf:li xml:lang="x-default"></rdf:li>' + localized), 'PASS'),
        ('title-absent', '', 'WARN'),
        ('title-plain', '<dc:title>Default title</dc:title>', 'WARN'),
        ('duplicate-default', alt(default + '<rdf:li xml:lang="x-default">Other title</rdf:li>'), 'WARN'),
        ('qualified-default', '<dc:title xmlns:ex="urn:example:qualifier" rdf:parseType="Resource"><rdf:value>'
            '<rdf:Alt><rdf:li xml:lang="x-default">Default title</rdf:li></rdf:Alt></rdf:value>'
            '<ex:note>Qualified title</ex:note></dc:title>', 'PASS')]
    cases = []
    for name, title, expected in recipes:
        with pikepdf.Pdf.open(SEED) as pdf:
            data = pdf.Root.Metadata.read_bytes().decode('utf-8')
            data, count = re.subn(r'<dc:title\b.*?</dc:title>', title, data, flags=re.S)
            assert count == 1
            pdf.Root.Metadata.write(data.encode('utf-8'))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='PASS', expected_failures=[], expected_title=expected))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.8 printed p84; Adobe XMP data types Text and Language Alternative',
        source='https://developer.adobe.com/xmp/docs/xmp-namespaces/xmp-data-types/',
        seed_sha256=hashlib.sha256(SEED.read_bytes()).hexdigest(),
        boundary='Only the XMP_TITLE recommendation is assessed. The later/duplicate defaults and plain title are generic serialization/type probes, not certified legal representations. Empty Text is allowed. Info/XMP crosswalk unchanged.',
        cases=cases), indent=2) + '\n')
    print('Built eight title-language recommendation controls.')

if __name__ == '__main__':
    main()
