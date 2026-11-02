"""Source-fidelity recommendations need the accepted original-source binding."""
import hashlib
import json
from email import policy
from email.parser import BytesParser
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-recommendation-source-scope'


def main():
    OUT.mkdir(exist_ok=True)
    cases = []
    for name in ('unassigned-equal', 'unassigned-different', 'assigned-equal',
                 'assigned-different', 'unassigned-invalid-type', 'assigned-invalid-type'):
        unassigned = name.startswith('unassigned')
        seed = ROOT / 'tests/fixtures/eapdf/validator-nested-source-scope' / (
            'tentative-outer.pdf' if unassigned else 'outer-consistent.pdf')
        with pikepdf.open(seed) as pdf:
            # Mail_Message itself has no page operator; adding its optional
            # UserProperty does not alter content operands, MCIDs or structure.
            message = next(obj for obj in pdf.objects if isinstance(obj, pikepdf.Dictionary)
                           and obj.get('/S') == pikepdf.Name('/Mail_Message'))
            if name.endswith('invalid-type'):
                prop = pikepdf.Dictionary(N='Mail_SizeInBytes', V='not an integer')
            else:
                spec = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source)
                original = BytesParser(policy=policy.default).parsebytes(spec.EF.F.read_bytes())
                value = str(original['Subject']) if name.endswith('-equal') else 'Different attribute subject'
                prop = pikepdf.Dictionary(N='Mail_Subject', V=value)
            message.A.P.append(prop)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        invalid_type = name.endswith('invalid-type')
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(),
                          expected_status='FAIL' if invalid_type else 'INCOMPLETE' if unassigned else 'PASS',
                          expected_failures=['USERPROPS_TYPE'] if invalid_type else [],
                          source_assessment='unavailable' if unassigned else 'available',
                          property_comparison='unavailable' if unassigned else None if invalid_type else
                          'equal' if name.endswith('-equal') else 'different'))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.7: source-derived UserProperties SHOULD retain original values. '
                  'A comparison requires an established owning original; unavailable ownership is not equality or alteration.',
        boundary='Supplied type constraints remain independent. A source recommendation alone does not fail conformance. '
                 'This fixture does not enumerate all rendered headers or establish original-input completeness.',
        cases=cases), indent=2) + '\n')


if __name__ == '__main__':
    main()
