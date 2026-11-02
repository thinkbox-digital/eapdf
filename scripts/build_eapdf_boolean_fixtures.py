"""Independent Boolean-value controls with a corrected collection child.

Expected values come from EA-PDF 9.1 p62 and 9.5 p68, not validator results.
Historical PDFs, creator sources and JARs are never changed.
"""
import hashlib
import io
import json
from pathlib import Path

import pikepdf
from review_eapdf_operand_corpus import inspect

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'tests/fixtures/eapdf/validator-booleans'


def correct_child_operands(pdf):
    for attachment in pdf.attachments.values():
        stream = attachment.obj.EF.F
        with pikepdf.Pdf.open(io.BytesIO(stream.read_bytes())) as child:
            expected = {(r['page'], r['mcid']): r['expected'] for r in inspect(child)}
            assert expected
            for number, page in enumerate(child.pages, 1):
                instructions = []
                for instruction in pikepdf.parse_content_stream(page):
                    if str(instruction.operator) == 'BDC':
                        tag, props = instruction.operands
                        resolved = page.Resources.Properties[str(props)] if isinstance(props, pikepdf.Name) else props
                        owner = expected.get((number, resolved.get('/MCID')))
                        if owner:
                            instruction = pikepdf.ContentStreamInstruction(
                                [pikepdf.Name('/' + owner), props], instruction.operator)
                    instructions.append(instruction)
                page.Contents = child.make_stream(pikepdf.unparse_content_stream(instructions))
            assert not inspect(child)
            output = io.BytesIO()
            child.save(output, static_id=True, fix_metadata_version=False)
        data = output.getvalue()
        stream.write(data)
        stream.Size = stream.Params.Size = len(data)
        stream.Params.CheckSum = pikepdf.String(hashlib.md5(data).digest())


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    seeds = {'title': ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf',
             'collection': ROOT / 'tests/fixtures/eapdf/validator-file-requirements/valid-container.pdf'}
    recipes = [
        ('title-true', 'title', True, []),
        ('title-false', 'title', False, ['DISPLAY_DOCTITLE']),
        ('title-integer', 'title', 1, ['DISPLAY_DOCTITLE']),
        ('title-string', 'title', pikepdf.String('true'), ['DISPLAY_DOCTITLE']),
        ('title-absent', 'title', None, ['DISPLAY_DOCTITLE']),
        ('collection-false', 'collection', False, []),
        ('collection-absent', 'collection', None, []),
        ('collection-true', 'collection', True, ['COLLECTION_SCHEMA_E_FALSE']),
        ('collection-integer', 'collection', 0, ['COLLECTION_SCHEMA_E_FALSE']),
        ('collection-string', 'collection', pikepdf.String(''), ['COLLECTION_SCHEMA_E_FALSE']),
        ('collection-array', 'collection', pikepdf.Array(), ['COLLECTION_SCHEMA_E_FALSE']),
        ('collection-dictionary', 'collection', pikepdf.Dictionary(), ['COLLECTION_SCHEMA_E_FALSE']),
        ('collection-extra', 'collection', 0, ['COLLECTION_SCHEMA_E_FALSE'])]
    cases = []
    for name, family, value, failures in recipes:
        with pikepdf.Pdf.open(seeds[family]) as pdf:
            if family == 'collection':
                correct_child_operands(pdf)
                field = pdf.Root.Collection.Schema.To
                if name == 'collection-extra':
                    pdf.Root.Collection.Schema.Extra = pikepdf.Dictionary(Subtype=pikepdf.Name.S, N='Extra')
                    field = pdf.Root.Collection.Schema.Extra
                if value is None:
                    del field.E
                else:
                    field.E = value
            elif value is None:
                del pdf.Root.ViewerPreferences.DisplayDocTitle
            else:
                pdf.Root.ViewerPreferences.DisplayDocTitle = value
            path = OUT / (name + '.pdf')
            pdf.save(path, static_id=True, fix_metadata_version=False)
        cases.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
                          expected_status='FAIL' if failures else 'INCOMPLETE' if family == 'collection' else 'PASS',
                          expected_failures=failures, expected_pipeline_status='FAIL' if failures else 'PASS'))
    (OUT / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.1 printed p62 and 9.5 printed p68: exact Boolean true/false',
        seeds={str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in seeds.values()},
        boundary='Collection child operands independently corrected; native payload lengths/checksums updated; original email CI Size retained; custom-only recursion stays INCOMPLETE because child PDF/A is skipped; complete pipeline can PASS',
        cases=cases), indent=2) + '\n')
    print('Built thirteen Boolean controls with independently corrected collection child operands.')


if __name__ == '__main__':
    main()
