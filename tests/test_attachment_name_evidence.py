"""Table 19 filename equality is literal evidence; Alternative is not a native copy."""
import hashlib
import json
import os
import unittest
from email import policy
from email.parser import BytesParser

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes


class AttachmentNameEvidenceTests(unittest.TestCase):
    def record(self, name='A B.txt'):
        return {'Filename':name,'SizeInBytes':'7','Content-Type':'text/plain','Mail_GUID':'owner'}

    def parsed_file(self,pdf,name):
        stream=pdf.make_stream(b'payload')
        stream.Type=pikepdf.Name.EmbeddedFile
        stream.Subtype=pikepdf.Name('/text/plain')
        stream.Params=pikepdf.Dictionary(Size=7,CheckSum=pikepdf.String(hashlib.md5(b'payload').digest()))
        spec=pdf.make_indirect(pikepdf.Dictionary(F=pikepdf.String(name),UF=pikepdf.String(name),
            EF=pikepdf.Dictionary(F=stream,UF=stream),AFRelationship=pikepdf.Name.Data))
        return v.parse_filespec('unrelated-tree-label',spec)

    def test_parsed_pdf_filenames_preserve_significant_spaces_for_association(self):
        for name in ('A  B.txt',' A B.txt ','\tA B.txt'):
            with self.subTest(name=name),pikepdf.Pdf.new() as pdf:
                file=self.parsed_file(pdf,name)
                record=self.record(name)
                self.assertIs(record,v.matching_attachment_for_embedded(file,[record]))
                self.assertTrue(v.attachment_record_matches_annotation(record,{'file':file}))
                collapsed=self.record('A B.txt')
                self.assertIsNone(v.matching_attachment_for_embedded(file,[collapsed]))
                self.assertFalse(v.attachment_record_matches_annotation(collapsed,{'file':file}))

    def test_filename_cleanup_cannot_establish_an_original_native_association(self):
        with pikepdf.Pdf.new() as pdf:
            file=self.parsed_file(pdf,'A B.txt')
            for name in ('folder/A B.txt','folder\\A B.txt',' A B.txt ','A B.txt\x00'):
                with self.subTest(name=name):
                    record=self.record(name)
                    self.assertIsNone(v.matching_attachment_for_embedded(file,[record]))
                    self.assertFalse(v.attachment_record_matches_annotation(record,{'file':file}))
                    report=v.Report('unknown-alias.pdf')
                    v.check_attachment_stream_consistency([record],[file],report)
                    self.assertFalse(failed_codes(report))
                    self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_payload_evidence_still_supports_permitted_different_stored_names(self):
        with pikepdf.Pdf.new() as pdf:
            file=self.parsed_file(pdf,'A B.txt')
            record=dict(self.record('folder/A  B.txt'),CheckSum=v.md5hex(b'payload'))
            self.assertIs(record,v.matching_attachment_for_embedded(file,[record]))
            self.assertTrue(v.attachment_record_matches_annotation(record,{'file':file}))
            no_checksum=self.record('folder/A  B.txt')
            self.assertTrue(v.attachment_record_matches_annotation(no_checksum,{'file':file},v.md5hex(b'payload')))

    def source(self,name):
        raw=(b'Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n'
             b'Content-Type: text/plain\r\nContent-Disposition: attachment; filename="'+name.encode()+
             b'"\r\n\r\npayload\r\n--parts--\r\n')
        parts=v.summarize_email_attachments(BytesParser(policy=policy.default).parsebytes(raw),raw)
        return dict(message_id='id',mail_guid='owner',size_in_bytes=100,subject='',sent_norm='',raw_from='',
                    source_sha256='source-a',mime_attachments=parts)

    def test_original_mime_filename_retains_quoted_spaces_and_parameter_alternatives(self):
        for header,expected in (
            ('Content-Disposition: attachment; filename=" A  B.txt "', ' A  B.txt '),
            ("Content-Disposition: attachment; filename*=UTF-8''%20A%20%20B.txt%20", ' A  B.txt '),
            ('Content-Type: application/octet-stream; name=" A  B.txt "', ' A  B.txt '),
            ('Content-Type: application/octet-stream', ''),
        ):
            with self.subTest(header=header):
                part=BytesParser(policy=policy.default).parsebytes((header+'\r\n\r\n').encode())
                self.assertEqual(expected,v.original_mime_filename(part))

    def test_source_hints_do_not_collapse_distinct_original_filenames(self):
        owners=[dict(Mail_GUID='owner',messageid='id',SizeInBytes='100')]
        body={'identity':{'Mail_GUID':'owner'}}
        for original in ('A  B.txt',' A B.txt '):
            source=self.source(original)
            with self.subTest(original=original):
                self.assertIsNone(v.attachment_source_checksum(self.record(),body,owners,[source]))
                self.assertEqual(v.md5hex(b'payload'),v.attachment_source_checksum(self.record(original),body,owners,[source]))

    def test_source_filename_comparison_preserves_actual_original_text(self):
        for original in ('A  B.txt',' A B.txt '):
            with self.subTest(original=original):
                source=self.source(original)
                for name,valid in ((original,True),('A B.txt',False)):
                    report=v.Report('source-names.pdf')
                    v.validate_attachment_records([self.record(name)],[source],report)
                self.assertEqual(not valid,'ATTACHMENT_XMP_FILENAME_MATCH_SOURCE' in failed_codes(report))

    def test_alternative_annotation_cannot_stand_in_for_a_native_attachment(self):
        with pikepdf.Pdf.new() as pdf:
            file=self.parsed_file(pdf,'A B.txt')
            record=dict(self.record(),CheckSum=v.md5hex(b'payload'))
            self.assertTrue(v.attachment_record_matches_annotation(record,{'file':file}))
            file['afrelationship']='Alternative'
            self.assertFalse(v.attachment_record_matches_annotation(record,{'file':file}))

    def test_actual_pdf_pipeline_reports_the_reviewed_filename_and_role_outcomes(self):
        base=ROOT/'tests/fixtures/eapdf/validator-attachment-evidence'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                report=v.validate(str(base/case['file']))
                self.assertEqual(set(case['expected_failures']),failed_codes(report))
                self.assertEqual(case['expected_status'],report.summary()['status'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_evidence_corpus(self):
        base=ROOT/'tests/fixtures/eapdf/validator-attachment-evidence'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=base/case['file']
                self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                result=v.validate_one(str(path),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{item['code'] for item in result['items'] if item['level']=='FAIL'})
                for code in case['required_unavailable_codes']:
                    self.assertTrue(any(item['code']==code and item['level']=='WARN' for item in result['items']))


if __name__=='__main__':unittest.main()
