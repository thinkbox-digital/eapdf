"""Structured source address parsing must preserve actual mailbox data."""
import unittest

from test_preservation_safety import validator as v, failed_source_fields
from test_foaf_mailbox_uris import metadata


class SourceMailboxDataTests(unittest.TestCase):
    def source(self, value, field='From'):
        raw = (field + ': ' + value + '\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\n\r\nBody').encode('ascii')
        return v.summarize_source_email([dict(afrelationship='Source', subtype='message/rfc822',
            bytes=raw, objgen=(1, 0), filename='source.eml')])

    def compare(self, source, values, field='From'):
        report = v.Report('mailbox.pdf')
        v.compare_agent_property(metadata(values, field), report, field, source[field.lower() + '_agents'],
            'MESSAGE_' + field.upper() + '_XMP', 'MESSAGE_' + field.upper() + '_FOAF',
            source['raw_' + field.lower()])
        return report

    def test_all_four_source_fields_keep_quoted_local_part_spaces(self):
        for field in ('From', 'To', 'Cc', 'Bcc'):
            with self.subTest(field=field):
                source = self.source('"a  b"@example.org', field)
                self.assertEqual('mailto:"a  b"@example.org', source[field.lower() + '_agents'][0]['mbox'])
                report = self.compare(source, ['mailto:%22a%20%20b%22@example.org'], field)
                self.assertFalse(any(item['level'] == 'FAIL' for item in report.items), report.items)

    def test_collapsing_source_spaces_cannot_produce_a_false_match(self):
        source = self.source('"a  b"@example.org')
        report = self.compare(source, ['mailto:%22a%20b%22@example.org'])
        self.assertIn('From', failed_source_fields(report))

    def test_spaces_at_edges_inside_quotes_remain_mailbox_data(self):
        source = self.source('" a  b "@example.org')
        self.assertEqual((' a  b ', 'example.org'), v.mailbox_comparison_key(source['from_agents'][0]['mbox']))

    def test_quoted_pair_equivalence_keeps_adjacent_space_data(self):
        source = self.source('"a\\  b"@example.org')
        self.assertEqual(('a  b', 'example.org'), v.mailbox_comparison_key(source['from_agents'][0]['mbox']))

    def test_encoded_display_comma_does_not_split_the_address_list(self):
        source = self.source('=?utf-8?q?Last=2C_First?= <person@example.org>')
        self.assertEqual([dict(name='Last, First', mbox='mailto:person@example.org')], source['from_agents'])

    def test_encoded_word_looking_local_part_remains_literal(self):
        value = '=?utf-8?q?foo?=@example.org'
        source = self.source(value)
        self.assertEqual('mailto:' + value, source['from_agents'][0]['mbox'])

    def test_literal_atom_fallback_requires_valid_smtp_punctuation_and_domain(self):
        self.assertTrue(v.valid_smtp_mailbox('=?utf-8?q?foo?=@example.org'))
        for value in ('=?utf-8?q?foo?=..bar@example.org', '=?utf-8?q?foo?=@-example.org',
                      '=?utf-8?q?foo?=@example..org', '=?utf-8?q?foo?=@@example.org'):
            with self.subTest(value=value):
                self.assertFalse(v.valid_smtp_mailbox(value))

    def test_source_address_order_remains_significant(self):
        source = self.source('"a  b"@example.org, second@example.org', 'To')
        values = ['mailto:%22a%20%20b%22@example.org', 'mailto:second@example.org']
        self.assertFalse(any(item['level'] == 'FAIL' for item in self.compare(source, values, 'To').items))
        self.assertTrue(any(item['level'] == 'FAIL' for item in self.compare(source, list(reversed(values)), 'To').items))
