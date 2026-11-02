"""Exercise previously unobserved PDF/A-3 and recommendation assertions."""
import unittest
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import validator as v


class MetadataCoverageTests(unittest.TestCase):
    values = dict(Producer='PDF producer', Author='First author', Subject='Description',
                  Creator='Creation tool', CreationDate='D:20261020120000Z', ModDate='D:20261020120000Z')
    codes = dict(Producer='DOCINFO_PRODUCER_MATCH', Author='DOCINFO_AUTHOR_MATCH',
                 Subject='DOCINFO_SUBJECT_MATCH', Creator='DOCINFO_CREATOR_MATCH',
                 CreationDate='DOCINFO_CREATIONDATE_MATCH', ModDate='DOCINFO_MODDATE_MATCH')

    def metadata(self):
        return ET.fromstring(f'''<rdf:RDF xmlns:rdf="{v.NS_RDF}">
<rdf:Description xmlns:dc="{v.NS_DC}" xmlns:pdf="{v.NS_PDF}" xmlns:xmp="{v.NS_XMP}">
<pdf:Producer>PDF producer</pdf:Producer>
<dc:creator><rdf:Seq><rdf:li>First author</rdf:li><rdf:li>Other author</rdf:li></rdf:Seq></dc:creator>
<dc:description><rdf:Alt><rdf:li xml:lang="x-default">Description</rdf:li></rdf:Alt></dc:description>
<xmp:CreatorTool>Creation tool</xmp:CreatorTool>
<xmp:CreateDate>2026-10-20T08:00:00-04:00</xmp:CreateDate>
<xmp:ModifyDate>2026-10-20T12:00:00Z</xmp:ModifyDate>
<pdf:Keywords>EA-PDF</pdf:Keywords></rdf:Description></rdf:RDF>''')

    def check(self, values, metadata=None, part=3):
        with pikepdf.Pdf.new() as pdf:
            for key, value in values.items():
                pdf.docinfo['/' + key] = value
            report = v.Report('independent-info.pdf')
            v.check_docinfo_consistency(pdf, self.metadata() if metadata is None else metadata, part, report)
            return report

    def test_each_supplied_info_field_matches_its_xmp_representation(self):
        report = self.check(self.values)
        self.assertEqual({code: 'PASS' for code in self.codes.values()},
                         {item['code']: item['level'] for item in report.items})

    def test_each_mismatched_info_field_fails_its_own_rule(self):
        for key, code in self.codes.items():
            with self.subTest(field=key):
                values = dict(self.values, **{key: 'D:20261021120000Z' if 'Date' in key else 'Different value'})
                report = self.check(values)
                self.assertEqual([code], [item['code'] for item in report.items if item['level'] == 'FAIL'])

    def test_each_supplied_field_without_matching_xmp_fails(self):
        empty = ET.Element('metadata')
        report = self.check(self.values, empty)
        self.assertEqual(set(self.codes.values()), {item['code'] for item in report.items if item['level'] == 'FAIL'})

    def test_absent_optional_info_fields_do_not_fabricate_comparisons(self):
        self.assertEqual([], self.check({}).items)

    def test_pdfa4_does_not_activate_the_pdfa3_crosswalk(self):
        self.assertEqual([], self.check(self.values, part=4).items)

    def test_info_author_uses_the_first_ordered_creator(self):
        report = self.check(dict(Author='Other author'))
        self.assertEqual([('DOCINFO_AUTHOR_MATCH', 'FAIL')], [(i['code'], i['level']) for i in report.items])

    def test_pdfa3_info_keyword_marker_presence_and_absence(self):
        for keyword, expected in [('Archive; EA-PDF', 'PASS'), ('Archive', 'FAIL'), ('', 'FAIL')]:
            with self.subTest(keyword=keyword), pikepdf.Pdf.new() as pdf:
                pdf.docinfo.Keywords = keyword
                report = v.Report('keywords.pdf')
                v.check_keywords(pdf, self.metadata(), 3, report)
                self.assertEqual([expected], [i['level'] for i in report.items if i['code'] == 'DOCINFO_KEYWORDS'])

    def test_generic_source_media_types_are_recommendation_warnings(self):
        for media in sorted(v.GENERIC_BAD_MIME):
            with self.subTest(media=media):
                report = v.Report('generic.pdf')
                v.check_source_media_types([dict(afrelationship='Source', subtype=media,
                    filename='source.bin', ufilename='', tree_name='source.bin')], 's', report)
                self.assertEqual([('SOURCE_MIME_GENERIC', 'WARN')], [(i['code'], i['level']) for i in report.items])
                self.assertNotEqual('FAIL', report.summary()['status'])


if __name__ == '__main__':
    unittest.main()
