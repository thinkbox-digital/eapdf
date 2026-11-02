import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import email_metadata, failed_codes, validator, web
from test_rule_refinement_evidence import owned_body_document


class ValidatorCompletionTests(unittest.TestCase):
    def attachment_document(self, checksum=None, filename="native.txt", subtype="/text/plain"):
        document = pikepdf.Pdf.new()
        page = document.add_blank_page()
        stream = document.make_stream(b"payload")
        stream.Subtype = pikepdf.Name(subtype)
        stream.Params = pikepdf.Dictionary(Size=7)
        if checksum is not None:
            stream.Params.CheckSum = checksum
        file = document.make_indirect(pikepdf.Dictionary(F=filename, UF=filename,
            AFRelationship=pikepdf.Name.Data, EF=pikepdf.Dictionary(F=stream)))
        page.Annots = pikepdf.Array([document.make_indirect(pikepdf.Dictionary(
            Subtype=pikepdf.Name.FileAttachment, FS=file, Rect=pikepdf.Array([10, 10, 30, 30])))])
        return document

    def test_annotation_only_checksums_use_binary_pdf_strings(self):
        digest = validator.md5hex(b"payload")
        for checksum, invalid in ((pikepdf.String(bytes.fromhex(digest)), False),
                                  (pikepdf.String(digest), True), (pikepdf.String(b"short"), True), (17, True)):
            with self.subTest(checksum=checksum), self.attachment_document(checksum=checksum) as document:
                report = validator.Report("annotation.pdf")
                validator.check_embedded_files(document, None, "si", 4, report)
                self.assertEqual(invalid, "EMBEDDED_MD5_BINARY_FORMAT" in failed_codes(report))

    def test_annotation_only_files_receive_general_filename_and_subtype_checks(self):
        for options, code in (({"filename": "folder\\native.txt"}, "EMBEDDED_FILESPEC_NO_PATH_COMPONENTS"),
                              ({"filename": "../native.txt"}, "EMBEDDED_FILESPEC_NO_PATH_COMPONENTS"),
                              ({"subtype": "/text/plain; charset=UTF-8"}, "EMBEDDED_SUBTYPE_NO_PARAMETERS")):
            with self.subTest(options=options), self.attachment_document(**options) as document:
                report = validator.Report("annotation.pdf")
                with patch.object(validator, "collect_page_text_analysis", return_value=None):
                    validator.check_additional_rule_coverage(document, None, b"", [], [], "si", 4, report)
                if code in validator.PDFA_COVERED_RULES:
                    self.assertFalse(any(row['code'] == code for row in report.items))
                else:
                    self.assertIn(code, failed_codes(report))
                self.assertNotIn("AF_IN_NAME_TREE", failed_codes(report))

    def test_source_date_parser_rejects_unconsumed_or_impossible_dates(self):
        for value in ("Thu, 30 Sep 2010 13:24:56 GMT", "Fri, 16 Oct 2026 12:00:00 +0000"):
            self.assertIsNotNone(validator.parse_source_datetime(value))
        for value in ("Tue, 31 Feb 2026 12:00:00 +0000", "Fri, 16 Oct 2026 12:00:00 +2500",
                      "Thu, 30 Sep 2010 13:24:56 GMT trailing garbage",
                      "Thu, 30 Sep 2010 13:24:56 GMT,Thu, 30 Sep 2010 13:24:56 GMT"):
            self.assertIsNone(validator.parse_source_datetime(value), value)

    def test_source_body_media_types_exclude_attachments_and_include_alternatives(self):
        raw = (b"Content-Type: multipart/mixed; boundary=mix\r\n\r\n--mix\r\n"
               b"Content-Type: multipart/alternative; boundary=alt\r\n\r\n"
               b"--alt\r\nContent-Type: text/plain\r\n\r\nPlain\r\n"
               b"--alt\r\nContent-Type: text/html\r\n\r\n<p>HTML</p>\r\n--alt--\r\n"
               b"--mix\r\nContent-Type: application/json\r\nContent-Disposition: attachment; filename=data.json\r\n\r\n{}\r\n--mix--\r\n")
        message = validator.BytesParser(policy=validator.policy.default).parsebytes(raw)
        self.assertEqual({"text/plain", "text/html"}, validator.source_body_media_types(message))

    def test_body_rendering_media_type_must_be_supported_by_its_source(self):
        raw = b"Message-ID: <body@example.org>\r\nContent-Type: text/html\r\n\r\n<p>Body</p>"
        source = {"afrelationship": "Source", "subtype": "message/rfc822", "bytes": raw,
                  "objgen": (10, 0), "filename": "source.eml"}
        record = {"Mail_GUID": "uuid:body", "messageid": "&lt;body@example.org&gt;"}
        for subtype, invalid in (("text/html", False), ("application/json", True)):
            with owned_body_document(subtype=subtype,guid='uuid:body')[0] as document:
                report = validator.Report("body.pdf")
                validator.check_body_media_types(document, email_metadata([record]), [source], report)
                self.assertEqual(invalid, "DPM_BODY_MEDIA_TYPE_MATCH_SOURCE" in failed_codes(report))

    def test_related_body_root_and_encrypted_source_media_types(self):
        raw = (b'Content-Type: multipart/related; boundary=parts; start="<root>"\r\n\r\n'
               b'--parts\r\nContent-Type: image/png\r\nContent-ID: <image>\r\n\r\nimage\r\n'
               b'--parts\r\nContent-Type: text/html\r\nContent-ID: <root>\r\n\r\n<p>Body</p>\r\n--parts--\r\n')
        message = validator.BytesParser(policy=validator.policy.default).parsebytes(raw)
        self.assertEqual({"text/html"}, validator.source_body_media_types(message))
        encrypted = validator.BytesParser(policy=validator.policy.default).parsebytes(
            b'Content-Type: multipart/encrypted; boundary=secret\r\n\r\n--secret--\r\n')
        self.assertEqual({validator.media_type_signature("multipart/encrypted; boundary=secret")},
                         {validator.media_type_signature(value) for value in validator.source_body_media_types(encrypted)})

    def test_core_field_structure_descendants_must_reference_actual_text(self):
        with pikepdf.Pdf.new() as document:
            page = document.add_blank_page()
            page.Contents = document.make_stream(b'/P <</MCID 0>> BDC BT (Subject) Tj ET EMC')
            root = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
            field = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name('/Mail_FieldName'), Pg=page.obj, P=root))
            child = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.P, P=field, K=0))
            field.K = pikepdf.Array([child])
            root.K = pikepdf.Array([field])
            document.Root.StructTreeRoot = root
            self.assertTrue(validator.structure_field_text_sequences(document)[0]["has_text_object"])
            child.K = 1
            self.assertFalse(validator.structure_field_text_sequences(document)[0]["has_text_object"])

    def test_valid_source_date_with_raw_sent_keeps_the_spec_recommendation_warning(self):
        raw = b'Date: Thu, 30 Sep 2010 13:24:56 GMT\r\nContent-Type: text/plain\r\n\r\nBody'
        source = {"afrelationship": "Source", "subtype": "message/rfc822", "bytes": raw,
                  "objgen": (10, 0), "filename": "source.eml"}
        metadata = email_metadata([{"Sent": "2010-09-30T13:24:56Z", "Raw-Sent": "Thu, 30 Sep 2010 13:24:56 GMT"}])
        with pikepdf.Pdf.new() as document:
            report = validator.Report("date.pdf")
            validator.check_additional_rule_coverage(document, metadata, b"", [source], [source], "s", 4, report)
            matching = [item for item in report.items if item["code"] == "MESSAGE_RAW_SENT_OMIT_IF_VALID"]
            self.assertEqual("WARN", matching[0]["level"])

    def test_web_discovers_and_displays_new_failures(self):
        numbers = [rule["rule_number"] for rule in web.get_rule_text().values()]
        self.assertEqual(len(numbers), len(set(numbers)))
        for code in ("EMBEDDED_MD5_BINARY_FORMAT", "DPM_BODY_MEDIA_TYPE_MATCH_SOURCE"):
            self.assertIn(code, web.all_rule_codes())
            report = validator.Report("invalid.pdf")
            report.fail(code, "Invalid test fixture")
            rows = web.collapse_items(report.items)
            self.assertEqual("FAIL", next(row for row in rows if row["code"] == code)["level"])
            self.assertTrue(web.get_rule_text()[code]["spec_excerpt"])

    def test_wrapped_url_annotations_require_the_visible_continuation(self):
        uri = "mailto:IMCEANOTES-Enron+20Announcements_Corp_Enron+40ENRON@ENRON.com"
        text = "mailto:IMCEANOTES-\n] On Behalf Of Charla Stuart- Enron+20Announcements_Corp_Enron+40ENRON@ENRON.com\n"
        self.assertTrue(validator.url_has_matching_annotation("mailto:IMCEANOTES-", {uri}, text))
        self.assertFalse(validator.url_has_matching_annotation("mailto:IMCEANOTES-", {uri}, "mailto:IMCEANOTES-\nOther text"))
        self.assertFalse(validator.url_has_matching_annotation("mailto:IMCEANOTES-", {uri}, text.replace("\n", " ")))
        self.assertFalse(validator.url_has_matching_annotation("http://example.org/path", {"https://example.org/path"}, ""))

    def test_missing_source_is_not_a_warning_for_isolated_profiles(self):
        for profile in ("si", "mi", "ci"):
            report = validator.Report("isolated.pdf")
            validator.check_source_media_types([], profile, report)
            self.assertEqual("PASS", report.items[0]["level"])

    def test_body_text_cannot_impersonate_an_unverified_header(self):
        record = {"Mail_GUID": "uuid:body", "Raw-From": "author@example.org", "Subject": "Actual subject",
                  "Sent": "2026-10-16T12:00:00Z"}
        body = {"identity": {"Mail_GUID": "uuid:body"}, "pages": [1]}
        text = "From: author@example.org\nDate: Fri, 16 Oct 2026 12:00:00 +0000\nSubject:\nUnrelated body\nSubject: Actual subject"
        with pikepdf.Pdf.new() as document, \
                patch.object(validator, "get_dpart_nodes", return_value=(None, None, [])), \
                patch.object(validator, "collect_body_content_sets", return_value=[body]):
            verified, unresolved = validator.core_field_text_evidence(document, email_metadata([record]), [{"page": 1, "text": text}])
            self.assertFalse(verified)
            self.assertTrue(unresolved)


if __name__ == "__main__":
    unittest.main()
