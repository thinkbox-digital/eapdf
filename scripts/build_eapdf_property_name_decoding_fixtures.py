"""Malformed property names retain data errors without aborting validation."""
import hashlib
import json
from pathlib import Path

import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements, guid_property

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-property-name-decoding'
SEED = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name in ('name-object-as-property-name', 'undecodable-name-object', 'undecodable-text-string',
                 'utf16-encoded-known-name', 'empty-ascii-extension', 'baseline-ascii-name',
                 'array-as-property-name', 'dictionary-as-property-name'):
        with pikepdf.open(SEED) as pdf:
            message = next(e for e in structure_elements(pdf) if e.get('/S') == pikepdf.Name('/Mail_Message'))
            prop = guid_property(message)
            if name == 'empty-ascii-extension':
                message.A.P.append(pikepdf.Dictionary(N='', V='Unicode value résumé'))
            elif name != 'baseline-ascii-name':
                prop.N = (pikepdf.Name('/Mail_GUID') if name == 'name-object-as-property-name' else
                    pikepdf.Object.parse(b'/Vendor#FF') if name == 'undecodable-name-object' else
                    pikepdf.String(b'\xef\xbb\xbf\xff') if name == 'undecodable-text-string' else
                    pikepdf.String(b'\xfe\xff' + 'Mail_GUID'.encode('utf-16be')) if name == 'utf16-encoded-known-name' else
                    pikepdf.Array([pikepdf.Object.parse(b'/Vendor#FF')]) if name == 'array-as-property-name' else
                    pikepdf.Dictionary(Raw=pikepdf.Object.parse(b'/Vendor#FF')))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        legal = name in ('empty-ascii-extension', 'baseline-ascii-name')
        failures = [] if legal else ['USERPROPS_NAME_ASCII']
        if not legal and name != 'utf16-encoded-known-name':
            failures.append('MAIL_MESSAGE_GUID_ATTRIBUTE')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='PASS' if legal else 'FAIL', expected_failures=sorted(failures)))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.7 printed p76/Table12 N must be PDF ASCII strings; ISO32000-2:2020 14.7.6.4 Table362 N Text string and7.9.2 string classes',
        source_files={str(SEED.relative_to(ROOT)): hashlib.sha256(SEED.read_bytes()).hexdigest()},
        boundary='Do not decode/coerce non-Text objects into names or let malformed Text strings abort known raw-ASCII validation. Keep decoded known UTF16 name presence independent of its forbidden N encoding; legal arbitrary/empty ASCII names and Unicode V text remain permitted. This fixes known data versus operational error classification, not conformance strength or all malformed-value paths.',
        cases=cases), indent=2) + '\n')
    print('Built eight property-name decoding controls.')


if __name__ == '__main__':
    main()
