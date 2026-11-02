"""Authored EA-PDF Table 11 boundaries, including the user's Mail_Field correction."""
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import validator


class CustomTagContainmentTests(unittest.TestCase):
    def document(self, parent_type, child_types, direct=False):
        pdf = pikepdf.Pdf.new()
        pdf.add_blank_page()
        structure = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        document = pdf.make_indirect(pikepdf.Dictionary(
            Type=pikepdf.Name.StructElem, S=pikepdf.Name.Document, P=structure))
        structure.K = document
        pdf.Root.StructTreeRoot = structure
        structure.RoleMap = pikepdf.Dictionary(
            Mail_Message=pikepdf.Name.Document, Mail_ContentGroup=pikepdf.Name.Part,
            Mail_ContentSet=pikepdf.Name.Art, Mail_Field=pikepdf.Name.P,
            Mail_FieldName=pikepdf.Name.Span, Mail_FieldValue=pikepdf.Name.Span)

        def element(tag, parent):
            elem = pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name('/' + tag))
            if not direct:
                elem = pdf.make_indirect(elem)
                elem.P = parent
            return elem

        parent = element(parent_type, document)
        document.K = parent
        children = [element(tag, parent) for tag in child_types]
        parent.K = pikepdf.Array(children)
        return pdf, parent, children

    def findings(self, pdf, profile='s'):
        report = validator.Report('table11.pdf')
        validator.check_logical_structure(pdf, profile, report)
        return report

    def levels(self, report, code):
        return [i['level'] for i in report.items if i['code'] == code]

    def test_content_set_accepts_mail_field_and_standard_children(self):
        with self.document('Mail_ContentSet', ['Mail_Field', 'P'])[0] as pdf:
            report = self.findings(pdf)
            self.assertEqual(['PASS'], self.levels(report, 'MAIL_CONTENTSET_CHILDREN'))

    def test_content_set_may_have_no_custom_children(self):
        for children in ([], ['P'], ['Mail_UnrelatedExtension']):
            with self.subTest(children=children), self.document('Mail_ContentSet', children)[0] as pdf:
                self.assertEqual(['PASS'], self.levels(self.findings(pdf), 'MAIL_CONTENTSET_CHILDREN'))

    def test_content_set_rejects_other_defined_custom_immediate_children(self):
        for tag in ('Mail_Message', 'Mail_ContentGroup', 'Mail_ContentSet', 'Mail_FieldName', 'Mail_FieldValue'):
            with self.subTest(tag=tag), self.document('Mail_ContentSet', [tag])[0] as pdf:
                self.assertIn('FAIL', self.levels(self.findings(pdf), 'MAIL_CONTENTSET_CHILDREN'))

    def test_parents_reject_disallowed_children_even_with_required_children(self):
        for parent, required, forbidden, code in (
            ('Mail_Message', 'Mail_ContentSet', 'Mail_Field', 'MAIL_MESSAGE_CHILDREN'),
            ('Mail_ContentGroup', 'Mail_ContentSet', 'Mail_Message', 'MAIL_CONTENTGROUP_CHILDREN'),
            ('Mail_Field', 'Mail_FieldName', 'Mail_ContentSet', 'MAIL_FIELD_CHILDREN'),
        ):
            with self.subTest(parent=parent), self.document(parent, [required, forbidden])[0] as pdf:
                self.assertIn('FAIL', self.levels(self.findings(pdf), code))

    def test_required_children_must_be_immediate(self):
        for parent, required, code in (
            ('Mail_Message', 'Mail_ContentSet', 'MAIL_MESSAGE_CHILDREN'),
            ('Mail_ContentGroup', 'Mail_ContentSet', 'MAIL_CONTENTGROUP_CHILDREN'),
            ('Mail_Field', 'Mail_FieldName', 'MAIL_FIELD_CHILDREN'),
        ):
            pdf, _, children = self.document(parent, ['Div'])
            with self.subTest(parent=parent), pdf:
                children[0].K = pdf.make_indirect(pikepdf.Dictionary(
                    Type=pikepdf.Name.StructElem, S=pikepdf.Name('/' + required), P=children[0]))
                self.assertIn('FAIL', self.levels(self.findings(pdf), code))

    def test_field_value_requires_an_immediate_mail_field_parent(self):
        with self.document('Mail_ContentSet', ['Mail_FieldName', 'Mail_FieldValue'])[0] as pdf:
            self.assertIn('FAIL', self.levels(self.findings(pdf), 'MAIL_FIELDVALUE_WITH_NAME'))

    def test_field_name_with_optional_values_is_valid(self):
        for tags in (['Mail_FieldName'], ['Mail_FieldName', 'Mail_FieldValue', 'Mail_FieldValue']):
            with self.subTest(tags=tags), self.document('Mail_Field', tags)[0] as pdf:
                report = self.findings(pdf)
                self.assertEqual(['PASS'], self.levels(report, 'MAIL_FIELD_CHILDREN'))
                self.assertNotIn('FAIL', self.levels(report, 'MAIL_FIELDVALUE_WITH_NAME'))

    def test_direct_dictionary_children_do_not_merge_unrelated_parents(self):
        pdf, parent, _ = self.document('Mail_Field', ['Mail_FieldName'], direct=True)
        with pdf:
            other = pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Mail_Field)
            other.K = pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Mail_FieldValue)
            pdf.Root.StructTreeRoot.K.K = pikepdf.Array([parent, other])
            report = self.findings(pdf)
            self.assertIn('FAIL', self.levels(report, 'MAIL_FIELD_CHILDREN'))
            self.assertIn('FAIL', self.levels(report, 'MAIL_FIELDVALUE_WITH_NAME'))

    def test_standard_only_structure_does_not_require_custom_vocabulary(self):
        for profile in ('s', 'si', 'm', 'mi', 'c', 'ci'):
            with self.subTest(profile=profile), self.document('Art', ['P'])[0] as pdf:
                report = self.findings(pdf, profile)
                for code in ('MAIL_MESSAGE_COUNT_SINGLE', 'MAIL_MESSAGE_COUNT_MULTI', 'MAIL_CONTENTSET_COUNT'):
                    self.assertNotIn('FAIL', self.levels(report, code))
                if profile in ('c', 'ci'):
                    self.assertEqual(['PASS'], self.levels(report, 'MAIL_MESSAGE_COUNT_CONTAINER'))
                ctx = validator.build_validation_context(pdf, None, profile, 3, [], [])
                self.assertIsNotNone(validator.na_reason_for_code('MAIL_CONTENTSET_CHILDREN', ctx))

    def test_empty_custom_message_remains_invalid(self):
        with self.document('Mail_Message', [])[0] as pdf:
            self.assertIn('FAIL', self.levels(self.findings(pdf), 'MAIL_MESSAGE_CHILDREN'))

    def additional_findings(self, pdf, profile):
        report = validator.Report('standard-structure.pdf')
        analysis = [dict(page=1, text='Body', lines=['Body'], candidate_lines=[], urls=[], links=[])]
        with patch.object(validator, 'collect_page_text_analysis', return_value=analysis):
            validator.check_additional_rule_coverage(pdf, None, b'', [], [], profile, 3, report)
        return report

    def test_standard_content_sets_do_not_activate_custom_tag_coverage(self):
        pdf, _, _ = self.document('Art', ['P'])
        with pdf:
            node = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart))
            leaf = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=node,
                Start=pdf.pages[0].obj, DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.BodyRendering)))
            node.DParts = pikepdf.Array([leaf])
            pdf.Root.DPartRoot = pdf.make_indirect(pikepdf.Dictionary(DPartRootNode=node))
            report = self.additional_findings(pdf, 's')
            self.assertEqual(['N/A'], self.levels(report, 'CONTENT_SET_MAIL_CONTENTSET_TAGGED'))

    def test_explicit_document_elements_can_represent_multiple_emails(self):
        pdf, _, _ = self.document('Document', ['P'])
        with pdf:
            root = pdf.Root.StructTreeRoot.K
            root.K = pikepdf.Array([root.K, pdf.make_indirect(pikepdf.Dictionary(
                Type=pikepdf.Name.StructElem, S=pikepdf.Name.Document, P=root))])
            report = self.additional_findings(pdf, 'm')
            self.assertEqual(['PASS'], self.levels(report, 'MULTI_EMAIL_DOCUMENT_STRUCTURE'))


class AuthoritativeReportReferenceTests(unittest.TestCase):
    def test_corrected_text_uses_authoritative_attribution(self):
        for code in ('MESSAGE_SENT_XMP', 'MAIL_FIELD_CHILDREN', 'MAIL_CONTENTSET_CHILDREN'):
            with self.subTest(code=code):
                refs = validator.spec_references_for(code)
                self.assertEqual(1, len(refs))
                self.assertEqual('validator_rule_text.json', refs[0]['source'])
                self.assertEqual(validator.RULE_TEXT[code]['spec_excerpt'], refs[0]['excerpt'])
                self.assertEqual(validator.RULE_TEXT[code]['spec_section'], refs[0]['spec'])


if __name__ == '__main__':
    unittest.main()
