"""EA-PDF required metadata retains standard XMP value types."""
import unittest
from xml.etree import ElementTree as ET
from test_preservation_safety import validator as v


class RequiredMetadataTypeTests(unittest.TestCase):
    valid = '<dc:creator><rdf:Seq><rdf:li>Creator</rdf:li></rdf:Seq></dc:creator><pdf:Producer>Producer</pdf:Producer><xmp:CreateDate>2026-10-20T12:00:00Z</xmp:CreateDate>'
    def check(self, before, after):
        xml = f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:dc="{v.NS_DC}" xmlns:pdf="{v.NS_PDF}" xmlns:xmp="{v.NS_XMP}" xmlns:ex="urn:example:qualifier"><rdf:Description>{self.valid.replace(before, after)}</rdf:Description></rdf:RDF>'
        report = v.Report('required-metadata.pdf')
        v.check_eapdf_xmp(ET.fromstring(xml), {}, report)
        return {i['code']: i for i in report.items}

    def test_creator_requires_ordered_names(self):
        for kind in ('Bag', 'Alt'):
            with self.subTest(kind=kind):
                rows = self.check(
                    '<dc:creator><rdf:Seq><rdf:li>Creator</rdf:li></rdf:Seq></dc:creator>',
                    f'<dc:creator><rdf:{kind}><rdf:li>Creator</rdf:li></rdf:{kind}></dc:creator>')
                self.assertEqual('FAIL', rows['XMP_CREATOR']['level'])

    def test_later_creator_items_must_also_be_names(self):
        for value in ('<rdf:li rdf:resource="urn:creator"/>', '<rdf:li rdf:parseType="Resource"><ex:name>Name</ex:name></rdf:li>'):
            with self.subTest(value=value):
                rows = self.check('</rdf:Seq>', value + '</rdf:Seq>')
                self.assertEqual('FAIL', rows['XMP_CREATOR']['level'])

    def test_creator_names_are_not_limited_to_software_or_forbidden_from_email_text(self):
        for value in ('Person', 'Organization', 'service@example.invalid'):
            self.assertEqual('PASS', self.check('>Creator<', '>' + value + '<')['XMP_CREATOR']['level'])

    def test_producer_requires_normal_text(self):
        for value in ('<pdf:Producer rdf:resource="urn:producer"/>',
                      '<pdf:Producer><rdf:Seq><rdf:li>Producer</rdf:li></rdf:Seq></pdf:Producer>',
                      '<pdf:Producer rdf:parseType="Resource"><ex:name>Producer</ex:name></pdf:Producer>'):
            with self.subTest(value=value):
                self.assertEqual('FAIL', self.check('<pdf:Producer>Producer</pdf:Producer>', value)['XMP_PRODUCER']['level'])

    def test_producer_text_has_no_invented_product_name_grammar(self):
        self.assertEqual('PASS', self.check('>Producer<', '>producer@example.invalid<')['XMP_PRODUCER']['level'])

    def test_creation_date_requires_a_normal_date_value(self):
        for value in ('<xmp:CreateDate rdf:resource="urn:date"/>',
                      '<xmp:CreateDate><rdf:Seq><rdf:li>2026</rdf:li></rdf:Seq></xmp:CreateDate>'):
            with self.subTest(value=value):
                self.assertEqual('FAIL', self.check('<xmp:CreateDate>2026-10-20T12:00:00Z</xmp:CreateDate>', value)['XMP_CREATEDATE']['level'])

    def test_creation_date_preserves_reduced_precision_and_unknown_timezone(self):
        for value in ('2026', '2026-10', '2026-10-20', '2026-10-20T12:00',
                      '2026-10-20T12:00Z', '2026-10-20T12:00:01.25-04:00', '2024-02-29'):
            with self.subTest(value=value):
                self.assertEqual('PASS', self.check('2026-10-20T12:00:00Z', value)['XMP_CREATEDATE']['level'])

    def test_impossible_or_malformed_creation_dates_fail(self):
        for value in ('not a date', '2026-02-29', '2026-13', '2026-01-32', '2026-10-20T25:00',
                      '2026-10-20T12:60Z', '2026-10-20T12:00:00+24:00', ' 2026 ', ''):
            with self.subTest(value=value):
                self.assertEqual('FAIL', self.check('2026-10-20T12:00:00Z', value)['XMP_CREATEDATE']['level'])

    def test_qualified_normal_values_retain_their_types(self):
        for tag, value, code in (('pdf:Producer', 'Producer', 'XMP_PRODUCER'), ('xmp:CreateDate', '2026-10-20T12:00:00Z', 'XMP_CREATEDATE')):
            with self.subTest(tag=tag):
                replacement = f'<{tag} rdf:parseType="Resource"><rdf:value>{value}</rdf:value><ex:note>Qualifier</ex:note></{tag}>'
                self.assertEqual('PASS', self.check(f'<{tag}>{value}</{tag}>', replacement)[code]['level'])

    def test_later_invalid_repeated_properties_are_not_hidden_by_the_first(self):
        for extra, code in (('<pdf:Producer rdf:resource="urn:producer"/>', 'XMP_PRODUCER'), ('<xmp:CreateDate>not a date</xmp:CreateDate>', 'XMP_CREATEDATE')):
            with self.subTest(code=code):
                rows = self.check('</xmp:CreateDate>', '</xmp:CreateDate>' + extra)
                self.assertEqual('FAIL', rows[code]['level'])

if __name__ == '__main__':
    unittest.main()
