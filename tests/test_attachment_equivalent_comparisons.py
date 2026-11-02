"""Equivalent metadata permits byte comparisons without assigning an owner."""
import hashlib
import itertools
import json
import os
import unittest

from test_preservation_safety import ROOT, validator as v, failed_codes


class EquivalentAttachmentComparisonTests(unittest.TestCase):
    def record(self, owner='A', **changes):
        return dict({'Filename':'same.txt','SizeInBytes':'7','Content-Type':'text/plain',
                     'CheckSum':v.md5hex(b'payload'),'Mail_GUID':owner}, **changes)

    def file(self, **changes):
        return dict({'filename':'same.txt','ufilename':'same.txt','size':7,
                     'md5':v.md5hex(b'payload'),'subtype':'text/plain','afrelationship':'Data'}, **changes)

    def check(self, records, files):
        report=v.Report('equivalent.pdf')
        return report,v.check_attachment_stream_consistency(records,files,report)

    def test_equivalent_owner_records_compare_in_every_record_and_file_order(self):
        records=[self.record('A'),self.record('B')]
        files=[self.file(objgen=(1,0)),self.file(ufilename='stored-safe.txt',objgen=(2,0))]
        for ordered_records,ordered_files in itertools.product(itertools.permutations(records),itertools.permutations(files)):
            report,associations=self.check(ordered_records,ordered_files)
            self.assertFalse(failed_codes(report))
            self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)
            self.assertEqual(2,len(associations))
            comparisons=next(r for r in report.items if r['code']=='ATTACHMENT_XMP_SIZE_MATCH')['details']['comparisons']
            self.assertEqual(2,len(comparisons))
            self.assertTrue(all(r['unique_occurrence_identity'] is False for r in comparisons))

    def test_equivalent_projection_does_not_choose_an_owner_or_occurrence(self):
        records=[self.record('A'),self.record('B')];file=self.file()
        self.assertIsNone(v.matching_attachment_for_embedded(file,records))
        report,associations=self.check(records,[file])
        self.assertEqual({'Content-Type':'text/plain'},associations[id(file)])
        self.assertFalse(failed_codes(report))

    def test_normalized_size_checksum_and_media_forms_are_equivalent(self):
        records=[self.record('A',**{'Content-Type':'text/plain; charset=UTF-8; name="A  B"'}),
                 self.record('B',SizeInBytes='+'+'0'*5000+'7',CheckSum=v.md5hex(b'payload').upper(),
                             **{'Content-Type':'TEXT/PLAIN; name="A  B"; charset=utf-8'})]
        file=self.file(reconstructed_media_type='text/plain; charset=utf-8; name="A  B"')
        report,associations=self.check(records,[file])
        self.assertIn(id(file),associations)
        self.assertFalse(failed_codes(report))
        self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_wrong_values_in_every_equivalent_record_still_fail(self):
        for changes in ({'SizeInBytes':'8'},{'CheckSum':'0'*32},{'SizeInBytes':'9'*5000}):
            with self.subTest(changes=tuple(changes)):
                records=[self.record(owner,**changes) for owner in ('A','B')]
                report,associations=self.check(records,[self.file()])
                self.assertEqual({'ATTACHMENT_XMP_CHECKSUM_MATCH' if 'CheckSum' in changes
                                  else 'ATTACHMENT_XMP_SIZE_MATCH'},failed_codes(report))
                self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)
                self.assertEqual(1,len(associations))

    def test_differing_expectations_do_not_select_an_owner_but_can_disprove_fields(self):
        for changes in ({'SizeInBytes':'8'},{'Content-Type':'application/pdf'},
                        {'Content-Type':'text/plain; name="A  B"'},{'SizeInBytes':v.XmpUri('7')}):
            records=[self.record('A'),self.record('B',**changes)]
            for order in itertools.permutations(records):
                with self.subTest(changes=changes):
                    report,associations=self.check(order,[self.file()])
                    self.assertFalse(associations)
                    expected=({'ATTACHMENT_XMP_SIZE_MATCH'} if changes.get('SizeInBytes')=='8' else set())
                    if changes.get('Content-Type'):
                        expected.add('EMBEDDED_MEDIA_TYPE_MATCH_XMP')
                    self.assertEqual(expected,failed_codes(report))
                    self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_distinct_actual_payloads_remain_unavailable(self):
        records=[self.record('A'),self.record('B')]
        for r in records:r.pop('CheckSum')
        report,associations=self.check(records,[self.file(),self.file(md5=v.md5hex(b'another'))])
        self.assertFalse(associations)
        self.assertFalse(failed_codes(report))
        self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_no_checksum_and_shared_native_bytes_are_permitted(self):
        records=[self.record('A'),self.record('B')]
        for r in records:r.pop('CheckSum')
        file=self.file()
        report,associations=self.check(records,[file])
        self.assertEqual({'Content-Type':'text/plain'},associations[id(file)])
        self.assertFalse(failed_codes(report))
        self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_actual_pdf_corpus(self):
        base=ROOT/'tests/fixtures/eapdf/validator-attachment-equivalence'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=base/case['file']
                self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                report=v.validate(str(path))
                self.assertEqual(set(case['expected_failures']),failed_codes(report))
                self.assertEqual(case['expected_status'],report.summary()['status'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_pdf_corpus(self):
        base=ROOT/'tests/fixtures/eapdf/validator-attachment-equivalence'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result=v.validate_one(str(base/case['file']),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{r['code'] for r in result['items'] if r['level']=='FAIL'})


if __name__=='__main__':unittest.main()
