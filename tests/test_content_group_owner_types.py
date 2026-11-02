"""Conditional group type comparison uses connected DPM data and exact values."""
import unittest
from xml.etree import ElementTree as ET

import pikepdf
import test_message_guid_content_owners as fixtures
from test_preservation_safety import validator as v, email_metadata

CODE = 'MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT'
RECOMMEND = 'MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED'


class ContentGroupOwnerTypeTests(unittest.TestCase):
    def document(self, types=('BodyRendering', 'Other')):
        pdf, messages, parts, paragraphs = fixtures.MessageGuidContentOwnerTests().document()
        pdf.Root.StructTreeRoot.RoleMap = pikepdf.Dictionary(
            Mail_Message=pikepdf.Name.Document, Mail_ContentGroup=pikepdf.Name.Part,
            Mail_ContentSet=pikepdf.Name.Art)
        groups = []
        for message, part, paragraph, value in zip(messages, parts, paragraphs, types):
            leaf = part.DParts[0][0]
            leaf.DPM.Mail_ContentSetType = pikepdf.Name('/' + value)
            leaf.DPM.Mail_Subtype = pikepdf.Name('/text/plain')
            group = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name('/Mail_ContentGroup'), P=message,
                A=pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                    P=pikepdf.Array([pikepdf.Dictionary(N='Mail_ContentSetType', V=value)]))))
            content = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name('/Mail_ContentSet'), P=group, K=paragraph,
                A=pikepdf.Dictionary(O=pikepdf.Name.UserProperties, P=pikepdf.Array([
                    pikepdf.Dictionary(N='Mail_ContentSetType', V=value),
                    pikepdf.Dictionary(N='Mail_Subtype', V='text/plain')]))))
            paragraph.P = content
            group.K = content
            message.K = group
            groups.append(group)
        return pdf, groups, parts

    def check(self, pdf, xmp_type=None):
        xroot = email_metadata([{'Mail_GUID': 'one'}, {'Mail_GUID': 'two'}])
        if xmp_type is not None:
            description = xroot.find('.//' + v.qn(v.NS_RDF, 'Description'))
            ET.SubElement(description, v.qn(v.NS_PDFMAILMETA, 'ContentSetType')).text = xmp_type
        report = v.Report('group-type-focused.pdf')
        v.check_additional_rule_coverage(pdf, xroot, ET.tostring(xroot), [], [], 'm', 4, report)
        return {item['code']: item for item in report.items}, report

    def assert_unavailable(self, pdf, xmp_type=None):
        findings, report = self.check(pdf, xmp_type)
        self.assertEqual('WARN', findings[CODE]['level'])
        self.assertTrue(any(item['code'] == 'VALIDATION_INCOMPLETE' and item['level'] == 'WARN'
                            for item in report.items))

    def test_each_group_matches_its_connected_content_set_type(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.assertEqual('PASS', self.check(pdf)[0][CODE]['level'])

    def test_type_on_another_content_set_cannot_satisfy_the_group(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].A.P[0].V = 'Other'
            self.assertEqual('FAIL', self.check(pdf)[0][CODE]['level'])

    def test_spaces_in_the_supplied_value_are_not_removed(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].A.P[0].V = ' BodyRendering '
            self.assertEqual('FAIL', self.check(pdf)[0][CODE]['level'])

    def test_supplied_empty_string_is_compared_despite_optional_presence(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].A.P[0].V = ''
            findings, _ = self.check(pdf)
            self.assertEqual('FAIL', findings[CODE]['level'])
            self.assertEqual('PASS', findings[RECOMMEND]['level'])

    def test_absent_attribute_stays_a_recommendation(self):
        pdf, groups, parts = self.document()
        with pdf:
            for group in groups:
                del group.A
            findings, _ = self.check(pdf)
            self.assertEqual('N/A', findings[CODE]['level'])
            self.assertEqual('WARN', findings[RECOMMEND]['level'])

    def test_opaque_extension_name_preserves_internal_and_boundary_spaces(self):
        pdf, groups, parts = self.document((' Vendor  type ', 'Other'))
        with pdf:
            self.assertEqual('PASS', self.check(pdf)[0][CODE]['level'])

    def test_missing_content_connection_is_not_global_membership(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].K = pikepdf.Array()
            self.assert_unavailable(pdf)

    def test_absent_connected_dpm_type_is_not_compared_with_another_leaf(self):
        pdf, groups, parts = self.document()
        with pdf:
            del parts[0].DParts[0][0].DPM.Mail_ContentSetType
            self.assert_unavailable(pdf)

    def test_non_string_attribute_has_no_fabricated_comparison(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].A.P[0].V = pikepdf.Name('/BodyRendering')
            self.assert_unavailable(pdf)

    def test_unscoped_matching_xmp_value_does_not_establish_group_ownership(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.assert_unavailable(pdf, 'BodyRendering')

    def test_unscoped_other_xmp_value_is_not_a_proved_group_mismatch(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.assert_unavailable(pdf, 'Other')

    def test_empty_supplied_xmp_candidate_is_not_ignored_as_absence(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.assert_unavailable(pdf, '')

    def test_known_contradiction_survives_another_unavailable_group(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].A.P[0].V = 'Other'
            groups[1].K = pikepdf.Array()
            self.assertEqual('FAIL', self.check(pdf)[0][CODE]['level'])

    def test_structure_order_does_not_choose_a_type_owner(self):
        pdf, groups, parts = self.document()
        with pdf:
            document = pdf.Root.StructTreeRoot.K
            document.K = pikepdf.Array(list(reversed(list(document.K))))
            self.assertEqual('PASS', self.check(pdf)[0][CODE]['level'])

    def test_unrecognized_padded_property_name_is_not_a_required_attribute(self):
        pdf, groups, parts = self.document()
        with pdf:
            for group in groups:
                group.A.P[0].N = ' Mail_ContentSetType'
            findings, _ = self.check(pdf)
            self.assertEqual('N/A', findings[CODE]['level'])
            self.assertEqual('WARN', findings[RECOMMEND]['level'])

    def test_unicode_text_value_encoding_remains_permitted(self):
        pdf, groups, parts = self.document()
        with pdf:
            groups[0].A.P[0].V = pikepdf.String(b'\xfe\xff' + 'BodyRendering'.encode('utf-16be'))
            self.assertEqual('PASS', self.check(pdf)[0][CODE]['level'])

    def test_unicode_name_value_is_not_restricted_to_ascii(self):
        pdf, groups, parts = self.document(('Vendor 中文', 'Other'))
        with pdf:
            self.assertEqual('PASS', self.check(pdf)[0][CODE]['level'])


if __name__ == '__main__':
    unittest.main()
