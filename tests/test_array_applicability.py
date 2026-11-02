import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, email_metadata, validator, web


class ArrayApplicabilityTests(unittest.TestCase):
    def attribute(self, name="Mail_GUID", value="uuid:one"):
        return pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
            P=pikepdf.Array([pikepdf.Dictionary(N=name, V=value)]))

    def document(self, root_array=False, attribute_array=False):
        pdf = pikepdf.Pdf.new()
        pdf.add_blank_page()
        tree = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        root = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name.Document, P=tree))
        attribute = self.attribute()
        message = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name('/Mail_Message'), P=root,
            A=pikepdf.Array([attribute, 0]) if attribute_array else attribute))
        root.K = pikepdf.Array([message])
        tree.K = pikepdf.Array([root]) if root_array else root
        pdf.Root.StructTreeRoot = tree
        return pdf

    def test_user_property_dictionary_and_pdf_array_have_equal_results(self):
        for name, value, failing in (("Mail_GUID", "uuid:one", None),
                                     ("Mail_GÜID", "uuid:one", "USERPROPS_NAME_ASCII"),
                                     ("Mail_NumberAttachments", -1, "USERPROPS_TYPE"),
                                     ("Mail_Subtype", "text/plain", "USERPROPS_TAG_SCOPE")):
            results = []
            for array in (False, True):
                with self.subTest(name=name, array=array):
                    attribute = self.attribute(name, value)
                    report = validator.Report("attributes.pdf")
                    validator.check_user_properties([{"type": "Mail_Message",
                        "attrs": pikepdf.Array([attribute, 0]) if array else attribute}], report)
                    self.assertGreaterEqual(len(report.items), 2)
                    levels = {item["code"]: item["level"] for item in report.items}
                    if failing:
                        self.assertEqual("FAIL", levels[failing])
                    else:
                        self.assertEqual({"PASS"}, set(levels.values()))
                    results.append(levels)
            self.assertEqual(results[0], results[1])

    def test_structure_root_and_attribute_arrays_preserve_context(self):
        for root_array in (False, True):
            for attribute_array in (False, True):
                with self.subTest(root_array=root_array, attribute_array=attribute_array), \
                        self.document(root_array, attribute_array) as pdf:
                    ctx = validator.build_validation_context(pdf, None, "s", 4, [], [])
                    self.assertEqual({"Mail_Message"}, ctx["custom_types"])
                    self.assertTrue(ctx["userprops_present"])

    def test_declared_container_attachment_records_are_checked(self):
        from xml.etree import ElementTree as ET
        metadata = email_metadata([])
        description = metadata.find(".//" + validator.qn(validator.NS_RDF, "Description"))
        attachments = ET.SubElement(description, validator.qn(validator.NS_PDFMAILMETA, "attachments"))
        bag = ET.SubElement(attachments, validator.qn(validator.NS_RDF, "Bag"))
        item = ET.SubElement(bag, validator.qn(validator.NS_RDF, "li"))
        for name, value in (("SizeInBytes", "0"), ("Content-Type", "text/plain"), ("Mail_GUID", "uuid:one")):
            ET.SubElement(item, validator.qn(validator.NS_PDFMAILMETA, name)).text = value
        for profile in ("c", "ci"):
            with self.subTest(profile=profile):
                report = validator.Report("container.pdf")
                validator.check_message_metadata(metadata, [], profile, report)
                self.assertIn("ATTACHMENT_XMP_FILENAME", {i["code"] for i in report.items if i["level"] == "FAIL"})
                self.assertIn("ATTACHMENT_XMP_SIZE_TYPE", report.seen_codes)
                self.assertNotIn("ATTACHMENT_XMP_SIZE_MATCH", report.seen_codes)
                self.assertNotIn("ATTACHMENT_XMP_CHECKSUM_MATCH", report.seen_codes)

    def test_optional_structure_absence_does_not_make_tag_checks_unperformed(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            ctx = validator.build_validation_context(pdf, None, "si", 4, [], [])
            report = validator.Report("untagged.pdf")
            codes = ["STRUCTTREE_ROOT_DOCUMENT", "MAIL_MESSAGE_COUNT_SINGLE", "ROLEMAPNS_PRESENT",
                     "MAIL_CONTENTSET_COUNT", "USERPROPS_TYPE", "STRUCTURE_CORRESPONDENCE"]
            with patch.object(validator, "ALL_RULE_CODES", codes):
                validator.finalize_rule_matrix(report, ctx)
            self.assertTrue(all(item["level"] == "N/A" for item in report.items))

    def test_unperformed_applicable_check_is_incomplete(self):
        with self.document() as pdf:
            ctx = validator.build_validation_context(pdf, None, "s", 4, [], [])
            report = validator.Report("not-checked.pdf")
            with patch.object(validator, "ALL_RULE_CODES", ["PAGE_COUNT"]):
                validator.finalize_rule_matrix(report, ctx)
            self.assertEqual("INCOMPLETE", report.summary()["status"])
            self.assertEqual("WARN", next(item for item in report.items if item["code"] == "PAGE_COUNT")["level"])

    def test_required_sent_checks_are_not_skipped_for_isolated_sources(self):
        with self.document() as pdf:
            ctx = validator.build_validation_context(pdf, None, "si", 4, [], [])
            report = validator.Report("isolated-unchecked.pdf")
            with patch.object(validator, "ALL_RULE_CODES", ["MESSAGE_SENT_XMP", "MESSAGE_SENT_FORMAT"]):
                validator.finalize_rule_matrix(report, ctx)
            self.assertEqual("INCOMPLETE", report.summary()["status"])
            self.assertEqual({"MESSAGE_SENT_XMP", "MESSAGE_SENT_FORMAT"},
                {item["code"] for item in report.items if isinstance(item.get("details"), dict)
                 and item["details"].get("state") == "unverified"})
            for profile in ("c", "ci"):
                ctx = validator.build_validation_context(pdf, None, profile, 4, [], [])
                report = validator.Report("container.pdf")
                with patch.object(validator, "ALL_RULE_CODES", ["MESSAGE_SENT_XMP", "MESSAGE_SENT_FORMAT"]):
                    validator.finalize_rule_matrix(report, ctx)
                self.assertTrue(all(item["level"] == "N/A" for item in report.items))


    def test_unknown_rule_does_not_become_not_applicable(self):
        with self.document() as pdf:
            ctx = validator.build_validation_context(pdf, None, "s", 4, [], [])
            report = validator.Report("unknown-check.pdf")
            with patch.object(validator, "ALL_RULE_CODES", ["FUTURE_REQUIRED_CHECK"]):
                validator.finalize_rule_matrix(report, ctx)
            self.assertEqual("INCOMPLETE", report.summary()["status"])

    def test_inapplicable_source_rule_remains_na(self):
        with self.document() as pdf:
            ctx = validator.build_validation_context(pdf, None, "si", 4, [], [])
            report = validator.Report("isolated.pdf")
            with patch.object(validator, "ALL_RULE_CODES", ["SOURCE_AF"]):
                validator.finalize_rule_matrix(report, ctx)
            self.assertEqual("N/A", report.items[0]["level"])
            self.assertTrue(report.items[0]["details"]["reason"])
            self.assertFalse(any(item["code"] == "VALIDATION_INCOMPLETE" and item["level"] == "WARN" for item in report.items))

    def test_existing_failure_survives_incomplete_check(self):
        with self.document() as pdf:
            ctx = validator.build_validation_context(pdf, None, "s", 4, [], [])
            report = validator.Report("failed.pdf")
            report.fail("PAGE_COUNT", "No pages")
            with patch.object(validator, "ALL_RULE_CODES", ["PAGE_COUNT", "FUTURE_REQUIRED_CHECK"]):
                validator.finalize_rule_matrix(report, ctx)
            self.assertEqual("FAIL", report.summary()["status"])
            self.assertEqual(1, len([item for item in report.items if item["code"] == "PAGE_COUNT"]))


class RuleReportTests(unittest.TestCase):
    def test_web_does_not_label_unreported_checks_not_applicable(self):
        report = validator.Report("partial.pdf")
        report.ok("PAGE_COUNT", "One page", {"pages": 1})
        report.na("SOURCE_AF", "Optional source absent", {"reason": "Isolated profile"})
        rows = {row["code"]: row for row in web.collapse_items(report.items)}
        self.assertEqual("PASS", rows["PAGE_COUNT"]["level"])
        self.assertEqual("N/A", rows["SOURCE_AF"]["level"])
        self.assertEqual("WARN", rows["USERPROPS_TYPE"]["level"])
        self.assertEqual("unverified", rows["USERPROPS_TYPE"]["details"]["state"])

    @unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"), "Set EAPDF_GENERATOR_CLASSPATH for generated rule fixtures")
    def test_creator_generated_rule_fixture_corpus(self):
        with tempfile.TemporaryDirectory(prefix=".eapdf-rule-fixtures-", dir=ROOT) as directory:
            process = subprocess.run(["python3", "-B", "scripts/build_eapdf_rule_fixtures.py"], cwd=ROOT,
                env=dict(os.environ, EAPDF_RULE_FIXTURE_DIR=directory), capture_output=True, text=True, timeout=180)
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            results = json.loads((Path(directory) / "results.json").read_text())
            self.assertEqual(7, len(results))
            self.assertTrue(all(case["expected_result_observed"] for case in results))
            seed = next((Path(directory) / "seed").glob("*.pdf"))
            with patch.object(validator, "check_user_properties", return_value=None):
                skipped = validator.validate(seed)
            self.assertEqual("INCOMPLETE", skipped.summary()["status"])
            unverified = {item["code"] for item in skipped.items if isinstance(item.get("details"), dict)
                          and item["details"].get("state") == "unverified"}
            self.assertEqual({"USERPROPS_NAME_ASCII", "USERPROPS_TYPE", "USERPROPS_TAG_SCOPE"}, unverified)
