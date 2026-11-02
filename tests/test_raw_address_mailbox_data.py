from test_preservation_safety import failed_source_fields
"""Raw address comparisons must not erase changed quoted mailbox data."""
import unittest

from test_preservation_safety import validator as v, failed_codes
import test_source_mailbox_data as source_data


class RawAddressMailboxDataTests(unittest.TestCase):
    def compare(self, original, actual, field='From'):
        source = source_data.SourceMailboxDataTests().source(original, field)
        report = v.Report('raw.pdf')
        v.check_source_field_values({'Raw-' + field: actual}, source, report)
        return report

    def test_all_four_raw_fields_reject_collapsed_quoted_mailbox_spaces(self):
        for field in ('To', 'From', 'Cc', 'Bcc'):
            with self.subTest(field=field):
                report = self.compare('"a  b"@example.org', '"a b"@example.org', field)
                self.assertIn('Raw-' + field, failed_source_fields(report))

    def test_exact_quoted_mailbox_data_still_matches(self):
        for field in ('To', 'From', 'Cc', 'Bcc'):
            with self.subTest(field=field):
                self.assertFalse(failed_codes(self.compare('"a  b"@example.org', '"a  b"@example.org', field)))

    def test_existing_outside_whitespace_policy_is_retained(self):
        self.assertFalse(failed_codes(self.compare('Name <"a  b"@example.org>', 'Name   <"a  b"@example.org>')))

    def test_quoted_edge_spaces_are_data(self):
        self.assertIn('Raw-From', failed_source_fields(self.compare('" a b "@example.org', '"a b"@example.org')))

    def test_missing_optional_raw_fields_are_not_required_by_this_comparison(self):
        source = source_data.SourceMailboxDataTests().source('"a  b"@example.org')
        report = v.Report('missing.pdf')
        v.check_source_field_values({}, source, report)
        self.assertNotIn('Raw-From', failed_source_fields(report))

    def test_non_smtp_raw_tokens_keep_the_existing_literal_comparison(self):
        self.assertFalse(failed_codes(self.compare("'person@example.org'", "'person@example.org'")))
        self.assertIn('Raw-From', failed_source_fields(self.compare("'person@example.org'", "'different@example.org'")))

    def test_missing_original_occurrence_evidence_does_not_invent_new_mailbox_data(self):
        source = source_data.SourceMailboxDataTests().source('"a  b"@example.org')
        source.pop('header_values')
        report = v.Report('unavailable.pdf')
        v.check_source_field_values({'Raw-From': '"a b"@example.org'}, source, report)
        self.assertFalse(failed_codes(report))
