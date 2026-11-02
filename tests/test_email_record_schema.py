"""Table 18 supplied types remain mandatory without an inspectable source."""
from xml.etree import ElementTree as ET
import os
import unittest

from test_preservation_safety import validator as v, failed_source_fields


def metadata(field='', value=''):
    root = ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:m="{v.NS_PDFMAILMETA}">'
        '<rdf:Description><m:email><rdf:Bag><rdf:li rdf:parseType="Resource">'
        '<m:Mail_GUID>example</m:Mail_GUID><m:Sent>2026-10-19T12:30:00Z</m:Sent>'
        '</rdf:li></rdf:Bag></m:email></rdf:Description></rdf:RDF>')
    record = root.find('.//' + v.qn(v.NS_RDF, 'li'))
    if field:
        existing = record.find(v.qn(v.NS_PDFMAILMETA, field))
        if existing is not None:
            record.remove(existing)
        node = ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, field))
        if isinstance(value, tuple) and value[0] == 'uri':
            node.set(v.qn(v.NS_RDF, 'resource'), value[1])
        elif isinstance(value, tuple) and value[0] == 'array':
            ET.SubElement(ET.SubElement(node, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = value[1]
        else:
            node.text = value
    return root


class EmailRecordSchemaTests(unittest.TestCase):
    def check(self, field='', value='', profile='si'):
        report = v.Report('isolated.pdf')
        v.check_required_email_metadata(metadata(field, value), profile, report)
        return {row['code'] for row in report.items if row['level'] == 'FAIL'}

    def test_sent_uri_and_array_cannot_supply_a_date_value(self):
        for kind in ('uri', 'array'):
            self.assertIn('MESSAGE_SENT_FORMAT', self.check('Sent', (kind, '2026-10-19T12:30:00Z')))

    def test_supplied_counts_require_nonnegative_integer_text_without_sources(self):
        for field, code in (('SizeInBytes', 'MESSAGE_SIZE_XMP'), ('NumberAttachments', 'MESSAGE_ATTACHMENT_COUNT_XMP')):
            for value in ('-1', '1_000', 'one', '', '\u00a012\u00a0', ('uri', '123'), ('array', '123')):
                with self.subTest(field=field, value=value):
                    self.assertIn(code, self.check(field, value))
            for value in ('0', '-0', '+00012', ' 12 ', '0' * 5000, '9' * 5000):
                self.assertNotIn(code, self.check(field, value))

    def test_optional_counts_are_not_mandatory_when_no_attachment_evidence_exists(self):
        self.assertEqual(set(), self.check())

    def test_supplied_free_text_cannot_be_a_uri_or_container(self):
        for field, code in (('Subject', 'MESSAGE_SUBJECT_XMP'), ('Raw-To', 'MESSAGE_RAW_TO_XMP'),
                            ('Raw-From', 'MESSAGE_RAW_FROM_XMP'), ('Raw-Cc', 'MESSAGE_RAW_CC_XMP'),
                            ('Raw-Bcc', 'MESSAGE_RAW_BCC_XMP'), ('Raw-Sent', 'MESSAGE_RAW_SENT_REQUIRED_IF_INVALID'),
                            ('messageid', 'MESSAGE_ID_XMP'), ('Message-ID', 'MESSAGE_ID_XMP')):
            for kind in ('uri', 'array'):
                with self.subTest(field=field, kind=kind):
                    self.assertIn(code, self.check(field, (kind, 'urn:free-text')))
            for value in ('', 'original  text', 'urn:literal-identifier'):
                self.assertNotIn(code, self.check(field, value))

    def test_container_profile_does_not_require_email_record_schema(self):
        self.assertEqual(set(), self.check('SizeInBytes', '-1', profile='c'))

    def test_datetime_lexical_alternatives_do_not_require_timezone_or_python_year_range(self):
        for value in ('2026-10-19T12:30:00', '2026-10-19T24:00:00',
                      '10000-01-01T00:00:00Z', '-0001-01-01T00:00:00Z', '-0004-02-29T00:00:00Z',
                      '10000-02-29T00:00:00Z', '2026-10-19T24:00:00.000Z'):
            with self.subTest(value=value):
                self.assertNotIn('MESSAGE_SENT_FORMAT', self.check('Sent', value))
        for value in ('0000-01-01T00:00:00Z', '+2026-10-19T12:30:00Z', '02026-10-19T12:30:00Z',
                      '-0001-02-29T00:00:00Z', '2026-10-19T24:00:00.0001Z',
                      '2026-10-19T12:30:60Z', '\u00a02026-10-19T12:30:00Z'):
            with self.subTest(value=value):
                self.assertIn('MESSAGE_SENT_FORMAT', self.check('Sent', value))

    def test_numeric_comparison_preserves_large_legal_values_without_python_coercion(self):
        self.assertTrue(v.nonnegative_integer_equals('0' * 5000 + '12', 12))
        self.assertFalse(v.nonnegative_integer_equals('9' * 5000, 12))
        self.assertIsNone(v.parse_nonneg_int_value('-1'))
        self.assertIsNone(v.parse_nonneg_int_value('1_000'))

    def test_unknown_timezone_or_unsupported_instant_is_unavailable_not_a_source_mismatch(self):
        source = dict(sent_norm='2026-10-19T12:30:00Z', sent_raw='Mon, 19 Oct 2026 12:30:00 +0000',
                      size_in_bytes=0, mime_attachment_count=0)
        for sent in ('2026-10-19T12:30:00', '10000-01-01T00:00:00Z'):
            with self.subTest(sent=sent):
                report = v.Report('source.pdf')
                v.check_source_field_values({'Sent': sent}, source, report)
                self.assertFalse(any(row['level'] == 'FAIL' for row in report.items))
                self.assertTrue(any(row['level'] == 'WARN' and row['code'] == 'MESSAGE_METADATA_MATCH_SOURCE'
                                    and row.get('details', {}).get('field') == 'Sent' for row in report.items))

    def test_fractional_source_comparison_does_not_round_to_python_microseconds(self):
        source = dict(sent_norm='2026-10-19T12:30:00Z', sent_raw='Mon, 19 Oct 2026 12:30:00 +0000',
                      size_in_bytes=0, mime_attachment_count=0)
        for sent, expected in (('2026-10-19T12:30:00.00000001Z', True),
                               ('2026-10-19T13:30:00.00000000+01:00', False)):
            report = v.Report('source.pdf')
            v.check_source_field_values({'Sent': sent}, source, report)
            self.assertEqual(expected, 'Sent' in failed_source_fields(report))

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for independent isolated-email PDF corpus checks')
    def test_frozen_isolated_email_schema_corpus(self):
        import hashlib
        import json
        from test_preservation_safety import ROOT
        root = ROOT / 'tests/fixtures/eapdf/validator-email-schema'
        for case in json.loads((root / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = root / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(sorted(case['expected_failures']), sorted({r['code'] for r in result['items'] if r['level'] == 'FAIL'}))
                self.assertEqual('FAIL' if case['expected_failures'] else 'PASS', result['summary']['status'])


if __name__ == '__main__':
    unittest.main()
