"""Info/XMP synchronization must not turn PDF numbers or names into strings."""
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET
import pikepdf
from test_preservation_safety import validator as v, failed_codes


class DocinfoPdfTypeTests(unittest.TestCase):
    fields = [('Title', 'dc:title', 'DOCINFO_TITLE_MATCH'), ('Subject', 'dc:description', 'DOCINFO_SUBJECT_MATCH'),
              ('Author', 'dc:creator', 'DOCINFO_AUTHOR_MATCH'), ('Creator', 'xmp:CreatorTool', 'DOCINFO_CREATOR_MATCH'),
              ('Producer', 'pdf:Producer', 'DOCINFO_PRODUCER_MATCH'), ('Keywords', 'pdf:Keywords', 'DOCINFO_KEYWORDS'),
              ('CreationDate', 'xmp:CreateDate', 'DOCINFO_CREATIONDATE_MATCH'), ('ModDate', 'xmp:ModifyDate', 'DOCINFO_MODDATE_MATCH')]

    def check(self, field, tag, raw, text, part=3):
        if tag in ('dc:title', 'dc:description'):value=f'<{tag}><rdf:Alt><rdf:li xml:lang="x-default">{text}</rdf:li></rdf:Alt></{tag}>'
        elif tag=='dc:creator':value=f'<dc:creator><rdf:Seq><rdf:li>{text}</rdf:li></rdf:Seq></dc:creator>'
        else:value=f'<{tag}>{text}</{tag}>'
        metadata=ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:dc="{v.NS_DC}" xmlns:pdf="{v.NS_PDF}" xmlns:xmp="{v.NS_XMP}"><rdf:Description>{value}</rdf:Description></rdf:RDF>')
        with pikepdf.Pdf.new() as pdf:
            pdf.docinfo['/'+field]=raw
            report=v.Report('info-type.pdf');v.check_docinfo_consistency(pdf,metadata,part,report)
            return report

    def test_all_info_fields_reject_numbers_even_when_coerced_text_would_match(self):
        for field,tag,code in self.fields:
            with self.subTest(field=field):
                self.assertIn(code,failed_codes(self.check(field,tag,2026,'2026')))

    def test_pdf_names_are_not_text_strings(self):
        report=self.check('Keywords','pdf:Keywords',pikepdf.Name('/EA-PDF'),'/EA-PDF')
        self.assertEqual({'DOCINFO_KEYWORDS'},failed_codes(report))
        row=next(i for i in report.items if i['code']=='DOCINFO_KEYWORDS')
        self.assertIs(False,row['details']['is_pdf_string'])

    def test_array_dictionary_and_boolean_values_are_not_coerced(self):
        for value in (pikepdf.Array([42]),pikepdf.Dictionary(Value=42),True):
            with self.subTest(value=value):
                self.assertIn('DOCINFO_TITLE_MATCH',failed_codes(self.check('Title','dc:title',value,str(value))))

    def test_numeric_and_name_like_text_strings_are_permitted(self):
        for field,tag,code in self.fields[:6]:
            for value in ('42','/EA-PDF'):
                with self.subTest(field=field,value=value):
                    self.assertNotIn(code,failed_codes(self.check(field,tag,pikepdf.String(value),value)))

    def test_ordinary_pdf_date_strings_keep_their_comparisons(self):
        for field,tag,code in self.fields[6:]:
            with self.subTest(field=field):
                self.assertNotIn(code,failed_codes(self.check(field,tag,pikepdf.String('D:20261020120000Z'),'2026-10-20T12:00:00Z')))

    def test_explicit_empty_text_stays_a_text_value(self):
        self.assertFalse(failed_codes(self.check('Title','dc:title',pikepdf.String(''),'')))

    def test_null_optional_dictionary_values_mean_absence(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.trailer.Info=pdf.make_indirect(pikepdf.Object.parse(b'<< /Title null /Keywords (EA-PDF) >>'))
            x=ET.Element('metadata')
            report=v.Report('null-info.pdf');v.check_docinfo_consistency(pdf,x,3,report)
            self.assertNotIn('DOCINFO_TITLE_MATCH',failed_codes(report))
            context=v.build_validation_context(pdf,x,'s',3,[],[])
            with patch.object(v,'ALL_RULE_CODES',['DOCINFO_TITLE_MATCH']):v.finalize_rule_matrix(report,context)
            self.assertEqual(['N/A'],[i['level'] for i in report.items if i['code']=='DOCINFO_TITLE_MATCH'])

    def test_pdfa4_does_not_activate_the_info_crosswalk(self):
        self.assertEqual([],self.check('Title','dc:title',42,'42',part=4).items)

if __name__=='__main__':unittest.main()
