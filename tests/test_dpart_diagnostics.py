from scripts.eapdf_rule_review import load_review_rules
import unittest

import pikepdf

from test_preservation_safety import validator, web


class PageDpartDiagnosticsTests(unittest.TestCase):
    def document(self, ranges, page_count=3):
        document = pikepdf.Pdf.new()
        for page_number in range(page_count):
            document.add_blank_page()
        root = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPartRoot))
        root_node = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=root))
        root.DPartRootNode = root_node
        leaves = []
        for start, end in ranges:
            leaf = document.make_indirect(pikepdf.Dictionary(
                Type=pikepdf.Name.DPart, Parent=root_node, Start=document.pages[start - 1].obj,
                DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.BodyRendering,
                                      Mail_Subtype=pikepdf.Name("/text/plain"))))
            if start != end:
                leaf.End = document.pages[end - 1].obj
            for page_number in range(start, end + 1):
                document.pages[page_number - 1].obj.DPart = leaf
            leaves.append(leaf)
        root_node.DParts = pikepdf.Array([pikepdf.Array(leaves)])
        document.Root.DPartRoot = root
        return document, leaves

    def check(self, document):
        _, _, nodes = validator.get_dpart_nodes(document)
        report = validator.Report("diagnostic.pdf")
        validator.check_page_dpart_links(document, nodes, report)
        self.assertEqual(1, len(report.items))
        return report, report.items[0]

    def test_bfo_style_gap_identifies_pdf_page_two_and_both_causes(self):
        document, leaves = self.document([(1, 1), (3, 3)])
        with document:
            report, finding = self.check(document)
            self.assertEqual("FAIL", finding["level"])
            self.assertEqual([2], finding["details"]["pages"])
            self.assertEqual(1, finding["details"]["count"])
            self.assertEqual([2], finding["details"]["missing_dpart_pages"])
            self.assertEqual([2], finding["details"]["uncovered_pages"])
            self.assertEqual([], finding["details"]["invalid_dpart_pages"])
            self.assertIn("Missing /DPart entry on PDF page 2", finding["message"])
            self.assertIn("Not covered by any DPart content-set range on PDF page 2", finding["message"])
            self.assertNotIn("PDF page 1", finding["message"])
            self.assertIn("PDF page 2", finding["details"]["page_issues"])

    def test_missing_back_link_does_not_claim_existing_range_is_missing(self):
        document, leaves = self.document([(1, 3)])
        with document:
            del document.pages[1].obj.DPart
            report, finding = self.check(document)
            self.assertEqual("FAIL", finding["level"])
            self.assertEqual([2], finding["details"]["missing_dpart_pages"])
            self.assertEqual([], finding["details"]["uncovered_pages"])
            self.assertEqual("Missing /DPart entry on PDF page 2", finding["message"])

    def test_detached_link_does_not_hide_a_gap_in_the_catalog_tree(self):
        document, leaves = self.document([(1, 1), (3, 3)])
        with document:
            document.pages[1].obj.DPart = document.make_indirect(pikepdf.Dictionary(
                Type=pikepdf.Name.DPart, Start=document.pages[1].obj))
            report, finding = self.check(document)
            self.assertEqual("FAIL", finding["level"])
            self.assertEqual([], finding["details"]["missing_dpart_pages"])
            self.assertEqual([2], finding["details"]["uncovered_pages"])
            self.assertEqual([2], finding["details"]["invalid_dpart_pages"])

    def test_link_to_another_pages_content_set_is_rejected(self):
        document, leaves = self.document([(1, 1), (2, 2), (3, 3)])
        with document:
            document.pages[1].obj.DPart = leaves[0]
            report, finding = self.check(document)
            self.assertEqual("FAIL", finding["level"])
            self.assertEqual([], finding["details"]["missing_dpart_pages"])
            self.assertEqual([], finding["details"]["uncovered_pages"])
            self.assertEqual([2], finding["details"]["invalid_dpart_pages"])
            self.assertEqual("Invalid or mismatched /DPart link on PDF page 2", finding["message"])

    def test_non_dictionary_link_fails_without_interrupting_validation(self):
        document, leaves = self.document([(1, 3)])
        with document:
            document.pages[1].obj.DPart = pikepdf.Name.NotADPart
            report, finding = self.check(document)
            self.assertEqual("FAIL", finding["level"])
            self.assertEqual([2], finding["details"]["invalid_dpart_pages"])
            self.assertEqual([], finding["details"]["uncovered_pages"])

    def test_valid_single_and_multi_page_content_sets_pass(self):
        for ranges in ([(1, 3)], [(1, 1), (2, 3)]):
            document, leaves = self.document(ranges)
            with document:
                report, finding = self.check(document)
                self.assertEqual("PASS", finding["level"])
                self.assertEqual({"page_count": 3}, finding["details"])

    def test_long_page_lists_are_bounded_without_losing_total_count(self):
        document, leaves = self.document([(1, 1), (70, 70)], page_count=70)
        with document:
            report, finding = self.check(document)
            self.assertEqual(68, finding["details"]["count"])
            self.assertEqual(list(range(2, 27)), finding["details"]["pages"])
            self.assertEqual(25, len(finding["details"]["page_issues"]))
            self.assertIn("first 25 of 68", finding["message"])

    def test_web_and_standalone_reports_keep_correct_page_numbers_and_reasons(self):
        document, leaves = self.document([(1, 1), (3, 3)])
        with document:
            report, finding = self.check(document)
            rows = web.collapse_items(report.items)
            row = next(row for row in rows if row["code"] == "PAGE_DPART_PRESENT")
            rendered = web.render_file_cell(row)
            self.assertIn("PDF page numbers (1-based)", rendered)
            self.assertIn("Affected page count", rendered)
            self.assertIn("<div class='detail-list'>2</div>", rendered)
            self.assertIn("Not covered by any DPart content-set range on PDF page 2", rendered)
            template = load_review_rules()["PAGE_DPART_PRESENT"]
            self.assertEqual(finding["message"], web.render_plain_language_from_template(template, finding))
            standalone = validator.render_html(report)
            self.assertIn(finding["message"], standalone)


if __name__ == "__main__":
    unittest.main()
