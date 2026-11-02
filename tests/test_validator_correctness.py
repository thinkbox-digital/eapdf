from test_preservation_safety import failed_source_fields
import io
import json
import os
import sys
from contextlib import redirect_stdout
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import pikepdf
from werkzeug.datastructures import FileStorage

from test_preservation_safety import email_metadata, failed_codes, validator, web


class DatesAndReportsTests(unittest.TestCase):
    def test_calendar_dates_and_timezone_ranges(self):
        for value in ("2026-99-99T99:99:99Z", "2025-02-29T12:00:00Z", "2026-01-01T12:00:00+14:01",
                      "2026-01-01T12:00:00+00:60", "2026-01-01T24:00:01Z"):
            with self.subTest(value=value):
                self.assertFalse(validator.looks_like_xmp_datetime(value))
        for value in ("2024-02-29T12:00:00Z", "2026-01-01T24:00:00Z", "2026-01-01T12:00:00-05:00"):
            self.assertTrue(validator.looks_like_xmp_datetime(value), value)
        for value in ("D:20260101120000+99'00'", "D:20260101120000+01'99'", "D:20260230120000Z"):
            self.assertIsNone(validator.parse_pdf_datetime(value))

    def test_same_name_reports_are_unique(self):
        paths = [validator.derive_html_path(path, None) for path in ("one/archive.pdf", "two/archive.pdf", "one/archive.pdf")]
        self.assertEqual(3, len(set(map(str, paths))))

    def test_batch_status_preserves_incomplete(self):
        for statuses, expected in ((["PASS"], "PASS"), (["PASS", "INCOMPLETE"], "INCOMPLETE"),
                                   (["INCOMPLETE", "FAIL"], "FAIL"), ([], "INCOMPLETE")):
            results = [{"summary": {"status": status}} for status in statuses]
            self.assertEqual(expected, validator.batch_validation_status(results))

    def test_cli_json_and_text_do_not_count_incomplete_as_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            results = []
            for index, status in enumerate(("PASS", "INCOMPLETE")):
                path = Path(directory) / f"{index}.pdf"
                path.write_bytes(b"pdf")
                report = validator.Report(str(path))
                summary = dict(report.summary(), status=status)
                results.append({"summary": summary, "pdfa": {"status": "PASS" if status == "PASS" else "SKIP"}, "items": [], "html_output": None})
            output = io.StringIO()
            arguments = ["validate_eapdf.py", directory, "--json", "--no-html", "--jobs", "1"]
            with patch.dict(os.environ, {"EAPDF_VALIDATION_WORKER": "1"}), patch.object(sys, "argv", arguments), patch.object(validator, "run_batch", return_value=(results, "mock")), redirect_stdout(output):
                with self.assertRaises(SystemExit) as stopped:
                    validator.main()
            self.assertEqual(2, stopped.exception.code)
            payload = json.loads(output.getvalue())
            self.assertEqual("INCOMPLETE", payload["summary"]["status"])
            self.assertEqual(0, payload["summary"]["fail_files"])
            self.assertEqual(1, payload["summary"]["incomplete_files"])
            output = io.StringIO()
            with redirect_stdout(output):
                validator.print_batch_text(results)
            self.assertIn("Status: INCOMPLETE", output.getvalue())
            self.assertIn("Failed files: 0 Incomplete files: 1", output.getvalue())


class SourceMatchingTests(unittest.TestCase):
    def source(self, subject, message_id="duplicate@example.org"):
        raw = (f"Subject: {subject}\r\nMessage-ID: <{message_id}>\r\n"
               "Date: Tue, 28 Jan 2003 21:20:54 -0500\r\n\r\nbody\r\n").encode()
        return {"afrelationship": "Source", "subtype": "message/rfc822", "filename": subject + ".eml",
                "objgen": (len(subject), 0), "bytes": raw}

    def record(self, source, guid):
        summary = validator.summarize_source_emails([source])[0]
        return {"Mail_GUID": guid, "messageid": "&lt;duplicate@example.org&gt;", "Subject": summary["subject"],
                "Sent": summary["sent_norm"], "SizeInBytes": str(summary["size_in_bytes"])}

    def test_later_email_corruption_is_detected_with_duplicate_message_ids(self):
        sources = [self.source("First"), self.source("Second email")]
        records = [self.record(source, f"uuid:{index}") for index, source in enumerate(sources)]
        good = validator.Report("archive.pdf")
        validator.check_message_metadata(email_metadata(records), sources, "m", good)
        self.assertEqual(set(), failed_codes(good))
        records[1]["Subject"] = "Wrong subject"
        report = validator.Report("archive.pdf")
        validator.check_message_metadata(email_metadata(records), sources, "m", report)
        self.assertIn('Subject', failed_source_fields(report))
        finding = next(item for item in report.items if item["code"] == "MESSAGE_METADATA_MATCH_SOURCE"
                       and item["level"] == "FAIL" and item.get("details", {}).get("field") == "Subject")
        self.assertEqual(2, finding["details"]["email"])

    def test_ambiguous_source_is_incomplete_not_first_source_fallback(self):
        sources = [self.source("Same"), self.source("Same")]
        sources[1]["bytes"] = sources[1]["bytes"].replace(b"body", b"else")
        records = [self.record(source, f"uuid:{index}") for index, source in enumerate(sources)]
        report = validator.Report("archive.pdf")
        validator.check_message_metadata(email_metadata(records), sources, "m", report)
        self.assertEqual("INCOMPLETE", report.summary()["status"])
        self.assertNotIn("PASS", [item["level"] for item in report.items if item["code"] == "MESSAGE_SOURCE_COVERAGE"])

    def test_raw_headers_keep_quotes_and_decode_encoded_words(self):
        source = self.source("unused")
        source["bytes"] = b'From: "Quoted Name" <name@example.org>\r\nSubject: =?UTF-8?Q?Hello_=C3=A9?=\r\n\r\nbody'
        summary = validator.summarize_source_emails([source])[0]
        self.assertEqual('"Quoted Name" <name@example.org>', summary["raw_from"])
        self.assertEqual("Hello é", validator.decoded_header_text(summary["subject"]))

    def test_attached_email_is_one_attachment_with_original_bytes(self):
        child = (b"Subject: child\r\nContent-Type: multipart/mixed; boundary=inner\r\n\r\n"
                 b"--inner\r\nContent-Disposition: attachment; filename=nested.txt\r\n\r\nnested\r\n--inner--\r\n")
        raw = (b"Content-Type: multipart/mixed; boundary=outer\r\n\r\n--outer\r\n"
               b"Content-Type: message/rfc822\r\nContent-Disposition: attachment; filename=child.eml\r\n\r\n"
               + child + b"\r\n--outer--\r\n")
        message = validator.BytesParser(policy=validator.policy.default).parsebytes(raw)
        attachments = validator.summarize_email_attachments(message, raw)
        self.assertEqual(1, len(attachments))
        self.assertEqual("child.eml", attachments[0]["filename"])
        self.assertEqual(len(child), attachments[0]["size_in_bytes"])
        self.assertEqual(validator.md5hex(child), attachments[0]["checksum"])

    def test_attachment_ownership_uses_guid_and_filenames_are_case_sensitive(self):
        checksum = validator.md5hex(b"second")
        sources = [{"message_id": "duplicate", "mail_guid": guid, "attachments": [
            {"filename": "Same.txt", "size_in_bytes": len(data), "checksum": validator.md5hex(data), "content_type": "text/plain; charset=utf-8"}]}
            for guid, data in (("uuid:first", b"first"), ("uuid:second", b"second"))]
        attachment = {"Filename": "Same.txt", "SizeInBytes": "6", "CheckSum": checksum,
                      "Content-Type": "text/plain; charset=utf-8", "Message-ID": "duplicate", "Mail_GUID": "uuid:second"}
        report = validator.Report("archive.pdf")
        validator.validate_attachment_records([attachment], sources, report)
        self.assertEqual(set(), failed_codes(report))
        without_checksum = dict(attachment)
        without_checksum.pop("CheckSum")
        sources[1]["attachments"].insert(0, dict(sources[0]["attachments"][0]))
        report = validator.Report("duplicate-names.pdf")
        validator.validate_attachment_records([without_checksum], sources, report)
        self.assertEqual(set(), failed_codes(report))
        attachment["Filename"] = "same.txt"
        report = validator.Report("archive.pdf")
        validator.validate_attachment_records([attachment], sources, report)
        self.assertIn("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", failed_codes(report))

    def test_reply_id_arrays_and_optional_fields(self):
        source = validator.summarize_source_emails([self.source("Reply")])[0]
        source["in_reply_to"] = "<one@example.org> <two@example.org>"
        record = {"Sent": source["sent_norm"], "Subject": source["subject"], "messageid": source["message_id"],
                  "In-Reply-To": validator.XmpArray(["<two@example.org>", "<one@example.org>"], "Bag")}
        report = validator.Report("reply.pdf")
        validator.check_source_field_values(record, source, report)
        self.assertEqual(set(), failed_codes(report))
        record["Sent"] = "2003-01-29T03:20:54Z"
        validator.check_source_field_values(record, source, report)
        self.assertIn('Sent', failed_source_fields(report))

    def test_addresses_are_compared_even_when_source_field_is_empty(self):
        root = email_metadata([{"Mail_GUID": "uuid:email"}])
        for source_agents, expected in (([], {"To"}), (None, set())):
            report = validator.Report("email.pdf")
            with patch.object(validator, "extract_message_foaf_agents", return_value=[{"mbox": "mailto:invented@example.org"}]):
                validator.compare_agent_property(root, report, "To", source_agents, "MESSAGE_TO_XMP", "MESSAGE_TO_FOAF")
            self.assertEqual(expected, failed_source_fields(report))


class ContentAndStructureTests(unittest.TestCase):
    def tagged_document(self, form=False):
        document = pikepdf.Pdf.new()
        page = document.add_blank_page()
        scope = document.make_stream(b"/Mail_ContentSet /Named BDC BT (body) Tj ET EMC") if form else page.obj
        resources = pikepdf.Dictionary(Properties=pikepdf.Dictionary(Named=pikepdf.Dictionary(MCID=0)))
        if form:
            scope.Subtype = pikepdf.Name.Form
            scope.Resources = resources
            page.Resources = pikepdf.Dictionary(XObject=pikepdf.Dictionary(Drawing=scope))
            page.Contents = document.make_stream(b"/Drawing Do /Drawing Do")
        else:
            page.Resources = resources
            page.Contents = document.make_stream(b"/Mail_ContentSet /Named BDC BT (body) Tj ET EMC")
        scope.StructParents = 0
        tree = document.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        element = document.make_indirect(pikepdf.Dictionary(S=pikepdf.Name.Document, P=tree))
        reference = pikepdf.Dictionary(Type=pikepdf.Name.MCR, Pg=page.obj, MCID=0)
        if form:
            reference.Stm = scope
        element.K = reference
        tree.K = element
        tree.ParentTree = pikepdf.Dictionary(Nums=pikepdf.Array([0, pikepdf.Array([element])]))
        tree.ParentTreeNextKey = 1
        document.Root.StructTreeRoot = tree
        return document, page, element

    def test_strings_and_comments_are_not_instructions(self):
        with pikepdf.Pdf.new() as document:
            page = document.add_blank_page()
            page.Contents = document.make_stream(b"% /Mail_Field BMC BT ET EMC\nBT (/Mail_Field BMC BT ET EMC) Tj ET")
            self.assertEqual([], validator.page_custom_marked_content(page)["all_tags"])
            self.assertEqual([], validator.page_marked_content_sequences(page, {"Mail_Field"}))
            page.Contents = document.make_stream(b"/P << /ActualText (BT ET) >> BDC EMC")
            self.assertFalse(validator.page_has_text_objects(page))

    def test_nested_sequences_named_properties_and_reused_forms(self):
        document, page, element = self.tagged_document(form=True)
        with document:
            self.assertEqual(["Mail_ContentSet", "Mail_ContentSet"], validator.page_custom_marked_content(page)["sequence_tags"])
            report = validator.Report("form.pdf")
            validator.check_structure_correspondence(document, report)
            self.assertEqual(set(), failed_codes(report))

    def test_parent_tree_and_content_links_are_checked_both_ways(self):
        mutations = (
            lambda document, page, element: setattr(element.K, "MCID", 1),
            lambda document, page, element: setattr(element, "P", page.obj),
            lambda document, page, element: setattr(document.Root.StructTreeRoot.ParentTree, "Nums", pikepdf.Array([0, pikepdf.Array([None, element])])),
            lambda document, page, element: setattr(page, "Contents", document.make_stream(b"/P << /MCID 0 >> BDC EMC /P << /MCID 0 >> BDC EMC")),
            lambda document, page, element: setattr(page, "Contents", document.make_stream(b"/P << /MCID 0 >> BDC")),
        )
        for mutation in mutations:
            document, page, element = self.tagged_document()
            with document:
                good = validator.Report("good.pdf")
                validator.check_structure_correspondence(document, good)
                self.assertEqual(set(), failed_codes(good))
                mutation(document, page, element)
                report = validator.Report("bad.pdf")
                validator.check_structure_correspondence(document, report)
                self.assertIn("STRUCTURE_CORRESPONDENCE", failed_codes(report))

    def test_annotation_object_references_and_orphans(self):
        document, page, element = self.tagged_document()
        with document:
            annotation = document.make_indirect(pikepdf.Dictionary(Subtype=pikepdf.Name.Link, StructParent=1))
            page.Annots = pikepdf.Array([annotation])
            report = validator.Report("orphan.pdf")
            validator.check_structure_correspondence(document, report)
            self.assertIn("STRUCTURE_CORRESPONDENCE", failed_codes(report))
            element.K = pikepdf.Array([element.K, pikepdf.Dictionary(Type=pikepdf.Name.OBJR, Obj=annotation, Pg=page.obj)])
            tree = document.Root.StructTreeRoot
            tree.ParentTree.Nums.extend([1, element])
            tree.ParentTreeNextKey = 2
            report = validator.Report("linked.pdf")
            validator.check_structure_correspondence(document, report)
            self.assertEqual(set(), failed_codes(report))

    def test_empty_eapdf_structure_is_not_a_substitute_for_content_links(self):
        document, page, element = self.tagged_document()
        with document:
            element.S = pikepdf.Name.Mail_ContentSet
            element.K = pikepdf.Array([])
            page.Contents = document.make_stream(b"BT (untagged body) Tj ET")
            del page.obj.StructParents
            document.Root.StructTreeRoot.ParentTree.Nums = pikepdf.Array([])
            report = validator.Report("disconnected.pdf")
            validator.check_structure_correspondence(document, report)
            self.assertIn("STRUCTURE_CORRESPONDENCE", failed_codes(report))

    def test_named_associated_files_inside_form_resources_are_inspected(self):
        document, page, element = self.tagged_document(form=True)
        with document:
            form = page.Resources.XObject.Drawing
            stream = document.make_stream(b"attachment")
            spec = document.make_indirect(pikepdf.Dictionary(F="file.txt", EF=pikepdf.Dictionary(F=stream)))
            form.Resources.Properties.Named.AF = pikepdf.Array([spec])
            associated = validator.collect_marked_content_af_specs(document)
            self.assertEqual(1, len(associated))
            self.assertEqual("file.txt", associated[0]["filename"])

    def test_verapdf_runs_as_the_caller_with_a_docker_side_timeout(self):
        with patch.object(validator.shutil, "which", return_value="docker"), \
                patch.object(validator, "ensure_verapdf_daemon", return_value=(True, "running")), \
                patch.dict(validator.os.environ, {"VERAPDF_JOB_CONTAINER": ""}):
            command = validator._verapdf_command(validator.VERAPDF_MOUNT_ROOT / "example.pdf", "json")
        self.assertIn("--user", command)
        self.assertIn(f"{validator.os.getuid()}:{validator.os.getgid()}", command)
        self.assertIn("timeout", command)

    def test_large_verapdf_job_uses_workload_time_and_assigned_memory(self):
        with tempfile.TemporaryDirectory() as directory:
            pdf = Path(directory) / "large.pdf"
            with pdf.open("wb") as output:
                output.truncate(1024**3)
            with patch.object(validator, "VERAPDF_TIMEOUT", 0), \
                    patch.object(validator.shutil, "which", return_value="docker"), \
                    patch.dict(validator.os.environ, {"VERAPDF_JOB_CONTAINER": "eapdf-web-" + "a" * 32,
                                                       "EAPDF_VERAPDF_HEAP_MB": "2048"}):
                command = validator._verapdf_command(pdf, "json")
                self.assertEqual(8792, validator._verapdf_timeout(pdf))
                self.assertIn("JAVA_OPTS=-Xmx2048m -XX:ActiveProcessorCount=4", command)
                self.assertEqual("3072m", command[command.index("--memory") + 1])
                self.assertIn("8792", command)
            with patch.object(validator, "VERAPDF_TIMEOUT", 17):
                self.assertEqual(17, validator._verapdf_timeout(pdf))


class ChildValidationTests(unittest.TestCase):
    def test_children_use_both_validators_and_share_cached_results(self):
        embedded = {"bytes": b"%PDF-child", "collection_item": True, "filename": "child.pdf"}
        context = validator.ChildValidationContext(include_pdfa=True)
        result = {"summary": {"status": "FAIL"}, "pdfa": {"status": "FAIL"}, "items": []}
        with patch.object(validator, "validate_one", return_value=result) as validate:
            report = validator.Report("collection.pdf")
            validator.check_collection_children([embedded, dict(embedded)], "c", report, context, 0)
        self.assertEqual(1, validate.call_count)
        self.assertEqual(2, len(report.items))
        self.assertIn("COLLECTION_CHILD_VALIDATION", failed_codes(report))

    def test_child_budgets_and_unavailable_pdfa_are_incomplete(self):
        embedded = {"bytes": b"%PDF-child", "collection_item": True, "filename": "child.pdf"}
        context = validator.ChildValidationContext(include_pdfa=True)
        context.max_bytes = 1
        with patch.object(validator, "validate_one") as validate:
            report = validator.Report("collection.pdf")
            validator.check_collection_children([embedded], "ci", report, context, 0)
            validate.assert_not_called()
        self.assertEqual("INCOMPLETE", report.summary()["status"])
        context = validator.ChildValidationContext()
        with patch.object(validator, "validate", return_value=validator.Report("child.pdf")):
            report = validator.Report("collection.pdf")
            validator.check_collection_children([embedded], "ci", report, context, 0)
        self.assertEqual("INCOMPLETE", report.summary()["status"])


class WebLifecycleTests(unittest.TestCase):
    def test_capabilities_work_across_workers_and_expire_without_deleting_local_archives(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(web, "ROOT", Path(directory)):
            root = Path(directory)
            report = root / ".validator_web_tmp" / "reports" / "report.html"
            report.parent.mkdir(parents=True)
            report.write_text("private report")
            local = root / "out" / "local.pdf"
            local.parent.mkdir()
            local.write_bytes(b"archive")
            with patch.object(web, "OUT_PDF_ROOT", local.parent):
                first_worker = web.CapabilityRegistry("report")
                second_worker = web.CapabilityRegistry("report")
                with patch.object(web.time, "time", return_value=100):
                    first_worker["a" * 32] = report
                    web.CapabilityRegistry("job")["b" * 32] = local
                    self.assertEqual(report, second_worker.get("a" * 32))
                with patch.object(web.time, "time", return_value=100 + web.RETENTION_SECONDS + 1):
                    self.assertIsNone(second_worker.get("a" * 32))
                    web.cleanup_expired_jobs()
                self.assertFalse(report.exists())
                self.assertEqual(b"archive", local.read_bytes())

    def test_proxy_prefix_is_present_in_navigation_forms_and_capabilities(self):
        headers = {"X-Forwarded-Prefix": "/ea-pdf-validator"}
        with tempfile.TemporaryDirectory() as directory, patch.object(web, "ROOT", Path(directory)), \
                patch.object(web, "OUT_PDF_ROOT", Path(directory) / "out"), \
                patch.dict(os.environ, {"VALIDATOR_TRUSTED_PROXIES": "127.0.0.1/32"}):
            client = web.app.test_client()
            page = client.get("/", headers=headers).get_data(as_text=True)
            self.assertIn('href="/ea-pdf-validator/generate"', page)
            self.assertIn('action="/ea-pdf-validator/"', page)
            page = client.get("/generate", headers=headers).get_data(as_text=True)
            self.assertIn('action="/ea-pdf-validator/generate/local"', page)

    def test_partial_pdf_upload_is_removed(self):
        def failed_save(upload, destination):
            Path(destination).write_bytes(b"partial")
            raise OSError("interrupted upload")
        with tempfile.TemporaryDirectory() as directory:
            staging = Path(directory) / "uploads"
            with patch.object(web, "ROOT", Path(directory)), patch.object(web, "WEB_TMP_ROOT", staging), \
                    patch.object(FileStorage, "save", failed_save):
                with web.app.test_request_context("/", method="POST", data={"pdfs": (io.BytesIO(b"pdf"), "email.pdf")}):
                    with self.assertRaises(OSError):
                        web.handle_uploads()
            self.assertEqual([], list(staging.iterdir()))

    def test_concurrency_limit_is_shared_and_released(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(web, "ROOT", Path(directory)), \
                patch.object(web, "MAX_CONCURRENT_JOBS", 1):
            with web.job_slot():
                with self.assertRaises(web.JobCapacityError):
                    with web.job_slot():
                        self.fail("Second job acquired an occupied slot")
            with web.job_slot():
                pass

    def test_validation_reports_are_unique_for_repeated_same_named_inputs(self):
        script = "import json,pathlib,sys; pathlib.Path(sys.argv[3]).write_text('report'); print(json.dumps({'summary':{},'pdfa':{},'items':[]}))"
        with tempfile.TemporaryDirectory() as directory, patch.object(web, "ROOT", Path(directory)), \
                patch.object(web, "VALIDATION_WORKER_SCRIPT", script):
            first = web.run_pdf_validation(Path(directory) / "archive.pdf")
            second = web.run_pdf_validation(Path(directory) / "archive.pdf")
            self.assertNotEqual(first["html_output"], second["html_output"])

    def test_separate_generation_requests_get_distinct_output_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output_paths = []
            def generate(arguments, cwd=None):
                output_paths.append(Path(arguments[arguments.index("--archive-output") + 1]))
                return {"stdout": "", "stderr": "", "code": 0}
            with patch.object(web, "ROOT", root), patch.object(web, "OUT_PDF_ROOT", root / "out"), \
                    patch.object(web, "GENERATOR_TMP_ROOT", root / "staging"), \
                    patch.object(web, "run_command", side_effect=generate):
                for index in range(2):
                    data = {"inputs": (io.BytesIO(b"Subject: email\r\n\r\nbody"), "same.eml"),
                            "profile": "1m", "archive_output": "same.pdf"}
                    with web.app.test_request_context("/generate/local", method="POST", data=data):
                        web.handle_local_generation()
            self.assertNotEqual(output_paths[0], output_paths[1])
            self.assertTrue(all(path.name == "same.pdf" and path.is_relative_to(root / "out" / ".web-jobs") for path in output_paths))

    def test_prefixed_report_download_survives_registry_recreation(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(web, "ROOT", Path(directory)):
            report = Path(directory) / ".validator_web_tmp" / "reports" / "report.html"
            report.parent.mkdir(parents=True)
            report.write_text("private report")
            with web.app.test_request_context(environ_overrides={"SCRIPT_NAME": "/ea-pdf-validator"}):
                link = web.register_validation_report(report)
            self.assertTrue(link.startswith("/ea-pdf-validator/validation-report/"))
            with patch.object(web, "VALIDATION_REPORT_MAP", web.CapabilityRegistry("report")):
                response = web.app.test_client().get(link.removeprefix("/ea-pdf-validator"), headers={"X-Forwarded-Prefix": "/ea-pdf-validator"})
            self.assertEqual(200, response.status_code)
            self.assertEqual(b"private report", response.data)
            self.assertEqual("no-store", response.headers["Cache-Control"])
            response.close()

    def test_oversized_posts_return_413_not_500(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(web, "ROOT", Path(directory)), \
                patch.dict(web.app.config, {"MAX_CONTENT_LENGTH": 16}):
            for endpoint, field in (("/", "pdfs"), ("/generate/local", "inputs")):
                response = web.app.test_client().post(endpoint, data={field: (io.BytesIO(b"too large" * 100), "email.pdf")})
                self.assertEqual(413, response.status_code)

    def test_expired_request_budget_does_not_start_another_process(self):
        token = web.JOB_DEADLINE.set(web.time.monotonic() - 1)
        try:
            with patch.object(web.subprocess, "Popen") as start:
                with self.assertRaisesRegex(ValueError, "time limit"):
                    web.bounded_subprocess(["never-start"], 60)
                start.assert_not_called()
        finally:
            web.JOB_DEADLINE.reset(token)


if __name__ == "__main__":
    unittest.main()
