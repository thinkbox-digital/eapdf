from test_preservation_safety import failed_source_fields
"""An outer message is not a complete inventory of a retained mail asset."""
import hashlib
import json
from pathlib import Path
import unittest

import pikepdf

from test_preservation_safety import validator as v, failed_codes
from rule_refinement_fixtures import corrected_rendered_count
import test_ambiguous_source_headers as source_tests


class NestedSourceScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-nested-source-scope'
        cls.cases = json.loads((cls.folder / 'expectations.json').read_text())['cases']
        cls.reports = {}
        for case in cls.cases:
            path = cls.folder / case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
            cls.reports[path.stem] = v.validate(str(path))

    def test_native_controls_match_reviewed_expectations(self):
        for case in self.cases:
            with self.subTest(file=case['file']):
                report = self.reports[Path(case['file']).stem]
                self.assertEqual(case['expected_status'], report.summary()['status'])
                self.assertEqual(set(case['expected_failures']), failed_codes(report))

    def test_uninspected_nested_candidates_block_outer_only_contradictions(self):
        for name in ('nested-rfc822', 'nested-global', 'nested-eml-octet', 'nested-msg',
                     'tentative-outer', 'mbox-envelope'):
            with self.subTest(name=name):
                rows = [i for i in self.reports[name].items if i['code'] == 'MESSAGE_SOURCE_COVERAGE']
                self.assertFalse(any(i['level'] == 'PASS' for i in rows))
                self.assertTrue(any((i.get('details') or {}).get('candidate_universe') == 'incomplete'
                                    for i in rows))

    def test_body_media_cannot_reselect_a_rejected_tentative_source(self):
        rows = [i for i in self.reports['tentative-outer'].items
                if i['code'] == 'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE'
                and i.get('details',{}).get('check') == 'body_source_media_type']
        self.assertEqual({'WARN'}, {i['level'] for i in rows})

    def test_matching_outer_message_with_mail_attachment_remains_checkable(self):
        report = self.reports['outer-consistent']
        self.assertTrue(any(i['code'] == 'MESSAGE_SOURCE_COVERAGE' and i['level'] == 'PASS'
                            for i in report.items))
        self.assertEqual({'ASSET_NUMBERMESSAGES_MATCH_RENDERED'}, failed_codes(report))
        # The fixture's count of two includes the unrendered nested message.
        # Correct only that claim; source binding and outer rendering remain valid.
        with corrected_rendered_count(self.folder/'outer-consistent.pdf','represented.eml',2,1) as path:
            self.assertEqual('PASS', v.validate(str(path)).summary()['status'])

    def test_ordinary_binary_attachment_retains_known_field_failure(self):
        self.assertEqual({'To'}, failed_source_fields(self.reports['ordinary-binary-wrong-to']))

    def test_rfc822_original_still_contains_exact_nested_message_bytes(self):
        seed = self.folder.parent / 'validator-source-mailbox-data/from-exact.pdf'
        with pikepdf.open(seed) as pdf:
            original = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source).EF.F.read_bytes()
        with pikepdf.open(self.folder / 'nested-rfc822.pdf') as pdf:
            retained = next(s for s in pdf.Root.AF if s.AFRelationship == pikepdf.Name.Source).EF.F.read_bytes()
        self.assertIn(original, retained)

    def test_independent_supplied_schema_failure_survives_unknown_nested_candidates(self):
        helper = source_tests.AmbiguousSourceHeaderTests()
        files = helper.sources()
        for source in v.summarize_source_emails(files):
            source['uninspected_email_candidates'] = [{'kind': 'mail_attachment'}]
        report = helper.check(files, to='bad..address@example.org')
        self.assertIn('MESSAGE_TO_FOAF', failed_codes(report))

    def test_incomplete_extra_source_blocks_universal_proof_in_either_order(self):
        helper = source_tests.AmbiguousSourceHeaderTests()
        files = helper.sources()
        v.summarize_source_emails(files)[1]['uninspected_email_candidates'] = [{'kind': 'mail_attachment'}]
        for ordered in (files, list(reversed(files))):
            report = helper.check(ordered, to='wrong@example.org')
            self.assertFalse(failed_codes(report))
            self.assertEqual('INCOMPLETE', report.summary()['status'])

    def test_mail_media_and_filename_independently_identify_uninspected_candidates(self):
        for media, filename in [('message/global', 'original.data'), ('message/rfc822', 'blob'),
                                ('application/mbox', 'blob'), ('application/vnd.ms-outlook', 'blob'),
                                ('application/octet-stream', 'ORIGINAL.EML'),
                                ('application/octet-stream', 'original.pst')]:
            with self.subTest(media=media, filename=filename):
                raw = (b'From: sender@example.org\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n'
                       b'--b\r\nContent-Disposition: attachment; filename="' + filename.encode() +
                       b'"\r\nContent-Type: ' + media.encode() + b'\r\n\r\nopaque\r\n--b--\r\n')
                source = dict(afrelationship='Source', filename='outer.eml', bytes=raw)
                summary = v.summarize_source_email([source])
                self.assertTrue(summary['uninspected_email_candidates'])

    def test_ordinary_named_binary_is_not_assumed_to_contain_email(self):
        helper = source_tests.AmbiguousSourceHeaderTests()
        self.assertTrue(all(not s['uninspected_email_candidates']
                            for s in v.summarize_source_emails(helper.sources())))
