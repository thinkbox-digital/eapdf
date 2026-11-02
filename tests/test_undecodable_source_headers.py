from test_preservation_safety import failed_source_fields
"""Undecoded original octets are evidence gaps, not invented text mismatches."""
import json
import unittest
from test_preservation_safety import validator as v, failed_codes, email_metadata
from test_foaf_mailbox_uris import metadata


class UndecodableSourceHeaderTests(unittest.TestCase):
    def source(self, headers):
        raw = headers + b'\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\n\r\nBody'
        file = dict(afrelationship='Source', subtype='message/rfc822', bytes=raw,
                    objgen=(1, 0), filename='original.eml')
        return file, v.summarize_source_email([file])

    def test_all_four_unknown_mailboxes_are_unavailable_not_mismatched(self):
        for field in ('To', 'From', 'Cc', 'Bcc'):
            with self.subTest(field=field):
                _, source = self.source(field.encode() + b': j\xffrg@example.org')
                report = v.Report('unknown.pdf')
                v.compare_agent_property(metadata(['mailto:joerg@example.org'], field), report, field,
                    source[field.lower() + '_agents'], 'MESSAGE_' + field.upper() + '_XMP', 'MESSAGE_' + field.upper() + '_FOAF')
                self.assertEqual('INCOMPLETE', report.summary()['status'])
                self.assertFalse(failed_codes(report))

    def test_unknown_source_cannot_invent_missing_foaf_presence(self):
        _, source = self.source(b'From: j\xffrg@example.org')
        report = v.Report('absent.pdf')
        v.compare_agent_property(metadata([], 'From'), report, 'From', source['from_agents'], 'MESSAGE_FROM_XMP', 'MESSAGE_FROM_FOAF')
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertFalse(failed_codes(report))

    def test_invalid_supplied_mailbox_still_fails_independently(self):
        _, source = self.source(b'From: j\xffrg@example.org')
        report = v.Report('bad.pdf')
        v.compare_agent_property(metadata(['mailto:bad..address@example.org'], 'From'), report, 'From', source['from_agents'], 'MESSAGE_FROM_XMP', 'MESSAGE_FROM_FOAF')
        self.assertIn('MESSAGE_FROM_FOAF', failed_codes(report))

    def test_unknown_values_do_not_become_source_text_failures(self):
        for field, header in [('Raw-From', 'From'), ('Subject', 'Subject'), ('messageid', 'Message-ID'), ('In-Reply-To', 'In-Reply-To'), ('Raw-Sent', 'Date')]:
            with self.subTest(field=field):
                _, source = self.source(header.encode() + b': bad\xffvalue')
                report = v.Report('unknown.pdf')
                value = v.XmpArray(['known'], 'Bag') if field == 'In-Reply-To' else 'known'
                v.check_source_field_values({field: value}, source, report)
                self.assertFalse(failed_codes(report))
                self.assertEqual('INCOMPLETE', report.summary()['status'])

    def test_empty_supplied_raw_text_is_also_unavailable(self):
        _, source = self.source(b'From: j\xffrg@example.org')
        report = v.Report('empty.pdf')
        v.check_source_field_values({'Raw-From': ''}, source, report)
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertFalse(failed_codes(report))

    def test_optional_omitted_raw_text_does_not_require_comparison(self):
        _, source = self.source(b'From: j\xffrg@example.org')
        report = v.Report('omitted.pdf')
        v.check_source_field_values({}, source, report)
        self.assertEqual('PASS', report.summary()['status'])

    def test_readable_other_field_mismatch_is_preserved(self):
        _, source = self.source(b'Subject: bad\xffvalue\r\nMessage-ID: <original@example.org>')
        report = v.Report('other.pdf')
        v.check_source_field_values({'Subject': 'guessed', 'messageid': '<different@example.org>'}, source, report)
        self.assertEqual({'messageid'}, failed_source_fields(report))
        self.assertEqual('FAIL', report.summary()['status'])

    def test_independently_known_missing_subject_still_fails(self):
        file, _ = self.source(b'Subject: bad\xffvalue')
        report = v.Report('missing.pdf')
        v.check_single_message_metadata(email_metadata([{'Mail_GUID':'uuid:one','Sent':'2003-01-29T02:20:54Z'}]), [file], 's', report)
        self.assertIn('Subject', failed_source_fields(report, 'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
        json.dumps(report.items, ensure_ascii=False).encode('utf-8')

    def test_unknown_display_text_does_not_hide_known_mailbox_mismatch(self):
        _, source = self.source(b'From: J\xffrg <known@example.org>')
        report = v.Report('display.pdf')
        v.compare_agent_property(metadata(['mailto:different@example.org'], 'From'), report, 'From', source['from_agents'], 'MESSAGE_FROM_XMP', 'MESSAGE_FROM_FOAF')
        self.assertIn('From', failed_source_fields(report))

    def test_reports_escape_octets_without_mutating_original_evidence(self):
        _, source = self.source(b'From: j\xffrg@example.org')
        details = {'source': source['header_occurrences'], 'unicode': 'café', 'numbers': [1, True, None], 'tuple': ('x\udcff',)}
        report = v.Report('diagnostics.pdf')
        report.warn('VALIDATION_INCOMPLETE', 'unknown \udcff', details)
        text = json.dumps(report.items, ensure_ascii=False)
        text.encode('utf-8')
        v.render_html(report).encode('utf-8')
        self.assertIn('\udcff', details['source'][0]['raw'])
        self.assertIn('\\udcff', report.items[0]['details']['source'][0]['raw'])
        self.assertEqual('café', report.items[0]['details']['unicode'])
        self.assertEqual([1, True, None], report.items[0]['details']['numbers'])
        self.assertIsInstance(report.items[0]['details']['tuple'], tuple)

    def test_valid_utf8_still_has_definite_comparisons(self):
        _, source = self.source('Subject: café'.encode())
        report = v.Report('known.pdf')
        v.check_source_field_values({'Subject':'different'}, source, report)
        self.assertEqual({'Subject'}, failed_source_fields(report))
        self.assertFalse(any(i['code']=='VALIDATION_INCOMPLETE' for i in report.items))

    def test_original_bytes_and_internal_unknown_text_remain_untouched(self):
        file, source = self.source(b'From: j\xffrg@example.org')
        self.assertIn(b'\xff', file['bytes'])
        self.assertIn('\udcff', source['raw_from'])
        with self.assertRaises(ValueError):
            v.decoded_source_header(source['raw_from'])

    def test_semantic_array_diagnostics_need_no_subclass_constructor(self):
        array = v.XmpArray(['bad\udcff'], 'Bag')
        report = v.Report('array.pdf')
        report.warn('VALIDATION_INCOMPLETE', 'unknown', {'array': array})
        self.assertEqual(['bad\\udcff'], report.items[0]['details']['array'])
        self.assertEqual('Bag', array.kind)
        self.assertEqual('bad\udcff', array[0])
