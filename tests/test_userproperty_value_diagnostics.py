"""Definite wrong V types must not abort when diagnostic text decoding fails."""
import tempfile
import unittest
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v


class UserPropertyValueDiagnosticTests(unittest.TestCase):
    def document(self):
        return pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf')

    def message(self, pdf):
        elements = []
        v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
        return next(e['elem'] for e in elements if e['type'] == 'Mail_Message')

    def bad_name(self):
        return pikepdf.Object.parse(b'/Vendor#FF')

    def bad_dictionary(self):
        return pikepdf.Object.parse(b'<< /Vendor#FF 1 >>')

    def check(self, name=None, value=None):
        with self.document() as pdf:
            message = self.message(pdf)
            if name is not None:
                prop = next((p for p in message.A.P if str(p.N) == name), None)
                if prop is None:
                    message.A.P.append(pikepdf.Dictionary(N=name, V=value))
                else:
                    prop.V = value
            with tempfile.TemporaryDirectory(prefix='.eapdf-value-diagnostics-', dir=ROOT) as directory:
                path = Path(directory) / 'focused.pdf'
                pdf.save(path, static_id=True, fix_metadata_version=False)
                report = v.validate(str(path))
        failures = {i['code'] for i in report.items if i['level'] == 'FAIL'}
        self.assertNotIn('VALIDATION_ERROR', failures)
        return failures, report

    def assert_type_failure(self, name, value):
        failures, report = self.check(name, value)
        self.assertEqual({'USERPROPS_TYPE'}, failures)
        self.assertEqual('FAIL', report.summary()['status'])
        return report

    def test_guid_name_value_reports_type_failure(self):
        self.assert_type_failure('Mail_GUID', self.bad_name())

    def test_guid_array_value_reports_type_failure(self):
        self.assert_type_failure('Mail_GUID', pikepdf.Array([self.bad_name()]))

    def test_guid_dictionary_value_reports_type_failure(self):
        self.assert_type_failure('Mail_GUID', self.bad_dictionary())

    def test_subject_name_value_reports_type_failure(self):
        self.assert_type_failure('Mail_Subject', self.bad_name())

    def test_subject_dictionary_value_reports_type_failure(self):
        self.assert_type_failure('Mail_Subject', self.bad_dictionary())

    def test_date_name_value_reports_type_failure(self):
        self.assert_type_failure('Mail_Sent', self.bad_name())

    def test_size_name_value_reports_type_failure(self):
        self.assert_type_failure('Mail_SizeInBytes', self.bad_name())

    def test_message_id_name_value_does_not_abort_owner_identity_assessment(self):
        self.assert_type_failure('Mail_MessageID', self.bad_name())

    def test_count_dictionary_with_unreadable_member_does_not_abort(self):
        self.assert_type_failure('Mail_NumberAttachments', pikepdf.Dictionary(Raw=self.bad_name()))

    def test_generic_header_name_value_keeps_existing_type_failure(self):
        self.assert_type_failure('Mail_X-Extra', self.bad_name())

    def test_generic_header_dictionary_value_keeps_existing_type_failure(self):
        self.assert_type_failure('Mail_X-Extra', self.bad_dictionary())

    def test_readable_wrong_guid_type_keeps_existing_type_failure(self):
        self.assert_type_failure('Mail_GUID', pikepdf.Name('/Unrelated'))

    def test_unicode_text_values_remain_legal(self):
        failures, report = self.check('Mail_Subject', 'Unicode résumé')
        self.assertEqual(set(), failures)
        self.assertEqual('PASS', report.summary()['status'])

    def test_baseline_guid_string_remains_legal(self):
        failures, report = self.check()
        self.assertEqual(set(), failures)
        self.assertEqual('PASS', report.summary()['status'])


if __name__ == '__main__':
    unittest.main()
