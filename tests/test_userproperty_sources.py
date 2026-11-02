"""EA-PDF 9.7: source fidelity is a message-scoped SHOULD recommendation."""
from html import escape
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import pikepdf

from test_preservation_safety import ROOT, email_metadata, validator


CODE = "USERPROPS_VALUES_UNMODIFIED"


class UserPropertySourceTests(unittest.TestCase):
    def source(self, subject="Source A", extra="", message_id="<duplicate@example.invalid>"):
        return ("From: =?UTF-8?Q?Fixture_Author?= <author@example.invalid>\r\n"
                "To: Reader <reader@example.invalid>\r\n"
                "Date: Tue, 28 Jan 2003 21:20:54 -0500\r\n"
                f"Message-ID: {message_id}\r\nSubject: {subject}\r\n{extra}"
                "Content-Type: text/plain; charset=UTF-8\r\n\r\nBody.\r\n").encode()

    def check(self, properties=(), second=False, array=False, descendant=False, isolated=False,
              ambiguous=False, guid="uuid:first", source=None, extra="", subject="Source A",
              orphan=False, no_structure=False, uninspectable=False):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            raw = source if source is not None else self.source(subject, extra)
            raws = [raw, self.source("Source B")] if second else [raw]
            files = []
            records = []
            for index, data in enumerate(raws):
                name = f"source-{index}.msg" if uninspectable else f"source-{index}.eml"
                stream = pdf.make_stream(data)
                stream.Subtype = pikepdf.Name('/application/vnd.ms-outlook' if uninspectable else '/message/rfc822')
                spec = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,
                    F=name, UF=name, AFRelationship=pikepdf.Name.Source, EF=pikepdf.Dictionary(F=stream)))
                files.append(validator.parse_filespec(name, spec))
                records.append({"Mail_GUID": f"uuid:{'first' if index == 0 else 'second'}",
                    "messageid": "<duplicate@example.invalid>",
                    "Subject": "Unknown" if ambiguous else subject if index == 0 else "Source B",
                    "SizeInBytes": str(len(raw) if ambiguous else len(data)),
                    "Sent": "2003-01-28T21:20:54-05:00"})
            metadata = email_metadata([{k: escape(v) for k, v in record.items()} for record in records])
            tree = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
            document = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.Document, P=tree))
            tree.K = document
            messages = []

            def attrs(values):
                attr = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                    P=pikepdf.Array([pikepdf.Dictionary(N=n, V=v) for n, v in values]))
                return pikepdf.Array([attr, 0]) if array else attr

            for index in range(len(raws)):
                owner_guid = guid if index == 0 else "uuid:second"
                message = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                    S=pikepdf.Name('/Mail_Message'), P=document,
                    A=attrs([("Mail_GUID", owner_guid), ("Mail_MessageID", "<duplicate@example.invalid>")])))
                messages.append(message)
                if index == 0:
                    if descendant or orphan:
                        parent = document if orphan else message
                        wrapper = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                            S=pikepdf.Name.Div, P=parent))
                        child = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                            S=pikepdf.Name('/Mail_FieldValue'), P=wrapper, A=attrs(properties)))
                        wrapper.K = child
                        if orphan:
                            messages.append(wrapper)
                        else:
                            message.K = wrapper
                    else:
                        props = message.A[0].P if array else message.A.P
                        for name, value in properties:
                            props.append(pikepdf.Dictionary(N=name, V=value))
            document.K = pikepdf.Array(messages)
            if not no_structure:
                pdf.Root.StructTreeRoot = tree
            report = validator.Report("source-properties.pdf")
            validator.check_additional_rule_coverage(pdf, metadata, b"", [] if isolated else files,
                [] if isolated else files, "mi" if isolated else "m" if second else "s", 4, report)
            return [item for item in report.items if item["code"] == CODE], report

    def test_matching_properties_pass_in_dictionary_and_array_forms(self):
        for array in (False, True):
            findings, _ = self.check([("Mail_Subject", "Source A"),
                ("Mail_From", "Fixture Author <author@example.invalid>"),
                ("Mail_Raw_From", "=?UTF-8?Q?Fixture_Author?= <author@example.invalid>"),
                ("Mail_Sent", "D:20030129022054Z"), ("Mail_NumberAttachments", 0)], array=array)
            self.assertEqual({"PASS"}, {item["level"] for item in findings})

    def test_modified_or_truncated_values_warn_without_failing_conformance(self):
        for name, value in (("Mail_Subject", "Source"), ("Mail_To", "Other"),
                            ("Mail_Sent", "D:20030129022055Z"), ("Mail_Sent", "D:2003"),
                            ("Mail_NumberAttachments", 2), ("Mail_SizeInBytes", 1)):
            with self.subTest(name=name, value=value):
                findings, _ = self.check([(name, value)])
                self.assertTrue(any(item["level"] == "WARN" for item in findings))
                self.assertFalse(any(item["level"] == "FAIL" for item in findings))

    def test_nearest_message_guid_controls_duplicate_message_id_ownership(self):
        for array in (False, True):
            for descendant in (False, True):
                for value, expected in (("Source A", "PASS"), ("Source B", "WARN")):
                    with self.subTest(array=array, descendant=descendant, value=value):
                        findings, _ = self.check([("Mail_Subject", value)], second=True,
                            array=array, descendant=descendant)
                        levels = {item["level"] for item in findings}
                        self.assertIn(expected, levels)
                        if expected == "PASS":
                            self.assertEqual({"PASS"}, levels)

    def test_raw_and_decoded_values_and_internal_spaces_are_distinct(self):
        for name, value, expected in (
            ("Mail_From", "Fixture Author <author@example.invalid>", "PASS"),
            ("Mail_Raw_From", "Fixture Author <author@example.invalid>", "WARN"),
            ("Mail_Subject", "Two  spaces", "PASS"),
            ("Mail_Subject", "Two spaces", "WARN")):
            with self.subTest(name=name, value=value):
                findings, _ = self.check([(name, value)], subject="Two  spaces")
                self.assertIn(expected, {i["level"] for i in findings})

    def test_custom_header_scalar_values_and_missing_header(self):
        for value, extra, expected in (("original", "X-Project: original\r\n", "PASS"),
                                        (2, "X-Project: 2\r\n", "PASS"),
                                        (True, "X-Project: true\r\n", "PASS"),
                                        ("invented", "", "WARN")):
            findings, _ = self.check([("Mail_X-Project", value)], extra=extra)
            self.assertIn(expected, {i["level"] for i in findings})

    def test_no_retained_or_inspectable_source_is_explicitly_na(self):
        for options in ({"isolated": True}, {"uninspectable": True}, {"no_structure": True}):
            findings, _ = self.check([("Mail_Subject", "Source A")], **options)
            self.assertEqual({"N/A"}, {i["level"] for i in findings})
            self.assertTrue(all(i.get("details", {}).get("reason") for i in findings))

    def test_unresolved_owner_or_source_does_not_claim_a_pass(self):
        for options in ({"second": True, "ambiguous": True},
                        {"second": True, "guid": "uuid:missing"}, {"orphan": True}):
            findings, _ = self.check([("Mail_Subject", "Source A")], **options)
            self.assertIn("WARN", {i["level"] for i in findings})

    def test_repeated_header_is_unavailable_and_folded_raw_header_is_preserved(self):
        findings, _ = self.check([("Mail_X-Project", "one")],
            extra="X-Project: one\r\nX-Project: two\r\n")
        row = next(i for i in findings if i["details"].get("name") == "Mail_X-Project")
        self.assertEqual("WARN", row["level"])
        self.assertEqual("unavailable", row["details"]["comparison"])
        raw = self.source().replace(b"To: Reader <reader@example.invalid>",
                                   b"To: Reader\r\n <reader@example.invalid>")
        for value in ("Reader <reader@example.invalid>", "Reader\r\n <reader@example.invalid>"):
            findings, _ = self.check([("Mail_Raw_To", value)], source=raw)
            self.assertEqual({"PASS"}, {i["level"] for i in findings})

    def test_reduced_date_precision_preserves_pdf_default_values(self):
        raw = self.source().replace(b"Tue, 28 Jan 2003 21:20:54 -0500",
                                   b"Wed, 01 Jan 2003 00:00:00 +0000")
        for value in ("D:2003", "D:200301010000Z", "D:20030101000000Z00'00'"):
            findings, _ = self.check([("Mail_Sent", value)], source=raw)
            self.assertEqual({"PASS"}, {i["level"] for i in findings})

    @unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"),
                         "Set EAPDF_GENERATOR_CLASSPATH for source-value fixtures")
    def test_creator_generated_source_fixture_corpus(self):
        with tempfile.TemporaryDirectory(prefix=".eapdf-source-fixtures-", dir=ROOT) as directory:
            process = subprocess.run(["python3", "-B", "scripts/build_eapdf_source_fixtures.py"], cwd=ROOT,
                env=dict(os.environ, EAPDF_SOURCE_FIXTURE_DIR=directory), capture_output=True, text=True, timeout=240)
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            results = json.loads((Path(directory) / "results.json").read_text())
            cases = json.loads((ROOT / "tests/fixtures/eapdf/batch04/cases.json").read_text())
            self.assertEqual({c["id"] for c in cases}, {r["case"] for r in results})
            self.assertTrue(all(r["expected_result_observed"] for r in results))
            if os.environ.get("EAPDF_INTEGRATION_REPORT_DIR"):
                shutil.copytree(directory, Path(os.environ["EAPDF_INTEGRATION_REPORT_DIR"]) / "source-corpus",
                                dirs_exist_ok=True)
