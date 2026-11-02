"""Build PDF/A-valid probes for the adopted EA-PDF Table 11 interpretation."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator


def main():
    output = ROOT / 'tests/fixtures/eapdf/validator-table11'
    output.mkdir(parents=True, exist_ok=True)
    reports = Path(tempfile.mkdtemp(prefix='eapdf-table11-'))
    source = ROOT / 'examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'
    shutil.copy2(source, output / 'valid.pdf')
    cases = [dict(file='valid.pdf', expected_failures=[])]
    for filename, parent_type, tags, expected in (
        ('content-set-field-name.pdf', 'Mail_ContentSet', ['Mail_FieldName'], ['MAIL_CONTENTSET_CHILDREN']),
        ('message-field-name.pdf', 'Mail_Message', ['Mail_FieldName'], ['MAIL_MESSAGE_CHILDREN']),
        ('field-content-set.pdf', 'Mail_Field', ['Mail_ContentSet'], ['MAIL_FIELD_CHILDREN']),
        ('misparented-field-value.pdf', 'Mail_ContentSet', ['Mail_FieldName', 'Mail_FieldValue'],
         ['MAIL_CONTENTSET_CHILDREN', 'MAIL_FIELDVALUE_WITH_NAME']),
    ):
        with pikepdf.Pdf.open(source) as pdf:
            elements = []
            validator.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
            parent = next(e['elem'] for e in elements if e['type'] == parent_type)
            children = validator.struct_children(parent)
            for tag in tags:
                child = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                    S=pikepdf.Name('/' + tag), P=parent, NS=parent.NS))
                # Retain actual marked text and ParentTree owners: only custom
                # containment changes, without injecting broken content references.
                paragraph = next(e['elem'] for e in elements if e['type'] == 'P'
                                 and isinstance(e['elem'].get('/K'), int))
                old_parent = paragraph.P
                old_parent.K = pikepdf.Array([k for k in validator.struct_children(old_parent)
                                             if validator.objgen(k) != validator.objgen(paragraph)])
                child.K = paragraph
                paragraph.P = child
                children.append(child)
                elements = [e for e in elements if validator.objgen(e['elem']) != validator.objgen(paragraph)]
            parent.K = pikepdf.Array(children)
            pdf.save(output / filename)
        cases.append(dict(file=filename, expected_failures=expected))
    for case in cases:
        case['sha256'] = hashlib.sha256((output / case['file']).read_bytes()).hexdigest()
        case['expected_eapdf_status'] = 'FAIL' if case['expected_failures'] else 'PASS'
        case['expected_upstream_failures'] = ['ISO32005_UPSTREAM'] if case['expected_failures'] else []
        case['expected_iso_clauses'] = {
            'content-set-field-name.pdf': ['Table 5. Art-TD'],
            'message-field-name.pdf': ['Table 5. Document-TD'],
            'field-content-set.pdf': ['Table 5. TR-Art'],
            'misparented-field-value.pdf': ['Table 5. Art-TD'],
        }.get(case['file'], [])
    (output / 'expectations.json').write_text(json.dumps(dict(
        authority='EA-PDF 9.7 Tables 11/13; Mail_ContentField→Mail_Field approved by user 2026-10-19',
        expectation_scope='expected_failures lists custom findings; expected_upstream_failures adds inherited ISO findings for complete validation.',
        source='examples/eml/eml_Agenda_and_timeline_slides_1s.pdf', source_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        cases=cases), indent=2) + '\n')
    for case in cases:
        result = validator.validate_one(output / case['file'], write_html=False)
        (reports / (case['file'] + '.json')).write_text(json.dumps(result, default=str, indent=2) + '\n')
        failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
        assert result['pdfa']['status'] == 'PASS', (case['file'], result['pdfa'])
        assert result['summary']['eapdf_status'] == case['expected_eapdf_status'], case['file']
        assert failures == sorted(case['expected_failures'] + case['expected_upstream_failures']), (case['file'], failures)
        print(case['file'], result['pdfa']['status'], failures, flush=True)
    print('Reports:', reports)


if __name__ == '__main__':
    main()
