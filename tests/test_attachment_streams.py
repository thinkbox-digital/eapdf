"""Table 19 stored-byte consistency applies without BodyRendering or source."""
import hashlib
import json
import os
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes


class AttachmentStreamTests(unittest.TestCase):
    def record(self, **changes):
        return dict({'Filename': 'original.txt', 'SizeInBytes': '7',
                     'Content-Type': 'text/plain', 'Mail_GUID': 'owner'}, **changes)

    def payload(self, **changes):
        return dict({'filename': 'original.txt', 'ufilename': 'original.txt',
                     'afrelationship': 'Data', 'size': 7, 'md5': v.md5hex(b'payload')}, **changes)

    def check(self, records, files, **options):
        report = v.Report('native-consistency.pdf')
        associations = v.check_attachment_stream_consistency(records, files, report, **options)
        return report, associations

    def test_wrong_size_or_checksum_does_not_erase_a_unique_name_association(self):
        for fields in ({'SizeInBytes': '1'}, {'CheckSum': '0' * 32}, {'SizeInBytes': '9' * 5000}):
            record = self.record(**fields)
            file = self.payload()
            with self.subTest(fields=tuple(fields)):
                self.assertIs(record, v.matching_attachment_for_embedded(file, [record]))
                report, associations = self.check([record], [file])
                self.assertEqual({'ATTACHMENT_XMP_CHECKSUM_MATCH' if 'CheckSum' in fields
                                  else 'ATTACHMENT_XMP_SIZE_MATCH'}, failed_codes(report))
                self.assertIs(record, associations[id(file)])

    def test_zero_padding_optional_checksum_and_empty_placeholders_remain_legal(self):
        for record, file in ((self.record(SizeInBytes='+' + '0' * 5000 + '7'), self.payload()),
                             (self.record(CheckSum=v.md5hex(b'payload').upper()), self.payload(ufilename='stored.txt')),
                             (self.record(SizeInBytes='0'), self.payload(size=0, md5=v.md5hex(b'')))):
            with self.subTest(size=file['size']):
                report, associations = self.check([record], [file])
                self.assertFalse(failed_codes(report))
                self.assertIn(id(file), associations)
                self.assertNotIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_size_or_media_type_cannot_select_an_ambiguous_record(self):
        records = [self.record(), self.record(SizeInBytes='1', **{'Content-Type': 'application/pdf'})]
        report, associations = self.check(records, [self.payload()])
        self.assertFalse(associations)
        self.assertEqual({'ATTACHMENT_XMP_SIZE_MATCH'}, failed_codes(report))
        self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_payload_checksum_takes_precedence_over_an_unrelated_filename(self):
        checksum = v.md5hex(b'payload')
        records = [self.record(CheckSum='0' * 32), self.record(Filename='different.txt', CheckSum=checksum)]
        self.assertIs(records[1], v.matching_attachment_for_embedded(self.payload(), records))

    def test_case_difference_without_payload_evidence_is_unavailable(self):
        report, associations = self.check([self.record()], [self.payload(ufilename='ORIGINAL.txt')])
        self.assertFalse(associations)
        self.assertFalse(failed_codes(report))
        self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_multiple_distinct_native_payloads_are_unavailable_not_false_mismatches(self):
        files = [self.payload(), self.payload(md5=v.md5hex(b'another'))]
        report, associations = self.check([self.record()], files)
        self.assertFalse(associations)
        self.assertFalse(failed_codes(report))
        self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)
        files[1] = self.payload()
        report, associations = self.check([self.record()], files)
        self.assertEqual(2, len(associations))
        self.assertNotIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_equal_bytes_with_conflicting_media_cannot_prove_a_unique_association(self):
        files = [self.payload(subtype='text/plain'), self.payload(subtype='application/pdf')]
        report, associations = self.check([self.record()], files)
        self.assertFalse(associations)
        self.assertFalse(failed_codes(report))
        self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_unique_name_can_distinguish_records_with_identical_payload_checksums(self):
        checksum = v.md5hex(b'payload')
        records = [self.record(CheckSum=checksum), self.record(Filename='another.txt', CheckSum=checksum)]
        self.assertIs(records[0], v.matching_attachment_for_embedded(self.payload(), records))

    def test_unknown_observations_and_unknown_alias_are_unavailable(self):
        for file in (self.payload(size=None), self.payload(md5=''), self.payload(ufilename='stored.txt')):
            with self.subTest(file=file):
                report, associations = self.check([self.record()], [file])
                self.assertFalse(associations)
                self.assertFalse(failed_codes(report))
                self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_source_files_are_excluded_and_absence_does_not_invent_required_native_copy(self):
        for files in ([], [self.payload(afrelationship='Source')]):
            report, associations = self.check([self.record(SizeInBytes='1')], files)
            self.assertFalse(associations)
            self.assertTrue(all(item['level']=='N/A' for item in report.items))

    def test_unique_retained_source_can_associate_a_renamed_native_payload(self):
        records = [dict(Mail_GUID='owner', messageid='id', SizeInBytes='100')]
        sources = [dict(message_id='id', size_in_bytes=100, subject='', sent_norm='', raw_from='',
                        source_sha256='source-a', mime_attachments=[dict(filename='original.txt', size_in_bytes=7,
                        checksum=v.md5hex(b'payload'), content_type='text/plain')])]
        file = self.payload(ufilename='stored.txt')
        report, associations = self.check([self.record()], [file], email_records=records, source_summaries=sources)
        self.assertIn(id(file), associations)
        self.assertNotIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_nonbody_attachment_bytes_are_checked_in_the_actual_pdf_pipeline(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-streams'
        for name, expected in (('valid', set()), ('wrong-size', {'ATTACHMENT_XMP_SIZE_MATCH'}),
                               ('wrong-checksum', {'ATTACHMENT_XMP_CHECKSUM_MATCH'})):
            with self.subTest(file=name), pikepdf.open(directory / (name + '.pdf')) as pdf:
                report = v.Report(name)
                root, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), report)
                v.check_additional_rule_coverage(pdf, root, v.read_metadata_stream(pdf),
                    v.get_embedded_files(pdf), v.get_af_specs(pdf), 'si', 4, report)
                # Frozen creator operands also violate EA-PDF 9.7 p75;
                # retain those bytes and require both independent findings.
                self.assertEqual(expected | {'CUSTOM_TAG_OPERAND_MATCH'}, failed_codes(report))
                annotations = [row for row in report.items if row['code'] == 'ATTACHMENT_ANNOTATIONS_PRESENT']
                self.assertEqual(['N/A'], [row['level'] for row in annotations])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'), 'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_nonbody_attachment_stream_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-streams'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(case['expected_status'], result['summary']['status'])
                self.assertEqual(set(case['expected_failures']), {row['code'] for row in result['items'] if row['level'] == 'FAIL'})
                for code in case['required_unavailable_codes']:
                    self.assertTrue(any(row['code'] == code and row['level'] == 'WARN' for row in result['items']))


if __name__ == '__main__':
    unittest.main()
