"""Freeze Table 7/8 type and extension probes; source is a retained valid PDF."""
import hashlib
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v


def main():
    source = ROOT / 'tests/fixtures/eapdf/upstream-issues/valid.pdf'
    output = ROOT / 'tests/fixtures/eapdf/validator-dpm-schema'
    output.mkdir(parents=True, exist_ok=True)
    recipes = [
        ('valid', None, None, []),
        ('description-number', 'Mail_Desc', 17, ['DPM_DESC_REQUIRED']),
        ('description-name', 'Mail_Desc', pikepdf.Name('/Body'), ['DPM_DESC_REQUIRED']),
        ('description-utf16', 'Mail_Desc', pikepdf.String(b'\xfe\xff' + 'résumé'.encode('utf-16be')), []),
        ('extension-key', 'Acme_Job', 17, []),
        ('invalid-utf8-name', 'Mail_ContentSetType', pikepdf.Object.parse(b'/Vendor#FF'),
         ['DPM_NAME_TYPES']),  # Actual Name is present; its decoded UTF8 is invalid.
    ]
    cases = []
    for name, key, value, failures in recipes:
        with pikepdf.Pdf.open(source) as pdf:
            _, _, nodes = v.get_dpart_nodes(pdf)
            leaf = next(n for n in nodes if n['start'] is not None)
            if key:
                leaf['node'].DPM['/' + key] = value
            pdf.save(output / (name + '.pdf'), fix_metadata_version=False)
        cases.append(dict(file=name + '.pdf', expected_failures=failures,
                          sha256=hashlib.sha256((output / (name + '.pdf')).read_bytes()).hexdigest()))
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority=['EA-PDF 9.6 Tables 7/8; text string and name types',
                   'EA-PDF 9: features not constrained may occur; unrelated extension keys allowed',
                   'ISO-approved erratum 86: decoded DPM name values use valid UTF-8'],
        source=str(source.relative_to(ROOT)), source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=cases), indent=2) + '\n')
    print('Wrote', len(cases), 'probes to', output)


if __name__ == '__main__':
    main()
