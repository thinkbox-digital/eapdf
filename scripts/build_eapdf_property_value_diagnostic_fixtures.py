"""Wrong property V types remain data failures even with unreadable display data."""
import hashlib
import json
from pathlib import Path

import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements, guid_property

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-property-value-diagnostics'
SEED = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    names = ('guid-name-value', 'guid-array-value', 'subject-name-value', 'date-name-value',
             'size-name-value', 'generic-name-value', 'unicode-string-value', 'baseline-guid-value',
             'guid-dictionary-value', 'subject-dictionary-value', 'generic-dictionary-value')
    for name in names:
        with pikepdf.open(SEED) as pdf:
            message = next(e for e in structure_elements(pdf) if e.get('/S') == pikepdf.Name('/Mail_Message'))
            if name.startswith('guid-'):
                guid_property(message).V = (pikepdf.Array([pikepdf.Object.parse(b'/Vendor#FF')])
                    if name == 'guid-array-value' else pikepdf.Object.parse(b'<< /Vendor#FF 1 >>')
                    if name == 'guid-dictionary-value' else pikepdf.Object.parse(b'/Vendor#FF'))
            elif name != 'baseline-guid-value':
                key = {'subject-name-value': 'Mail_Subject', 'date-name-value': 'Mail_Sent',
                    'size-name-value': 'Mail_SizeInBytes', 'generic-name-value': 'Mail_X-Extra',
                    'unicode-string-value': 'Mail_Subject', 'subject-dictionary-value': 'Mail_Subject',
                    'generic-dictionary-value': 'Mail_X-Extra'}[name]
                value = ('Unicode résumé' if name == 'unicode-string-value' else
                    pikepdf.Object.parse(b'<< /Vendor#FF 1 >>') if name.endswith('dictionary-value') else
                    pikepdf.Object.parse(b'/Vendor#FF'))
                message.A.P.append(pikepdf.Dictionary(N=key, V=value))
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        legal = name in ('unicode-string-value', 'baseline-guid-value')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='PASS' if legal else 'FAIL', expected_failures=[] if legal else ['USERPROPS_TYPE']))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.7 Table12 prescribed V types; ISO32000-2:2020 14.7.6.4 Table362 generic V any type does not replace narrower EA field types',
        source_files={str(SEED.relative_to(ROOT)): hashlib.sha256(SEED.read_bytes()).hexdigest()},
        boundary='Known wrong types are data failures independent of display decoding. Wrong/unreadable message identity data cannot choose a source owner. Preserve existing generic Mail_* scalar constraints, Unicode text, raw name checks, presence and matching uncertainty. Unreadable String V values and other scalar owner policies remain separate.',
        cases=cases), indent=2) + '\n')
    print('Built eleven property-value diagnostic controls.')


if __name__ == '__main__':
    main()
