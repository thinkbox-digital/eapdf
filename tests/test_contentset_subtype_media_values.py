"""Connected Mail_Subtype values use MIME data rather than whitespace cleanup."""
import unittest

import pikepdf
import test_content_group_owner_types as groups
import test_message_guid_content_owners as owners

CODE = 'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'


class ContentSetSubtypeMediaValueTests(unittest.TestCase):
    def check_pair(self, dpm, value):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            parts[0].DParts[0][0].DPM.Mail_Subtype = pikepdf.Name('/' + dpm)
            next(p for p in tags[0].K.A.P if str(p.N) == 'Mail_Subtype').V = value
            return groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level']

    def test_identical_quoted_spaces_remain_data(self):
        self.assertEqual('PASS', self.check_pair('text/plain; name="A  B"', 'text/plain; name="A  B"'))

    def test_different_quoted_spaces_do_not_match(self):
        self.assertEqual('FAIL', self.check_pair('text/plain; name="A B"', 'text/plain; name="A  B"'))

    def test_type_and_parameter_names_compare_without_case(self):
        self.assertEqual('PASS', self.check_pair('text/plain; name=Ab', 'TEXT/PLAIN; NAME=Ab'))

    def test_generic_parameter_values_retain_case(self):
        self.assertEqual('FAIL', self.check_pair('text/plain; name=Ab', 'text/plain; name=ab'))

    def test_charset_values_compare_without_case(self):
        self.assertEqual('PASS', self.check_pair('text/plain; charset=UTF-8', 'text/plain; charset=utf-8'))

    def test_parameter_order_is_not_significant(self):
        self.assertEqual('PASS', self.check_pair('text/plain; charset=utf-8; name=Ab', 'text/plain; name=Ab; charset=utf-8'))

    def test_quotes_delimit_parameter_values(self):
        self.assertEqual('PASS', self.check_pair('text/plain; name=Ab', 'text/plain; name="Ab"'))

    def test_layout_spaces_do_not_modify_quoted_value_data(self):
        self.assertEqual('PASS', self.check_pair('text/plain;  name="A  B"', 'text/plain; name="A  B"'))

    def test_malformed_parameter_text_cannot_be_repaired_into_equality(self):
        self.assertEqual('FAIL', self.check_pair('text/plain; name=x', 'text/plain; name=x; =ignored'))

    def test_malformed_media_type_has_no_default_text_plain_match(self):
        self.assertEqual('FAIL', self.check_pair('text/plain', 'broken source header'))

    def test_repeated_parameter_recovery_cannot_hide_a_conflicting_value(self):
        self.assertEqual('FAIL', self.check_pair('text/plain; name=x', 'text/plain; name=x; name=y'))

    def shared(self, subtypes, value):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        paragraphs = [tag.K.K for tag in tags]
        owners.MessageGuidContentOwnerTests().shared_form(pdf, paragraphs)
        for part, subtype in zip(parts, subtypes):
            part.DParts[0][0].DPM.Mail_Subtype = pikepdf.Name('/' + subtype)
        next(p for p in tags[0].K.A.P if str(p.N) == 'Mail_Subtype').V = value
        next(p for p in tags[1].K.A.P if str(p.N) == 'Mail_Subtype').V = subtypes[1]
        return pdf

    def test_shared_form_equivalent_candidates_can_match_without_selecting_owner(self):
        with self.shared(('text/plain', 'TEXT/PLAIN'), 'text/plain') as pdf:
            self.assertEqual('PASS', groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level'])

    def test_shared_form_distinct_compatible_candidates_remain_unverified(self):
        with self.shared(('text/plain', 'text/html'), 'text/plain') as pdf:
            findings, report = groups.ContentGroupOwnerTypeTests().check(pdf)
            self.assertEqual('WARN', findings[CODE]['level'])
            self.assertTrue(any(i['code'] == 'VALIDATION_INCOMPLETE' and i['level'] == 'WARN' for i in report.items))

    def test_shared_form_distinct_candidates_cannot_hide_universal_contradiction(self):
        with self.shared(('text/plain', 'text/html'), 'application/pdf') as pdf:
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level'])


if __name__ == '__main__':
    unittest.main()
