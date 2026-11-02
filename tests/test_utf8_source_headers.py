"""Lossless RFC6532 UTF-8 restoration precedes structured header parsing."""
import hashlib
import base64
import json
import unittest

from test_preservation_safety import validator as v, failed_codes
from test_foaf_mailbox_uris import metadata


class Utf8SourceHeaderTests(unittest.TestCase):
    def source(self, headers):
        raw = (headers + '\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\n\r\nBody').encode()
        file = dict(afrelationship='Source', subtype='message/global', bytes=raw,
                    objgen=(1, 0), filename='source.eml')
        return file, v.summarize_source_email([file])

    def test_all_four_address_fields_restore_utf8_mailbox_data(self):
        for field in ('From', 'To', 'Cc', 'Bcc'):
            with self.subTest(field=field):
                file, source = self.source(field + ': Jörg <jörg@example.org>')
                self.assertEqual([dict(name='Jörg', mbox='mailto:jörg@example.org')], source[field.lower() + '_agents'])
                report = v.Report('utf8.pdf')
                v.compare_agent_property(metadata(['mailto:j%C3%B6rg@example.org'], field), report, field,
                    source[field.lower() + '_agents'], 'MESSAGE_' + field.upper() + '_XMP', 'MESSAGE_' + field.upper() + '_FOAF')
                self.assertFalse(failed_codes(report))
                self.assertEqual(hashlib.sha256(file['bytes']).hexdigest(), source['source_sha256'])

    def test_utf8_subject_and_message_identifier_values_are_restored(self):
        _, source = self.source('Subject: Résumé café\r\nMessage-ID: <näive@example.org>')
        self.assertEqual('Résumé café', source['subject'])
        self.assertEqual('<näive@example.org>', source['message_id'])
        self.assertEqual(['Résumé café'], source['header_values']['subject'])

    def test_original_occurrences_remain_usable_unicode_for_diagnostics(self):
        _, source = self.source('From: Jörg <jörg@example.org>')
        self.assertEqual('Jörg <jörg@example.org>', source['header_occurrences'][0]['raw'])
        json.dumps(source['header_occurrences'], ensure_ascii=False).encode('utf-8')

    def test_raw_source_field_comparisons_receive_the_restored_text(self):
        _, source = self.source('From: Jörg <jörg@example.org>')
        report = v.Report('raw.pdf')
        v.check_source_field_values({'Raw-From': 'Jörg <jörg@example.org>'}, source, report)
        self.assertFalse(failed_codes(report))

    def test_encoded_display_words_keep_the_structured_parsing_order(self):
        _, source = self.source('From: =?utf-8?q?Last=2C_First?= <person@example.org>')
        self.assertEqual([dict(name='Last, First', mbox='mailto:person@example.org')], source['from_agents'])

    def test_decomposed_unicode_data_is_not_silently_normalized(self):
        _, source = self.source('From: jo\u0308rg@example.org')
        self.assertEqual('mailto:jo\u0308rg@example.org', source['from_agents'][0]['mbox'])

    def test_quoted_unicode_mailbox_spaces_remain_data(self):
        _, source = self.source('From: "jö  rg"@example.org')
        self.assertEqual(('jö  rg', 'example.org'), v.mailbox_comparison_key(source['from_agents'][0]['mbox']))

    def test_ascii_input_and_original_bytes_are_unchanged(self):
        file, source = self.source('From: Sender <sender@example.org>')
        before = file['bytes']
        self.assertEqual('mailto:sender@example.org', source['from_agents'][0]['mbox'])
        self.assertEqual(before, file['bytes'])

    def test_legacy_invalid_utf8_is_not_replaced_by_guessed_characters(self):
        file = dict(afrelationship='Source', subtype='message/rfc822', bytes=b'From: j\xffrg@example.org\r\n\r\nBody',
                    objgen=(1, 0), filename='source.eml')
        source = v.summarize_source_email([file])
        self.assertIn('\udcff', source['header_values']['from'][0])
        self.assertNotIn('ÿ', source['header_values']['from'][0])

    def test_message_global_is_inspectable_without_an_eml_filename(self):
        file, _ = self.source('From: Jörg <jörg@example.org>')
        file['filename'] = 'original.data'
        self.assertEqual(1, len(v.summarize_source_emails([file])))
        self.assertEqual((1, 0), v.source_email_attachment_expectation([file]))
        self.assertEqual((1, 0), v.source_email_mime_attachment_expectation([file]))

    def test_nested_encoded_message_global_preserves_original_payload_bytes(self):
        child = 'From: Jörg <jörg@example.org>\r\n\r\nBody\r\n'.encode()
        raw = (b'Content-Type: multipart/mixed; boundary=x\r\n\r\n--x\r\n'
               b'Content-Type: message/global\r\nContent-Transfer-Encoding: base64\r\n\r\n'
               + base64.b64encode(child) + b'\r\n--x--\r\n')
        file = dict(afrelationship='Source', subtype='message/global', bytes=raw,
                    objgen=(1, 0), filename='original.data')
        source = v.summarize_source_email([file])
        self.assertEqual(1, source['mime_attachment_count'])
        attachment = source['mime_attachments'][0]
        self.assertEqual('message/global', attachment['content_type'])
        self.assertEqual(len(child), attachment['size_in_bytes'])
        self.assertEqual(hashlib.md5(child).hexdigest(), attachment['checksum'])

    def test_nested_message_global_without_raw_bytes_has_a_nonempty_fallback(self):
        message = v.BytesParser(policy=v.policy.default).parsebytes(
            b'Content-Type: message/global\r\n\r\nFrom: sender@example.org\r\n\r\nBody')
        parts = list(v.iter_email_attachment_parts(message))
        self.assertEqual(1, len(parts))
        self.assertTrue(parts[0][1])
