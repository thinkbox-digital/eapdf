"""A blank original Date header exists and cannot supply an XMP dateTime."""
import unittest

from test_preservation_safety import validator as v, failed_codes, email_metadata


class BlankSourceDateTests(unittest.TestCase):
    def file(self, date):
        raw = b'From: sender@example.org\r\n'
        if date is not None:
            raw += b'Date: ' + date.encode() + b'\r\n'
        raw += b'\r\nBody'
        return dict(afrelationship='Source', subtype='message/rfc822', bytes=raw,
                    objgen=(1, 0), filename='source.eml')

    def root(self, raw_sent=None):
        record = dict(Mail_GUID='uuid:date-test', Sent='2003-01-29T02:20:54Z')
        if raw_sent is not None:
            record['Raw-Sent'] = raw_sent
        return email_metadata([record])

    def test_absent_and_blank_headers_have_distinct_presence(self):
        for date, present in [(None, False), ('', True), ('   ', True), ('not a date', True)]:
            with self.subTest(date=date):
                source = v.summarize_source_email([self.file(date)])
                self.assertEqual(present, source['sent_present'])
                self.assertEqual(present, v.source_date_present(source))

    def test_blank_and_whitespace_original_dates_require_raw_sent(self):
        for date in ('', '   '):
            with self.subTest(date=date):
                report = v.Report('blank.pdf')
                v.check_single_message_metadata(self.root(), [self.file(date)], 's', report)
                self.assertIn('MESSAGE_RAW_SENT_REQUIRED_IF_INVALID', failed_codes(report))

    def test_present_empty_raw_sent_preserves_a_blank_original(self):
        file = self.file('')
        report = v.Report('empty.pdf')
        root = self.root('')
        v.check_single_message_metadata(root, [file], 's', report)
        self.assertNotIn('MESSAGE_RAW_SENT_REQUIRED_IF_INVALID', failed_codes(report))
        self.assertTrue(any(item['code'] == 'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID' and item['level'] == 'PASS' for item in report.items))
        v.check_source_field_values(v.flatten_email_records(root)[0], v.summarize_source_email([file]), report)
        self.assertNotIn('MESSAGE_RAW_SENT_REQUIRED_IF_INVALID', failed_codes(report))

    def test_no_original_date_header_does_not_invent_an_error_value(self):
        report = v.Report('absent.pdf')
        v.check_single_message_metadata(self.root(), [self.file(None)], 's', report)
        self.assertNotIn('MESSAGE_RAW_SENT_REQUIRED_IF_INVALID', failed_codes(report))

    def test_old_summary_dictionaries_use_original_occurrence_presence(self):
        self.assertTrue(v.source_date_present(dict(sent_raw='', header_values={'date': ['']})))
        self.assertFalse(v.source_date_present(dict(sent_raw='', header_values={})))
        self.assertTrue(v.source_date_present(dict(sent_raw='not a date')))

    def test_blank_date_does_not_activate_valid_date_omission_recommendation(self):
        report = v.Report('blank-present.pdf')
        v.check_single_message_metadata(self.root(''), [self.file('')], 's', report)
        self.assertNotIn('MESSAGE_RAW_SENT_OMIT_IF_VALID', report.seen_codes)
