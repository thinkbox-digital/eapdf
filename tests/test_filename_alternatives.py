"""Original MIME filename alternatives retain exact text and occurrence counts."""
from email import policy
from email.parser import BytesParser
import hashlib
import json
from pathlib import Path
import unittest

from test_preservation_safety import validator as v, failed_codes


class FilenameAlternativeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        folder = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-filename-alternatives'
        cls.cases = json.loads((folder / 'expectations.json').read_text())['cases']
        cls.reports = {}
        for case in cls.cases:
            path = folder / case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
            cls.reports[path.stem] = v.validate(str(path))

    def record(self, filename):
        return dict(Filename=filename, SizeInBytes='7', **{'Content-Type': 'text/plain', 'Mail_GUID': 'owner'})

    def candidate(self, checksum=None):
        return dict(original_filename='disposition.txt', original_filenames=['disposition.txt', 'type.txt'],
                    filename='disposition.txt', size_in_bytes=7, content_type='text/plain',
                    checksum=checksum or v.md5hex(b'payload'))

    def summary(self, candidates):
        return dict(message_id='id', mail_guid='owner', size_in_bytes=100, subject='', sent_norm='', raw_from='',
                    source_sha256='known-source', mime_attachments=candidates)

    def test_native_controls_match_reviewed_expectations(self):
        for case in self.cases:
            report = self.reports[Path(case['file']).stem]
            with self.subTest(file=case['file']):
                self.assertEqual(case['expected_status'], report.summary()['status'])
                self.assertEqual(set(case['expected_failures']), failed_codes(report))

    def test_either_original_parameter_can_name_the_same_source_occurrence(self):
        for name in ('disposition.txt', 'type.txt'):
            report = v.Report('either.pdf')
            v.validate_attachment_records([self.record(name)], self.summary([self.candidate()]), report)
            self.assertFalse(failed_codes(report))

    def test_nonempty_original_whitespace_is_not_trimmed_into_absence(self):
        for filename in (' ', '\t', '  '):
            with self.subTest(filename=repr(filename)):
                report = v.Report('literal-name.pdf')
                v.validate_attachment_records([self.record(filename)], [], report)
                self.assertNotIn('ATTACHMENT_XMP_FILENAME', failed_codes(report))

    def test_empty_supplied_filename_keeps_its_existing_failure(self):
        report = v.Report('empty-name.pdf')
        v.validate_attachment_records([self.record('')], [], report)
        self.assertIn('ATTACHMENT_XMP_FILENAME', failed_codes(report))

    def test_two_permitted_names_do_not_create_two_attachment_occurrences(self):
        report = v.Report('one-occurrence.pdf')
        v.validate_attachment_records([self.record('disposition.txt'), self.record('type.txt')],
                                      self.summary([self.candidate()]), report)
        findings=[r for r in report.items if r['code']=='ATTACHMENT_XMP_FILENAME_MATCH_SOURCE']
        self.assertEqual(['PASS', 'WARN'], [r['level'] for r in findings])
        self.assertEqual('unavailable', findings[1]['details']['assessment'])

    def test_alternative_name_supplies_a_known_source_alias_checksum(self):
        record = dict(Mail_GUID='owner', messageid='id', SizeInBytes='100')
        checksum = v.attachment_source_checksum(self.record('type.txt'), {'identity': {'Mail_GUID': 'owner'}},
                                                [record], [self.summary([self.candidate()])])
        self.assertEqual(v.md5hex(b'payload'), checksum)

    def test_differing_payload_candidates_still_leave_alias_checksum_unavailable(self):
        record = dict(Mail_GUID='owner', messageid='id', SizeInBytes='100')
        checksum = v.attachment_source_checksum(self.record('type.txt'), {'identity': {'Mail_GUID': 'owner'}},
            [record], [self.summary([self.candidate(), self.candidate(v.md5hex(b'another'))])])
        self.assertIsNone(checksum)

    def test_rfc2231_alternative_preserves_decoded_unicode_and_spaces(self):
        part = BytesParser(policy=policy.default).parsebytes(
            b'Content-Disposition: attachment; filename=first.txt\r\n'
            b"Content-Type: text/plain; name*=UTF-8''%20caf%C3%A9.txt%20\r\n\r\n")
        self.assertEqual({'first.txt', ' café.txt '}, set(v.original_mime_filenames(part)))

    def test_content_type_alternative_is_known_even_with_an_empty_disposition_parameter(self):
        part = BytesParser(policy=policy.default).parsebytes(
            b'Content-Disposition: attachment; filename=""\r\nContent-Type: text/plain; name=known.txt\r\n\r\n')
        self.assertEqual({'known.txt'}, v.source_attachment_filenames(
            {'original_filename': v.original_mime_filename(part), 'original_filenames': v.original_mime_filenames(part)}))

    def test_filename_matching_does_not_invent_whitespace_or_case_aliases(self):
        candidate = dict(self.candidate(), original_filenames=[' A  B.txt ', 'TYPE.txt'])
        for name in ('A B.txt', 'type.txt', ' A B.txt '):
            report = v.Report('wrong-name.pdf')
            v.validate_attachment_records([self.record(name)], self.summary([candidate]), report)
            self.assertIn('ATTACHMENT_XMP_FILENAME_MATCH_SOURCE', failed_codes(report))

    def test_mail_candidate_recognition_considers_both_original_filename_parameters(self):
        raw = (b'From: outer@example.org\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n'
               b'--b\r\nContent-Type: application/octet-stream; name=original.eml\r\n'
               b'Content-Disposition: attachment; filename=payload.bin\r\n\r\n'
               b'Subject: Nested email\r\n\r\nNested body\r\n--b--\r\n')
        summary = v.summarize_source_email([dict(afrelationship='Source', filename='outer.eml', bytes=raw)])
        self.assertTrue(summary['uninspected_email_candidates'])
        self.assertEqual(['original.eml', 'payload.bin'], summary['uninspected_email_candidates'][0]['filenames'])
