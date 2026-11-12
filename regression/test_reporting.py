import tempfile
import pickle
from pathlib import Path
import unittest
from unittest.mock import patch

import validate_eapdf as validator


class ReportingCompatibilityTests(unittest.TestCase):
    def test_report_uses_live_root_policy_and_retains_rule_precedence(self):
        with patch.object(validator, "RULEBOOK_SHA256", "creation-time hash"):
            report = validator.Report("mail.pdf")
        with patch.object(validator, "PDFA_COVERED_RULES", {"DELEGATED"}), \
                patch.object(validator, "ALL_RULE_CODES", ["LOCAL"]):
            report.ok("DELEGATED", "handled elsewhere")
            report.warn("LOCAL", "warning")
            report.fail("LOCAL", "failure")
            summary = report.summary()
        self.assertEqual("creation-time hash", summary["rulebook_sha256"])
        self.assertEqual(2, len(report.items))
        self.assertEqual("FAIL", summary["status"])
        self.assertEqual(1, summary["rule_total"])
        self.assertEqual(1, summary["rule_fail"])
        self.assertEqual(0, summary["rule_warn"])

    def test_report_pickle_round_trip_keeps_public_type_state_and_behavior(self):
        report = validator.Report("mail.pdf")
        report.delegated_codes.add("DELEGATED")
        report.warn("VALIDATION_INCOMPLETE", "unavailable", {"state": "not run"})
        restored = pickle.loads(pickle.dumps(report))
        self.assertIs(type(restored), validator.Report)
        self.assertEqual(report.__dict__, restored.__dict__)
        self.assertEqual(report.summary(), restored.summary())
        self.assertTrue(restored.delegates("DELEGATED"))
        self.assertEqual("INCOMPLETE", restored.summary()["status"])

    def test_html_uses_live_root_callbacks_and_escapes_email_values(self):
        report = validator.Report("<email>.pdf")
        report.warn("CUSTOM_RULE", "<unsafe>", {"field": "value"})
        with patch.object(validator, "VALIDATION_SCOPE", "scope marker"), \
                patch.object(validator, "combined_validation_status", return_value="INCOMPLETE") as status, \
                patch.object(validator, "spec_references_for", return_value=[]) as references, \
                patch.object(validator, "format_details", return_value="detail marker") as details:
            html = validator.render_html(report)
        status.assert_called_once()
        references.assert_called_once_with("CUSTOM_RULE")
        details.assert_called_once_with({"field": "value"})
        self.assertIn("scope marker", html)
        self.assertIn("detail marker", html)
        self.assertIn("&lt;unsafe&gt;", html)
        self.assertNotIn("<unsafe>", html)
        self.assertIn("&lt;email&gt;.pdf", html)

    def test_report_save_uses_root_path_and_renderer_callbacks(self):
        report = validator.Report("mail.pdf")
        pdfa = {"status": "PASS"}
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "report.html"
            with patch.object(validator, "derive_html_path", return_value=destination) as derive, \
                    patch.object(validator, "render_html", return_value="Résumé report") as render:
                saved = validator.write_html_report(report, "mail.pdf", "requested.html", pdfa)
            self.assertEqual(destination, saved)
            self.assertEqual("Résumé report", saved.read_text(encoding="utf-8"))
            derive.assert_called_once_with("mail.pdf", "requested.html")
            render.assert_called_once_with(report, pdfa)

    def test_known_failure_wins_over_incomplete_and_pass_requires_both_checks(self):
        for ea, pdfa, expected in [("FAIL", "INCOMPLETE", "FAIL"),
                                   ("INCOMPLETE", "FAIL", "FAIL"),
                                   ("PASS", "INCOMPLETE", "INCOMPLETE"),
                                   ("INCOMPLETE", "PASS", "INCOMPLETE"),
                                   ("PASS", "PASS", "PASS")]:
            with self.subTest(ea=ea, pdfa=pdfa):
                self.assertEqual(expected, validator.combined_validation_status(
                    {"status": ea}, {"status": pdfa, "ran": True,
                                     "compliant": True, "profile_allowed": True}))
        self.assertEqual("INCOMPLETE", validator.combined_validation_status(
            {"status": "PASS"}, {"status": "PASS", "ran": False}))
        self.assertEqual("INCOMPLETE", validator.batch_validation_status([]))


if __name__ == "__main__":
    unittest.main()
