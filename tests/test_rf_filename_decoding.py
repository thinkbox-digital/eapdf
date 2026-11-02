"""An unreadable RF name is unknown evidence, while known violations remain."""
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes


class RelatedFilenameDecodingTests(unittest.TestCase):
    def check(self, name, expected=None, extra=False):
        with pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-coverage-branches/rf-valid.pdf') as pdf:
            source = next(spec for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source)
            source.RF.UF[0] = name
            if extra:
                source.RF.UF.extend([pikepdf.Name.Bad, source.RF.UF[1]])
            report = v.Report('rf.pdf')
            related, _ = v.check_source_file_sets(pdf, None, v.get_af_specs(pdf), report,
                {str(source.UF): expected} if expected is not None else None)
            return report, list(related.values())

    def test_unknown_filename_keeps_the_stream_and_unavailable_assessment(self):
        for raw in (b'\xef\xbb\xbf\xff', b'\xef\xbb\xbf\xed\xa0\x80'):
            with self.subTest(raw=raw):
                report, related = self.check(pikepdf.String(raw))
                self.assertEqual('INCOMPLETE', report.summary()['status'])
                self.assertEqual(set(), failed_codes(report))
                self.assertEqual(1, len(related))
                self.assertEqual(b'Original companion bytes\n', related[0]['bytes'])
                self.assertTrue(related[0]['filename_unavailable'])
                self.assertEqual('', v.source_file_name(related[0]))

    def test_unknown_name_cannot_establish_missing_known_companion(self):
        report, _ = self.check(pikepdf.String(b'\xef\xbb\xbf\xff'), ['companion.txt'])
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertNotIn('SOURCE_FILE_SET', failed_codes(report))

    def test_unknown_pair_cannot_cover_two_distinct_required_companions(self):
        report, _ = self.check(pikepdf.String(b'\xef\xbb\xbf\xff'), ['one.txt', 'two.txt'])
        self.assertIn('SOURCE_FILE_SET', failed_codes(report))
        row = next(item for item in report.items if item['code'] == 'SOURCE_FILE_SET' and item['level'] == 'FAIL')
        self.assertEqual(1, row['details']['actual'][0]['minimum_missing'])

    def test_known_bad_later_pair_still_fails(self):
        report, related = self.check(pikepdf.String(b'\xef\xbb\xbf\xff'), extra=True)
        self.assertIn('SOURCE_FILE_SET', failed_codes(report))
        self.assertEqual(1, len(related))
        self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_valid_encoded_names_preserve_exact_filename_data(self):
        for raw in (b'\xef\xbb\xbf companion.txt ', b'\xfe\xff' + ' companion.txt '.encode('utf-16-be')):
            with self.subTest(raw=raw):
                report, related = self.check(pikepdf.String(raw), [' companion.txt '])
                self.assertEqual('PASS', report.summary()['status'])
                self.assertEqual(' companion.txt ', v.source_file_name(related[0]))

    def test_decoded_difference_still_proves_missing_companion(self):
        report, _ = self.check(pikepdf.String('different.txt'), ['companion.txt'])
        self.assertIn('SOURCE_FILE_SET', failed_codes(report))

    def test_empty_and_wrong_class_names_remain_known_failures(self):
        for name in (pikepdf.String(''), pikepdf.Name.Bad):
            with self.subTest(name=name):
                report, related = self.check(name)
                self.assertIn('SOURCE_FILE_SET', failed_codes(report))
                self.assertEqual([], related)
