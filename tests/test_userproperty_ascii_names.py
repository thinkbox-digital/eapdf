"""Table12 property names retain ASCII bytes and exact characters."""
import unittest

import pikepdf
import test_message_guid_content_owners as fixtures
from test_preservation_safety import validator as v, failed_codes


class UserPropertyAsciiNameTests(unittest.TestCase):
    def property_report(self, name, value=True):
        report = v.Report('property-name.pdf')
        attrs = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
            P=pikepdf.Array([pikepdf.Dictionary(N=name, V=value)]))
        v.check_user_properties([dict(type='Mail_Message', attrs=attrs)], report)
        return report

    def test_literal_hexadecimal_and_escape_spellings_have_the_same_ascii_name(self):
        for name in (pikepdf.String('Mail_GUID'), pikepdf.Object.parse(br'(Mail\137GUID)'),
                     pikepdf.Object.parse(b'<4d61696c5f47554944>')):
            with self.subTest(raw=bytes(name)):
                self.assertNotIn('USERPROPS_NAME_ASCII', failed_codes(self.property_report(name, 'one')))

    def test_utf16_and_utf8_bom_names_are_not_ascii_strings(self):
        for name in (pikepdf.String(b'\xfe\xff' + 'Mail_GUID'.encode('utf-16be')),
                     pikepdf.String(b'\xef\xbb\xbfMail_GUID')):
            with self.subTest(raw=bytes(name)):
                self.assertEqual('Mail_GUID', str(name))
                self.assertIn('USERPROPS_NAME_ASCII', failed_codes(self.property_report(name, 'one')))

    def test_leading_or_trailing_spaces_do_not_supply_required_guid_name(self):
        for name in (' Mail_GUID', 'Mail_GUID '):
            pdf, messages, dparts, paragraphs = fixtures.MessageGuidContentOwnerTests().document()
            with self.subTest(name=name), pdf:
                messages[0].A.P[0].N = name
                report = fixtures.MessageGuidContentOwnerTests().check(pdf)[1]
                self.assertIn('MAIL_MESSAGE_GUID_ATTRIBUTE', failed_codes(report))

    def test_ascii_control_padding_is_not_removed_from_known_names(self):
        pdf, messages, dparts, paragraphs = fixtures.MessageGuidContentOwnerTests().document()
        with pdf:
            messages[0].A.P[0].N = '\tMail_GUID\n'
            report = fixtures.MessageGuidContentOwnerTests().check(pdf)[1]
            self.assertIn('MAIL_MESSAGE_GUID_ATTRIBUTE', failed_codes(report))
            self.assertNotIn('USERPROPS_NAME_ASCII', failed_codes(report))

    def test_unrelated_ascii_extension_names_may_contain_spaces(self):
        self.assertFalse(failed_codes(self.property_report(pikepdf.String(' Vendor flag '))))

    def test_non_string_pdf_objects_cannot_supply_ascii_string_names(self):
        for value in (pikepdf.Name('/Mail_GUID'), 17, True):
            with self.subTest(value=value):
                self.assertIn('USERPROPS_NAME_ASCII', failed_codes(self.property_report(value)))

    def test_non_ascii_characters_in_a_name_remain_rejected(self):
        self.assertIn('USERPROPS_NAME_ASCII', failed_codes(self.property_report(pikepdf.String('Mail_中文'))))

    def test_ascii_name_does_not_impose_ascii_encoding_on_guid_value(self):
        pdf, messages, dparts, paragraphs = fixtures.MessageGuidContentOwnerTests().document()
        with pdf:
            messages[0].A.P[0].V = pikepdf.String(b'\xfe\xff' + 'one'.encode('utf-16be'))
            self.assertEqual('PASS', fixtures.MessageGuidContentOwnerTests().check(pdf)[0]['level'])
            self.assertFalse(failed_codes(self.property_report(pikepdf.String('Mail_GUID'),
                pikepdf.String(b'\xfe\xff' + 'one'.encode('utf-16be')))))


if __name__ == '__main__':
    unittest.main()
