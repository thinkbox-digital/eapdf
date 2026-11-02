"""Missing native association is unavailable parameter evidence, not agreement."""
import hashlib
import json
import os
import unittest

from test_preservation_safety import ROOT, validator as v, failed_codes

MEDIA='EMBEDDED_MEDIA_TYPE_MATCH_XMP'
PARAMS='SOURCE_MEDIATYPE_PARAMETERS_MATCH'


class UnmatchedAttachmentParameterTests(unittest.TestCase):
    def record(self, **changes):
        return dict({'Filename':'original.txt','SizeInBytes':'7','Content-Type':'text/plain; note=x','Mail_GUID':'owner'},**changes)

    def file(self, **changes):
        return dict({'ufilename':'stored.txt','size':7,'md5':v.md5hex(b'payload'),
                     'reconstructed_media_type':'text/plain;note=x','afrelationship':'Data'},**changes)

    def check(self, records, files):
        report=v.Report('unmatched.pdf');ambiguities=[]
        associations=v.check_attachment_stream_consistency(records,files,report,ambiguities=ambiguities)
        return report,associations,[r for r in ambiguities if r.get('parameter_comparison_unavailable')]

    def test_no_native_association_retains_declared_parameter_evidence(self):
        report,associations,unavailable=self.check([self.record()],[self.file()])
        self.assertFalse(failed_codes(report));self.assertFalse(associations)
        self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)
        self.assertEqual(1,len(unavailable));self.assertEqual(0,unavailable[0]['candidate_count'])
        self.assertEqual('text/plain; note=x',unavailable[0]['xmp_content_type'])

    def test_raw_malformed_and_empty_parameter_text_remains_unavailable(self):
        for value in ('text/plain; note=x; =ignored','malformed; raw=text','text/plain;'):
            report,_,unavailable=self.check([self.record(**{'Content-Type':value})],[self.file()])
            self.assertFalse(failed_codes(report));self.assertEqual(value,unavailable[0]['xmp_content_type'])

    def test_schema_invalid_fields_do_not_invent_parameter_obligations(self):
        for value in (v.XmpUri('text/plain; note=x'),['text/plain; note=x'],None,'','text/plain'):
            report,_,unavailable=self.check([self.record(**{'Content-Type':value})],[self.file()])
            self.assertFalse(failed_codes(report));self.assertFalse(unavailable)

    def test_absence_sources_and_alternatives_do_not_invent_a_native_copy_requirement(self):
        for files in ([],[self.file(afrelationship='Source')],[self.file(afrelationship='Alternative')]):
            report,associations,unavailable=self.check([self.record()],files)
            self.assertTrue(all(item['level']=='N/A' for item in report.items));self.assertFalse(associations);self.assertFalse(unavailable)

    def test_checksum_can_establish_a_permitted_alias(self):
        file=self.file()
        report,associations,unavailable=self.check([self.record(CheckSum=file['md5'])],[file])
        self.assertFalse(failed_codes(report));self.assertIn(id(file),associations);self.assertFalse(unavailable)
        self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_one_verified_record_cannot_remove_unavailable_evidence_for_another(self):
        records=[self.record(Filename='matched.txt'),self.record()]
        file=self.file(ufilename='matched.txt')
        report,associations,unavailable=self.check(records,[file])
        self.assertFalse(failed_codes(report));self.assertIn(id(file),associations)
        self.assertEqual(['original.txt'],[r['filename'] for r in unavailable])
        self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_actual_pdf_corpus_and_parameter_severity(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-unmatched-parameters'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=directory/case['file'];self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                report=v.validate(str(path));self.assertEqual(case['expected_status'],report.summary()['status'])
                self.assertEqual(set(case['expected_failures']),failed_codes(report))
                parameters=[i for i in report.items if i['code']==PARAMS]
                levels={i['level'] for i in report.items if i['code']==MEDIA}
                self.assertEqual({case['expected_media_consistency_level']},levels)
                if case['file'] in ('mixed-verified-and-unknown.pdf','mixed-failure-and-unknown.pdf',
                                    'checksum-known-alias.pdf','mixed-bare-and-unknown-parameters.pdf'):
                    self.assertEqual({'N/A'},{i['level'] for i in parameters
                                     if i.get('details',{}).get('check')=='original_parameter_fidelity'})
                if case['file']=='mixed-verified-and-unknown.pdf':
                    finding=next(i for i in report.items if i['code']==MEDIA)
                    self.assertEqual('WARN',finding['level'])
                    self.assertEqual(['same.txt'],[c['filename'] for c in finding['details']['verified_comparisons']])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_pdf_corpus(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-unmatched-parameters'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result=v.validate_one(str(directory/case['file']),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{i['code'] for i in result['items'] if i['level']=='FAIL'})
                self.assertEqual({case['expected_media_consistency_level']},{i['level'] for i in result['items'] if i['code']==MEDIA})


if __name__=='__main__':unittest.main()
