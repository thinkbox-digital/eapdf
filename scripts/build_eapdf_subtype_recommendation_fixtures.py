"""Mail_ContentSet occurrence defines the subtype SHOULD recommendation."""
import hashlib
import json
from pathlib import Path

import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-subtype-recommendation'
SEED = ROOT / 'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf'
CODE = 'MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    for name in ('all-subtypes-present', 'body-subtype-omitted', 'body-subtype-and-self-type-omitted',
                 'frontmatter-subtype-omitted', 'all-subtypes-omitted', 'all-body-properties-omitted',
                 'empty-subtype-present', 'unresolved-content-attributes'):
        with pikepdf.open(SEED) as pdf:
            sets = [e for e in structure_elements(pdf) if e.get('/S') == pikepdf.Name('/Mail_ContentSet')]
            bodies = [e for e in sets if any(str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'BodyRendering' for p in e.A.P)]
            front = next(e for e in sets if any(str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'FrontMatter' for p in e.A.P))
            if name in ('body-subtype-omitted', 'body-subtype-and-self-type-omitted'):
                names = {'Mail_Subtype', 'Mail_ContentSetType'} if name.endswith('and-self-type-omitted') else {'Mail_Subtype'}
                bodies[0].A.P = pikepdf.Array([p for p in bodies[0].A.P if str(p.N) not in names])
            elif name == 'frontmatter-subtype-omitted':
                front.A.P = pikepdf.Array([p for p in front.A.P if str(p.N) != 'Mail_Subtype'])
            elif name in ('all-subtypes-omitted', 'all-body-properties-omitted'):
                for element in sets:
                    names = {'Mail_Subtype', 'Mail_ContentSetType'} if name == 'all-body-properties-omitted' and element in bodies else {'Mail_Subtype'}
                    element.A.P = pikepdf.Array([p for p in element.A.P if str(p.N) not in names])
            elif name == 'empty-subtype-present':
                next(p for p in bodies[0].A.P if str(p.N) == 'Mail_Subtype').V = ''
            elif name == 'unresolved-content-attributes':
                bodies[0].A.O = pikepdf.String('UserProperties')
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status='FAIL' if name == 'empty-subtype-present' else 'INCOMPLETE' if name == 'unresolved-content-attributes' else 'PASS',
            expected_failures=['MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'] if name == 'empty-subtype-present' else [],
            expected_recommendation='PASS' if name in ('all-subtypes-present', 'empty-subtype-present') else 'WARN'))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF9.7 Table13 printed p79; T13-SET-SUBTYPE inventory condition Mail_ContentSet occurs',
        source_files={str(SEED.relative_to(ROOT)): hashlib.sha256(SEED.read_bytes()).hexdigest()},
        boundary='This is a SHOULD recommendation for every custom Content Set; missing fields do not create conformance failures or mandatory DPM subtype presence. Actual supplied-name presence, raw name encoding, value type/matching and unresolved attributes retain separate assessments. FrontMatter fixture metadata does not certify source/vendor semantics.',
        cases=cases), indent=2) + '\n')
    print('Built eight subtype recommendation controls.')


if __name__ == '__main__':
    main()
