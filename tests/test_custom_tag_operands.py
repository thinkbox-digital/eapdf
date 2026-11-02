"""EA-PDF 9.7 p75: match the actual content owner, not a global tag set."""
import hashlib
import json
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v


class CustomTagOperandTests(unittest.TestCase):
    def document(self, tags=('Mail_FieldName', 'Mail_FieldValue')):
        pdf = pikepdf.Pdf.new()
        page = pdf.add_blank_page()
        root = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        parent = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name.Document, P=root, Pg=page.obj))
        owners = [pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name('/' + tag), P=parent, K=number)) for number, tag in enumerate(tags)]
        parent.K = pikepdf.Array(owners)
        root.K = parent
        root.ParentTree = pdf.make_indirect(pikepdf.Dictionary(Nums=pikepdf.Array([0, pikepdf.Array(owners)])))
        pdf.Root.StructTreeRoot = root
        page.StructParents = 0
        page.Contents = pdf.make_stream(b' '.join(
            f'/{tag} <</MCID {number}>> BDC 0 g 0 0 1 1 re f EMC'.encode()
            for number, tag in enumerate(tags)))
        return pdf, page, root, parent, owners

    def finding(self, pdf):
        report = v.Report('operand.pdf')
        v.check_additional_rule_coverage(pdf, None, b'', [], [], 'si', 4, report)
        rows = [item for item in report.items if item['code'] == 'CUSTOM_TAG_OPERAND_MATCH']
        self.assertEqual(1, len(rows))
        return rows[0], report

    def test_correct_content_owners_pass(self):
        pdf, *_ = self.document()
        with pdf:
            self.assertEqual('PASS', self.finding(pdf)[0]['level'])

    def test_swapped_existing_names_fail(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'/Mail_FieldValue <</MCID 0>> BDC EMC /Mail_FieldName <</MCID 1>> BDC EMC')
            self.assertEqual('FAIL', self.finding(pdf)[0]['level'])

    def test_standard_operand_cannot_hide_a_custom_owner(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'/Span <</MCID 0>> BDC EMC /Mail_FieldValue <</MCID 1>> BDC EMC')
            self.assertEqual('FAIL', self.finding(pdf)[0]['level'])

    def test_custom_operand_does_not_match_a_standard_owner(self):
        pdf, page, root, parent, owners = self.document()
        with pdf:
            owners[0].S = pikepdf.Name.Span
            self.assertEqual('FAIL', self.finding(pdf)[0]['level'])

    def test_named_properties_resolve_mcid(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Resources.Properties = pikepdf.Dictionary(First=pikepdf.Dictionary(MCID=0), Second=pikepdf.Dictionary(MCID=1))
            page.Contents = pdf.make_stream(b'/Mail_FieldName /First BDC EMC /Mail_FieldValue /Second BDC EMC')
            self.assertEqual('PASS', self.finding(pdf)[0]['level'])

    def test_mcr_page_content_stream_reference_passes(self):
        pdf, page, root, parent, owners = self.document()
        with pdf:
            owners[0].K = pikepdf.Dictionary(Type=pikepdf.Name.MCR, MCID=0, Pg=page.obj, Stm=page.Contents)
            self.assertEqual('PASS', self.finding(pdf)[0]['level'])

    def test_form_mcid_has_its_own_scope(self):
        pdf, page, root, parent, owners = self.document()
        with pdf:
            form = pdf.make_stream(b'/Mail_FieldValue <</MCID 0>> BDC 0 g 0 0 1 1 re f EMC')
            form.Type = pikepdf.Name.XObject
            form.Subtype = pikepdf.Name.Form
            form.BBox = pikepdf.Array([0, 0, 10, 10])
            form.Resources = pikepdf.Dictionary()
            page.Resources.XObject = pikepdf.Dictionary(Fm=form)
            page.Contents = pdf.make_stream(b'/Mail_FieldName <</MCID 0>> BDC EMC /Fm Do')
            owners[1].K = pikepdf.Dictionary(Type=pikepdf.Name.MCR, MCID=0, Pg=page.obj, Stm=form)
            self.assertEqual('PASS', self.finding(pdf)[0]['level'])
            form.write(b'/Mail_FieldName <</MCID 0>> BDC EMC')
            self.assertEqual('FAIL', self.finding(pdf)[0]['level'])

    def test_missing_content_owner_is_unavailable(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'/Mail_FieldName <</MCID 9>> BDC EMC')
            row, report = self.finding(pdf)
            self.assertEqual('WARN', row['level'])
            self.assertIn('VALIDATION_INCOMPLETE', {item['code'] for item in report.items if item['level'] == 'WARN'})

    def test_ambiguous_content_owner_cannot_pass(self):
        pdf, page, root, parent, owners = self.document()
        with pdf:
            owners[1].K = 0
            self.assertEqual('WARN', self.finding(pdf)[0]['level'])

    def test_multiple_structure_roots_are_inspected(self):
        pdf, page, root, parent, owners = self.document()
        with pdf:
            root.K = pikepdf.Array(owners)
            for owner in owners:
                owner.P = root
                owner.Pg = page.obj
            self.assertEqual('PASS', self.finding(pdf)[0]['level'])

    def test_standard_structure_has_no_custom_operand_requirement(self):
        pdf, *_ = self.document(tags=('P', 'Span'))
        with pdf:
            self.assertEqual('N/A', self.finding(pdf)[0]['level'])

    def test_definite_mismatch_wins_over_unavailable_owner(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'/Mail_FieldValue <</MCID 0>> BDC EMC /Mail_FieldName <</MCID 9>> BDC EMC')
            self.assertEqual('FAIL', self.finding(pdf)[0]['level'])

    def test_missing_custom_sequence_is_unavailable(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'')
            self.assertEqual('WARN', self.finding(pdf)[0]['level'])

    def test_custom_sequence_without_mcid_is_unavailable(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'/Mail_FieldName BMC EMC')
            self.assertEqual('WARN', self.finding(pdf)[0]['level'])

    def test_custom_sequence_without_structure_is_unavailable(self):
        pdf, page, *_ = self.document()
        with pdf:
            del pdf.Root.StructTreeRoot
            self.assertEqual('WARN', self.finding(pdf)[0]['level'])

    def test_mixed_verified_and_unavailable_owners_cannot_pass(self):
        pdf, page, *_ = self.document()
        with pdf:
            page.Contents = pdf.make_stream(b'/Mail_FieldName <</MCID 0>> BDC EMC /Mail_FieldValue <</MCID 9>> BDC EMC')
            self.assertEqual('WARN', self.finding(pdf)[0]['level'])

    def test_no_structure_and_no_custom_content_is_not_applicable(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            self.assertEqual('N/A', self.finding(pdf)[0]['level'])

    def test_frozen_independent_pdf_controls(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-operands'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                report = v.validate(str(path))
                self.assertEqual(case['expected_status'], report.summary()['status'])
                self.assertEqual(set(case['expected_failures']), {i['code'] for i in report.items if i['level'] == 'FAIL'})
                self.assertEqual({case['expected_operand_level']}, {i['level'] for i in report.items if i['code'] == 'CUSTOM_TAG_OPERAND_MATCH'})


if __name__ == '__main__':
    unittest.main()
