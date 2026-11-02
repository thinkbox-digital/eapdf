"""EA-PDF Table 12 and ISO 32000 7.9.4: PDF dates in Mail_Sent."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator


class UserPropertyDateTests(unittest.TestCase):
    def check_date(self, value, array=False, tag="Mail_Message", name="Mail_Sent"):
        attr = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
            P=pikepdf.Array([pikepdf.Dictionary(N=name, V=value)]))
        report = validator.Report("date.pdf")
        validator.check_user_properties([{"type": tag,
            "attrs": pikepdf.Array([attr, 0]) if array else attr}], report)
        return next(item for item in report.items if item["code"] == "USERPROPS_TYPE")

    def assert_dates(self, values, expected):
        for value in values:
            for array in (False, True):
                for tag in ("Mail_Message", "Mail_FieldValue"):
                    with self.subTest(value=str(value), array=array, tag=tag):
                        self.assertEqual(expected, self.check_date(value, array, tag)["level"])

    def test_valid_calendar_and_reduced_precision(self):
        self.assert_dates(["D:2003", "D:200311", "D:20031121", "D:2003112115",
            "D:200311211530", "D:20031121153000", "D:20040229000000Z",
            "D:20000229235959Z", "D:0000", "D:99991231235959Z"], "PASS")

    def test_valid_timezone_forms_and_legacy_apostrophe(self):
        self.assert_dates(["D:199812231952-08'00", "D:20031121153000Z",
            "D:20031121153000Z00", "D:20031121153000Z00'00",
            "D:20031121153000Z00'00'", "D:20031121153000+",
            "D:20031121153000-", "D:20031121153000+05",
            "D:20031121153000+05'", "D:20031121153000+05'30",
            "D:20031121153000+05'30'", "D:20031121153000-23'59"], "PASS")

    def test_invalid_syntax_is_not_normalized_into_a_date(self):
        self.assert_dates(["not-a-date", "", "20031121153000Z", "d:20031121153000Z",
            "2003-11-21T15:30:00Z", "Fri, 21 Nov 2003 15:30:00 +0000",
            " D:20031121153000Z", "D:20031121153000Z ", "D:20031121153000Z\n",
            "D:20031121 153000Z", "D:20031121153000.1Z", "D:20031121153000z",
            "D:20031121153000+05:30", "D:20031121153000+0530",
            "D:20031121153000+05'3", "D:20031121153000Z'",
            "D:20031121153000Zjunk", "D:200", "D:20031", "D:2003112",
            "D:200311211530001", "D:２００３１１２１１５３０００Z"], "FAIL")

    def test_invalid_calendar_time_and_offset_ranges(self):
        self.assert_dates(["D:200300", "D:200313", "D:20030200", "D:20030229",
            "D:19000229", "D:20030431", "D:20031132", "D:2003112124",
            "D:200311211560", "D:20031121153060", "D:20031121153000+24'00",
            "D:20031121153000-00'60", "D:20031121153000Z01",
            "D:20031121153000Z00'01"], "FAIL")

    def test_date_requires_pdf_string_and_reports_date_expectation(self):
        self.assert_dates([pikepdf.Name('/D:20031121153000Z'), 2003, True, None,
                           pikepdf.Array(["D:20031121153000Z"])], "FAIL")
        failure = self.check_date("not-a-date")
        self.assertEqual("pdf-date", failure["details"]["expected"])
        self.assertEqual("not-a-date", failure["details"]["value"])

    def test_decoded_utf16_and_hex_strings_are_dates(self):
        value = "D:20031121153000Z"
        self.assert_dates([pikepdf.String(b"\xfe\xff" + value.encode("utf-16-be")),
            pikepdf.Object.parse(b"<443A32303033313132313135333030305A>")], "PASS")

    def test_raw_date_and_unrelated_text_remain_text(self):
        for name in ("Mail_Raw_Sent", "Mail_Subject", "Mail_X_Example"):
            for array in (False, True):
                self.assertEqual("PASS", self.check_date("not-a-date", array, name=name)["level"])

    @unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"),
                         "Set EAPDF_GENERATOR_CLASSPATH for generated PDF-date fixtures")
    def test_creator_generated_date_fixture_corpus(self):
        staging = ROOT / ".generator_web_tmp"
        staging.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".eapdf-date-fixtures-", dir=staging) as directory:
            process = subprocess.run(["python3", "-B", "scripts/build_eapdf_rule_fixtures.py"], cwd=ROOT,
                env=dict(os.environ, EAPDF_RULE_FIXTURE_SET="batch03", EAPDF_RULE_FIXTURE_DIR=directory),
                capture_output=True, text=True,
                timeout=120 + 30 * len(json.loads((ROOT / "tests/fixtures/eapdf/batch03/cases.json").read_text())))
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            results = json.loads((Path(directory) / "results.json").read_text())
            cases = json.loads((ROOT / "tests/fixtures/eapdf/batch03/cases.json").read_text())
            self.assertEqual({case["id"] for case in cases}, {case["case"] for case in results})
            self.assertTrue(all(case["expected_result_observed"] for case in results))
