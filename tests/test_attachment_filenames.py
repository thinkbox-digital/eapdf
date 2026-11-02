"""Original Table 19 filenames need not equal native PDF file-spec names."""
import hashlib
import json
import os
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import ROOT, validator as v


class AttachmentFilenameTests(unittest.TestCase):
    def attachment(self, checksum=True):
        record = {'Filename': 'original.txt', 'SizeInBytes': '7', 'Content-Type': 'text/plain', 'Mail_GUID': 'owner'}
        if checksum:
            record['CheckSum'] = v.md5hex(b'payload')
        return record

    def annotation(self, data=b'payload'):
        return {'filename': 'stored-safe.txt', 'file': {'filename': 'stored-safe.txt',
            'ufilename': 'stored-safe.txt', 'afrelationship': 'Data', 'size': len(data),
            'size_entry': len(data), 'bytes': data, 'md5': v.md5hex(data),
            'checksum': v.md5hex(data), 'subtype': 'text/plain'}}

    def test_matching_checksum_supports_a_different_pdf_filename(self):
        self.assertTrue(v.attachment_record_matches_annotation(self.attachment(), self.annotation()))

    def test_large_legal_integer_cannot_bypass_actual_size_comparison(self):
        self.assertFalse(v.attachment_record_matches_annotation(
            dict(self.attachment(), SizeInBytes='9' * 5000), self.annotation()))
        self.assertTrue(v.attachment_record_matches_annotation(
            dict(self.attachment(), SizeInBytes='+' + '0' * 5000 + '7'), self.annotation()))

    def test_alias_does_not_hide_wrong_size_media_checksum_or_stream_parameters(self):
        for field, value in (('SizeInBytes', '8'), ('Content-Type', 'application/pdf'), ('CheckSum', '0' * 32)):
            with self.subTest(field=field):
                self.assertFalse(v.attachment_record_matches_annotation(dict(self.attachment(), **{field: value}), self.annotation()))
        for field, value in (('size_entry', 0), ('checksum', '0' * 32), ('afrelationship', 'Source')):
            annotation = self.annotation()
            annotation['file'][field] = value
            with self.subTest(field=field):
                self.assertFalse(v.attachment_record_matches_annotation(self.attachment(), annotation))

    def test_optional_checksum_can_use_independent_source_payload_evidence(self):
        self.assertTrue(v.attachment_record_matches_annotation(self.attachment(False), self.annotation(),
                        association_checksum=v.md5hex(b'payload')))
        self.assertFalse(v.attachment_record_matches_annotation(self.attachment(False), self.annotation(),
                         association_checksum=v.md5hex(b'other')))
        self.assertFalse(v.attachment_record_matches_annotation(self.attachment(False), self.annotation()))

    def test_renamed_zero_byte_placeholder_does_not_require_optional_checksum(self):
        self.assertTrue(v.attachment_record_matches_annotation(
            dict(self.attachment(False), SizeInBytes='0'), self.annotation(b'')))

    def sources(self):
        return [dict(message_id='id', subject='Subject', size_in_bytes=100, sent_norm='', raw_from='',
                     source_sha256='source-a', mime_attachments=[dict(filename='original.txt', size_in_bytes=7,
                         checksum=v.md5hex(b'payload'), content_type='text/plain')])]

    def test_source_hint_requires_a_unique_owner_source_and_original_filename(self):
        records = [{'Mail_GUID': 'owner', 'messageid': 'id', 'SizeInBytes': '100', 'Subject': 'Subject'}]
        body = {'identity': {'Mail_GUID': 'owner'}}
        sources = self.sources()
        self.assertEqual(v.md5hex(b'payload'), v.attachment_source_checksum(self.attachment(False), body, records, sources))
        for attachment, owners, summaries in (
                (dict(self.attachment(False), Filename='ORIGINAL.txt'), records, sources),
                (self.attachment(False), records + [dict(records[0])], sources),
                (self.attachment(False), records, sources + [dict(sources[0], source_sha256='source-b')]),
                (dict(self.attachment(False), Mail_GUID='another'), records, sources)):
            with self.subTest(attachment=attachment, owners=len(owners), sources=len(summaries)):
                self.assertIsNone(v.attachment_source_checksum(attachment, body, owners, summaries))

    def test_equal_name_and_size_with_different_source_bytes_do_not_supply_a_hint(self):
        records = [{'Mail_GUID': 'owner', 'messageid': 'id', 'SizeInBytes': '100'}]
        sources = self.sources()
        sources[0]['mime_attachments'].append(dict(sources[0]['mime_attachments'][0], checksum=v.md5hex(b'changed')))
        self.assertIsNone(v.attachment_source_checksum(self.attachment(False), {'identity': {'Mail_GUID': 'owner'}}, records, sources))

    def pipeline(self, checksum=False, filename='stored-safe.txt', hidden=False, outside=False, present=True):
        with pikepdf.Pdf.new() as pdf:
            page = pdf.add_blank_page()
            if present:
                stream = pdf.make_stream(b'payload')
                stream.Subtype = pikepdf.Name('/text/plain')
                stream.Params = pikepdf.Dictionary(Size=7)
                spec = pdf.make_indirect(pikepdf.Dictionary(F=filename, UF=filename, AFRelationship=pikepdf.Name.Data,
                    EF=pikepdf.Dictionary(F=stream)))
                page.Annots = pikepdf.Array([pdf.make_indirect(pikepdf.Dictionary(Subtype=pikepdf.Name.FileAttachment,
                    FS=spec, Rect=pikepdf.Array([10000, 10000, 10020, 10020] if outside else [10, 10, 30, 30]), F=2 if hidden else 0))])
            root = ET.Element(v.qn(v.NS_RDF, 'RDF'))
            description = ET.SubElement(root, v.qn(v.NS_RDF, 'Description'))
            bag = ET.SubElement(ET.SubElement(description, v.qn(v.NS_PDFMAILMETA, 'attachments')), v.qn(v.NS_RDF, 'Bag'))
            row = ET.SubElement(bag, v.qn(v.NS_RDF, 'li'), {v.qn(v.NS_RDF, 'parseType'): 'Resource'})
            for field, value in self.attachment(checksum).items():
                ET.SubElement(row, v.qn(v.NS_PDFMAILMETA, field)).text = value
            bodies = [{'identity': {'Mail_GUID': 'owner'}, 'pages': [1], 'start_page': 1, 'end_page': 1}]
            report = v.Report('source-omitted.pdf')
            with patch.object(v, 'get_dpart_nodes', return_value=(None, None, [])), \
                 patch.object(v, 'collect_body_content_sets', return_value=bodies), \
                 patch.object(v, 'collect_page_text_analysis', return_value=None):
                v.check_additional_rule_coverage(pdf, root, b'', [], [], 'si', 4, report)
            return report

    def test_source_absent_optional_checksum_and_unknown_alias_are_unavailable(self):
        report = self.pipeline()
        finding = next(item for item in report.items if item['code'] == 'ATTACHMENT_ANNOTATIONS_PRESENT')
        self.assertEqual('WARN', finding['level'])
        self.assertEqual('unavailable', finding['details']['assessment'])
        self.assertIn('VALIDATION_INCOMPLETE', report.seen_codes)

    def test_known_payload_alias_still_requires_a_visible_in_bounds_annotation(self):
        finding = next(item for item in self.pipeline(checksum=True).items if item['code'] == 'ATTACHMENT_ANNOTATIONS_PRESENT')
        self.assertEqual('PASS', finding['level'])
        for options in ({'hidden': True}, {'outside': True}, {'present': False}):
            with self.subTest(options=options):
                code = ('ATTACHMENT_ANNOTATIONS_PRESENT' if options.get('present') is False
                        else 'ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX')
                finding = next(item for item in self.pipeline(checksum=True, **options).items if item['code'] == code)
                self.assertEqual('FAIL', finding['level'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'), 'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_attachment_filename_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-attachment-filenames'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(case['expected_status'], result['summary']['status'])
                self.assertEqual(set(case['expected_failures']), {item['code'] for item in result['items'] if item['level'] == 'FAIL'})
                for code in case['required_unavailable_codes']:
                    self.assertTrue(any(item['code'] == code and item['level'] == 'WARN' for item in result['items']))


if __name__ == '__main__':
    unittest.main()
