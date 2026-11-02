"""MIME-equivalent subtype representations and preserved parameter data."""
import hashlib
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements

OUT = ROOT / 'tests/fixtures/eapdf/validator-subtype-media-values'
SEED = ROOT / 'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf'
CASES = {
    'matching-quoted-spaces': ('application/pdf; x-eapdf="a  b"', 'application/pdf; x-eapdf="a  b"'),
    'changed-quoted-spaces': ('application/pdf; x-eapdf="a b"', 'application/pdf; x-eapdf="a  b"'),
    'equivalent-type-case': ('application/pdf', 'APPLICATION/PDF'),
    'equivalent-quoted-token': ('application/pdf; x-eapdf=ok', 'application/pdf; x-eapdf="ok"'),
    'equivalent-parameter-order': ('application/pdf; x-eapdf=one; y-eapdf=two', 'application/pdf; y-eapdf=two; x-eapdf=one'),
    'changed-parameter-case': ('application/pdf; x-eapdf=Ab', 'application/pdf; x-eapdf=ab'),
    'matching-layout-spaces': ('application/pdf;  x-eapdf=ok', 'application/pdf;  x-eapdf=ok'),
    'matching-baseline': ('application/pdf', 'application/pdf'),
}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name, (dpm, value) in CASES.items():
        with pikepdf.open(SEED) as pdf:
            for element in structure_elements(pdf):
                if element.get('/S') == pikepdf.Name('/Mail_ContentSet') and any(
                        str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'FrontMatter' for p in element.A.P):
                    next(p for p in element.A.P if str(p.N) == 'Mail_Subtype').V = value
            _, _, nodes = v.get_dpart_nodes(pdf)
            for item in nodes:
                if not item['child_count'] and item['dpm'].get('Mail_ContentSetType') == pikepdf.Name.FrontMatter:
                    item['node'].DPM.Mail_Subtype = pikepdf.Name('/' + dpm)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        invalid = name in ('changed-quoted-spaces', 'changed-parameter-case')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='FAIL' if invalid else 'PASS',
            expected_failures=['MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'] if invalid else []))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.6 Table8 and9.7 Tables12/13; RFC2045 sections2/5/5.1 and RFC2046 media types',
        source_files={str(SEED.relative_to(ROOT)): hashlib.sha256(SEED.read_bytes()).hexdigest()},
        boundary='Connected DPart subtype correspondence uses reviewed MIME semantics. Quoted parameter data and generic parameter case remain meaningful. Optional FrontMatter controls establish internal declared correspondence, not original-source/vendor format fidelity. Other opaque names are not MIME-normalized.',
        cases=cases), indent=2) + '\n')
    print('Built eight subtype media-value controls.')


if __name__ == '__main__':
    main()
