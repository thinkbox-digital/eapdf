"""Invalid UserProperties names are data failures, not validator exceptions."""
import tempfile
import unittest
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v

ASCII = 'USERPROPS_NAME_ASCII'
GUID = 'MAIL_MESSAGE_GUID_ATTRIBUTE'


class UserPropertyNameDecodingTests(unittest.TestCase):
    def document(self):
        return pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf')

    def element(self, pdf, tag='Mail_Message'):
        elements = []
        v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
        return next(e['elem'] for e in elements if e['type'] == tag)

    def guid(self, pdf):
        return next(p for p in self.element(pdf).A.P if str(p.N) == 'Mail_GUID')

    def check(self, pdf):
        with tempfile.TemporaryDirectory(prefix='.eapdf-name-decode-', dir=ROOT) as directory:
            path = Path(directory) / 'focused.pdf'
            pdf.save(path, static_id=True, fix_metadata_version=False)
            report = v.validate(str(path))
        failures = {i['code'] for i in report.items if i['level'] == 'FAIL'}
        self.assertNotIn('VALIDATION_ERROR', failures)
        return failures, report

    def assert_invalid_guid_name(self, value):
        with self.document() as pdf:
            self.guid(pdf).N = value
            self.assertEqual({ASCII, GUID}, self.check(pdf)[0])

    def test_undecodable_pdf_name_is_not_coerced_into_text(self):
        self.assert_invalid_guid_name(pikepdf.Object.parse(b'/Vendor#FF'))

    def test_undecodable_text_string_keeps_raw_ascii_failure(self):
        self.assert_invalid_guid_name(pikepdf.String(b'\xef\xbb\xbf\xff'))

    def test_nested_array_name_cannot_abort_validation(self):
        self.assert_invalid_guid_name(pikepdf.Array([pikepdf.Object.parse(b'/Vendor#FF')]))

    def test_nested_dictionary_name_cannot_abort_validation(self):
        self.assert_invalid_guid_name(pikepdf.Dictionary(Raw=pikepdf.Object.parse(b'/Vendor#FF')))

    def test_ascii_pdf_name_object_is_not_a_text_name(self):
        self.assert_invalid_guid_name(pikepdf.Name('/Mail_GUID'))

    def test_numeric_property_name_is_not_coerced_into_text(self):
        self.assert_invalid_guid_name(42)

    def test_known_utf16_text_name_keeps_presence_with_separate_encoding_failure(self):
        with self.document() as pdf:
            self.guid(pdf).N = pikepdf.String(b'\xfe\xff' + 'Mail_GUID'.encode('utf-16be'))
            self.assertEqual({ASCII}, self.check(pdf)[0])

    def test_ordinary_ascii_name_still_matches(self):
        with self.document() as pdf:
            self.assertEqual(set(), self.check(pdf)[0])

    def test_empty_ascii_extension_and_unicode_value_remain_legal(self):
        with self.document() as pdf:
            self.element(pdf).A.P.append(pikepdf.Dictionary(N='', V='Unicode résumé'))
            failures, report = self.check(pdf)
            self.assertEqual(set(), failures)
            self.assertEqual('PASS', report.summary()['status'])

    def test_bad_extra_name_does_not_erase_proven_guid_presence(self):
        with self.document() as pdf:
            self.element(pdf).A.P.append(pikepdf.Dictionary(N=pikepdf.Object.parse(b'/Vendor#FF'), V='value'))
            self.assertEqual({ASCII}, self.check(pdf)[0])

    def test_bad_content_set_name_does_not_abort_later_active_name_lookups(self):
        with self.document() as pdf:
            content = self.element(pdf, 'Mail_ContentSet')
            next(p for p in content.A.P if str(p.N) == 'Mail_Subtype').N = pikepdf.String(b'\xef\xbb\xbf\xff')
            self.assertEqual({ASCII}, self.check(pdf)[0])

    def test_replaced_earlier_array_does_not_supply_active_names(self):
        with self.document() as pdf:
            message = self.element(pdf)
            earlier = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                P=pikepdf.Array([pikepdf.Dictionary(N=pikepdf.String(b'\xef\xbb\xbf\xff'), V='earlier')]))
            message.A = pikepdf.Array([earlier, message.A])
            self.assertEqual(set(), self.check(pdf)[0])


if __name__ == '__main__':
    unittest.main()
