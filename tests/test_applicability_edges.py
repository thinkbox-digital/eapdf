"""Data-dependent completion boundaries exposed by recursive profile reports."""
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import validator


class ApplicabilityEdgeTests(unittest.TestCase):
    def finalize(self, ctx, codes):
        report = validator.Report("unchecked.pdf")
        with patch.object(validator, "ALL_RULE_CODES", codes):
            validator.finalize_rule_matrix(report, ctx)
        return report

    def test_no_embedded_files_make_payload_diagnostics_inapplicable(self):
        codes = ["EMBEDDED_STREAM", "EMBEDDED_SIZE_MATCH", "EMBEDDED_MD5_MATCH", "EMBEDDED_MD5_BINARY_FORMAT"]
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            ctx = validator.build_validation_context(pdf, None, "si", 4, [], [])
            report = self.finalize(ctx, codes)
            self.assertEqual({"N/A"}, {item["level"] for item in report.items})
            ctx["embedded_candidate_count"] = 1
            ctx["embedded_checksum_count"] = 1
            report = self.finalize(ctx, codes)
            self.assertEqual("INCOMPLETE", report.summary()["status"])
            self.assertEqual(set(codes), {item["code"] for item in report.items
                if isinstance(item.get("details"), dict) and item["details"].get("state") == "unverified"})

    def test_optional_checksum_form_requires_a_declared_checksum(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            ctx = validator.build_validation_context(pdf, None, "s", 4, [], [])
            ctx["embedded_candidate_count"] = 1
            report = self.finalize(ctx, ["EMBEDDED_MD5_BINARY_FORMAT"])
            self.assertEqual("N/A", report.items[0]["level"])
            ctx["embedded_checksum_count"] = 1
            report = self.finalize(ctx, ["EMBEDDED_MD5_BINARY_FORMAT"])
            self.assertEqual("INCOMPLETE", report.summary()["status"])

    def test_optional_raw_address_omission_does_not_depend_on_source_presence(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            ctx = validator.build_validation_context(pdf, None, "s", 4, [], [])
            ctx["inspectable_source"] = True
            for code, source, xmp in (("MESSAGE_RAW_TO_XMP", "raw_to", "Raw-To"),
                                      ("MESSAGE_RAW_FROM_XMP", "raw_from", "Raw-From")):
                for present in ("neither", "source", "xmp", "empty-xmp"):
                    with self.subTest(code=code, present=present):
                        ctx["source_raw_fields"] = {source} if present == "source" else set()
                        ctx["message_properties"] = {xmp} if present == "xmp" else set()
                        ctx["raw_address_properties"] = {xmp} if present in ("xmp", "empty-xmp") else set()
                        report = self.finalize(ctx, [code])
                        if present in ("neither", "source"):
                            self.assertEqual("N/A", report.items[0]["level"])
                        else:
                            self.assertEqual("INCOMPLETE", report.summary()["status"])
