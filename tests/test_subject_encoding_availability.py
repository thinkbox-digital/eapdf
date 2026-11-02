from test_preservation_safety import failed_source_fields
"""Unknown Subject normalization cannot prove equality or a contradiction."""
import hashlib
import json
from pathlib import Path
import unittest

from test_preservation_safety import validator as v, failed_codes
import test_ambiguous_source_headers as source_tests


class SubjectEncodingAvailabilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        folder = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-subject-encoding'
        cls.cases = json.loads((folder / 'expectations.json').read_text())['cases']
        cls.reports = {}
        for case in cls.cases:
            path = folder / case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
            cls.reports[path.stem] = v.validate(str(path))

    def test_native_subject_controls_match_reviewed_expectations(self):
        for case in self.cases:
            report = self.reports[Path(case['file']).stem]
            with self.subTest(file=case['file']):
                self.assertEqual(case['expected_status'], report.summary()['status'])
                self.assertEqual(set(case['expected_failures']), failed_codes(report))

    def test_unsupported_source_normalization_is_explicitly_unavailable(self):
        for name in ('malformed-q-repaired', 'malformed-b-repaired', 'unknown-charset', 'malformed-different'):
            rows = [i for i in self.reports[name].items if i['code'] == 'MESSAGE_METADATA_MATCH_SOURCE'
                    and i.get('details', {}).get('field') == 'Subject']
            self.assertTrue(any(i['level'] == 'WARN' and (i.get('details') or {}).get('assessment') == 'unavailable'
                                for i in rows), name)
            self.assertFalse(failed_codes(self.reports[name]))

    def test_exact_malformed_original_text_remains_preservable(self):
        self.assertEqual('PASS', self.reports['malformed-literal'].summary()['status'])

    def test_original_presence_still_requires_nonempty_subject(self):
        self.assertEqual({'Subject'}, failed_source_fields(self.reports['required-subject-omitted'], 'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))

    def test_invalid_supplied_encoded_word_cannot_borrow_decoder_repair(self):
        self.assertEqual({'Subject'}, failed_source_fields(self.reports['supplied-malformed']))

    def test_valid_decoded_subjects_remain_comparable(self):
        self.assertEqual('PASS', self.reports['valid-encoded'].summary()['status'])
        self.assertEqual({'Subject'}, failed_source_fields(self.reports['valid-different']))

    def test_actual_replacement_character_is_known_unicode_data(self):
        for raw in ('\ufffd', '=?utf-8?B?77+9?=', '=?utf-8?Q?=EF=BF=BD?='):
            with self.subTest(raw=raw):
                self.assertEqual('\ufffd', v.decoded_source_header(raw))
        with self.assertRaises(ValueError):
            v.decoded_source_header('=?utf-8?B?/w==?=')

    def test_unsupported_candidate_blocks_a_universal_subject_contradiction(self):
        helper = source_tests.AmbiguousSourceHeaderTests()
        files = helper.sources(lambda raw: raw.replace(b'Subject: Same', b'Subject: =?X-NO-CHARSET?Q?unknown?='))
        size = max(len(item['bytes']) for item in files)
        for item in files:
            item['bytes'] += b' ' * (size - len(item['bytes']))
        report = helper.check(files, fields={'Subject': 'Different'})
        self.assertNotIn('Subject', failed_source_fields(report))
        self.assertEqual('INCOMPLETE', report.summary()['status'])
