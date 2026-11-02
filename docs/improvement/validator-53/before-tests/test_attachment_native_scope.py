"""Native attachment comparisons respect collection and Alternative file roles."""
import json
import os
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes


class NativeAttachmentScopeTests(unittest.TestCase):
    def test_alternative_bytes_cannot_be_used_as_the_original_native_payload(self):
        record = {'Filename': 'same.txt', 'SizeInBytes': '7', 'Content-Type': 'text/plain', 'Mail_GUID': 'owner'}
        native = dict(filename='same.txt', ufilename='same.txt', size=7, md5=v.md5hex(b'payload'), afrelationship='Data')
        alternate = dict(native, size=13, md5=v.md5hex(b'converted PDF'), afrelationship='Alternative')
        for files in ([native, alternate], [alternate]):
            report = v.Report('roles.pdf')
            associations = v.check_attachment_stream_consistency([record], files, report)
            self.assertNotIn(id(alternate), associations)
            self.assertNotIn('VALIDATION_INCOMPLETE', report.seen_codes)
            self.assertFalse(failed_codes(report))
            if native in files:
                self.assertIs(record, associations[id(native)])

    def test_collection_and_alternative_name_collisions_keep_their_distinct_roles(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-scope'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']), pikepdf.open(directory / case['file']) as pdf:
                report = v.Report(str(directory / case['file']))
                xmp = v.read_metadata_stream(pdf)
                root, _, _ = v.parse_xmp(xmp, report)
                profile = v.first_xmp_value(root, v.NS_PDFMAILID, 'conformance')
                v.check_additional_rule_coverage(pdf, root, xmp, v.get_embedded_files(pdf), v.get_af_specs(pdf), profile, 4, report)
                # This entry point checks the parent, not contained PDF bytes.
                self.assertEqual(set(case['expected_failures']) - {'COLLECTION_CHILD_VALIDATION'},
                                 failed_codes(report), report.items)
                self.assertNotIn('VALIDATION_INCOMPLETE', report.seen_codes)
                if profile in ('c', 'ci'):
                    context = v.build_validation_context(pdf, root, profile, 4, v.get_embedded_files(pdf), v.get_af_specs(pdf))
                    self.assertIn('owning contained email', v.na_reason_for_code('ATTACHMENT_XMP_MATCH', context))

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'), 'Set EAPDF_GENERATOR_CLASSPATH for independent PDF/A checks')
    def test_frozen_role_corpus_including_recursive_collection_checks(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-scope'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result = v.validate_one(str(directory / case['file']), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual('PASS', result['summary']['status'])
                self.assertFalse([row for row in result['items'] if row['level'] == 'FAIL'])


if __name__ == '__main__':
    unittest.main()
