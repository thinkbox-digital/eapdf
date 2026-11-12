from types import SimpleNamespace
import unittest
from unittest.mock import call, patch

import validate_eapdf as validator


class PdfRuleCompatibilityTests(unittest.TestCase):
    def test_version_and_catalog_rules_use_live_root_callbacks(self):
        pdf = SimpleNamespace(Root={}, pages=[None])
        report = validator.Report("mail.pdf")
        with patch.object(validator, "PDFA_COVERED_RULES", set()), \
                patch.object(validator, "get_declared_pdf_version", return_value=("1.7", "")) as declared, \
                patch.object(validator, "effective_pdf_version", return_value="1.7") as effective, \
                patch.object(validator, "expected_navigation_mode", return_value="UseAttachments") as mode:
            validator.check_pdf_version(pdf, report)
            validator.check_pages(pdf, report)
            validator.check_catalog_rules(pdf, report, "c")
        declared.assert_called_once_with(pdf)
        effective.assert_called_once_with("1.7", "")
        mode.assert_called_once_with("c", None, None, None)
        self.assertTrue(any(row["code"] == "PDF_EFFECTIVE_VERSION" and row["level"] == "PASS"
                            for row in report.items))
        page_mode = next(row for row in report.items if row["code"] == "PAGEMODE_PRESENT")
        self.assertEqual("UseAttachments", page_mode["details"]["expected"])
        self.assertEqual("c", page_mode["details"]["profile"])

    def test_font_rule_uses_root_discovery_and_retains_delegation(self):
        pdf = SimpleNamespace()
        report = validator.Report("mail.pdf")
        with patch.object(validator, "PDFA_COVERED_RULES", set()), \
                patch.object(validator, "collect_all_fonts", return_value=[{"embedded": False}]) as fonts:
            validator.check_fonts(pdf, report)
            self.assertEqual("FAIL", next(row for row in report.items if row["code"] == "FONTS_MISSING")["level"])
            delegated = validator.Report("mail.pdf")
            delegated.delegated_codes.update({"FONTS_EMBEDDED", "FONTS_MISSING", "FONTS_ALL_EMBEDDED"})
            validator.check_fonts(pdf, delegated)
            self.assertEqual([], delegated.items)
        self.assertEqual([call(pdf), call(pdf)], fonts.call_args_list)


if __name__ == "__main__":
    unittest.main()
