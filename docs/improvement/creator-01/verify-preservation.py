"""Compare native header content before and after the creator repair."""
import hashlib
import json
from pathlib import Path
import pikepdf

HERE = Path(__file__).resolve().parent


def canonical(page):
    instructions = []
    changes = 0
    for instruction in pikepdf.parse_content_stream(page):
        operands = list(instruction.operands)
        if str(instruction.operator) == 'BDC' and str(operands[0]) in {
                '/Mail_Field', '/Mail_FieldName', '/Mail_FieldValue'}:
            operands[0] = pikepdf.Name.Span
            changes += 1
        instructions.append((operands, instruction.operator))
    return pikepdf.unparse_content_stream(instructions), changes


def assets(pdf):
    return sorted((str(fs.get('/UF', fs.get('/F', ''))),
                   hashlib.sha256(fs.EF.F.read_bytes()).hexdigest()) for fs in pdf.Root.AF)


with pikepdf.Pdf.open(HERE / 'before/header.pdf') as before, pikepdf.Pdf.open(HERE / 'after/header.pdf') as after:
    assert len(before.pages) == len(after.pages)
    rows = []
    for old_page, new_page in zip(before.pages, after.pages):
        old, _ = canonical(old_page)
        new, count = canonical(new_page)
        assert old == new
        rows.append(dict(operators=len(list(pikepdf.parse_content_stream(old_page))),
                         corrected_header_operands=count,
                         unchanged_painting_and_properties_sha256=hashlib.sha256(new).hexdigest()))
    assert sum(row['corrected_header_operands'] for row in rows) == 10
    assert assets(before) == assets(after)
    result = dict(scope='Exact probe pages and source assets; canonical content differs only in corrected BDC tag operands.',
                  pages=rows, retained_assets=assets(after))
    (HERE / 'preservation-comparison.json').write_text(json.dumps(result, indent=2) + '\n')
    print('Ten header operands corrected; painting commands, properties and source bytes retained.')
