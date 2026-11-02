"""Source fidelity stays a SHOULD recommendation when attribute text is unreadable."""
import unittest
import pikepdf
import test_userproperty_sources as source_tests


class UserPropertySourceTextDecodingTests(unittest.TestCase):
    def bad(self):
        return pikepdf.String(b'\xef\xbb\xbf\xff')

    def findings(self, properties, descendant=False):
        findings, _ = source_tests.UserPropertySourceTests().check(properties, descendant=descendant)
        names = {name for name, _ in properties}
        return [item for item in findings if item['details'].get('name') in names]

    def assert_unavailable(self, name, descendant=False):
        items = self.findings([(name, self.bad())], descendant=descendant)
        self.assertEqual(1, len(items))
        self.assertEqual('WARN', items[0]['level'])
        self.assertEqual('unavailable', items[0]['details'].get('comparison'))
        self.assertNotIn('expected', items[0]['details'])

    def test_unreadable_subject_is_unavailable_recommendation(self):
        self.assert_unavailable('Mail_Subject')

    def test_unreadable_from_is_unavailable_recommendation(self):
        self.assert_unavailable('Mail_From')

    def test_unreadable_raw_sent_is_not_normalized_or_compared(self):
        self.assert_unavailable('Mail_Raw_Sent')

    def test_unreadable_generic_header_is_unavailable_recommendation(self):
        self.assert_unavailable('Mail_X-Extra')

    def test_unreadable_descendant_uses_the_existing_source_owner(self):
        self.assert_unavailable('Mail_Subject', descendant=True)

    def check_duplicates(self, before):
        values = [('Mail_Subject', self.bad()), ('Mail_Subject', 'Source A')]
        if not before:
            values.reverse()
        items = self.findings(values)
        self.assertEqual(2, len(items))
        self.assertEqual(['PASS', 'WARN'], sorted(i['level'] for i in items))
        self.assertEqual({'equal', 'unavailable'}, {i['details']['comparison'] for i in items})

    def test_unreadable_before_readable_does_not_abort_other_occurrence(self):
        self.check_duplicates(True)

    def test_unreadable_after_readable_preserves_other_occurrence(self):
        self.check_duplicates(False)

    def test_valid_utf8_subject_compares_equal(self):
        items = self.findings([('Mail_Subject', pikepdf.String(b'\xef\xbb\xbfSource A'))])
        self.assertEqual(['PASS'], [i['level'] for i in items])
        self.assertEqual('equal', items[0]['details']['comparison'])

    def test_valid_utf16_subject_compares_equal(self):
        value = pikepdf.String(b'\xfe\xff' + 'Source A'.encode('utf-16-be'))
        items = self.findings([('Mail_Subject', value)])
        self.assertEqual(['PASS'], [i['level'] for i in items])
        self.assertEqual('equal', items[0]['details']['comparison'])

    def test_readable_mismatch_remains_recommendation_warning(self):
        items = self.findings([('Mail_Subject', 'Source B')])
        self.assertEqual(['WARN'], [i['level'] for i in items])
        self.assertEqual('different', items[0]['details']['comparison'])

    def test_wrong_pdf_value_type_remains_separately_checked(self):
        items = self.findings([('Mail_Subject', pikepdf.Name('/WrongType'))])
        self.assertEqual(['N/A'], [i['level'] for i in items])
        self.assertIn('USERPROPS_TYPE', items[0]['details']['reason'])

    def test_unreadable_pdf_date_retains_separate_type_assessment(self):
        items = self.findings([('Mail_Sent', self.bad())])
        self.assertEqual(['N/A'], [i['level'] for i in items])
        self.assertIn('USERPROPS_TYPE', items[0]['details']['reason'])


if __name__ == '__main__':
    unittest.main()
