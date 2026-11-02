"""Independent DPart dictionary types and multi-message range/order controls."""
import hashlib
import json
from pathlib import Path
import sys

import pikepdf
from review_eapdf_operand_corpus import inspect

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as v

OUT = ROOT / 'tests/fixtures/eapdf/validator-dpart-object-ranges'
SINGLE = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'


def multi_source():
    return ROOT / '.eapdf-verification-v24/.eapdf-integration-_2w7plry/1m/1m.pdf'


def correct_proved_operands(pdf):
    expected = {(r['page'], r['mcid']): r['expected'] for r in inspect(pdf)}
    for number, page in enumerate(pdf.pages, 1):
        instructions = []
        for instruction in pikepdf.parse_content_stream(page):
            if str(instruction.operator) == 'BDC':
                tag, props = instruction.operands
                resolved = page.Resources.Properties[str(props)] if isinstance(props, pikepdf.Name) else props
                value = expected.get((number, resolved.get('/MCID')))
                if value:
                    instruction = pikepdf.ContentStreamInstruction([pikepdf.Name('/' + value), props], instruction.operator)
            instructions.append(instruction)
        page.Contents = pdf.make_stream(pikepdf.unparse_content_stream(instructions))
    assert not inspect(pdf)


def replace_with_stream(pdf, original):
    root, node, nodes = v.get_dpart_nodes(pdf)
    identity = v.objgen(original)
    replacement = pdf.make_stream(b'policy')
    for key, value in original.items():
        replacement[key] = value
    if identity == v.objgen(root):
        pdf.Root.DPartRoot = replacement
        node.Parent = replacement
        return
    item = next(item for item in nodes if item['objgen'] == identity)
    if item['parent'] is None:
        root.DPartRootNode = replacement
    else:
        parent = item['parent']
        parent.DParts = pikepdf.Array([pikepdf.Array([
            replacement if v.objgen(child) == identity else child for child in group]) for group in parent.DParts])
    for child in nodes:
        if child['parent'] is not None and v.objgen(child['parent']) == identity:
            child['node'].Parent = replacement
    for page in pdf.pages:
        if v.objgen(page.obj.get('/DPart')) == identity:
            page.obj.DPart = replacement


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    source = multi_source()
    cases = []
    for name in ('root-stream', 'root-node-stream-preserved', 'leaf-stream-preserved',
                 'valid', 'optional-types-omitted', 'multi-ordered', 'multi-reversed', 'multi-overlapping'):
        seed = source if name.startswith('multi-') else SINGLE
        with pikepdf.Pdf.open(seed) as pdf:
            if name.startswith('multi-'):
                correct_proved_operands(pdf)
            root, node, nodes = v.get_dpart_nodes(pdf)
            leaves = [item for item in nodes if item['child_count'] == 0]
            if name == 'root-stream': replace_with_stream(pdf, root)
            elif name == 'root-node-stream-preserved': replace_with_stream(pdf, node)
            elif name == 'leaf-stream-preserved': replace_with_stream(pdf, leaves[-1]['node'])
            elif name == 'optional-types-omitted':
                for value in [root] + [item['node'] for item in nodes]:
                    if '/Type' in value: del value.Type
            elif name == 'multi-reversed':
                node.DParts = pikepdf.Array([pikepdf.Array(list(reversed(list(group)))) for group in reversed(list(node.DParts))])
            elif name == 'multi-overlapping':
                leaves[0]['node'].End = leaves[1]['node'].get('/End', leaves[1]['node'].Start)
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        failures = (['DPART_OBJECT_STRUCTURE'] if name in ('root-stream', 'root-node-stream-preserved') else
                    ['DPART_OBJECT_STRUCTURE', 'PAGE_DPART_PRESENT'] if name == 'leaf-stream-preserved' else
                    ['PAGE_DPART_PRESENT'] if name in ('multi-reversed', 'multi-overlapping') else [])
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'PASS', expected_failures=failures))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='ISO32000-2:2020 sections14.12.2/3 and Tables408/409; EA-PDF9.6',
        source_files={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in (SINGLE, source)},
        boundary='Original creator bytes are unchanged. Multi-message fixture copies repair only independently proved operand mismatches before isolated range/order mutations. Optional Type entries remain optional.',
        cases=cases), indent=2) + '\n')
    print('Built eight DPart dictionary and range controls.')


if __name__ == '__main__':
    main()
