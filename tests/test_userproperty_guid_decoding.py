"""Unreadable GUID evidence stays unavailable without hiding readable mismatches."""
import tempfile
import unittest
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v


class UserPropertyGuidDecodingTests(unittest.TestCase):
    def check(self, mode):
        with pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
            elements = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
            message = next(e['elem'] for e in elements if e['type'] == 'Mail_Message')
            prop = next(p for p in message.A.P if str(p.N) == 'Mail_GUID')
            original = str(prop.V)
            bad = pikepdf.Dictionary(N='Mail_GUID', V=pikepdf.String(b'\xef\xbb\xbf\xff'))
            if mode == 'bad':
                prop.V = bad.V
            elif mode == 'before':
                message.A.P.insert(0, bad)
            elif mode == 'after':
                message.A.P.append(bad)
            elif mode in ('wrong-before', 'wrong-after'):
                prop.V = 'known-wrong-guid'
                if mode == 'wrong-before':
                    message.A.P.insert(0, bad)
                else:
                    message.A.P.append(bad)
            elif mode == 'utf8':
                prop.V = pikepdf.String(b'\xef\xbb\xbf' + original.encode())
            elif mode == 'utf16':
                prop.V = pikepdf.String(b'\xfe\xff' + original.encode('utf-16-be'))
            elif mode == 'empty':
                prop.V = pikepdf.String(b'\xef\xbb\xbf')
            elif mode == 'wrong-type':
                prop.V = pikepdf.Name('/WrongType')
            elif mode == 'inactive':
                message.A = pikepdf.Array([pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                    P=pikepdf.Array([bad])), message.A])
            with tempfile.TemporaryDirectory(prefix='.eapdf-guid-decode-', dir=ROOT) as directory:
                path = Path(directory) / 'focused.pdf'
                pdf.save(path, static_id=True, fix_metadata_version=False)
                report = v.validate(str(path))
        self.assertNotIn('VALIDATION_ERROR', {i['code'] for i in report.items if i['level'] == 'FAIL'})
        return report

    def levels(self, report, code):
        return {i['level'] for i in report.items if i['code'] == code}

    def test_unreadable_required_guid_presence_is_unavailable(self):
        report = self.check('bad')
        self.assertEqual({'WARN'}, self.levels(report, 'MAIL_MESSAGE_GUID_ATTRIBUTE'))
        self.assertEqual('INCOMPLETE', report.summary()['status'])

    def test_unreadable_guid_match_is_unavailable(self):
        report = self.check('bad')
        self.assertEqual({'WARN'}, self.levels(report, 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP'))
        self.assertEqual({'WARN'}, self.levels(report, 'VALIDATION_INCOMPLETE'))
        self.assertEqual({'PASS'}, self.levels(report, 'USERPROPS_TYPE'))

    def test_unreadable_before_valid_does_not_hide_unavailable_occurrence(self):
        report = self.check('before')
        self.assertEqual({'PASS'}, self.levels(report, 'MAIL_MESSAGE_GUID_ATTRIBUTE'))
        self.assertEqual({'WARN'}, self.levels(report, 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP'))
        self.assertEqual('INCOMPLETE', report.summary()['status'])

    def test_unreadable_after_valid_does_not_hide_unavailable_occurrence(self):
        report = self.check('after')
        self.assertEqual({'PASS'}, self.levels(report, 'MAIL_MESSAGE_GUID_ATTRIBUTE'))
        self.assertEqual({'WARN'}, self.levels(report, 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP'))
        self.assertEqual('INCOMPLETE', report.summary()['status'])

    def check_mismatch(self, mode):
        report = self.check(mode)
        self.assertEqual({'FAIL'}, self.levels(report, 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP'))
        self.assertEqual({'WARN'}, self.levels(report, 'VALIDATION_INCOMPLETE'))
        self.assertEqual('FAIL', report.summary()['status'])

    def test_unreadable_before_mismatch_preserves_definite_failure(self):
        self.check_mismatch('wrong-before')

    def test_unreadable_after_mismatch_preserves_definite_failure(self):
        self.check_mismatch('wrong-after')

    def test_valid_utf8_guid_matches(self):
        self.assertEqual('PASS', self.check('utf8').summary()['status'])

    def test_valid_utf16_guid_matches(self):
        self.assertEqual('PASS', self.check('utf16').summary()['status'])

    def test_decoded_empty_guid_remains_missing(self):
        report = self.check('empty')
        self.assertEqual({'FAIL'}, self.levels(report, 'MAIL_MESSAGE_GUID_ATTRIBUTE'))
        self.assertEqual('FAIL', report.summary()['status'])

    def test_wrong_pdf_type_retains_type_failure(self):
        report = self.check('wrong-type')
        self.assertEqual({'FAIL'}, {i['level'] for i in report.items
            if i['code'] == 'USERPROPS_TYPE' and i['details'].get('name') == 'Mail_GUID'})
        self.assertEqual('FAIL', report.summary()['status'])

    def test_replaced_unreadable_array_remains_inactive(self):
        self.assertEqual('PASS', self.check('inactive').summary()['status'])

    def test_unreadable_identity_cannot_establish_source_owner(self):
        report = self.check('before')
        findings = [i for i in report.items if i['code'] == 'USERPROPS_VALUES_UNMODIFIED']
        self.assertTrue(findings)
        self.assertFalse(any(i['level'] == 'FAIL' for i in findings))
        self.assertTrue(all(i['level'] == 'N/A' or (i.get('details') or {}).get('comparison') == 'unavailable'
                            for i in findings))


if __name__ == '__main__':
    unittest.main()
