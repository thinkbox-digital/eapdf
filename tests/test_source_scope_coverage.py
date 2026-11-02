from test_preservation_safety import failed_source_fields
"""Complete PDF decisions follow actual source ownership rather than catalog order."""
import hashlib
import json
import unittest
from pathlib import Path

from test_preservation_safety import validator as v, failed_codes


class SourceScopeCoverageTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        folder=Path(__file__).resolve().parent/'fixtures/eapdf/validator-source-scope'
        cls.reports={}
        for case in json.loads((folder/'expectations.json').read_text())['cases']:
            path=folder/case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
            cls.reports[path.stem]=v.validate(str(path))

    def levels(self,name,code):
        return {item['level'] for item in self.reports[name].items if item['code']==code}

    def test_one_compatible_original_blocks_invented_missing_recipient(self):
        for field in ('to','from','cc','bcc','subject'):
            with self.subTest(field=field):
                report=self.reports['missing-'+field]
                self.assertFalse(failed_codes(report))
                self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_unassigned_original_does_not_get_a_successful_owner(self):
        for name in ('missing-to','missing-to-reversed','mixed-date'):
            self.assertNotIn('PASS',self.levels(name,'MESSAGE_SOURCE_COVERAGE'))

    def test_catalog_order_does_not_decide_ambiguous_presence(self):
        left=self.reports['missing-to'];right=self.reports['missing-to-reversed']
        self.assertEqual(left.summary()['status'],right.summary()['status'])
        self.assertEqual(failed_codes(left),failed_codes(right))

    def test_second_source_match_does_not_inherit_first_source_recipient(self):
        for name in ('assigned-second','assigned-second-reversed'):
            self.assertEqual('PASS',self.reports[name].summary()['status'])
            self.assertNotIn('FAIL',self.levels(name,'MESSAGE_TO_XMP'))
            self.assertEqual({'N/A'},self.levels(name,'MESSAGE_RAW_TO_XMP'))

    def test_matching_receipt_identifies_the_actual_second_original(self):
        for name in ('assigned-second','assigned-second-reversed'):
            owners=[item['details'] for item in self.reports[name].items
                    if item['code']=='MESSAGE_SOURCE_COVERAGE' and item['level']=='PASS']
            self.assertEqual(1,len(owners))
            self.assertEqual('candidate.eml',owners[0]['source'])
            self.assertEqual(64,len(owners[0]['source_sha256']))
            self.assertIn('source_objgen',owners[0])

    def test_unrelated_invalid_date_does_not_require_raw_sent(self):
        name='assigned-date-second-valid'
        self.assertEqual('PASS',self.reports[name].summary()['status'])
        self.assertEqual({'N/A'},self.levels(name,'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'))

    def test_unrelated_valid_date_does_not_activate_raw_sent_omission(self):
        name='assigned-date-second-invalid'
        self.assertEqual('PASS',self.reports[name].summary()['status'])
        self.assertEqual({'N/A'},self.levels(name,'MESSAGE_RAW_SENT_OMIT_IF_VALID'))
        self.assertIn('PASS',self.levels(name,'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'))

    def test_unassigned_date_condition_remains_unknown(self):
        self.assertEqual('INCOMPLETE',self.reports['mixed-date'].summary()['status'])
        self.assertIn('WARN',self.levels('mixed-date','MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'))
        self.assertNotIn('N/A',self.levels('mixed-date','MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'))

    def test_universal_missing_values_still_fail_independently(self):
        self.assertEqual({'To'},failed_source_fields(self.reports['all-require-to'], 'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
        self.assertEqual({'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'},failed_codes(self.reports['all-require-raw-sent']))

    def test_single_consistent_source_keeps_passing(self):
        self.assertEqual('PASS',self.reports['assigned-positive'].summary()['status'])
