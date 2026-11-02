"""PDF/A-3 Info crosswalk: target language, Keywords and optional key applicability."""
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET
import pikepdf
from test_preservation_safety import validator as v, failed_codes


class DocinfoCrosswalkTests(unittest.TestCase):
    def xmp(self, property, items):
        return ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:dc="{v.NS_DC}" xmlns:pdf="{v.NS_PDF}"><rdf:Description><dc:{property}><rdf:Alt>{items}</rdf:Alt></dc:{property}></rdf:Description></rdf:RDF>')
    def li(self, lang, text):return f'<rdf:li xml:lang="{lang}">{text}</rdf:li>'
    def check(self, info_key, value, metadata):
        with pikepdf.Pdf.new() as pdf:
            pdf.docinfo['/' + info_key] = value
            report = v.Report('crosswalk.pdf')
            v.check_docinfo_consistency(pdf, metadata, 3, report)
            return report

    def test_title_and_subject_match_the_default_language(self):
        for info, prop, code in [('Title', 'title', 'DOCINFO_TITLE_MATCH'), ('Subject', 'description', 'DOCINFO_SUBJECT_MATCH')]:
            for items in (self.li('x-default', 'Default') + self.li('fr', 'Localized'), self.li('fr', 'Localized') + self.li('x-default', 'Default')):
                with self.subTest(info=info, items=items):
                    self.assertNotIn(code, failed_codes(self.check(info, 'Default', self.xmp(prop, items))))
                    self.assertIn(code, failed_codes(self.check(info, 'Localized', self.xmp(prop, items))))

    def test_localized_only_text_cannot_satisfy_the_crosswalk(self):
        for info, prop, code in [('Title', 'title', 'DOCINFO_TITLE_MATCH'), ('Subject', 'description', 'DOCINFO_SUBJECT_MATCH')]:
            with self.subTest(info=info):
                self.assertIn(code, failed_codes(self.check(info, 'Localized', self.xmp(prop, self.li('fr', 'Localized')))))

    def test_an_explicit_empty_default_can_match_an_empty_info_string(self):
        for info, prop in [('Title', 'title'), ('Subject', 'description')]:
            with self.subTest(info=info):
                self.assertFalse(failed_codes(self.check(info, '', self.xmp(prop, self.li('x-default', '') + self.li('fr', 'Localized')))))

    def test_keywords_must_match_xmp_as_well_as_contain_the_marker(self):
        x = ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:pdf="{v.NS_PDF}"><rdf:Description><pdf:Keywords>EA-PDF; Archive</pdf:Keywords></rdf:Description></rdf:RDF>')
        for value, expected in [('EA-PDF; Archive', 'PASS'), ('EA-PDF; Different', 'FAIL')]:
            with self.subTest(value=value):
                rows = self.check('Keywords', value, x).items
                self.assertEqual([expected], [i['level'] for i in rows if i['code'] == 'DOCINFO_KEYWORDS'])

    def test_info_keywords_without_a_normal_xmp_text_value_fail(self):
        for content in ('', '<pdf:Keywords rdf:resource="urn:EA-PDF"/>'):
            x = ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:pdf="{v.NS_PDF}"><rdf:Description>{content}</rdf:Description></rdf:RDF>')
            self.assertIn('DOCINFO_KEYWORDS', failed_codes(self.check('Keywords', 'EA-PDF', x)))

    def test_absent_info_keywords_do_not_create_a_synchronization_claim(self):
        self.assertEqual([], self.check('Other', 'Extension', ET.Element('metadata')).items)

    def context(self, pdf):
        return v.build_validation_context(pdf, ET.Element('metadata'), 's', 3, [], [])

    def test_absent_optional_info_fields_are_na_but_present_unchecked_fields_remain_incomplete(self):
        codes = ['DOCINFO_TITLE_MATCH', 'DOCINFO_PRODUCER_MATCH', 'DOCINFO_AUTHOR_MATCH', 'DOCINFO_SUBJECT_MATCH', 'DOCINFO_CREATOR_MATCH', 'DOCINFO_CREATIONDATE_MATCH', 'DOCINFO_MODDATE_MATCH']
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.docinfo.Keywords = 'EA-PDF'
            report = v.Report('optional.pdf')
            with patch.object(v, 'ALL_RULE_CODES', codes):v.finalize_rule_matrix(report, self.context(pdf))
            self.assertEqual({'N/A'}, {i['level'] for i in report.items})
            pdf.docinfo.Title = 'Present'
            report = v.Report('unchecked.pdf')
            with patch.object(v, 'ALL_RULE_CODES', codes):v.finalize_rule_matrix(report, self.context(pdf))
            self.assertEqual('INCOMPLETE', report.summary()['status'])
            self.assertEqual(['DOCINFO_TITLE_MATCH'], [i['code'] for i in report.items if i['level'] == 'WARN' and i['code'] in codes])

    def test_malformed_info_is_not_proof_of_optional_key_absence(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page();pdf.trailer.Info = 42
            report = v.Report('bad-info.pdf')
            with patch.object(v, 'ALL_RULE_CODES', ['DOCINFO_TITLE_MATCH']):v.finalize_rule_matrix(report, self.context(pdf))
            self.assertEqual('INCOMPLETE', report.summary()['status'])

if __name__ == '__main__':unittest.main()
