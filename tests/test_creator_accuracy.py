import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, email_metadata, validator, web


class CreatorAccuracyValidationTests(unittest.TestCase):
    def declaration(self, namespace="http://pdfa.org/declarations/", uri="https://pdfa.org/declarations#iso32005", resource=False):
        target = '<d:conformsTo rdf:resource="' + uri + '"/>' if resource else '<d:conformsTo>' + uri + '</d:conformsTo>'
        return ('<rdf:RDF xmlns:rdf="' + validator.NS_RDF + '" xmlns:d="' + namespace + '">'
                '<rdf:Description><d:declarations><rdf:Bag><rdf:li>' + target
                + '</rdf:li></rdf:Bag></d:declarations></rdf:Description></rdf:RDF>').encode()

    def test_iso_declaration_requires_the_actual_rdf_property_and_exact_target(self):
        self.assertTrue(validator.has_ts32005_declaration(self.declaration()))
        self.assertTrue(validator.has_ts32005_declaration(self.declaration(resource=True)))
        for data in (b'<description>https://pdfa.org/declarations#iso32005</description>',
                     self.declaration(namespace="https://pdfa.org/declarations/"),
                     self.declaration(namespace="https://example.org/not-a-declaration/"),
                     self.declaration(uri="https://example.org/iso32005"),
                     self.declaration(uri="https://pdfa.org/declarations#iso32005-extra"),
                     b'<broken>https://pdfa.org/declarations#iso32005'):
            with self.subTest(data=data):
                self.assertFalse(validator.has_ts32005_declaration(data))

    def test_mime_parameters_are_compared_without_losing_case_sensitive_values(self):
        attachment = {"Filename": "notes.txt", "SizeInBytes": "7",
                      "Content-Type": 'Text/Plain; charset="UTF-8"; name="CaseSensitive.txt"'}
        annotation = {"filename": "notes.txt", "file": {
            "size": 7, "bytes": b"payload", "subtype": "text/plain", "afrelationship": "Data",
            "media_type_parameters": 'name="CaseSensitive.txt"; charset=utf-8'}}
        self.assertTrue(validator.attachment_record_matches_annotation(attachment, annotation))
        annotation["filename"] = "NOTES.txt"
        self.assertFalse(validator.attachment_record_matches_annotation(attachment, annotation))
        annotation["filename"] = "notes.txt"
        for parameters in ('charset=utf-8', 'name="casesensitive.txt"; charset=utf-8',
                           'name="CaseSensitive.txt"; charset=iso-8859-1'):
            annotation["file"]["media_type_parameters"] = parameters
            self.assertFalse(validator.attachment_record_matches_annotation(attachment, annotation))

    def test_encoded_mime_parameters_and_portable_filenames_match_without_losing_case(self):
        self.assertEqual(validator.media_type_signature("text/plain; name*=utf-8''r%C3%A9sum%C3%A9.txt"),
                         validator.media_type_signature('text/plain; name="résumé.txt"'))
        self.assertEqual("notes_txt", validator.filename_key("folder\\notes..txt"))
        self.assertNotEqual(validator.filename_key("Notes.txt"), validator.filename_key("notes.txt"))

    def test_annotation_stream_parameters_must_describe_the_actual_payload(self):
        attachment = {"Filename": "notes.txt", "SizeInBytes": "7", "Content-Type": "text/plain"}
        fileinfo = {"size": 7, "size_entry": 7, "bytes": b"payload", "subtype": "text/plain",
                    "md5": validator.md5hex(b"payload"), "checksum": validator.md5hex(b"payload")}
        annotation = {"filename": "notes.txt", "file": fileinfo}
        self.assertTrue(validator.attachment_record_matches_annotation(attachment, annotation))
        fileinfo["size_entry"] = 0
        self.assertFalse(validator.attachment_record_matches_annotation(attachment, annotation))
        fileinfo["size_entry"] = 7
        fileinfo["checksum"] = validator.md5hex(b"corrupt")
        self.assertFalse(validator.attachment_record_matches_annotation(attachment, annotation))

    def test_source_attachment_content_type_keeps_original_parameters(self):
        raw = (b"Content-Type: multipart/mixed; boundary=parts\r\n\r\n--parts\r\n"
               b"Content-Type: text/plain; charset=UTF-8; format=flowed\r\n"
               b"Content-Disposition: attachment; filename=notes.txt\r\n\r\npayload\r\n--parts--\r\n")
        message = validator.BytesParser(policy=validator.policy.default).parsebytes(raw)
        attachment = validator.summarize_email_attachments(message, raw)[0]
        self.assertEqual(validator.media_type_signature("text/plain; charset=UTF-8; format=flowed"),
                         validator.media_type_signature(attachment["content_type"]))

    def test_missing_core_field_tags_are_unverified_not_silently_not_applicable(self):
        with pikepdf.Pdf.new() as document:
            document.add_blank_page()
            report = validator.Report("untagged-fields.pdf")
            with patch.object(validator, "collect_page_text_analysis", return_value=None):
                validator.check_additional_rule_coverage(document, email_metadata([]), b"", [], [], "s", 4, report)
            finding = next(item for item in report.items if item["code"] == "CORE_FIELDS_TEXT_OBJECTS")
            self.assertEqual("WARN", finding["level"])
            self.assertTrue(any(item["code"] == "VALIDATION_INCOMPLETE" for item in report.items))


@unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"), "Set EAPDF_GENERATOR_CLASSPATH to run creator accuracy integration checks")
class CreatorAccuracyIntegrationTests(unittest.TestCase):
    def test_dates_stored_payload_metadata_css_and_portable_names(self):
        with tempfile.TemporaryDirectory(prefix=".eapdf-accuracy-", dir=ROOT) as directory:
            workspace = Path(directory)
            source = workspace / "folder\\source.eml"
            raw = (b"From: Sender <sender@example.org>\r\nTo: Reader <reader@example.org>\r\n"
                   b"Subject: Accuracy probe\r\nDate: Fri, 16 Oct 2026 12:00:00 +0000\r\n"
                   b"MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=parts\r\n\r\n"
                   b"--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
                   b"<style>.hidden {display:none} .hdr {display:none}</style>"
                   b"<p class=hidden>HIDDENMARKER</p><p>VISIBLEMARKER</p>\r\n"
                   b"--parts\r\nContent-Type: text/plain; charset=UTF-8; format=flowed\r\n"
                   b"Content-Disposition: attachment; filename*=utf-8''folder%5Cnotes..txt\r\n\r\n"
                   b"payload\r\n--parts--\r\n")
            source.write_bytes(raw)
            reports = Path(os.environ.get("EAPDF_INTEGRATION_REPORT_DIR", workspace / "reports")) / "accuracy"
            reports.mkdir(parents=True, exist_ok=True)

            def generate(profile, label):
                output = workspace / label
                command = ["java", "-Xmx256m", "-cp", os.environ["EAPDF_GENERATOR_CLASSPATH"],
                           "eapdf.EmailToPDFA", "--out", str(output)]
                command.extend(web.generation_profile_args(profile, str(output / (label + ".pdf"))))
                command.append(str(source))
                process = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=120)
                (reports / (label + ".log")).write_text(process.stdout + process.stderr)
                return process, output

            for profile in ("1s", "1si"):
                process, output = generate(profile, profile)
                self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                path = next(output.glob("*.pdf"))
                report = validator.validate_one(str(path), html_path=str(reports / (profile + ".html")))
                (reports / (profile + ".json")).write_text(json.dumps(report, default=str, indent=2))
                self.assertEqual([], [item for item in report["items"] if item["level"] == "FAIL"])
                text = "\n".join(page["text"] for page in validator.collect_page_text_analysis(str(path)))
                self.assertIn("VISIBLEMARKER", text)
                self.assertNotIn("HIDDENMARKER", text)
                self.assertIn("sender@example.org", text)
                with pikepdf.Pdf.open(path) as document:
                    root = validator.ET.fromstring(bytes(document.Root.Metadata.read_bytes()))
                    metadata = validator.flatten_attachments(root)[0]
                    self.assertEqual("7" if profile == "1s" else "0", str(metadata["SizeInBytes"]))
                    annotations = validator.collect_file_attachment_annotations(document)
                    native = next(item for item in annotations if item["file"]["ufilename"] == "notes_txt")
                    self.assertEqual(b"payload" if profile == "1s" else b"", native["file"]["bytes"])
                    self.assertTrue(validator.attachment_record_matches_annotation(metadata, native))

            source.write_bytes(raw.replace(b"Fri, 16 Oct 2026", b"Tue, 31 Feb 2026"))
            process, output = generate("1s", "invalid-date")
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            path = next(output.glob("*.pdf"))
            report = validator.validate_one(str(path), html_path=str(reports / "invalid-date.html"))
            (reports / "invalid-date.json").write_text(json.dumps(report, default=str, indent=2))
            self.assertEqual([], [item for item in report["items"] if item["level"] == "FAIL"])
            with pikepdf.Pdf.open(path) as document:
                self.assertIn(b"<pdfmailmeta:Raw-Sent>Tue, 31 Feb 2026", bytes(document.Root.Metadata.read_bytes()))
            original = path.read_bytes()
            source.write_bytes(raw.replace(b"Date: Fri, 16 Oct 2026 12:00:00 +0000\r\n", b""))
            process, _output = generate("1s", "invalid-date")
            self.assertNotEqual(0, process.returncode)
            self.assertIn("without a valid sent date", process.stdout + process.stderr)
            self.assertEqual(original, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
