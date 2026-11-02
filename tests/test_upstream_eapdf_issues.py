"""Apply reviewed upstream dispositions without treating proposals as amendments."""
import unittest
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import validator, failed_codes


class CreationProvenanceTests(unittest.TestCase):
    def root(self, value):
        root = ET.Element(validator.qn(validator.NS_RDF, 'RDF'))
        desc = ET.SubElement(root, validator.qn(validator.NS_RDF, 'Description'))
        if value is not None:
            ET.SubElement(desc, validator.qn(validator.NS_XMP, 'CreateDate')).text = value
        return root

    def source(self):
        raw = b'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: source\r\n\r\nBody'
        return dict(afrelationship='Source', subtype='message/rfc822', bytes=raw,
                    objgen=(10, 0), filename='source.eml')

    def test_sent_relation_neither_proves_nor_disproves_creation_provenance(self):
        for date in ('2003-01-29T02:20:54Z', '2000-01-01T00:00:00Z', '2026-01-01T00:00:00Z'):
            with self.subTest(date=date):
                report = validator.Report('dates.pdf')
                validator.check_standard_xmp_semantics(self.root(date), [self.source()], report)
                rows = [item for item in report.items if item['code'] == 'XMP_CREATEDATE_SEMANTICS']
                self.assertEqual(['WARN'], [item['level'] for item in rows])
                self.assertEqual('unavailable', rows[0]['details']['assessment'])
                self.assertIn('provenance', rows[0]['message'].lower())

    def test_missing_source_does_not_fabricate_creation_provenance_pass(self):
        report = validator.Report('isolated.pdf')
        validator.check_standard_xmp_semantics(self.root('2026-01-01T00:00:00Z'), [], report)
        rows = [item for item in report.items if item['code'] == 'XMP_CREATEDATE_SEMANTICS']
        self.assertEqual(['WARN'], [item['level'] for item in rows])

    def test_missing_create_date_still_fails_required_field(self):
        report = validator.Report('missing.pdf')
        validator.check_eapdf_xmp(self.root(None), {}, report)
        self.assertIn('XMP_CREATEDATE', failed_codes(report))


class CollectionIssueTests(unittest.TestCase):
    def pdf(self, extension=5, base=None):
        pdf = pikepdf.Pdf.new()
        pdf.add_blank_page()
        pdf.Root.Version = pikepdf.Name('/1.7')
        pdf.Root.Extensions = pikepdf.Dictionary(ADBE=pikepdf.Dictionary(
            BaseVersion=pikepdf.Name('/1.7') if base is None else base, ExtensionLevel=extension))
        schema = pikepdf.Dictionary()
        for name, kind in (('To', 'S'), ('From', 'S'), ('Sent', 'D'), ('Subject', 'S'),
                           ('Message-ID', 'S'), ('Cc', 'S'), ('Bcc', 'S'), ('Size', 'Size'), ('Attachments', 'N')):
            schema['/' + name] = pikepdf.Dictionary(Subtype=pikepdf.Name('/' + kind), N=name)
        pdf.Root.Collection = pikepdf.Dictionary(Schema=schema)
        return pdf

    def test_extension_is_exactly_integer_five(self):
        for value, valid in ((4, False), (5, True), (6, False), ('5', False), (5.0, False), (True, False)):
            with self.subTest(value=value), self.pdf(value) as pdf:
                report = validator.Report('collection.pdf')
                validator.check_collection_rules(pdf, 'c', report)
                self.assertEqual(not valid, 'COLLECTION_EXTENSIONS' in failed_codes(report))

    def test_extension_base_version_must_be_a_name(self):
        with self.pdf(base=pikepdf.String('1.7')) as pdf:
            report = validator.Report('collection.pdf')
            validator.check_collection_rules(pdf, 'c', report)
            self.assertIn('COLLECTION_EXTENSIONS', failed_codes(report))

    def test_bcc_schema_field_stays_required_even_for_blank_bcc(self):
        with self.pdf() as pdf:
            member = pdf.make_indirect(pikepdf.Dictionary(CI=pikepdf.Dictionary(Bcc='')))
            report = validator.Report('blank-bcc.pdf')
            validator.check_collection_rules(pdf, 'c', report)
            self.assertEqual('', str(member.CI.Bcc))
            self.assertNotIn('COLLECTION_SCHEMA_FIELD', failed_codes(report))
            del pdf.Root.Collection.Schema.Bcc
            missing = validator.Report('missing-bcc.pdf')
            validator.check_collection_rules(pdf, 'c', missing)
            self.assertTrue(any(item['code'] == 'COLLECTION_SCHEMA_FIELD' and item['level'] == 'FAIL'
                                and item['details']['field'] == 'Bcc' for item in missing.items))


class CorrectedMarkedContentAssociationTests(unittest.TestCase):
    def document(self, operator='BDC', tag='AF', form=False):
        pdf = pikepdf.Pdf.new()
        page = pdf.add_blank_page()
        payload = pdf.make_stream(b'asset')
        payload.Subtype = pikepdf.Name('/text/plain')
        spec = pdf.make_indirect(pikepdf.Dictionary(F='asset.txt', UF='asset.txt',
                    AFRelationship=pikepdf.Name.Data, EF=pikepdf.Dictionary(F=payload)))
        resources = pikepdf.Dictionary(Properties=pikepdf.Dictionary(
            Association=pikepdf.Dictionary(MCAF=pikepdf.Array([spec]))))
        data = f'/{tag} /Association {operator}\n'.encode() + (b'EMC' if operator == 'BDC' else b'')
        if form:
            drawing = pdf.make_stream(data)
            drawing.Type = pikepdf.Name.XObject
            drawing.Subtype = pikepdf.Name.Form
            drawing.BBox = pikepdf.Array([0, 0, 100, 100])
            drawing.Resources = resources
            page.Resources = pikepdf.Dictionary(XObject=pikepdf.Dictionary(Drawing=drawing))
            page.Contents = pdf.make_stream(b'/Drawing Do')
        else:
            page.Resources = resources
            page.Contents = pdf.make_stream(data)
        return pdf

    def test_corrected_named_mcaf_association_is_read_on_page_and_form(self):
        for form in (False, True):
            with self.subTest(form=form), self.document(form=form) as pdf:
                records = validator.collect_marked_content_af_specs(pdf)
                self.assertEqual(1, len(records))
                self.assertEqual('asset.txt', records[0]['filename'])
                self.assertEqual(b'asset', records[0]['bytes'])

    def test_mcaf_does_not_associate_wrong_tag_or_marked_content_point(self):
        for tag, operator in (('Span', 'BDC'), ('AF', 'DP')):
            with self.subTest(tag=tag, operator=operator), self.document(tag=tag, operator=operator) as pdf:
                self.assertEqual([], validator.collect_marked_content_af_specs(pdf))

    def test_distinct_direct_file_specs_are_not_merged(self):
        with self.document() as pdf:
            page = pdf.pages[0]
            specs = pikepdf.Array()
            for name in ('first.txt', 'second.txt', 'third.txt'):
                stream = pdf.make_stream(name.encode())
                specs.append(pikepdf.Dictionary(F=name, EF=pikepdf.Dictionary(F=stream)))
            page.Resources.Properties.Association.MCAF = specs
            self.assertEqual(['first.txt', 'second.txt', 'third.txt'],
                             [row['filename'] for row in validator.collect_marked_content_af_specs(pdf)])


if __name__ == '__main__':
    unittest.main()
