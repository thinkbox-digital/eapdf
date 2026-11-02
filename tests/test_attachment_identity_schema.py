"""Supplied conditional Table 19 identities keep their required XMP type."""
import hashlib
import json
import os
import unittest

from test_preservation_safety import ROOT, validator as v, failed_codes


class AttachmentIdentitySchemaTests(unittest.TestCase):
    def test_supplied_guid_type_is_checked_independently_of_message_id_presence(self):
        base = {'Filename': 'file.txt', 'SizeInBytes': '0', 'Content-Type': 'text/plain', 'Message-ID': 'id'}
        for value in (v.XmpUri('owner'), ['owner'], {'Label': 'owner'}):
            with self.subTest(value=value):
                report = v.Report('identity.pdf')
                v.validate_attachment_records([dict(base, Mail_GUID=value)], [], report)
                self.assertEqual({'ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID'}, failed_codes(report))
        for values in (base, dict(base, Mail_GUID='owner'), dict(base, Mail_GUID='urn:owner')):
            report = v.Report('identity.pdf')
            v.validate_attachment_records([values], [], report)
            self.assertFalse(failed_codes(report))

    def test_identity_representations_in_the_custom_pdf_pipeline(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-identity'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                report = v.validate(str(directory / case['file']))
                self.assertEqual(set(case['expected_failures']), failed_codes(report))
                self.assertEqual(case['expected_status'], report.summary()['status'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'), 'Set EAPDF_GENERATOR_CLASSPATH for independent PDF/A checks')
    def test_frozen_identity_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-identity'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            path = directory / case['file']
            with self.subTest(file=case['file']):
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(case['expected_status'], result['summary']['status'])
                self.assertEqual(set(case['expected_failures']), {r['code'] for r in result['items'] if r['level'] == 'FAIL'})


if __name__ == '__main__':
    unittest.main()
