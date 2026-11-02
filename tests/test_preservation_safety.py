import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import types
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf


ROOT = Path(__file__).resolve().parents[1]


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / filename)
    module = importlib.util.module_from_spec(spec)
    with patch("shutil.which", return_value=None):
        spec.loader.exec_module(module)
    return module


validator = load_module("audit_validator", "validate_eapdf.py")
web = load_module("audit_web", "validator_web.py")


def failed_codes(report):
    return {item["code"] for item in report.items if item["level"] == "FAIL"}


def failed_source_fields(report, code="MESSAGE_METADATA_MATCH_SOURCE"):
    """Keep property identity visible when one source rule has repeated instances."""
    return {item["details"]["field"] for item in report.items
            if item["code"] == code and item["level"] == "FAIL"}


def email_metadata(records, container="Bag"):
    entries = "".join(
        "<rdf:li rdf:parseType='Resource'>"
        + "".join(f"<pdfmailmeta:{name}>{value}</pdfmailmeta:{name}>" for name, value in record.items())
        + "</rdf:li>" for record in records
    )
    return ET.fromstring(
        f"<rdf:RDF xmlns:rdf='{validator.NS_RDF}' xmlns:pdfmailmeta='{validator.NS_PDFMAILMETA}'>"
        f"<rdf:Description><pdfmailmeta:email><rdf:{container}>{entries}</rdf:{container}>"
        "</pdfmailmeta:email><pdfmailmeta:attachments><rdf:Bag/></pdfmailmeta:attachments></rdf:Description></rdf:RDF>"
    )


class UploadSafetyTests(unittest.TestCase):
    def test_unsafe_paths_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for name in ("/tmp/escape.eml", "../escape.eml", "folder/../../escape.eml",
                         "C:/escape.eml", "C:escape.eml", "\\\\host\\share\\escape.eml",
                         "folder\\..\\escape.eml", "", ".", "bad\x00.eml"):
                with self.subTest(name=name), self.assertRaises(ValueError):
                    web.staged_upload_path(Path(directory), name)

    def test_relative_folder_paths_are_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            stage = Path(directory)
            self.assertEqual(stage / "Inbox" / "message.eml", web.staged_upload_path(stage, "Inbox/message.eml"))
            self.assertEqual(stage / "Inbox" / "message.eml", web.staged_upload_path(stage, "Inbox\\message.eml"))

    def test_symlink_escape_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stage = root / "stage"
            stage.mkdir()
            (stage / "link").symlink_to(root, target_is_directory=True)
            with self.assertRaises(ValueError):
                web.staged_upload_path(stage, "link/message.eml")

    def run_generation(self, names, output="out", profile="1s", archive_output=""):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging = root / "staging"
            data = {"inputs": [(io.BytesIO(b"Subject: preserved\r\n\r\nbody"), name) for name in names],
                    "profile": profile, "output_dir": output, "archive_output": archive_output}

            def fake_command(arguments, cwd=None):
                stage = Path(arguments[-1])
                stored = sorted(path.relative_to(stage).as_posix() for path in stage.rglob("*") if path.is_file())
                self.assertEqual(sorted(name.replace("\\", "/") for name in names), stored)
                for path in stage.rglob("*"):
                    if path.is_file():
                        self.assertEqual(b"Subject: preserved\r\n\r\nbody", path.read_bytes())
                return {"stdout": "", "stderr": "", "code": 0}

            with patch.object(web, "ROOT", root), patch.object(web, "OUT_PDF_ROOT", root / "out"), \
                    patch.object(web, "GENERATOR_TMP_ROOT", staging), \
                    patch.object(web, "run_command", side_effect=fake_command) as command:
                with web.app.test_request_context("/generate/local", method="POST", data=data):
                    try:
                        result = web.handle_local_generation()
                        self.assertEqual("Local Generation", result["title"])
                    except (ValueError, FileExistsError):
                        self.assertEqual(0, command.call_count)
                        raise
                    finally:
                        if staging.exists():
                            self.assertEqual([], list(staging.iterdir()))
                return command.call_count

    def test_nested_uploads_are_cleaned_up(self):
        self.assertEqual(1, self.run_generation(["Inbox/message.eml"]))
        self.assertEqual(1, self.run_generation(["message.eml"], output="out/reports"))

    def test_absolute_upload_never_starts_generator(self):
        with self.assertRaises(ValueError):
            self.run_generation(["/tmp/escape.eml"])

    def test_duplicate_upload_does_not_overwrite_first(self):
        with self.assertRaises(FileExistsError):
            self.run_generation(["Inbox/message.eml", "Inbox/message.eml"])

    def test_outputs_cannot_be_written_into_source_or_config(self):
        for output in ("src", "config", ".", "../outside"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                self.run_generation(["message.eml"], output=output)

    def test_archive_output_is_a_filename_not_a_path(self):
        for name in ("../escape.pdf", "/tmp/escape.pdf", "folder/escape.pdf", "C:\\escape.pdf", "source.java"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.run_generation(["message.eml"], profile="1mi", archive_output=name)

    def test_invalid_second_upload_cleans_up_first_upload(self):
        with self.assertRaises(ValueError):
            self.run_generation(["Inbox/message.eml", "../escape.eml"])


class ValidationSafetyTests(unittest.TestCase):
    def pdfa_result(self, status="PASS"):
        return {"status": status, "ran": status == "PASS", "compliant": status == "PASS",
                "profile_allowed": status == "PASS", "message": status}

    def test_skipped_pdfa_never_produces_pass(self):
        report = validator.Report("mock.pdf")
        with patch.object(validator, "run_verapdf", return_value=self.pdfa_result("SKIP")), \
                patch.object(validator, "validate", return_value=report):
            result = validator.validate_one("mock.pdf", write_html=False)
        self.assertEqual("INCOMPLETE", result["summary"]["status"])
        self.assertEqual("INCOMPLETE", web.effective_eapdf_status(result["summary"], result["pdfa"]))
        self.assertIn("<strong>Status:</strong> INCOMPLETE", validator.render_html(report, result["pdfa"]))

    def test_pdfa_failure_and_error_are_not_hidden_by_eapdf_checks(self):
        for status, expected in (("FAIL", "FAIL"), ("ERROR", "INCOMPLETE")):
            with self.subTest(status=status):
                report = validator.Report("mock.pdf")
                self.assertEqual(expected, validator.combined_validation_status(report.summary(), self.pdfa_result(status)))
                self.assertEqual(expected, web.effective_eapdf_status(report.summary(), self.pdfa_result(status)))
                self.assertIn("<strong>Status:</strong> " + expected, validator.render_html(report, self.pdfa_result(status)))

    def test_verapdf_cannot_pass_without_an_allowed_profile_and_successful_exit(self):
        for profile, exit_code, expected in (("PDF/A-4F validation profile", 0, "PASS"),
                                              ("PDF/A-4F validation profile", 1, "ERROR"),
                                              ("PDF/A-3B validation profile", 0, "FAIL"),
                                              ("", 0, "ERROR")):
            with self.subTest(profile=profile, exit_code=exit_code):
                payload = {"report": {"jobs": [{"validationResult": [{"compliant": True, "profileName": profile}]}]}}
                completed = types.SimpleNamespace(stdout=json.dumps(payload), stderr="", returncode=exit_code)
                with patch.object(validator.shutil, "which", return_value="docker"), \
                        patch.object(validator, "_verapdf_command", return_value=["mock"]), \
                        patch.object(validator.subprocess, "run", return_value=completed):
                    self.assertEqual(expected, validator.run_verapdf("mock.pdf")["status"])

    def test_empty_input_directory_cannot_report_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run([sys.executable, "-B", str(ROOT / "validate_eapdf.py"), directory,
                                     "--no-html", "--json"], capture_output=True, text=True, check=False)
        self.assertEqual(2, result.returncode)
        self.assertIn("No PDF files found", result.stderr)

    def test_pass_requires_successful_pdfa_and_complete_eapdf_checks(self):
        report = validator.Report("mock.pdf")
        self.assertEqual("PASS", validator.combined_validation_status(report.summary(), self.pdfa_result()))
        report.warn("VALIDATION_INCOMPLETE", "Child PDFs not checked")
        self.assertEqual("INCOMPLETE", validator.combined_validation_status(report.summary(), self.pdfa_result()))

    def test_nonapplicable_coverage_warning_does_not_make_checks_incomplete(self):
        report = validator.Report("mock.pdf")
        report.na("VALIDATION_INCOMPLETE", "Not applicable")
        self.assertEqual("PASS", report.summary()["status"])

    def test_missing_pdfa_evidence_is_incomplete_even_with_a_pass_label(self):
        summary = validator.Report("mock.pdf").summary()
        self.assertEqual("INCOMPLETE", validator.combined_validation_status(summary, {"status": "PASS"}))
        self.assertEqual("INCOMPLETE", web.effective_eapdf_status(summary, {"status": "PASS"}))

    def test_isolated_email_requires_sent_without_inspectable_source(self):
        metadata = email_metadata([{"Mail_GUID": "uuid:1"}])
        report = validator.Report("mock.pdf")
        validator.check_required_email_metadata(metadata, "si", report)
        self.assertIn("MESSAGE_SENT_XMP", failed_codes(report))

    def test_every_email_requires_valid_sent_and_unique_guid(self):
        metadata = email_metadata([{"Mail_GUID": "uuid:1", "Sent": "2026-10-13T12:00:00Z"},
                                   {"Mail_GUID": "uuid:1", "Sent": "not-a-date"}])
        report = validator.Report("mock.pdf")
        validator.check_required_email_metadata(metadata, "mi", report)
        self.assertTrue({"MESSAGE_SENT_FORMAT", "MAIL_GUID_UNIQUE"}.issubset(failed_codes(report)))

    def test_valid_email_metadata_and_invalid_ordered_container(self):
        record = {"Mail_GUID": "uuid:1", "Sent": "2026-10-13T12:00:00Z"}
        report = validator.Report("mock.pdf")
        validator.check_required_email_metadata(email_metadata([record]), "si", report)
        self.assertEqual(set(), failed_codes(report))
        report = validator.Report("mock.pdf")
        validator.check_required_email_metadata(email_metadata([record], "Seq"), "si", report)
        self.assertIn("EMAIL_XMP_CONTAINER", failed_codes(report))

    def test_folder_summary_schema_cannot_replace_required_core_fields(self):
        with pikepdf.Pdf.new() as document:
            document.Root.Collection = pikepdf.Dictionary(Schema=pikepdf.Dictionary(
                FileName=pikepdf.Dictionary(Subtype=pikepdf.Name.F),
                Messages=pikepdf.Dictionary(Subtype=pikepdf.Name.N)))
            report = validator.Report("mock.pdf")
            validator.check_collection_rules(document, "c", report)
            missing = [item for item in report.items if item["code"] == "COLLECTION_SCHEMA_FIELD" and item["level"] == "FAIL"]
            self.assertEqual(9, len(missing))

    def test_container_requires_source_associated_file(self):
        with pikepdf.Pdf.new() as document:
            report = validator.Report("mock.pdf")
            validator.check_embedded_files(document, None, "c", 4, report)
            self.assertIn("SOURCE_AF", failed_codes(report))

    def test_child_name_trees_and_content_stream_arrays_are_read(self):
        with pikepdf.Pdf.new() as document:
            payload = b"original attachment"
            stream = document.make_stream(payload)
            spec = document.make_indirect(pikepdf.Dictionary(F=pikepdf.String("attachment.txt"),
                EF=pikepdf.Dictionary(F=stream)))
            leaf = document.make_indirect(pikepdf.Dictionary(Names=pikepdf.Array(["attachment.txt", spec])))
            document.Root.Names = pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Kids=pikepdf.Array([leaf])))
            self.assertEqual(payload, validator.get_embedded_files(document)[0]["bytes"])
            page = document.add_blank_page()
            page.Contents = pikepdf.Array([document.make_stream(b"BT (body) Tj ET")])
            self.assertTrue(validator.page_has_text_objects(page))
            self.assertTrue(validator.page_has_visibleish_content(page))
            page.Contents = document.make_stream(b"q Q")
            self.assertFalse(validator.page_has_visibleish_content(page))

    def test_malformed_or_cyclic_pdf_is_a_failure_not_an_exception(self):
        with patch.object(validator.pikepdf.Pdf, "open", side_effect=ValueError("bad PDF")):
            report = validator.validate("mock.pdf")
        self.assertIn("OPEN", failed_codes(report))

    def test_dpart_parents_arrays_and_pdf_name_types_are_enforced(self):
        with pikepdf.Pdf.new() as document:
            page = document.add_blank_page()
            root = document.make_indirect(pikepdf.Dictionary())
            root_node = document.make_indirect(pikepdf.Dictionary(Parent=root))
            leaf = document.make_indirect(pikepdf.Dictionary(Parent=root_node, Start=page.obj,
                DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.BodyRendering,
                                      Mail_Subtype=pikepdf.Name("/text/plain"))))
            root.DPartRootNode = root_node
            root_node.DParts = pikepdf.Array([pikepdf.Array([leaf])])
            document.Root.DPartRoot = root
            _, _, nodes = validator.get_dpart_nodes(document)
            report = validator.Report("mock.pdf")
            validator.check_dpart_object_structure(root, root_node, nodes, report)
            self.assertEqual(set(), failed_codes(report))
            del leaf.Parent
            leaf.DPM.Mail_ContentSetType = pikepdf.String("BodyRendering")
            root_node.DParts = pikepdf.Array([leaf])
            _, _, nodes = validator.get_dpart_nodes(document)
            report = validator.Report("mock.pdf")
            validator.check_dpart_object_structure(root, root_node, nodes, report)
            self.assertTrue({"DPART_OBJECT_STRUCTURE", "DPM_NAME_TYPES"}.issubset(failed_codes(report)))

    def test_cyclic_dpart_tree_is_rejected(self):
        with pikepdf.Pdf.new() as document:
            root = document.make_indirect(pikepdf.Dictionary())
            root.DParts = pikepdf.Array([pikepdf.Array([root])])
            document.Root.DPartRoot = pikepdf.Dictionary(DPartRootNode=root)
            with self.assertRaises(ValueError):
                validator.get_dpart_nodes(document)

    def test_single_page_dpart_omits_end_and_reversed_ranges_are_rejected(self):
        with pikepdf.Pdf.new() as document:
            first = document.add_blank_page()
            second = document.add_blank_page()
            pages = validator.page_objgen_map(document)
            single = {"start": first.obj, "end": None}
            self.assertEqual((1, 1, first.obj.objgen, first.obj.objgen), validator.page_range_from_dpart(single, pages))
            self.assertIsNone(validator.page_range_from_dpart({"start": second.obj, "end": first.obj}, pages))

    def test_frontmatter_self_value_does_not_exempt_noncollection_parent(self):
        for content_type in ("FrontMatter", "BodyRendering"):
            with self.subTest(content_type=content_type), pikepdf.Pdf.new() as document:
                document.add_blank_page()
                property_value = pikepdf.Dictionary(N=pikepdf.String("Mail_ContentSetType"), V=pikepdf.String(content_type))
                attributes = pikepdf.Dictionary(O=pikepdf.Name.UserProperties, P=pikepdf.Array([property_value]))
                root = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Document))
                content = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Mail_ContentSet, P=root, A=attributes))
                root.K = content
                document.Root.StructTreeRoot = pikepdf.Dictionary(K=root)
                report = validator.Report("mock.pdf")
                validator.check_additional_rule_coverage(document, None, b"", [], [], "m", 4, report)
                levels = {i['level'] for i in report.items if i['code'] == 'MAIL_CONTENTSET_NESTED_UNDER_MESSAGE'}
                # A label without any connected content cannot prove the exception.
                self.assertEqual({'WARN'}, levels)
                self.assertIn('VALIDATION_INCOMPLETE', {i['code'] for i in report.items})

    def test_namespace_uri_alone_is_not_a_valid_rolemap(self):
        with pikepdf.Pdf.new() as document:
            document.Root.Version = pikepdf.Name("/2.0")
            message = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Mail_Message))
            root = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Document, K=message))
            document.Root.StructTreeRoot = pikepdf.Dictionary(K=root,
                Namespaces=pikepdf.Array([pikepdf.Dictionary(NS=pikepdf.String("https://pdfa.org/ns/ea-pdf/mail-1"))]))
            report = validator.Report("mock.pdf")
            validator.check_logical_structure(document, "si", report)
            self.assertIn("ROLEMAPNS_PRESENT", failed_codes(report))

    def test_report_keeps_declared_profile_instead_of_reclassifying_missing_source(self):
        report = validator.Report("mock.pdf")
        report.ok("EAPDF_CONFORMANCE_VALUE", "Declared profile", {"actual": "s"})
        report.fail("SOURCE_AF", "Missing source", {"count": 0})
        self.assertEqual("1s", validator.classify_verification_spec(report.items))


if __name__ == "__main__":
    unittest.main()
