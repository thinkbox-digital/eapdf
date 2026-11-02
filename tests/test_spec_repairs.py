import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import email_metadata, failed_codes, failed_source_fields, validator, web
from test_rule_refinement_evidence import owned_body_document


class SpecRepairTests(unittest.TestCase):
    def test_auxiliary_files_do_not_determine_email_attachment_navigation(self):
        for auxiliary in ("premis.xml", "Finding_Aid.xlsx"):
            with self.subTest(auxiliary=auxiliary), pikepdf.Pdf.new() as document:
                document.add_blank_page()
                document.Root.PageMode = pikepdf.Name.UseOutlines
                document.Root.ViewerPreferences = pikepdf.Dictionary(NonFullScreenPageMode=pikepdf.Name.UseOutlines)
                files = [{"afrelationship": "Supplement", "filename": auxiliary}]
                metadata = email_metadata([{"NumberAttachments": "0"}])
                self.assertEqual("UseOutlines", validator.expected_navigation_mode("s", files, [], metadata))
                report = validator.Report("auxiliary.pdf")
                validator.check_profile_rules(document, files, [], "s", 4, report, metadata)
                self.assertEqual(set(), failed_codes(report))

    def test_wrong_single_message_nonfullscreen_mode_is_a_mandatory_failure(self):
        for profile in ("s", "si"):
            for count in (0, 1):
                for pdfa_part in (3, 4):
                    with self.subTest(profile=profile, count=count, part=pdfa_part), pikepdf.Pdf.new() as document:
                        document.Root.PageMode = pikepdf.Name.UseAttachments if count else pikepdf.Name.UseOutlines
                        document.Root.ViewerPreferences = pikepdf.Dictionary(NonFullScreenPageMode=pikepdf.Name.UseNone)
                        report = validator.Report("navigation.pdf")
                        validator.check_profile_rules(document, [], [], profile, pdfa_part, report,
                                                      email_metadata([{"NumberAttachments": str(count)}]))
                        code = "PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS" if count else "PROFILE_NFSM_SINGLE_NO_ATTACHMENTS"
                        self.assertIn(code, failed_codes(report))
                        self.assertEqual("FAIL", report.summary()["status"])

    def test_missing_nonempty_source_core_fields_fail_but_empty_optional_fields_do_not(self):
        raw = (b"From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n"
               b"Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: Retain this\r\n"
               b"Message-ID: <source@example.org>\r\nIn-Reply-To: <parent@example.org>\r\n\r\nBody")
        source = {"afrelationship": "Source", "subtype": "message/rfc822", "bytes": raw,
                  "objgen": (10, 0), "filename": "source.eml", "ufilename": "source.eml"}
        metadata = email_metadata([{"Mail_GUID": "uuid:source", "SizeInBytes": str(len(raw)),
                                    "Sent": "2003-01-29T02:20:54Z"}])
        report = validator.Report("missing.pdf")
        validator.check_message_metadata(metadata, [source], "s", report)
        self.assertTrue({"Subject", "messageid", "In-Reply-To", "From", "To"}.issubset(
            failed_source_fields(report, "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED")))
        report = validator.Report("empty.pdf")
        validator.check_source_field_values({}, {"size_in_bytes": 0, "mime_attachment_count": 0}, report)
        self.assertEqual(set(), failed_codes(report))

    def test_parameterized_body_mime_matches_semantically_without_losing_parameters(self):
        raw = b"Message-ID: <body@example.org>\r\nContent-Type: text/plain; charset=UTF-8; format=flowed\r\n\r\nBody"
        message = validator.BytesParser(policy=validator.policy.default).parsebytes(raw)
        self.assertEqual({validator.media_type_signature("text/plain; charset=UTF-8; format=flowed")},
                         {validator.media_type_signature(value) for value in validator.source_body_media_types(message)})
        source = {"afrelationship": "Source", "subtype": "message/rfc822", "bytes": raw,
                  "objgen": (10, 0), "filename": "source.eml"}
        for subtype, invalid in (("text/plain; format=flowed; charset=\"utf-8\"", False),
                                 ("text/plain", True), ("text/plain; charset=UTF-8; format=fixed", True)):
            with self.subTest(subtype=subtype), owned_body_document(subtype=subtype,guid='uuid:body')[0] as document:
                report = validator.Report("body.pdf")
                validator.check_body_media_types(document, email_metadata([{"Mail_GUID": "uuid:body",
                    "messageid": "&lt;body@example.org&gt;"}]), [source], report)
                self.assertEqual(invalid, "DPM_BODY_MEDIA_TYPE_MATCH_SOURCE" in failed_codes(report))

    def test_body_mime_syntax_accepts_parameters_but_rejects_malformed_types(self):
        for value in ("text/plain", "text/plain; charset=UTF-8; format=flowed", 'text/html; title="a;b"'):
            self.assertTrue(validator.valid_body_media_type(value), value)
        for value in ("plain", "text/", "text/plain; charset", "text/plain; charset=", 'text/plain; title="bad', "text/plain\r\nx: y"):
            self.assertFalse(validator.valid_body_media_type(value), value)

    def test_web_dpm_expectations_match_specification_names(self):
        self.assertIn("/Mail_", web.infer_expected_value({"code": "DPM_KEY_PREFIX"}, {}, {}))
        self.assertIn("Mail_Subtype", web.infer_expected_value({"code": "DPM_SUBTYPE_REQUIRED"}, {}, {}))

    def test_custom_collection_fields_cannot_bypass_noneditable_rule(self):
        with pikepdf.Pdf.new() as document:
            document.Root.Collection = pikepdf.Dictionary(Schema=pikepdf.Dictionary(
                Custom=pikepdf.Dictionary(Subtype=pikepdf.Name.S, E=True)))
            report = validator.Report("custom-schema.pdf")
            validator.check_collection_rules(document, "c", report)
            self.assertIn("COLLECTION_SCHEMA_E_FALSE", failed_codes(report))
