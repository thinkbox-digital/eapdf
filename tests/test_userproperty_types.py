"""EA-PDF 1.0 section 9.7 / Table 12: PDF object types, not text coercion."""
from decimal import Decimal
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator


class UserPropertyTypeTests(unittest.TestCase):
    def check_property(self, name, value, tag="Mail_Message", array=False, missing=()):
        prop = pikepdf.Dictionary(N=name, V=value)
        for key in missing:
            del prop[key]
        attr = pikepdf.Dictionary(O=pikepdf.Name.UserProperties, P=pikepdf.Array([prop]))
        report = validator.Report("types.pdf")
        validator.check_user_properties([{"type": tag,
            "attrs": pikepdf.Array([attr, 0]) if array else attr}], report)
        return {item["code"]: item["level"] for item in report.items}

    def test_string_fields_require_pdf_strings(self):
        values = [(pikepdf.String("uuid:one"), "PASS"),
                  (pikepdf.String("café"), "PASS"),
                  (pikepdf.Name.Example, "FAIL"),
                  (pikepdf.Dictionary(Example=1), "FAIL"),
                  (pikepdf.Array(["text"]), "FAIL"),
                  (None, "FAIL"), (True, "FAIL"), (42, "FAIL"),
                  (Decimal("1.5"), "FAIL")]
        for name in ("Mail_GUID", "Mail_MessageID", "Mail_Subject", "Mail_Sent"):
            for value, expected in values:
                for array in (False, True):
                    with self.subTest(name=name, value=str(value), array=array):
                        if name == "Mail_Sent" and expected == "PASS":
                            value = pikepdf.String("D:20031121153000Z")
                        self.assertEqual(expected,
                            self.check_property(name, value, array=array)["USERPROPS_TYPE"])

    def test_count_fields_require_nonnegative_pdf_integers(self):
        values = [(0, "PASS"), (2, "PASS"), (2**40, "PASS"), (-1, "FAIL"),
                  (pikepdf.String("2"), "FAIL"), (pikepdf.Name('/2'), "FAIL"),
                  (True, "FAIL"), (False, "FAIL"),
                  (Decimal("2.0"), "FAIL"), (Decimal("2.5"), "FAIL"),
                  (pikepdf.Dictionary(Count=2), "FAIL"), (None, "FAIL")]
        for name in ("Mail_SizeInBytes", "Mail_NumberAttachments"):
            for value, expected in values:
                for array in (False, True):
                    with self.subTest(name=name, value=str(value), array=array):
                        self.assertEqual(expected,
                            self.check_property(name, value, array=array)["USERPROPS_TYPE"])

    def test_names_require_ascii_pdf_strings(self):
        for name, expected in ((pikepdf.String("Mail_GUID"), "PASS"),
                               (pikepdf.String("Mail_GÜID"), "FAIL"),
                               (pikepdf.String("Mail_\u00a0GUID"), "FAIL"),
                               (pikepdf.Name.Mail_GUID, "FAIL"), (7, "FAIL"),
                               (None, "FAIL")):
            for array in (False, True):
                with self.subTest(name=str(name), array=array):
                    self.assertEqual(expected, self.check_property(name, "uuid:one",
                        array=array)["USERPROPS_NAME_ASCII"])

    def test_missing_name_and_value_fail(self):
        for array in (False, True):
            self.assertEqual("FAIL", self.check_property("Mail_GUID", "uuid:one",
                array=array, missing=("/N",))["USERPROPS_NAME_ASCII"])
            self.assertEqual("FAIL", self.check_property("Mail_GUID", "uuid:one",
                array=array, missing=("/V",))["USERPROPS_TYPE"])

    def test_generic_header_values_allow_real_numbers_but_reject_objects(self):
        values = [(pikepdf.String("text"), "PASS"), (12, "PASS"), (-1, "PASS"),
                  (True, "PASS"), (False, "PASS"), (Decimal("1.5"), "PASS"),
                  (pikepdf.Name.Example, "FAIL"), (pikepdf.Dictionary(Example=1), "FAIL"),
                  (pikepdf.Array([1]), "FAIL"), (None, "FAIL")]
        for value, expected in values:
            for array in (False, True):
                with self.subTest(value=str(value), array=array):
                    self.assertEqual(expected, self.check_property("Mail_X_Example", value,
                        array=array)["USERPROPS_TYPE"])

    def test_generic_header_scope_matches_table12(self):
        for tag in ("Mail_Message", "Mail_FieldName", "Mail_FieldValue",
                    "Mail_ContentGroup", "Mail_ContentSet", "Mail_Field"):
            for array in (False, True):
                with self.subTest(tag=tag, array=array):
                    result = self.check_property("Mail_X_Example", "text", tag=tag, array=array)
                    self.assertEqual("PASS" if tag in {"Mail_Message", "Mail_FieldName", "Mail_FieldValue"}
                                     else "FAIL", result.get("USERPROPS_TAG_SCOPE"))

    def test_stream_is_not_a_string(self):
        with pikepdf.Pdf.new() as pdf:
            for array in (False, True):
                for name in ("Mail_GUID", "Mail_X_Example"):
                    self.assertEqual("FAIL", self.check_property(name, pdf.make_stream(b"text"),
                        array=array)["USERPROPS_TYPE"])

    @unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"),
                         "Set EAPDF_GENERATOR_CLASSPATH for generated type fixtures")
    def test_creator_generated_type_fixture_corpus(self):
        staging = ROOT / ".generator_web_tmp"
        staging.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".eapdf-type-fixtures-", dir=staging) as directory:
            process = subprocess.run(["python3", "-B", "scripts/build_eapdf_rule_fixtures.py"], cwd=ROOT,
                env=dict(os.environ, EAPDF_RULE_FIXTURE_SET="batch02", EAPDF_RULE_FIXTURE_DIR=directory),
                capture_output=True, text=True,
                timeout=120 + 30 * len(json.loads((ROOT / "tests/fixtures/eapdf/batch02/cases.json").read_text())))
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            results = json.loads((Path(directory) / "results.json").read_text())
            cases = json.loads((ROOT / "tests/fixtures/eapdf/batch02/cases.json").read_text())
            self.assertEqual({case["id"] for case in cases}, {case["case"] for case in results})
            self.assertTrue(all(case["expected_result_observed"] for case in results))
