"""Prove field contradictions across candidates without selecting an occurrence."""
import hashlib
import itertools
import json
import os
import unittest

from test_preservation_safety import ROOT, validator as v, failed_codes


class AmbiguousAttachmentByteTests(unittest.TestCase):
    def record(self, **changes):
        return dict({'Filename':'same.txt','SizeInBytes':'7','Content-Type':'text/plain','Mail_GUID':'owner'},**changes)

    def file(self, **changes):
        return dict({'ufilename':'same.txt','size':7,'md5':v.md5hex(b'payload'),
                     'subtype':'text/plain','afrelationship':'Data'},**changes)

    def check(self, records, files):
        report=v.Report('ambiguous.pdf')
        associations=v.check_attachment_stream_consistency(records,files,report)
        return report,associations

    def contradictions(self, report):
        return [detail for row in report.items if row['level']=='FAIL' and row['code'] in
                {'ATTACHMENT_XMP_SIZE_MATCH','ATTACHMENT_XMP_CHECKSUM_MATCH'}
                for detail in row['details']['actual']]

    def test_differing_expectations_cannot_hide_an_impossible_size_in_any_order(self):
        records=[self.record(),self.record(SizeInBytes='8')]
        files=[self.file(objgen=(1,0)),self.file(objgen=(2,0))]
        for rows,payloads in itertools.product(itertools.permutations(records),itertools.permutations(files)):
            report,associations=self.check(rows,payloads)
            self.assertEqual({'ATTACHMENT_XMP_SIZE_MATCH'},failed_codes(report))
            self.assertFalse(associations)
            self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)
            mismatch=self.contradictions(report)
            self.assertEqual(1,len(mismatch))
            self.assertEqual(['SizeInBytes'],mismatch[0]['mismatched_fields'])
            self.assertFalse(mismatch[0]['unique_occurrence_identity'])
            self.assertEqual(2,len(mismatch[0]['native_candidates']))

    def test_distinct_payloads_can_prove_size_and_checksum_failures_independently(self):
        for records,files,expected in (
            ([self.record(SizeInBytes='9')],[self.file(),self.file(size=8,md5=v.md5hex(b'longerer'))],['SizeInBytes']),
            ([self.record(CheckSum='0'*32)],[self.file(),self.file(md5=v.md5hex(b'another'))],['CheckSum']),
            ([self.record(SizeInBytes='9',CheckSum='0'*32)],[self.file(),self.file(size=8,md5=v.md5hex(b'longerer'))],['SizeInBytes','CheckSum'])):
            for order in itertools.permutations(files):
                report,associations=self.check(records,order)
                self.assertFalse(associations)
                self.assertEqual({'ATTACHMENT_XMP_SIZE_MATCH' if field=='SizeInBytes' else
                                  'ATTACHMENT_XMP_CHECKSUM_MATCH' for field in expected}, failed_codes(report))
                self.assertEqual(expected,self.contradictions(report)[0]['mismatched_fields'])
                self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_a_compatible_candidate_prevents_a_field_failure(self):
        records=[self.record(CheckSum=v.md5hex(b'payload').upper())]
        files=[self.file(),self.file(size=8,md5=v.md5hex(b'longerer'))]
        report,associations=self.check(records,files)
        self.assertFalse(failed_codes(report))
        self.assertFalse(associations)
        self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_unreadable_observation_blocks_only_its_own_field(self):
        report,_=self.check([self.record(SizeInBytes='9',CheckSum='0'*32)],
                            [self.file(),self.file(size=8,md5='')])
        self.assertEqual(['SizeInBytes'],self.contradictions(report)[0]['mismatched_fields'])
        report,_=self.check([self.record(SizeInBytes='9',CheckSum='0'*32)],
                            [self.file(),self.file(size=None,md5=v.md5hex(b'another'))])
        self.assertEqual(['CheckSum'],self.contradictions(report)[0]['mismatched_fields'])
        for changes in ({'size':None,'md5':''},{'size':None,'md5':'bad'}):
            report,_=self.check([self.record(SizeInBytes='9',CheckSum='0'*32)],[self.file(),self.file(**changes)])
            self.assertFalse(failed_codes(report))

    def test_schema_errors_are_not_fabricated_as_byte_mismatches(self):
        for changes in ({'SizeInBytes':v.XmpUri('9')},{'SizeInBytes':'-9'},{'CheckSum':v.XmpUri('0'*32)},
                        {'CheckSum':'bad'},{'SizeInBytes':True}):
            records=[self.record(**changes),self.record()]
            report,_=self.check(records,[self.file()])
            self.assertFalse(failed_codes(report))
            self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_huge_integer_values_and_padding_are_compared_without_conversion_limits(self):
        files=[self.file(),self.file(size=8,md5=v.md5hex(b'longerer'))]
        report,_=self.check([self.record(SizeInBytes='9'*5000)],files)
        self.assertEqual(['SizeInBytes'],self.contradictions(report)[0]['mismatched_fields'])
        report,_=self.check([self.record(SizeInBytes='+'+'0'*5000+'7')],files)
        self.assertFalse(failed_codes(report))

    def test_absence_aliases_alternatives_and_sources_do_not_invent_candidates(self):
        for files in ([],[self.file(ufilename='alias.txt')],[self.file(afrelationship='Source')],
                      [self.file(afrelationship='Alternative')]):
            report,associations=self.check([self.record(SizeInBytes='9')],files)
            self.assertFalse(failed_codes(report))
            self.assertFalse(associations)

    def test_optional_checksum_and_zero_length_candidate_remain_permitted(self):
        files=[self.file(),self.file(size=0,bytes=b'',md5=v.md5hex(b''))]
        report,associations=self.check([self.record(SizeInBytes='0')],files)
        self.assertFalse(failed_codes(report))
        self.assertFalse(associations)
        self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_actual_pdf_corpus(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-ambiguous-bytes'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=directory/case['file']
                self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                report=v.validate(str(path))
                self.assertEqual(case['expected_status'],report.summary()['status'])
                self.assertEqual(set(case['expected_failures']),failed_codes(report))

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_pdf_corpus(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-ambiguous-bytes'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result=v.validate_one(str(directory/case['file']),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{r['code'] for r in result['items'] if r['level']=='FAIL'})


if __name__=='__main__':unittest.main()
