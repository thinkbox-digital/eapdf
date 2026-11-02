"""MIME parser recovery must not fabricate parameter equality."""
import hashlib
import json
import os
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, email_metadata, validator as v, failed_codes
from test_rule_refinement_evidence import owned_body_document


class MediaParameterPreservationTests(unittest.TestCase):
    def message(self, content_type, attachment=False):
        headers='Message-ID: <media@example.org>\r\n'
        if content_type is not None:headers+='Content-Type: '+content_type+'\r\n'
        if attachment:headers+='Content-Disposition: attachment; filename=file.txt\r\n'
        raw=(headers+'\r\npayload').encode('utf-8')
        return v.BytesParser(policy=v.policy.default).parsebytes(raw),raw

    def test_recovery_cannot_equate_malformed_and_repaired_parameters(self):
        pairs=[('text/plain; ; charset=utf-8','text/plain; charset=utf-8'),
               ('text/plain; name=x; =ignored','text/plain; name=x'),
               ('text/plain; name=A B.txt','text/plain; name="A B.txt"'),
               ('text/plain; name=x; name=y','text/plain; name=y; name=x')]
        for malformed,repaired in pairs:
            with self.subTest(value=malformed):
                self.assertNotEqual(v.media_type_signature(malformed),v.media_type_signature(repaired))

    def test_matching_malformed_text_remains_permitted(self):
        for content_type in ('text/plain; ; charset=utf-8','text/plain; name=x; =ignored','broken source header'):
            with self.subTest(content_type=content_type):
                self.assertEqual(v.media_type_signature(content_type),v.media_type_signature(content_type))
                record={'Filename':'file.txt','SizeInBytes':'7','Content-Type':content_type,'Mail_GUID':'owner'}
                report=v.Report('schema.pdf');v.validate_attachment_records([record],[],report)
                self.assertFalse(failed_codes(report))

    def test_legal_equivalent_forms_still_compare(self):
        for first,second in [
            ('TEXT/PLAIN; charset="UTF-8"; name="A  B"','text/plain; name="A  B"; charset=utf-8'),
            ('text/plain;\r\n\tcharset=UTF-8','text/plain; charset=utf-8'),
            ("text/plain; name*=UTF-8''r%C3%A9sum%C3%A9.txt",'text/plain; name="résumé.txt"'),
            ("text/plain; name*0*=utf-8''r%C3; name*1*=%A9.txt",'text/plain; name="ré.txt"'),
            ('text/plain;','text/plain'),
        ]:
            with self.subTest(first=first):
                self.assertEqual(v.media_type_signature(first),v.media_type_signature(second))

    def test_source_attachment_headers_retain_original_parameter_text(self):
        for content_type in ('text/plain; name="A  B.txt"','text/plain; name=x; =ignored',
                             'text/plain; ; charset=utf-8','text/plain; name="résumé.txt"'):
            message,raw=self.message(content_type,attachment=True)
            with self.subTest(content_type=content_type):
                self.assertEqual(content_type,v.original_mime_content_type(message))
                self.assertEqual(content_type,v.summarize_email_attachments(message,raw)[0]['content_type'])

    def test_source_attachment_comparison_detects_changed_spaces_or_repairs(self):
        for original,repaired in [('text/plain; name="A  B.txt"','text/plain; name="A B.txt"'),
                                  ('text/plain; name=x; =ignored','text/plain; name=x')]:
            message,raw=self.message(original,attachment=True)
            source=dict(mail_guid='owner',message_id='<media@example.org>',mime_attachments=v.summarize_email_attachments(message,raw))
            for value,correct in ((original,True),(repaired,False)):
                with self.subTest(value=value):
                    record={'Filename':'file.txt','SizeInBytes':'7','Content-Type':value,'Mail_GUID':'owner'}
                    report=v.Report('source.pdf');v.validate_attachment_records([record],source,report)
                    self.assertEqual(not correct,'ATTACHMENT_XMP_CONTENTTYPE_MATCH' in failed_codes(report))

    def test_source_body_headers_retain_malformed_parameters(self):
        for content_type in ('text/plain; name=x; =ignored','text/plain; ; charset=utf-8'):
            message,_=self.message(content_type)
            self.assertEqual({content_type},v.source_body_media_types(message))

    def test_body_source_comparison_does_not_accept_parser_repair(self):
        original='text/plain; name=x; =ignored'
        _,raw=self.message(original)
        source={'afrelationship':'Source','subtype':'message/rfc822','bytes':raw,
                'objgen':(10,0),'filename':'source.eml'}
        metadata=email_metadata([{'Mail_GUID':'owner','messageid':'&lt;media@example.org&gt;'}])
        for actual,correct in ((original,True),('text/plain; name=x',False)):
            with self.subTest(actual=actual),owned_body_document(subtype=actual,guid='owner')[0] as pdf:
                report=v.Report('body.pdf')
                v.check_body_media_types(pdf,metadata,[source],report)
                self.assertEqual(not correct,'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE' in failed_codes(report))

    def test_missing_content_type_retains_the_mime_default(self):
        message,_=self.message(None)
        self.assertEqual('text/plain',v.original_mime_content_type(message))
        for content_type in (None,'','\r\n\t'):
            message,_=self.message(content_type)
            self.assertEqual({'text/plain'},v.source_body_media_types(message))

    def test_pdf_corpus(self):
        base=ROOT/'tests/fixtures/eapdf/validator-media-parameters'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=base/case['file'];self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                report=v.validate(str(path))
                self.assertEqual(set(case['expected_failures']),failed_codes(report))
                self.assertEqual(case['expected_status'],report.summary()['status'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for independent PDF/A checks')
    def test_frozen_pdf_corpus(self):
        base=ROOT/'tests/fixtures/eapdf/validator-media-parameters'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result=v.validate_one(str(base/case['file']),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{r['code'] for r in result['items'] if r['level']=='FAIL'})


if __name__=='__main__':unittest.main()
