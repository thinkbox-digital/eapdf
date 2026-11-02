"""A rule's strongest result must not hide its other observations or conditions."""
import unittest
from html import unescape

from test_preservation_safety import validator, web


class RefinedReportTests(unittest.TestCase):
    def test_collapsed_findings_preserve_every_observation(self):
        report = validator.Report("count.pdf")
        code = "ASSET_NUMBERMESSAGES_MATCH_RENDERED"
        report.ok(code, "The rendered count matches the first source", {"NumberMessages": "3", "rendered_messages": 3})
        report.warn(code, "The rendered count cannot be verified", {"assessment": "unavailable", "source": "<source>"})
        report.fail(code, "A different source has an incorrect count", {"expected": 2, "actual": 9})
        row = next(row for row in web.collapse_items(report.items) if row["code"] == code)
        self.assertEqual("FAIL", row["level"])
        self.assertEqual(["PASS", "WARN", "FAIL"], [item["level"] for item in row["occurrences"]])
        rendered = web.render_file_cell(row)
        for message in ("The rendered count matches the first source", "The rendered count cannot be verified",
                        "A different source has an incorrect count"):
            self.assertIn(message, rendered)
        self.assertIn("&lt;source&gt;", rendered)
        self.assertNotIn("<source>", rendered)

    def test_specification_includes_conditions_and_source_evidence(self):
        report = validator.Report("mail.pdf")
        code = "EMAIL_XMP_CONTAINER"
        report.ok(code, "The email container is well formed", {"records": 1})
        row = next(row for row in web.collapse_items(report.items) if row["code"] == code)
        finding = web.render_file_cell(row).split("<summary>Finding</summary>", 1)[1].split("</details>", 1)[0]
        self.assertIn("The email container is well formed", finding)
        for rendered in (web.render_file_cell(row), validator.render_html(report)):
            self.assertIn("Specification", rendered)
            self.assertIn("paraphrase, not a quotation", rendered)
            self.assertIn("nonempty Core Fields", rendered)
            self.assertIn("Evidence needed", rendered)
            self.assertIn("independently associated original email", rendered)
            self.assertIn("veraPDF", rendered)

    def test_local_role_map_choice_is_not_presented_as_authoritative_erratum(self):
        code = "MAIL_MESSAGE_ROLEMAP_DOCUMENT"
        row = {"code": code, "level": "PASS", "message": "Used tag maps to Document", "details": {},
               "spec_refs": validator.spec_references_for(code)}
        rendered = web.render_file_cell(row)
        self.assertIn("Local interpretation", rendered)
        self.assertIn("not an adopted erratum", rendered)

    def test_expected_values_remain_visible_for_passes_and_warnings(self):
        for level in ("PASS", "WARN", "FAIL"):
            with self.subTest(level=level):
                rendered = web.render_details({"actual": 3, "expected": 2}, level)
                self.assertIn("Observed", rendered)
                self.assertIn("Expected", rendered)
                self.assertIn("actual:", rendered)
                self.assertIn("expected:", rendered)

    def test_inferred_expectations_follow_the_observed_assertion(self):
        def expected(code, details):
            return web.infer_expected_value({"code":code}, details, {})
        self.assertIsNone(expected("SOURCE_IN_ISOLATED", {"source_count":1}))
        self.assertEqual(0, expected("ASSET_NUMBERMESSAGES_MATCH_RENDERED", {
            "check":"rendered_message_count", "rendered_messages":0, "declared":"1"}))
        self.assertIn("independently established", expected("DPM_EMAIL_CONTENT_SET_OWNERSHIP", {
            "assertion":"DPM-EMAIL-OWNERSHIP"}))
        for code in ("ASSET_CONTENTTYPE_MATCH", "ATTACHMENT_XMP_CONTENTTYPE_MATCH"):
            self.assertNotIn("non-generic", expected(code, {}))
        self.assertNotIn("Why Not Applied", web.render_details({"reason":"The supplied count is wrong"}, "FAIL"))

    def test_other_catalogue_is_not_given_current_specification_text(self):
        row = {"code":"SOURCE_MEDIATYPE_PARAMETERS_MATCH", "catalogue_notice":"Different catalogue; rerun validation."}
        rendered = web.render_spec_refs([], row)
        self.assertIn("Different catalogue", rendered)
        self.assertNotIn("Full linked requirement", rendered)

    def test_unknown_catalogue_preserves_retired_findings_without_current_meaning(self):
        results = [{"summary":{"file":"old.pdf"}, "items":[
            {"code":"MESSAGE_SOURCE_MATCH", "level":"FAIL", "message":"Original historical failure", "details":{"source":"<old>"}},
            {"code":"SOURCE_MEDIATYPE_PARAMETERS_MATCH", "level":"PASS", "message":"Old bundled parameter result", "details":{}}]}]
        rendered = web.render_comparison_table(results)
        self.assertIn("Original historical failure", rendered)
        self.assertIn("Old bundled parameter result", rendered)
        self.assertIn("Historical / unmapped rule", rendered)
        self.assertIn("does not identify its rule catalogue", rendered)
        self.assertNotIn("Full linked requirement (paraphrase)", rendered)
        self.assertIn("&lt;old&gt;", rendered)

    def test_dpm_prefix_expectation_permits_unrelated_extensions(self):
        text = web.infer_expected_value({"code":"DPM_KEY_PREFIX"}, {}, {})
        self.assertIn("unrelated extension keys may remain", text)

    def test_split_rules_have_independent_rows_and_explicit_predicates(self):
        report = validator.Report("counts.pdf")
        report.ok("ASSET_NUMBERMESSAGES_TYPE", "The supplied count is a valid integer", {"actual":2})
        report.fail("ASSET_NUMBERMESSAGES_MATCH_RENDERED", "Only one email was rendered", {"actual":2,"rendered_messages":1})
        rows = {row["code"]: row for row in web.collapse_items(report.items)}
        self.assertEqual("PASS", rows["ASSET_NUMBERMESSAGES_TYPE"]["level"])
        self.assertEqual("FAIL", rows["ASSET_NUMBERMESSAGES_MATCH_RENDERED"]["level"])
        for code in ("ASSET_NUMBERMESSAGES_TYPE", "ASSET_NUMBERMESSAGES_MATCH_RENDERED"):
            rendered = web.render_file_cell(rows[code])
            self.assertIn("This rule checks", rendered)
            self.assertIn("Full linked requirement (paraphrase)", rendered)
            self.assertIn(web.get_rule_text()[code]["validation_scope"]["check"]["assertion"], unescape(rendered))


if __name__ == "__main__":
    unittest.main()
