"""Table 19 Bags must compare independently of metadata/source ordering."""
import hashlib
import itertools
import json
import os
import unittest

from test_preservation_safety import ROOT, validator as v, failed_codes


class AttachmentOccurrenceTests(unittest.TestCase):
    def record(self, data=b'a', **changes):
        return dict({'Filename': 'same.txt', 'SizeInBytes': str(len(data)),
                     'Content-Type': 'text/plain', 'Mail_GUID': 'owner'}, **changes)

    def source(self, payloads=(b'a', b'b'), **changes):
        return dict(mail_guid='owner', message_id='id', mime_attachments=[
            dict(filename='same.txt', size_in_bytes=len(data), checksum=v.md5hex(data),
                 content_type='text/plain') for data in payloads], **changes)

    def check(self, records, sources):
        report=v.Report('occurrences.pdf')
        v.validate_attachment_records(records, sources, report)
        return report

    def test_optional_checksum_cannot_steal_a_constrained_source_occurrence(self):
        records=[self.record(), self.record(CheckSum=v.md5hex(b'a').upper())]
        for order in itertools.permutations(records):
            for payloads in ((b'a', b'b'), (b'b', b'a')):
                with self.subTest(order=[r.get('CheckSum') for r in order], payloads=payloads):
                    self.assertFalse(failed_codes(self.check(order, self.source(payloads))))

    def test_placeholder_cannot_steal_a_retained_attachment_occurrence(self):
        for checksum in (None, v.md5hex(b'')):
            placeholder=self.record(SizeInBytes='0')
            if checksum is not None:
                placeholder['CheckSum']=checksum
            records=[placeholder, self.record(CheckSum=v.md5hex(b'a'))]
            for order in itertools.permutations(records):
                for payloads in ((b'a', b'longer'), (b'longer', b'a')):
                    with self.subTest(checksum=checksum, payloads=payloads, order=order):
                        self.assertFalse(failed_codes(self.check(order, self.source(payloads))))

    def test_several_displaced_records_still_find_a_complete_assignment(self):
        records=[self.record(SizeInBytes='0'), self.record(), self.record(CheckSum=v.md5hex(b'a'))]
        for order in itertools.permutations(records):
            for payloads in itertools.permutations((b'a', b'b', b'longer')):
                self.assertFalse(failed_codes(self.check(order, self.source(payloads))))

    def test_source_occurrences_cannot_be_reused(self):
        for records in ([self.record(), self.record()],
                        [self.record(CheckSum=v.md5hex(b'a'))]*2):
            report=self.check(records, self.source((b'a',)))
            findings=[r for r in report.items if r['code']=='ATTACHMENT_XMP_FILENAME_MATCH_SOURCE']
            self.assertEqual(['PASS', 'WARN'], [r['level'] for r in findings])
            self.assertEqual('unavailable', findings[1]['details']['assessment'])

    def test_source_only_checks_separate_known_type_from_unavailable_byte_and_owner_evidence(self):
        for changes in ({'CheckSum': '0'*32}, {'SizeInBytes': '2'},
                        {'Content-Type': 'application/pdf'}, {'Mail_GUID': 'other'},
                        {'Mail_GUID': '', 'Message-ID': 'wrong'}):
            for order in itertools.permutations([self.record(), self.record(**changes)]):
                with self.subTest(changes=changes):
                    report=self.check(order, self.source())
                    if 'Content-Type' in changes:
                        self.assertIn('ATTACHMENT_XMP_CONTENTTYPE_MATCH', failed_codes(report))
                    else:
                        self.assertFalse(failed_codes(report))
                        # Original metadata alone cannot prove retained native bytes.
                        self.assertNotIn('PASS', {r['level'] for r in report.items
                            if r['code']=='ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED'})

    def test_equivalent_assignments_do_not_claim_unique_occurrence_identity(self):
        report=self.check([self.record(), self.record()], self.source((b'a', b'a')))
        self.assertFalse(failed_codes(report))
        comparisons=[r for r in report.items if r['code']=='ATTACHMENT_XMP_FILENAME_MATCH_SOURCE']
        self.assertEqual(2, len(comparisons))
        self.assertTrue(all(r['level']=='PASS' for r in comparisons))
        self.assertTrue(all(r['details']['unique_occurrence_identity'] is False for r in comparisons))

    def test_generated_names_for_unnamed_source_attachments_remain_allowed(self):
        source=self.source()
        for item in source['mime_attachments']:
            item['filename']=''
        records=[self.record(Filename='generated-1.txt'), self.record(Filename='generated-2.txt', CheckSum=v.md5hex(b'a'))]
        for order in itertools.permutations(records):
            self.assertFalse(failed_codes(self.check(order, source)))

    def test_pdf_occurrence_corpus(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-attachment-occurrences'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=directory/case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                report=v.validate(str(path))
                self.assertEqual(set(case['expected_failures']), failed_codes(report))
                self.assertEqual(case['expected_status'], report.summary()['status'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'), 'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_pdf_occurrences_with_independent_pdfa(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-attachment-occurrences'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result=v.validate_one(str(directory/case['file']), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(case['expected_status'], result['summary']['status'])
                self.assertEqual(set(case['expected_failures']), {r['code'] for r in result['items'] if r['level']=='FAIL'})


if __name__=='__main__': unittest.main()
