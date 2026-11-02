import base64
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import email_metadata, validator


class SourceAttachmentReferenceTests(unittest.TestCase):
    def source_bytes(self, subject="Source A", payload=b"payload"):
        return (f"Subject: {subject}\r\nMessage-ID: <duplicate@example.org>\r\n"
                "Date: Tue, 28 Jan 2003 21:20:54 -0500\r\n"
                "Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                "--outer\r\nContent-Type: text/plain\r\n\r\nBody\r\n"
                "--outer\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=notes.txt\r\n"
                "Content-Transfer-Encoding: base64\r\n\r\n").encode() + base64.b64encode(payload) + b"\r\n--outer--\r\n"

    def check_reference(self, hidden=False, outside=False, corrupt=False, wrong_owner=False,
                        detached=False, uninspectable=False, missing_record=False, annotated=True):
        with pikepdf.Pdf.new() as document:
            page = document.add_blank_page()
            original = self.source_bytes()
            raw = b"native MSG bytes" if uninspectable else self.source_bytes(payload=b"corrupt") if corrupt else original
            name = "source.msg" if uninspectable else "source.eml"
            subtype = "/application/vnd.ms-outlook" if uninspectable else "/message/rfc822"

            def source_file(data, filename):
                stream = document.make_stream(data)
                stream.Subtype = pikepdf.Name(subtype)
                stream.Params = pikepdf.Dictionary(Size=len(data))
                return document.make_indirect(pikepdf.Dictionary(
                    F=filename, UF=filename, AFRelationship=pikepdf.Name.Source, EF=pikepdf.Dictionary(F=stream)))

            spec = source_file(raw, name)
            files = [validator.parse_filespec(name, spec)]
            record = {"Mail_GUID": "uuid:first", "messageid": "&lt;duplicate@example.org&gt;", "Subject": "Source A",
                      "Sent": "2003-01-28T21:20:54-05:00", "SizeInBytes": str(len(original)), "NumberAttachments": "1"}
            records = [] if missing_record else [record]
            if wrong_owner:
                other = self.source_bytes(subject="Source B")
                spec = source_file(other, "other.eml")
                files.append(validator.parse_filespec("other.eml", spec))
                records.append(dict(record, Mail_GUID="uuid:second", Subject="Source B", SizeInBytes=str(len(other))))
            if detached:
                spec = source_file(raw, name)
            if annotated:
                page.Annots = pikepdf.Array([document.make_indirect(pikepdf.Dictionary(
                    Subtype=pikepdf.Name.FileAttachment, FS=spec,
                    Rect=pikepdf.Array([10000, 10000, 10020, 10020] if outside else [10, 10, 30, 30]), F=2 if hidden else 0))])
            root = email_metadata(records)
            description = root.find(validator.qn(validator.NS_RDF, "Description"))
            bag = ET.SubElement(ET.SubElement(description, validator.qn(validator.NS_PDFMAILMETA, "attachments")),
                                validator.qn(validator.NS_RDF, "Bag"))
            attachment = ET.SubElement(bag, validator.qn(validator.NS_RDF, "li"))
            for field, value in {"Filename": "notes.txt", "SizeInBytes": "7", "Content-Type": "text/plain",
                                 "Mail_GUID": "uuid:first", "CheckSum": validator.md5hex(b"payload")}.items():
                ET.SubElement(attachment, validator.qn(validator.NS_PDFMAILMETA, field)).text = value
            bodies = [{"identity": {"Mail_GUID": "uuid:first", "Message-ID": "<duplicate@example.org>"},
                       "pages": [1], "start_page": 1, "end_page": 1}]
            report = validator.Report("source-reference.pdf")
            with patch.object(validator, "get_dpart_nodes", return_value=(None, None, [])), \
                    patch.object(validator, "collect_body_content_sets", return_value=bodies), \
                    patch.object(validator, "collect_page_text_analysis", return_value=None):
                validator.check_additional_rule_coverage(document, root, b"", files, files, "s", 4, report)
            finding = next(item for item in report.items if item["code"] == "ATTACHMENT_ANNOTATIONS_PRESENT")
            return finding, report

    def test_source_email_reference_does_not_replace_a_native_attachment_annotation(self):
        finding, report = self.check_reference()
        self.assertEqual("FAIL", finding["level"])
        self.assertIn("no matching File Attachment", str(finding["details"]))
        self.assertFalse(any(item["code"] == "VALIDATION_INCOMPLETE" and "referenced source" in item["message"] for item in report.items))

    def test_hidden_or_off_page_source_links_fail(self):
        for options in ({"hidden": True}, {"outside": True}):
            with self.subTest(options=options):
                finding, report = self.check_reference(**options)
                self.assertEqual("FAIL", finding["level"])

    def test_changed_attachment_bytes_in_source_fail(self):
        finding, report = self.check_reference(corrupt=True)
        self.assertEqual("FAIL", finding["level"])
        self.assertIn("matching the declared", str(finding["details"]))

    def test_another_emails_source_with_duplicate_message_id_is_not_accepted(self):
        finding, report = self.check_reference(wrong_owner=True)
        self.assertEqual("FAIL", finding["level"])
        self.assertIn("another email", str(finding["details"]))

    def test_source_not_in_catalog_is_not_accepted(self):
        finding, report = self.check_reference(detached=True)
        self.assertEqual("FAIL", finding["level"])
        self.assertIn("catalog", str(finding["details"]))

    def test_preserved_source_without_visible_annotation_still_fails(self):
        finding, report = self.check_reference(annotated=False)
        self.assertEqual("FAIL", finding["level"])

    def test_uninspectable_source_and_missing_owner_do_not_excuse_missing_native_annotations(self):
        for options in ({"uninspectable": True}, {"missing_record": True}):
            with self.subTest(options=options):
                finding, report = self.check_reference(**options)
                self.assertEqual("FAIL", finding["level"])

    def test_guid_takes_priority_over_duplicate_message_ids(self):
        self.assertFalse(validator.identities_match(
            {"Mail_GUID": "uuid:first", "Message-ID": "duplicate"},
            {"Mail_GUID": "uuid:second", "Message-ID": "duplicate"}))


if __name__ == "__main__":
    unittest.main()
