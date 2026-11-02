"""Every applicable property in the active P array retains its constraints."""
import hashlib
import json
from pathlib import Path

import pikepdf
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import validate_eapdf as v
from build_eapdf_message_owner_fixtures import structure_elements, guid_property

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-active-userproperties'
MULTI = ROOT / 'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf'
SINGLE = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    legal = ('duplicate-guid-uniform', 'duplicate-group-uniform',
             'duplicate-subtype-uniform', 'later-array-replacement', 'owner-subtype-consistent')
    for name in ('duplicate-guid-uniform', 'duplicate-guid-wrong-first', 'duplicate-guid-wrong-last',
                 'duplicate-guid-all-wrong', 'unknown-guid-first', 'empty-guid-first',
                 'wrong-group-first', 'wrong-subtype-first', 'duplicate-group-uniform',
                 'duplicate-subtype-uniform', 'later-array-replacement', 'attribute-owner-text',
                 'wrong-owner-after-valid', 'owner-subtype-consistent', 'owner-subtype-foreign',
                 'owner-subtype-missing'):
        seed = SINGLE if name == 'attribute-owner-text' else MULTI
        with pikepdf.open(seed) as pdf:
            elements = structure_elements(pdf)
            messages = [e for e in elements if e.get('/S') == pikepdf.Name('/Mail_Message')]
            prop = guid_property(messages[0])
            actual = prop.V
            foreign = guid_property(messages[1]).V if len(messages) > 1 else pikepdf.String('not-in-metadata')
            if name.startswith('duplicate-guid-'):
                duplicate = pikepdf.Dictionary(N='Mail_GUID', V=actual if name == 'duplicate-guid-uniform' else foreign)
                if name == 'duplicate-guid-all-wrong': prop.V = foreign
                if name == 'duplicate-guid-wrong-last': messages[0].A.P.append(duplicate)
                else: messages[0].A.P.insert(0, duplicate)
            elif name in ('unknown-guid-first', 'empty-guid-first'):
                messages[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_GUID',
                    V='not-in-metadata' if name == 'unknown-guid-first' else ''))
            elif name in ('wrong-group-first', 'duplicate-group-uniform'):
                group = next(e for e in elements if e.get('/S') == pikepdf.Name('/Mail_ContentGroup'))
                value = next(p.V for p in group.A.P if str(p.N) == 'Mail_ContentSetType')
                group.A.P.insert(0, pikepdf.Dictionary(N='Mail_ContentSetType',
                    V='FrontMatter' if name == 'wrong-group-first' else value))
            elif name in ('wrong-subtype-first', 'duplicate-subtype-uniform'):
                content = next(e for e in elements if e.get('/S') == pikepdf.Name('/Mail_ContentSet')
                    and any(str(p.N) == 'Mail_Subtype' for p in e.A.P))
                value = next(p.V for p in content.A.P if str(p.N) == 'Mail_Subtype')
                content.A.P.insert(0, pikepdf.Dictionary(N='Mail_Subtype',
                    V='application/x-other' if name in ('wrong-subtype-first', 'owner-subtype-foreign') else value))
            elif name.startswith('owner-subtype-'):
                front = next(e for e in elements if e.get('/S') == pikepdf.Name('/Mail_ContentSet')
                    and any(str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'FrontMatter' for p in e.A.P))
                front.A.P.append(pikepdf.Dictionary(N='Mail_Subtype', V='application/pdf'))
                if name != 'owner-subtype-missing':
                    _, _, nodes = v.get_dpart_nodes(pdf)
                    for item in nodes:
                        if not item['child_count'] and item['dpm'].get('Mail_ContentSetType') == pikepdf.Name.FrontMatter:
                            item['node'].DPM.Mail_Subtype = pikepdf.Name('/application/pdf')
                if name == 'owner-subtype-foreign':
                    body = next(e for e in elements if e.get('/S') == pikepdf.Name('/Mail_ContentSet')
                        and any(str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'BodyRendering' for p in e.A.P))
                    next(p for p in body.A.P if str(p.N) == 'Mail_Subtype').V = 'application/pdf'
            elif name == 'later-array-replacement':
                earlier = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                    P=pikepdf.Array([pikepdf.Dictionary(N='Mail_GUID', V='not-in-metadata')]))
                messages[0].A = pikepdf.Array([earlier, messages[0].A])
            elif name == 'attribute-owner-text':
                messages[0].A.O = pikepdf.String('UserProperties')
            elif name == 'wrong-owner-after-valid':
                invalid = pikepdf.Dictionary(O=pikepdf.String('UserProperties'),
                    P=pikepdf.Array([pikepdf.Dictionary(N='Mail_GUID', V=foreign)]))
                messages[0].A = pikepdf.Array([messages[0].A, invalid])
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failure = ('MAIL_MESSAGE_GUID_ATTRIBUTE' if name == 'attribute-owner-text' else
                   'MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT' if name == 'wrong-group-first' else
                   'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT' if name in ('wrong-subtype-first', 'owner-subtype-foreign') else
                   'MAIL_MESSAGE_GUID_MATCH_DPM_XMP')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='PASS' if name in legal else 'INCOMPLETE' if name in ('wrong-owner-after-valid', 'owner-subtype-missing') else 'FAIL',
                          expected_failures=[] if name in legal or name in ('wrong-owner-after-valid', 'owner-subtype-missing') else [failure]))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='ISO32000-2:2020 14.7.6.4 Tables361/362 individual properties; EA-PDF9.7 Tables12/13 named constraints',
        source_files={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (MULTI, SINGLE)},
        boundary='No within-P-array last-value priority or duplicate-name prohibition. Every applicable property retains its constraints; whole-P replacement across attribute objects is preserved. A text owner does not identify the UserProperties name owner. Subtype comparisons use connected DPart owners; formatting and other scalar consumers remain separate. Optional FrontMatter subtype controls establish internal declared correspondence, not original-source/vendor format fidelity.',
        cases=cases), indent=2) + '\n')
    print('Built sixteen active-property occurrence/owner controls.')


if __name__ == '__main__':
    main()
