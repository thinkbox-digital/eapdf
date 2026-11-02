"""A date decoder rejection is an invalid PDF date, not an operational failure."""
import tempfile
import unittest
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v
import test_userproperty_sources as source_tests


class UserPropertyDateDecodingTests(unittest.TestCase):
    def bad(self, tail=False):
        return pikepdf.String(b'\xef\xbb\xbf' + (b'D:20261021' if tail else b'') + b'\xff')

    def property_report(self, tag):
        attr = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
            P=pikepdf.Array([pikepdf.Dictionary(N='Mail_Sent', V=self.bad())]))
        report = v.Report('date.pdf')
        v.check_user_properties([dict(type=tag, attrs=attr)], report)
        self.assertEqual({'USERPROPS_TYPE'}, {i['code'] for i in report.items if i['level'] == 'FAIL'})

    def test_invalid_utf8_is_not_a_date(self):
        self.assertFalse(v.looks_like_pdf_date(self.bad()))

    def test_invalid_utf8_after_date_prefix_is_not_a_date(self):
        self.assertFalse(v.looks_like_pdf_date(self.bad(tail=True)))

    def test_unreadable_message_date_reports_type_failure(self):
        self.property_report('Mail_Message')

    def test_unreadable_field_date_reports_type_failure(self):
        self.property_report('Mail_FieldValue')

    def check_source(self, descendant=False):
        findings, _ = source_tests.UserPropertySourceTests().check(
            [('Mail_Sent', self.bad())], descendant=descendant)
        findings = [i for i in findings if i['details'].get('name') == 'Mail_Sent']
        self.assertEqual(['N/A'], [i['level'] for i in findings])
        self.assertIn('USERPROPS_TYPE', findings[0]['details']['reason'])

    def test_invalid_message_date_does_not_abort_source_recommendation(self):
        self.check_source()

    def test_invalid_field_date_does_not_abort_source_recommendation(self):
        self.check_source(descendant=True)

    def test_valid_utf8_date_remains_valid(self):
        self.assertTrue(v.looks_like_pdf_date(pikepdf.String(b'\xef\xbb\xbfD:20261021120000Z')))

    def test_valid_utf16_date_remains_valid(self):
        self.assertTrue(v.looks_like_pdf_date(pikepdf.String(b'\xfe\xff' + 'D:20261021120000Z'.encode('utf-16-be'))))

    def test_readable_invalid_ascii_remains_invalid(self):
        self.assertFalse(v.looks_like_pdf_date(pikepdf.String('not-a-date')))

    def test_truncated_utf16_date_remains_invalid(self):
        self.assertFalse(v.looks_like_pdf_date(pikepdf.String(b'\xfe\xff\x00D\x00:\x00')))

    def test_fullwidth_digits_are_not_normalized(self):
        self.assertFalse(v.looks_like_pdf_date(pikepdf.String('D:２０２６１０２１')))

    def test_full_validation_reports_data_failure_without_operational_error(self):
        with pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
            elements = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
            message = next(e['elem'] for e in elements if e['type'] == 'Mail_Message')
            message.A.P.append(pikepdf.Dictionary(N='Mail_Sent', V=self.bad()))
            with tempfile.TemporaryDirectory(prefix='.eapdf-date-decode-', dir=ROOT) as directory:
                path = Path(directory) / 'focused.pdf'
                pdf.save(path, static_id=True, fix_metadata_version=False)
                report = v.validate(str(path))
        self.assertEqual({'USERPROPS_TYPE'}, {i['code'] for i in report.items if i['level'] == 'FAIL'})
        self.assertEqual('FAIL', report.summary()['status'])


if __name__ == '__main__':
    unittest.main()
