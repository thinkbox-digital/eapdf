"""Document-wide content ownership is independent of its optional self-type attribute."""
import hashlib
import json
from pathlib import Path
import shutil
import sys

import pikepdf
from build_eapdf_message_owner_fixtures import structure_elements

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v
OUT = ROOT / 'tests/fixtures/eapdf/validator-contentset-parent'
MULTI = ROOT / 'tests/fixtures/eapdf/validator-dpart-object-ranges/multi-ordered.pdf'
SUBTYPE = ROOT / 'tests/fixtures/eapdf/validator-active-userproperties/owner-subtype-consistent.pdf'
COLLECTION = ROOT / 'tests/fixtures/eapdf/validator-booleans/collection-false.pdf'
SINGLE = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def nest_front(pdf):
    """Nest both logical content and its independent DPart email ownership."""
    document = pdf.Root.StructTreeRoot.K
    children = list(document.K)
    front = next(e for e in children if e.get('/S') == pikepdf.Name('/Mail_ContentSet'))
    message = next(e for e in children if e.get('/S') == pikepdf.Name('/Mail_Message'))
    document.K = pikepdf.Array([e for e in children if e.objgen != front.objgen])
    existing = message.K
    message.K = pikepdf.Array((list(existing) if isinstance(existing, pikepdf.Array) else [existing]) + [front])
    front.P = message
    _, _, nodes = v.get_dpart_nodes(pdf)
    top = nodes[0]['node']
    leaf = next(n['node'] for n in nodes if n['dpm'].get('Mail_ContentSetType') == pikepdf.Name.FrontMatter)
    branch = next(n['node'] for n in nodes if n['parent_objgen'] == top.objgen and n['child_count'])
    root_children = [n['node'] for n in nodes if n['parent_objgen'] == top.objgen and n['node'].objgen != leaf.objgen]
    branch_children = [n['node'] for n in nodes if n['parent_objgen'] == branch.objgen]
    top.DParts = pikepdf.Array([pikepdf.Array(root_children)])
    branch.DParts = pikepdf.Array([pikepdf.Array([leaf] + branch_children)])
    leaf.Parent = branch


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cases = []
    sources = {'nested-multi': MULTI, 'nested-subtype': SUBTYPE,
        'orphan-front-noncollection': MULTI, 'orphan-empty-self': MULTI,
        'orphan-unreadable-self': MULTI, 'orphan-front-collection': COLLECTION,
        'orphan-other-collection': COLLECTION, 'nested-single': SINGLE}
    for name, source in sources.items():
        path = OUT / (name + '.pdf')
        if name in ('nested-single', 'orphan-front-noncollection', 'orphan-front-collection'):
            shutil.copyfile(source, path)
        else:
            with pikepdf.open(source) as pdf:
                if name.startswith('nested-'):
                    nest_front(pdf)
                else:
                    target = next(e for e in structure_elements(pdf) if e.get('/S') == pikepdf.Name('/Mail_ContentSet')
                        and any(str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'FrontMatter' for p in e.A.P))
                    prop = next(p for p in target.A.P if str(p.N) == 'Mail_ContentSetType')
                    prop.V = ('' if name == 'orphan-empty-self' else 'Other' if name == 'orphan-other-collection'
                        else pikepdf.String(b'\xef\xbb\xbf\xff'))
                pdf.save(path, static_id=True, fix_metadata_version=False)
        collection = name.endswith('-collection') and name != 'orphan-front-noncollection'
        report = v.validate(str(path))
        parent = 'N/A' if collection else 'PASS'
        assert {item['level'] for item in report.items if item['code'] == 'MAIL_CONTENTSET_NESTED_UNDER_MESSAGE'} == {parent}
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
            expected_status=report.summary()['status'],
            expected_failures=sorted({item['code'] for item in report.items if item['level'] == 'FAIL'}),
            expected_parent_level=parent))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA9.7 Table13 ancestry; 2026-10-27 local document-wide ownership interpretation, report findings 28-29',
        source_files={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources.values()},
        boundary='No scalar FrontMatter exception. Verified document-wide DPart/content ownership permits a document parent. Optional self attributes cannot select or defeat that exception. Email-owned sets still require message ancestry.',
        cases=cases), indent=2) + '\n')
    print('Built eight Content Set parent controls.')


if __name__ == '__main__':
    main()
