"""Unreadable group/subtype values cannot hide other active comparisons."""
import tempfile
import unittest
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v


class UserPropertyComparisonDecodingTests(unittest.TestCase):
    def code(self, family):
        return ('MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT' if family == 'group'
                else 'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT')

    def check(self, family, mode):
        seed = ('validator-dpart-object-ranges/multi-ordered.pdf' if family == 'group'
                else 'validator-active-userproperties/owner-subtype-consistent.pdf')
        key = 'Mail_ContentSetType' if family == 'group' else 'Mail_Subtype'
        with pikepdf.open(ROOT / 'tests/fixtures/eapdf' / seed) as pdf:
            elements = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
            if family == 'group':
                target = next(e['elem'] for e in elements if e['type'] == 'Mail_ContentGroup')
            else:
                target = next(e['elem'] for e in elements if e['type'] == 'Mail_ContentSet' and
                    any(str(p.N) == 'Mail_ContentSetType' and str(p.V) == 'FrontMatter' for p in e['elem'].A.P))
            prop = next(p for p in target.A.P if str(p.N) == key)
            original = str(prop.V)
            bad = pikepdf.Dictionary(N=key, V=pikepdf.String(b'\xef\xbb\xbf\xff'))
            if mode == 'bad':
                prop.V = bad.V
            elif mode in ('before', 'after', 'wrong-before', 'wrong-after'):
                if mode.startswith('wrong'):
                    prop.V = 'FrontMatter' if family == 'group' else 'application/octet-stream'
                if mode.endswith('before'):
                    target.A.P.insert(0, bad)
                else:
                    target.A.P.append(bad)
            elif mode == 'utf8':
                prop.V = pikepdf.String(b'\xef\xbb\xbf' + original.encode())
            elif mode == 'utf16':
                prop.V = pikepdf.String(b'\xfe\xff' + original.encode('utf-16-be'))
            elif mode == 'wrong-type':
                prop.V = pikepdf.Name('/WrongType')
            elif mode == 'inactive':
                target.A = pikepdf.Array([pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                    P=pikepdf.Array([bad])), target.A])
            with tempfile.TemporaryDirectory(prefix='.eapdf-comparison-decode-', dir=ROOT) as directory:
                path = Path(directory) / 'focused.pdf'
                pdf.save(path, static_id=True, fix_metadata_version=False)
                report = v.validate(str(path))
        self.assertNotIn('VALIDATION_ERROR', {i['code'] for i in report.items if i['level'] == 'FAIL'})
        return report

    def levels(self, report, code):
        return {i['level'] for i in report.items if i['code'] == code}

    def assert_modes(self, mode, expected_status, level):
        for family in ('group', 'subtype'):
            with self.subTest(family=family):
                report = self.check(family, mode)
                self.assertEqual(expected_status, report.summary()['status'])
                self.assertEqual({level}, self.levels(report, self.code(family)))
                if expected_status in ('INCOMPLETE', 'FAIL') and mode != 'wrong-type':
                    self.assertEqual({'WARN'}, self.levels(report, 'VALIDATION_INCOMPLETE'))

    def test_unreadable_conditional_correspondence_is_incomplete(self):
        self.assert_modes('bad', 'INCOMPLETE', 'WARN')

    def test_unreadable_value_does_not_erase_presence_recommendation(self):
        for family in ('group', 'subtype'):
            with self.subTest(family=family):
                report = self.check(family, 'bad')
                code = ('MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED' if family == 'group'
                        else 'MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED')
                self.assertEqual({'PASS'}, self.levels(report, code))

    def test_unreadable_before_valid_occurrence_remains_unavailable(self):
        self.assert_modes('before', 'INCOMPLETE', 'WARN')

    def test_unreadable_after_valid_occurrence_remains_unavailable(self):
        self.assert_modes('after', 'INCOMPLETE', 'WARN')

    def test_unreadable_before_mismatch_cannot_hide_failure(self):
        self.assert_modes('wrong-before', 'FAIL', 'FAIL')

    def test_unreadable_after_mismatch_cannot_hide_failure(self):
        self.assert_modes('wrong-after', 'FAIL', 'FAIL')

    def test_valid_utf8_values_still_match(self):
        self.assert_modes('utf8', 'PASS', 'PASS')

    def test_valid_utf16_values_still_match(self):
        self.assert_modes('utf16', 'PASS', 'PASS')

    def test_wrong_pdf_types_retain_prescribed_failures(self):
        for family in ('group', 'subtype'):
            with self.subTest(family=family):
                report = self.check(family, 'wrong-type')
                self.assertEqual({'USERPROPS_TYPE'}, {i['code'] for i in report.items if i['level'] == 'FAIL'})
                self.assertEqual('FAIL', report.summary()['status'])

    def test_replaced_unreadable_property_array_is_inactive(self):
        self.assert_modes('inactive', 'PASS', 'PASS')


if __name__ == '__main__':
    unittest.main()
