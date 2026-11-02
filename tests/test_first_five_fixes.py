import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import failed_codes, validator, web


class GeneratedDownloadTests(unittest.TestCase):
    def test_only_registered_pdf_capabilities_are_served(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "out"
            output.mkdir()
            nested = output / "job"
            nested.mkdir()
            pdf = nested / "private_email.pdf"
            pdf.write_bytes(b"%PDF-test")
            token = "a" * 32
            escaped = "b" * 32
            outside = root / "outside.pdf"
            outside.write_bytes(b"%PDF-outside")
            symlink = output / "linked.pdf"
            symlink.symlink_to(outside)
            registry = {token: pdf, escaped: symlink}
            with patch.object(web, "OUT_PDF_ROOT", output), patch.object(web, "GENERATED_FILE_MAP", registry):
                client = web.app.test_client()
                response = client.get("/generated/" + token)
                self.assertEqual(200, response.status_code)
                self.assertEqual(b"%PDF-test", response.data)
                response.close()
                for name in ("private_email.pdf", "job/private_email.pdf", escaped, "c" * 32, "../outside.pdf"):
                    self.assertEqual(404, client.get("/generated/" + name).status_code, name)
                pdf.unlink()
                self.assertEqual(404, client.get("/generated/" + token).status_code)

    def test_generator_does_not_register_files_outside_output_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outside = root / "outside.pdf"
            outside.write_bytes(b"%PDF-test")
            with patch.object(web, "OUT_PDF_ROOT", root / "out"), patch.object(web, "run_pdf_validation") as validate:
                self.assertEqual([], web.summarize_generated_files([outside]))
                validate.assert_not_called()


class TreeSafetyTests(unittest.TestCase):
    def test_bookmark_cycles_are_failures_without_hanging(self):
        with tempfile.TemporaryDirectory() as directory, pikepdf.Pdf.new() as document:
            document.add_blank_page()
            bookmark = document.make_indirect(pikepdf.Dictionary(Title="Cycle"))
            bookmark.Next = bookmark
            document.Root.Outlines = pikepdf.Dictionary(First=bookmark)
            with self.assertRaisesRegex(ValueError, "outline"):
                validator.get_outline_items(document)
            path = Path(directory) / "cycle.pdf"
            document.save(path)
            self.assertIn("VALIDATION_ERROR", failed_codes(validator.validate(str(path))))

    def test_logical_structure_and_folder_cycles_are_rejected(self):
        with pikepdf.Pdf.new() as document:
            node = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Document))
            node.K = node
            with self.assertRaisesRegex(ValueError, "logical structure"):
                validator.walk_struct_elems(node, [])
            folder = document.make_indirect(pikepdf.Dictionary(ID=1, Name="Cycle"))
            folder.Child = folder
            with self.assertRaisesRegex(ValueError, "collection folder"):
                validator.walk_collection_folder_nodes(folder, [])

    def test_tree_item_and_depth_limits_are_enforced(self):
        with pikepdf.Pdf.new() as document:
            bookmarks = [document.make_indirect(pikepdf.Dictionary(Title=str(index))) for index in range(4)]
            for current, following in zip(bookmarks, bookmarks[1:]):
                current.Next = following
            with patch.object(validator, "MAX_TREE_ITEMS", 2), self.assertRaises(ValueError):
                validator.walk_outline_items(bookmarks[0], [])
            for current, following in zip(bookmarks, bookmarks[1:]):
                del current.Next
                current.First = following
            with patch.object(validator, "MAX_TREE_DEPTH", 1), self.assertRaises(ValueError):
                validator.walk_outline_items(bookmarks[0], [])

    def test_font_resource_cycles_are_rejected_but_shared_resources_work(self):
        with pikepdf.Pdf.new() as document:
            resources = document.make_indirect(pikepdf.Dictionary())
            form = document.make_stream(b"q Q")
            form.Subtype = pikepdf.Name.Form
            resources.XObject = pikepdf.Dictionary(First=form, Second=form)
            page = pikepdf.Dictionary(Resources=resources)
            self.assertEqual([], validator.collect_font_objects(page))
            form.Resources = resources
            with self.assertRaisesRegex(ValueError, "font resource"):
                validator.collect_font_objects(page)


class ValidationWorkerTests(unittest.TestCase):
    def test_validation_runs_in_a_separate_process(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "validator.py"
            script.write_text("import json, os, pathlib, sys\n"
                              "pathlib.Path(sys.argv[-1]).write_text('report')\n"
                              "print(json.dumps({'summary': {'worker_pid': os.getpid()}, 'pdfa': {}, 'items': []}))\n")
            with patch.object(web, "ROOT", root), patch.object(web, "VALIDATOR_PATH", script):
                result = web.run_pdf_validation(root / "email.pdf")
            self.assertNotEqual(os.getpid(), result["summary"]["worker_pid"])
            self.assertTrue(Path(result["html_output"]).is_file())

    def test_timed_out_worker_is_stopped(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(web, "ROOT", root), patch.object(web, "VALIDATION_TIMEOUT", 0.1), \
                    patch.object(web, "VALIDATION_WORKER_SCRIPT", "import time; time.sleep(30)"):
                result = web.run_pdf_validation(root / "email.pdf")
                self.assertEqual("INCOMPLETE", result["summary"]["status"])
                self.assertIn("time limit", result["items"][0]["message"])
                self.assertTrue(Path(result["html_output"]).is_file())

    @unittest.skipIf(os.name == "nt", "POSIX memory limit")
    def test_worker_memory_is_limited(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            script = root / "validator.py"
            script.write_text("payload = bytearray(256 * 1024 * 1024)\n")
            with patch.object(web, "ROOT", root), patch.object(web, "VALIDATOR_PATH", script), \
                    patch.object(web, "VALIDATION_MEMORY_MB", 128):
                result = web.run_pdf_validation(root / "email.pdf")
                self.assertEqual("INCOMPLETE", result["summary"]["status"])
                self.assertIn("memory limit", result["items"][0]["message"])
                self.assertTrue(Path(result["html_output"]).is_file())


class AttachmentAccessTests(unittest.TestCase):
    def metadata(self, checksum, size=7):
        return ET.fromstring(
            f"<rdf:RDF xmlns:rdf='{validator.NS_RDF}' xmlns:pdfmailmeta='{validator.NS_PDFMAILMETA}'>"
            "<rdf:Description><pdfmailmeta:attachments><rdf:Bag><rdf:li rdf:parseType='Resource'>"
            "<pdfmailmeta:Filename>notes.txt</pdfmailmeta:Filename>"
            f"<pdfmailmeta:SizeInBytes>{size}</pdfmailmeta:SizeInBytes>"
            "<pdfmailmeta:Content-Type>text/plain</pdfmailmeta:Content-Type>"
            f"<pdfmailmeta:CheckSum>{checksum}</pdfmailmeta:CheckSum>"
            "<pdfmailmeta:Mail_GUID>uuid:email</pdfmailmeta:Mail_GUID>"
            "</rdf:li></rdf:Bag></pdfmailmeta:attachments></rdf:Description></rdf:RDF>")

    def check_annotations(self, embedded=False, annotated=False, placeholder=False, hidden=False, corrupt=False, declared_empty=False, consistent_placeholder=False):
        with pikepdf.Pdf.new() as document:
            page = document.add_blank_page()
            payload = b"payload"
            checksum = validator.md5hex(payload)
            files = []
            if embedded or annotated:
                stream = document.make_stream(b"" if placeholder else b"corrupt" if corrupt else payload)
                stream.Subtype = pikepdf.Name("/text/plain")
                stream.Params = pikepdf.Dictionary(Size=0 if declared_empty else len(stream.read_bytes()))
                spec = document.make_indirect(pikepdf.Dictionary(F="notes.txt", UF="notes.txt", EF=pikepdf.Dictionary(F=stream)))
                files = [validator.parse_filespec("notes.txt", spec)]
                if annotated:
                    page.Annots = pikepdf.Array([document.make_indirect(pikepdf.Dictionary(
                        Subtype=pikepdf.Name.FileAttachment, FS=spec, Rect=pikepdf.Array([10, 10, 30, 30]), F=2 if hidden else 0))])
            bodies = [{"identity": {"Mail_GUID": "uuid:email"}, "pages": [1], "start_page": 1, "end_page": 1}]
            report = validator.Report("mock.pdf")
            with patch.object(validator, "get_dpart_nodes", return_value=(None, None, [])), \
                    patch.object(validator, "collect_body_content_sets", return_value=bodies), \
                    patch.object(validator, "collect_page_text_analysis", return_value=None):
                metadata=self.metadata(validator.md5hex(b"") if consistent_placeholder else checksum, 0 if consistent_placeholder else 7)
                validator.check_additional_rule_coverage(document, metadata, b"", files, [], "si", 4, report)
            return report

    def test_missing_annotations_still_fail_when_native_files_are_omitted(self):
        report = self.check_annotations()
        findings = [item for item in report.items if item["code"] == "ATTACHMENT_ANNOTATIONS_PRESENT"]
        self.assertEqual(["FAIL"], [item["level"] for item in findings])
        self.assertEqual("FAIL", report.summary()["status"])

    def test_embedded_files_still_require_matching_annotations(self):
        for options in ({"embedded": True}, {"annotated": True, "corrupt": True},
                        {"annotated": True, "declared_empty": True}):
            with self.subTest(options=options):
                self.assertIn("ATTACHMENT_ANNOTATIONS_PRESENT", failed_codes(self.check_annotations(**options)))

    def test_hidden_annotation_exists_but_fails_visibility(self):
        report = self.check_annotations(annotated=True, hidden=True)
        self.assertEqual({"ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX"}, failed_codes(report))
        self.assertTrue(any(item["code"] == "ATTACHMENT_ANNOTATIONS_PRESENT" and item["level"] == "PASS"
                            for item in report.items))

    def test_visible_complete_attachment_and_consistent_placeholder_have_annotations(self):
        for placeholder in (False, True):
            report = self.check_annotations(annotated=True, placeholder=placeholder, consistent_placeholder=placeholder)
            for code in ("ATTACHMENT_ANNOTATIONS_PRESENT", "ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX"):
                finding = next(item for item in report.items if item["code"] == code)
                self.assertEqual("PASS", finding["level"])

    def test_zero_byte_placeholder_cannot_keep_original_size_or_checksum(self):
        report=self.check_annotations(annotated=True,placeholder=True)
        self.assertIn("ATTACHMENT_ANNOTATIONS_PRESENT",failed_codes(report))


if __name__ == "__main__":
    unittest.main()
