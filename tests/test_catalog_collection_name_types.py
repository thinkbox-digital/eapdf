"""Catalog Version and required Collection Subtype values are PDF names."""
import io
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes
import test_upstream_eapdf_issues as collection_tests


class CatalogCollectionNameTests(unittest.TestCase):
    def version(self, value=None, header='2.0'):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            if value is not None:
                pdf.Root.Version = value
            data = io.BytesIO()
            pdf.save(data, force_version=header)
        with pikepdf.Pdf.open(io.BytesIO(data.getvalue())) as pdf:
            report = v.Report('version.pdf')
            v.check_pdf_version(pdf, report)
            return report, v.get_declared_pdf_version(pdf)

    def test_valid_name_and_absent_version_retain_header_fallback(self):
        for value in (None, pikepdf.Name('/2.0'), pikepdf.Name('/1.7'), pikepdf.Name('/1.6')):
            with self.subTest(value=value):
                report, (header, declared) = self.version(value)
                self.assertFalse(failed_codes(report))
                self.assertEqual('2.0', header)
                self.assertIn(declared, ('', '2.0', '1.7', '1.6'))

    def test_only_eapdf_header_versions_are_allowed_even_with_a_valid_catalog(self):
        for header in ('1.4', '1.5', '1.6', '1.7', '2.0'):
            with self.subTest(header=header):
                report, _ = self.version(pikepdf.Name('/2.0'), header)
                self.assertEqual(header not in ('1.7', '2.0'),
                                 'PDF_HEADER_VERSION_READ' in failed_codes(report))

    def test_malformed_named_versions_cannot_look_absent_or_numeric(self):
        for value in ('/', '/2.00', '/20', '/ 2.0', '/2.0 '):
            with self.subTest(value=value):
                report, _ = self.version(pikepdf.Name(value))
                self.assertIn('PDF_CATALOG_VERSION', failed_codes(report))

    def test_non_name_versions_fail_and_cannot_override_the_header(self):
        for value in (pikepdf.String('2.0'), pikepdf.String('/2.0'), pikepdf.String('1.7'),
                      pikepdf.String(''), 20, True, pikepdf.Array(), pikepdf.Dictionary()):
            with self.subTest(value=value):
                report, (header, declared) = self.version(value)
                self.assertIn('PDF_CATALOG_VERSION', failed_codes(report))
                self.assertEqual(('2.0', ''), (header, declared))
                row = next(i for i in report.items if i['code'] == 'PDF_EFFECTIVE_VERSION')
                self.assertEqual('2.0', row['details']['effective_version'])

    def test_disallowed_named_version_still_fails(self):
        # In-memory check avoids asking qpdf to write an unsupported PDF version.
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.Root.Version = pikepdf.Name('/1.8')
            report = v.Report('bad-version.pdf')
            v.check_pdf_version(pdf, report)
            self.assertTrue({'PDF_CATALOG_VERSION', 'PDF_EFFECTIVE_VERSION'} <= failed_codes(report))

    def test_lower_catalog_name_cannot_downgrade_header_or_collection_requirements(self):
        for header, catalog, expected in [('1.7', '2.0', '2.0'), ('2.0', '1.7', '2.0'),
                                           ('1.7', '', '1.7'), ('1.7', '1.7', '1.7')]:
            with self.subTest(header=header, catalog=catalog):
                self.assertEqual(expected, v.effective_pdf_version(header, catalog))
        path = ROOT / 'tests/fixtures/eapdf/validator-booleans/collection-false.pdf'
        with pikepdf.Pdf.open(path) as pdf:
            self.assertEqual('2.0', str(pdf.pdf_version))
            pdf.Root.Version = pikepdf.Name('/1.7')
            report = v.Report('lower-catalog.pdf')
            v.check_pdf_version(pdf, report)
            v.check_collection_rules(pdf, 'ci', report)
            self.assertFalse(failed_codes(report))
            self.assertNotIn('COLLECTION_EXTENSIONS', report.seen_codes)

    def collection_rows(self, pdf):
        report = v.Report('schema.pdf')
        v.check_collection_rules(pdf, 'c', report)
        return {item['details']['field']: item for item in report.items if item['code'] == 'COLLECTION_SCHEMA_FIELD'}

    def test_all_nine_prescribed_subtypes_accept_names(self):
        with collection_tests.CollectionIssueTests().pdf() as pdf:
            rows = self.collection_rows(pdf)
            self.assertEqual(9, len(rows))
            self.assertEqual({'PASS'}, {row['level'] for row in rows.values()})

    def test_text_subtypes_cannot_satisfy_any_required_field(self):
        for slash in ('', '/'):
            with self.subTest(slash=slash), collection_tests.CollectionIssueTests().pdf() as pdf:
                for field in pdf.Root.Collection.Schema.values():
                    field.Subtype = pikepdf.String(slash + str(field.Subtype).lstrip('/'))
                rows = self.collection_rows(pdf)
                self.assertEqual(9, len(rows))
                self.assertEqual({'FAIL'}, {row['level'] for row in rows.values()})
                self.assertTrue(all(row['details']['is_pdf_name'] is False for row in rows.values()))

    def test_wrong_or_missing_subtypes_fail_and_extra_custom_fields_remain_allowed(self):
        for absent in (False, True):
            with self.subTest(absent=absent), collection_tests.CollectionIssueTests().pdf() as pdf:
                pdf.Root.Collection.Schema.Extra = pikepdf.Dictionary(Subtype=pikepdf.Name.N, N='Extra')
                if absent:
                    del pdf.Root.Collection.Schema.To.Subtype
                else:
                    pdf.Root.Collection.Schema.To.Subtype = pikepdf.Name.D
                rows = self.collection_rows(pdf)
                self.assertEqual('FAIL', rows['To']['level'])
                self.assertEqual(['To'], [name for name, row in rows.items() if row['level'] == 'FAIL'])


if __name__ == '__main__':
    unittest.main()
