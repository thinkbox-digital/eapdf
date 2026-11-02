from test_preservation_safety import failed_source_fields
"""Source date range limits cannot prove a mandatory Raw-Sent violation."""
import unittest
from test_preservation_safety import validator as v, failed_codes, email_metadata


class SourceDateAvailabilityTests(unittest.TestCase):
    def test_creator_records_missing_source_as_incomplete(self):
        import tempfile
        from pathlib import Path
        import pikepdf
        source = Path(__file__).resolve().parents[1] / "examples/eml/eml_Agenda_and_timeline_slides_1s.pdf"
        with tempfile.TemporaryDirectory() as directory:
            marked = Path(directory) / "incomplete.pdf"
            with pikepdf.open(source) as pdf:
                xml = pdf.Root.Metadata.read_bytes().decode("utf-8")
                xml = xml.replace("</rdf:RDF>",
                    "<rdf:Description rdf:about='' xmlns:c='https://thinkbox.digital/ns/eapdf/conversion/1.0/'>"
                    "<c:status>INCOMPLETE</c:status></rdf:Description></rdf:RDF>")
                pdf.Root.Metadata.write(xml.encode("utf-8"))
                pdf.save(marked)
            report = v.validate(marked)
            self.assertTrue(any(item["code"] == "VALIDATION_INCOMPLETE" and item["level"] == "WARN"
                and "creator recorded missing source" in item["message"] for item in report.items))

    def test_legacy_email_years_use_the_specified_century(self):
        for raw,year in (("03",2003),("49",2049),("50",1950),("68",1968),("99",1999)):
            with self.subTest(year=raw):
                self.assertEqual(year,v.parse_source_datetime("14 Apr "+raw+" 13:17:55 -0400").year)

    def source(self, date):
        raw = ('Date: ' + date + '\r\n\r\nBody').encode()
        file = dict(afrelationship='Source', subtype='message/rfc822', bytes=raw,
                    filename='original.eml', objgen=(1,0))
        return file, v.summarize_source_email([file])

    def compare(self, source, sent):
        report = v.Report('date.pdf')
        v.check_source_field_values({'Sent':sent}, source, report)
        return report

    def test_negative_zero_is_utc_without_inventing_the_originating_zone(self):
        file, source = self.source('Tue, 28 Jan 2003 21:20:54 -0000')
        self.assertEqual('2003-01-28T21:20:54+00:00',source['sent_norm'])
        self.assertFalse(source['sent_normalization_unavailable'])
        self.assertIn('-0000',source['sent_raw'])
        self.assertIn(b'-0000',file['bytes'])

    def test_negative_zero_equivalent_instant_compares(self):
        _, source = self.source('Tue, 28 Jan 2003 21:20:54 -0000')
        self.assertEqual('PASS',self.compare(source,'2003-01-28T16:20:54-05:00').summary()['status'])

    def test_negative_zero_different_instant_still_fails(self):
        _, source = self.source('Tue, 28 Jan 2003 21:20:54 -0000')
        self.assertEqual({'Sent'},failed_source_fields(self.compare(source,'2003-01-28T21:20:55Z')))

    def test_extended_year_is_unavailable_not_established_invalid(self):
        file, source = self.source('1 Jan 10000 12:00:00 +0000')
        self.assertTrue(source['sent_normalization_unavailable'])
        self.assertEqual('',source['sent_norm'])
        report = v.Report('extended.pdf')
        v.check_single_message_metadata(email_metadata([dict(Mail_GUID='uuid:date',Sent='10000-01-01T12:00:00Z')]),[file],'s',report)
        self.assertNotIn('MESSAGE_RAW_SENT_REQUIRED_IF_INVALID',failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_calendar_cycle_does_not_hide_invalid_leap_dates(self):
        for text, unavailable in [('29 Feb 10000 12:00:00 +0000',True),('29 Feb 10001 12:00:00 +0000',False)]:
            with self.subTest(text=text):
                _, source = self.source(text)
                self.assertEqual(unavailable,source['sent_normalization_unavailable'])

    def test_weekday_must_match_even_for_extended_years(self):
        for day, unavailable in [('Sat',True),('Mon',False)]:
            _, source = self.source(day+', 1 Jan 10000 12:00:00 +0000')
            self.assertEqual(unavailable,source['sent_normalization_unavailable'])

    def test_folded_commented_extended_date_retains_original_text(self):
        _, source = self.source('1 Jan (original)\r\n 10000 12:00:00 +0000')
        self.assertTrue(source['sent_normalization_unavailable'])
        self.assertIn('(original)',source['sent_raw'])

    def test_large_year_needs_no_unbounded_integer_conversion(self):
        _, source = self.source('1 Jan '+'1'+'0'*5000+' 12:00:00 +0000')
        self.assertTrue(source['sent_normalization_unavailable'])
        self.assertFalse(v.source_date_exceeds_parser_range('1 Jan 0000000010 12:00:00 +0000'))

    def test_wide_rfc_offset_normalizes_to_valid_xsd_utc(self):
        _, source = self.source('1 Jan 2003 12:00:00 +1500')
        self.assertEqual('2002-12-31T21:00:00+00:00',source['sent_norm'])
        self.assertTrue(v.looks_like_xmp_datetime(source['sent_norm']))
        self.assertEqual('PASS',self.compare(source,'2002-12-31T21:00:00Z').summary()['status'])

    def test_utc_overflow_is_unavailable_not_invalid(self):
        _, source = self.source('31 Dec 9999 23:00:00 -1500')
        self.assertTrue(source['sent_normalization_unavailable'])
        self.assertEqual('INCOMPLETE',self.compare(source,'10000-01-01T14:00:00Z').summary()['status'])

    def test_unperformed_instant_comparisons_cannot_pass(self):
        _, source = self.source('Tue, 28 Jan 2003 21:20:54 +0000')
        for sent in ('2003-01-28T21:20:54','10000-01-01T12:00:00Z'):
            with self.subTest(sent=sent):
                report = self.compare(source,sent)
                self.assertEqual('INCOMPLETE',report.summary()['status'])
                self.assertFalse(failed_codes(report))

    def test_raw_text_does_not_establish_unavailable_instant_comparison(self):
        file, source = self.source('1 Jan 10000 12:00:00 +0000')
        record=dict(Mail_GUID='uuid:date',Sent='10000-01-01T12:00:00Z',**{'Raw-Sent':source['sent_raw']})
        report=v.Report('raw.pdf')
        v.check_single_message_metadata(email_metadata([record]),[file],'s',report)
        v.check_source_field_values(record,source,report)
        self.assertEqual('INCOMPLETE',report.summary()['status'])
        self.assertFalse(failed_codes(report))
