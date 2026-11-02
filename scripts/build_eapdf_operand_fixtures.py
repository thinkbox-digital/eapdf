"""Correct a retained seed by independently inspected page/MCID ownership,
then change only content operands. Never changes creator or historical PDFs.
"""
import hashlib
import json
from pathlib import Path

import pikepdf
from review_eapdf_operand_corpus import inspect

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-operands'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    seed = ROOT / 'tests/fixtures/eapdf/validator-table11/valid.pdf'
    cases = []
    for name in ('valid', 'swapped', 'standard-operand', 'named-properties', 'standard-only'):
        path = OUT / (name + '.pdf')
        with pikepdf.Pdf.open(seed) as pdf:
            expected = {(r['page'], r['mcid']): r['expected'] for r in inspect(pdf)}
            for number, page in enumerate(pdf.pages, 1):
                instructions = []
                for instruction in pikepdf.parse_content_stream(page):
                    if str(instruction.operator) == 'BDC':
                        tag, props = instruction.operands
                        resolved = page.Resources.Properties[str(props)] if isinstance(props, pikepdf.Name) else props
                        mcid = resolved.get('/MCID')
                        value = expected.get((number, mcid))
                        if value:
                            if name == 'swapped':
                                value = {'Mail_FieldName': 'Mail_FieldValue', 'Mail_FieldValue': 'Mail_FieldName'}[value]
                            elif name == 'standard-operand' and number == 1 and mcid == 0:
                                value = 'Span'
                            elif name == 'standard-only':
                                value = 'Span'
                            if name == 'named-properties':
                                key = '/OperandFixture' + str(mcid)
                                page.Resources.Properties[key] = resolved
                                props = pikepdf.Name(key)
                            instruction = pikepdf.ContentStreamInstruction([pikepdf.Name('/' + value), props], instruction.operator)
                    instructions.append(instruction)
                page.Contents = pdf.make_stream(pikepdf.unparse_content_stream(instructions))
            if name == 'standard-only':
                pending = [pdf.Root.StructTreeRoot.K]
                while pending:
                    element = pending.pop()
                    if not isinstance(element, pikepdf.Dictionary):
                        continue
                    tag = str(element.get('/S', ''))
                    if tag.startswith('/Mail_'):
                        element.S = pdf.Root.StructTreeRoot.RoleMap[tag]
                    children = element.get('/K', [])
                    pending.extend(list(children) if isinstance(children, pikepdf.Array) else [children])
            pdf.save(path, static_id=True)
        failures = ['CUSTOM_TAG_OPERAND_MATCH'] if name in ('swapped', 'standard-operand') else []
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'INCOMPLETE' if name == 'standard-only' else 'PASS', expected_failures=failures,
                          expected_operand_level='FAIL' if failures else 'N/A' if name == 'standard-only' else 'PASS'))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.7 printed p75; independent page/MCID inspection',
        seed=str(seed.relative_to(ROOT)), seed_sha256=hashlib.sha256(seed.read_bytes()).hexdigest(), cases=cases), indent=2) + '\n')
    print('Built five independent operand controls.')


if __name__ == '__main__':
    main()
