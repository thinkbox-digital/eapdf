"""Native Catalog Version / Collection Subtype values and version precedence."""
import hashlib
import json
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-version-schema-names'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    seeds = {'version': ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf',
             'collection': ROOT / 'tests/fixtures/eapdf/validator-booleans/collection-false.pdf'}
    catalog = ['PDF_CATALOG_VERSION']
    subtype = ['COLLECTION_SCHEMA_FIELD']
    recipes = [('valid-version', 'version', []), ('catalog-text', 'version', catalog),
               ('catalog-slash-text', 'version', catalog), ('catalog-empty-text', 'version', catalog),
               ('valid-schema', 'collection', []), ('subtype-text', 'collection', subtype),
               ('subtype-slash-text', 'collection', subtype),
               ('collection-version-text', 'collection', catalog),
               ('lower-catalog-name', 'collection', []),
               ('earlier-catalog-name', 'version', []),
               ('header-1.6', 'version', ['PDF_HEADER_VERSION_READ'])]
    required = ('To', 'From', 'Sent', 'Subject', 'Message-ID', 'Cc', 'Bcc', 'Size', 'Attachments')
    cases = []
    for name, family, failures in recipes:
        with pikepdf.Pdf.open(seeds[family]) as pdf:
            if name in ('valid-version', 'header-1.6'):
                pdf.Root.Version = pikepdf.Name('/2.0')
            elif name in ('catalog-text', 'catalog-slash-text', 'catalog-empty-text'):
                value = {'catalog-text': '2.0', 'catalog-slash-text': '/2.0', 'catalog-empty-text': ''}[name]
                pdf.Root.Version = pikepdf.String(value)
            elif name == 'subtype-text':
                for field in required:
                    value = pdf.Root.Collection.Schema['/' + field]
                    value.Subtype = pikepdf.String(str(value.Subtype).lstrip('/'))
            elif name == 'subtype-slash-text':
                pdf.Root.Collection.Schema.To.Subtype = pikepdf.String('/S')
            elif name == 'collection-version-text':
                pdf.Root.Version = pikepdf.String('1.7')
            elif name == 'earlier-catalog-name':
                pdf.Root.Version = pikepdf.Name('/1.6')
            elif name == 'lower-catalog-name':
                pdf.Root.Version = pikepdf.Name('/1.7')
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        if name == 'header-1.6':
            data = path.read_bytes()
            assert data.startswith(b'%PDF-2.0')
            path.write_bytes(b'%PDF-1.6' + data[8:])
        with pikepdf.Pdf.open(path) as pdf:
            assert str(pdf.pdf_version) == ('1.6' if name == 'header-1.6' else '2.0')
            if name == 'lower-catalog-name':
                assert pdf.Root.Version == pikepdf.Name('/1.7')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'INCOMPLETE' if family == 'collection' else 'PASS',
                          expected_pipeline_status='FAIL' if failures or name in ('lower-catalog-name', 'earlier-catalog-name') else 'PASS', expected_failures=failures,
                          expected_pdfa_status='FAIL' if name in ('header-1.6', 'lower-catalog-name', 'earlier-catalog-name') else 'PASS'))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.1 printed p61 / 9.5 Table 6 printed p68; ISO 32000-2:2020 Table 29 printed p99 and Table 155 printed p451',
        seeds={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in seeds.values()},
        boundary='Only native Version/Subtype values change; PDF 2.0 header retained except the explicit 1.6-header control; Collection child PDF/A is skipped in custom-only runs',
        cases=cases), indent=2) + '\n')
    print('Built eleven independent Version/Subtype/header/precedence controls.')


if __name__ == '__main__':
    main()
