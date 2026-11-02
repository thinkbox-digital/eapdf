"""Generic additional PDF UserProperties retain V(any); EA fields keep their types."""
import hashlib
import json
from pathlib import Path

import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-generic-property-scope'
SEED = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name in ('vendor-name-value', 'vendor-array-value', 'vendor-dictionary-value',
                 'vendor-unreadable-name-value', 'mail-header-name-value', 'vendor-unicode-text',
                 'vendor-missing-value', 'vendor-null-value'):
        with pikepdf.open(SEED) as pdf:
            message = next(e for e in structure_elements(pdf) if e.get('/S') == pikepdf.Name('/Mail_Message'))
            key = 'Mail_X-Extra' if name == 'mail-header-name-value' else 'Vendor_Data'
            if name == 'vendor-null-value':
                prop = pikepdf.Object.parse(b'<< /N (Vendor_Data) /V null >>')
            elif name == 'vendor-missing-value':
                prop = pikepdf.Dictionary(N=key)
            else:
                value = (pikepdf.Array(['one', 2, True]) if name == 'vendor-array-value' else
                    pikepdf.Dictionary(Label='value') if name == 'vendor-dictionary-value' else
                    pikepdf.Object.parse(b'/Vendor#FF') if name == 'vendor-unreadable-name-value' else
                    'Unicode résumé' if name == 'vendor-unicode-text' else pikepdf.Name('/Example'))
                prop = pikepdf.Dictionary(N=key, V=value)
            message.A.P.append(prop)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        invalid = name in ('mail-header-name-value', 'vendor-missing-value', 'vendor-null-value')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='FAIL' if invalid else 'PASS', expected_failures=['USERPROPS_TYPE'] if invalid else []))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.7 Table12 named EA fields/Mail_* headers; ISO32000-2:2020 14.7.6.4 Table362 generic V(any) and required V entry',
        source_files={str(SEED.relative_to(ROOT)): hashlib.sha256(SEED.read_bytes()).hexdigest()},
        boundary='Prescribed EA field types and Mail_* scalar/header scope do not restrict unrelated additional property values. Keep N ASCII validation and generic required V entry. Vendor semantics are not certified. pikepdf canonicalizes null V to the same bytes as omitted V; direct parsed-null unit checks independently exercise null-as-absent semantics.',
        cases=cases), indent=2) + '\n')
    print('Built eight generic-property scope controls (seven distinct new byte hashes).')


if __name__ == '__main__':
    main()
