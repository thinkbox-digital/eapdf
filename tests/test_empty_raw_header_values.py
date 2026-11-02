"""Empty normal text is present; type, source meaning and omission differ."""
import unittest
import json
from xml.etree import ElementTree as ET

from test_preservation_safety import validator as v, failed_codes, email_metadata, failed_source_fields


class EmptyRawHeaderTests(unittest.TestCase):
    fields = [('To', 'raw_to', 'MESSAGE_RAW_TO_XMP'), ('From', 'raw_from', 'MESSAGE_RAW_FROM_XMP'),
              ('Cc', 'raw_cc', 'MESSAGE_RAW_CC_XMP'), ('Bcc', 'raw_bcc', 'MESSAGE_RAW_BCC_XMP'),
              ('Sent', 'sent_raw', 'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID')]

    def source(self, field='From', value='person@example.org', date='Tue, 28 Jan 2003 21:20:54 -0500'):
        raw = ((field + ': ' + value + '\r\n') if field != 'Sent' else '')
        raw += 'Date: ' + date + '\r\n\r\nBody'
        file = dict(afrelationship='Source', subtype='message/rfc822', bytes=raw.encode(),
                    objgen=(1, 0), filename='source.eml')
        return file, v.summarize_source_email([file])

    def record(self, field, value):
        return dict(Mail_GUID='uuid:raw-test', Sent='2003-01-29T02:20:54Z', **{'Raw-' + field: value})

    def test_supplied_empty_text_cannot_reflect_a_nonempty_known_source(self):
        for field, _, code in self.fields:
            with self.subTest(field=field):
                _, source = self.source(field)
                report = v.Report('empty.pdf')
                v.check_source_field_values(self.record(field, ''), source, report)
                self.assertIn('Raw-'+field, failed_source_fields(report))

    def test_omission_is_distinct_from_supplied_empty_text(self):
        for field, _, code in self.fields:
            with self.subTest(field=field):
                _, source = self.source(field)
                report = v.Report('omitted.pdf')
                v.check_source_field_values({}, source, report)
                self.assertNotIn('Raw-'+field, failed_source_fields(report))

    def test_empty_original_values_can_have_matching_empty_raw_text(self):
        for field, _, code in self.fields:
            with self.subTest(field=field):
                _, source = self.source(field, '', date='' if field == 'Sent' else 'Tue, 28 Jan 2003 21:20:54 -0500')
                report = v.Report('original-empty.pdf')
                v.check_source_field_values(self.record(field, ''), source, report)
                self.assertNotIn('Raw-'+field, failed_source_fields(report))

    def test_physical_presence_includes_empty_attribute_and_qualified_text(self):
        for xml in [f'<rdf:Description xmlns:rdf="{v.NS_RDF}" xmlns:m="{v.NS_PDFMAILMETA}" m:Raw-Sent=""/>',
                    f'<rdf:Description xmlns:rdf="{v.NS_RDF}" xmlns:m="{v.NS_PDFMAILMETA}"><m:Raw-Sent><rdf:Description><rdf:value/></rdf:Description></m:Raw-Sent></rdf:Description>']:
            self.assertTrue(v.message_xmp_property_present(ET.fromstring(xml), 'Raw-Sent'))

    def test_unrelated_attachment_property_cannot_satisfy_email_presence(self):
        root = ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:m="{v.NS_PDFMAILMETA}"><rdf:Description><m:attachments><rdf:Bag><rdf:li rdf:parseType="Resource"><m:Raw-Sent/></rdf:li></rdf:Bag></m:attachments></rdf:Description></rdf:RDF>')
        self.assertFalse(v.message_xmp_property_present(root, 'Raw-Sent'))

    def test_valid_source_date_recommends_omitting_even_an_empty_raw_sent(self):
        file, source = self.source()
        report = v.Report('valid-date.pdf')
        v.check_single_message_metadata(email_metadata([self.record('Sent', '')]), [file], 's', report)
        levels = {item['level'] for item in report.items if item['code'] == 'MESSAGE_RAW_SENT_OMIT_IF_VALID'}
        self.assertEqual({'WARN'}, levels)
        v.check_source_field_values(self.record('Sent', ''), source, report)
        self.assertIn('Raw-Sent', failed_source_fields(report))

    def test_invalid_date_presence_and_nonempty_source_fidelity_are_separate(self):
        file, source = self.source(date='not a date')
        record = self.record('Sent', '')
        report = v.Report('invalid-date.pdf')
        v.check_single_message_metadata(email_metadata([record]), [file], 's', report)
        self.assertTrue(any(item['code'] == 'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID' and item['level'] == 'PASS' for item in report.items))
        v.check_source_field_values(record, source, report)
        self.assertIn('Raw-Sent', failed_source_fields(report))

    def test_invalid_empty_uri_type_is_not_a_normal_empty_text_comparison(self):
        _, source = self.source()
        report = v.Report('uri.pdf')
        v.check_source_field_values(self.record('From', v.XmpUri('')), source, report)
        self.assertNotIn('Raw-From', failed_source_fields(report))

    def test_unreadable_original_text_cannot_create_an_unsupported_empty_value_pass(self):
        file = dict(afrelationship='Source', subtype='message/rfc822', bytes=b'Date: bad\xffdate\r\n\r\nBody',
                    objgen=(1, 0), filename='unknown.eml')
        source = v.summarize_source_email([file])
        report = v.Report('unknown.pdf')
        v.check_source_field_values({'Raw-Sent': ''}, source, report)
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertFalse(failed_codes(report))
        json.dumps(report.items, ensure_ascii=False).encode('utf-8')

    def test_optional_raw_address_absence_is_not_a_specification_warning(self):
        for field, _, code in self.fields[:4]:
            with self.subTest(field=field):
                file, _ = self.source(field)
                report = v.Report('optional.pdf')
                v.check_single_message_metadata(email_metadata([{'Mail_GUID': 'uuid:test', 'Sent': '2003-01-29T02:20:54Z'}]), [file], 's', report)
                self.assertEqual({'N/A'}, {item['level'] for item in report.items if item['code'] == code})
                self.assertIn(field, failed_source_fields(report,'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
