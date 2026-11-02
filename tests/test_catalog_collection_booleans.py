"""EA-PDF 9.1/9.5 require Boolean values, not Python truthiness."""
import io
import unittest

import pikepdf

from test_preservation_safety import validator as v
import test_upstream_eapdf_issues as collection_tests


class CatalogCollectionBooleanTests(unittest.TestCase):
    def title_level(self, value, present=True):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.Root.ViewerPreferences = pikepdf.Dictionary(
                NonFullScreenPageMode=pikepdf.Name.UseOutlines)
            pdf.Root.PageMode = pikepdf.Name.UseOutlines
            if present:
                pdf.Root.ViewerPreferences.DisplayDocTitle = value
            # Exercise the decoded values that the normal pipeline receives.
            buffer = io.BytesIO()
            pdf.save(buffer)
        with pikepdf.Pdf.open(io.BytesIO(buffer.getvalue())) as pdf:
            report = v.Report('title.pdf')
            v.check_catalog_rules(pdf, report, 's')
            return [i['level'] for i in report.items if i['code'] == 'DISPLAY_DOCTITLE']

    def collection_level(self, value, present=True, extra=False):
        with collection_tests.CollectionIssueTests().pdf() as pdf:
            if extra:
                pdf.Root.Collection.Schema.Extra = pikepdf.Dictionary(Subtype=pikepdf.Name.S, N='Extra')
            field = pdf.Root.Collection.Schema.Extra if extra else pdf.Root.Collection.Schema.To
            if present:
                field.E = value
            buffer = io.BytesIO()
            pdf.save(buffer)
        with pikepdf.Pdf.open(io.BytesIO(buffer.getvalue())) as pdf:
            report = v.Report('collection.pdf')
            v.check_collection_rules(pdf, 'c', report)
            name = '/Extra' if extra else '/To'
            return [i['level'] for i in report.items
                    if i['code'] == 'COLLECTION_SCHEMA_E_FALSE' and i['details']['field'] == name]

    def test_title_requires_true_boolean(self):
        self.assertEqual(['PASS'], self.title_level(True))
        self.assertEqual(['FAIL'], self.title_level(False))

    def test_truthy_nonboolean_title_values_fail(self):
        for value in (1, -1, pikepdf.String('true'), pikepdf.Name.true,
                      pikepdf.Array([1]), pikepdf.Dictionary(Value=True)):
            with self.subTest(value=value):
                self.assertEqual(['FAIL'], self.title_level(value))

    def test_false_like_and_missing_title_values_fail(self):
        for value in (0, pikepdf.String(''), pikepdf.Array(), pikepdf.Dictionary()):
            with self.subTest(value=value):
                self.assertEqual(['FAIL'], self.title_level(value))
        self.assertEqual(['FAIL'], self.title_level(None, present=False))

    def test_collection_false_and_absent_e_are_allowed(self):
        for extra in (False, True):
            with self.subTest(extra=extra):
                self.assertEqual(['PASS'], self.collection_level(False, extra=extra))
                self.assertEqual(['PASS'], self.collection_level(None, present=False, extra=extra))

    def test_false_like_nonboolean_collection_values_fail(self):
        for value in (0, pikepdf.String(''), pikepdf.Array(), pikepdf.Dictionary()):
            for extra in (False, True):
                with self.subTest(value=value, extra=extra):
                    self.assertEqual(['FAIL'], self.collection_level(value, extra=extra))

    def test_true_and_truthy_collection_values_fail(self):
        for value in (True, 1, pikepdf.String('false'), pikepdf.Name.false):
            with self.subTest(value=value):
                self.assertEqual(['FAIL'], self.collection_level(value))


if __name__ == '__main__':
    unittest.main()
