from test_preservation_safety import failed_source_fields
import hashlib
import unittest
from xml.sax.saxutils import escape

from test_preservation_safety import ROOT, email_metadata, failed_codes, validator


class OutlookSourceTests(unittest.TestCase):
    def source(self, name="TxtSampleEmail.msg"):
        path = ROOT / "in" / ("sample.eml" if name == "not-a-MSG-file.msg" else name)
        if name=="contact-unicode.msg":
            path=ROOT / "src/test/resources/eapdf/outlook-non-email" / name
        if not path.is_file():
            self.skipTest(f"Optional source fixture absent: {name}")
        return {"afrelationship": "Source", "filename": name,
                "subtype": "application/vnd.ms-outlook", "bytes": path.read_bytes(), "objgen": (12, 0)}

    def test_original_msg_bytes_and_stored_headers_are_the_evidence(self):
        source = self.source()
        summary = validator.summarize_source_email([source])
        self.assertEqual("TxtSampleEmail", summary["subject"])
        self.assertEqual(len(source["bytes"]), summary["size_in_bytes"])
        self.assertEqual(hashlib.sha256(source["bytes"]).hexdigest(), summary["source_sha256"])
        self.assertEqual("2017-10-23T08:31:27+01:00", summary["sent_norm"])
        self.assertEqual([], summary["other_parse_defects"])
        self.assertIn("message-id", summary["header_values"])
        self.assertEqual((1, 0), validator.source_email_attachment_expectation([source]))

    def test_by_value_attachments_keep_exact_payload_evidence_without_inventing_a_type(self):
        summary = validator.summarize_source_email([self.source("TxtSampleEmailWithAttachment.msg")])
        self.assertEqual(1, summary["attachment_count"])
        attachment = summary["attachments"][0]
        self.assertEqual("SampleAttachment.pdf", attachment["original_filename"])
        self.assertGreater(attachment["size_in_bytes"], 0)
        self.assertEqual(32, len(attachment["checksum"]))
        self.assertFalse(attachment["content_type_available"])
        record = {"Filename": attachment["filename"], "SizeInBytes": str(attachment["size_in_bytes"]),
                  "CheckSum": attachment["checksum"], "Content-Type": "application/pdf", "Mail_GUID": "uuid:source"}
        summary["mail_guid"] = "uuid:source"
        report = validator.Report("MSG attachment")
        validator.validate_attachment_records([record], summary, report)
        self.assertFalse(failed_codes(report))
        self.assertTrue(any(row["code"] == "ATTACHMENT_XMP_CONTENTTYPE_MATCH" and row["level"] == "N/A" for row in report.items))
        record["CheckSum"] = "0" * 32
        report = validator.Report("MSG corrupt checksum")
        validator.validate_attachment_records([record], summary, report)
        # Original-only evidence cannot establish agreement with the PDF's
        # retained native stream. Its size/checksum are checked separately.
        self.assertFalse(failed_codes(report))
        self.assertFalse(any(row["code"] in {"ATTACHMENT_XMP_SIZE_MATCH", "ATTACHMENT_XMP_CHECKSUM_MATCH"}
                             for row in report.items))

    def test_changed_subject_date_and_size_are_rejected_against_the_original_msg(self):
        summary = validator.summarize_source_email([self.source()])
        record = {"Subject": summary["subject"], "messageid": summary["message_id"],
                  "Sent": summary["sent_norm"], "SizeInBytes": str(summary["size_in_bytes"])}
        for field, value in [("Subject", "Wrong"), ("Sent", "2026-01-01T00:00:00Z"),
                             ("SizeInBytes", "1")]:
            with self.subTest(field=field):
                report = validator.Report("MSG metadata")
                validator.check_source_field_values(dict(record, **{field: value}), summary, report)
                self.assertIn(field, failed_source_fields(report))

    def test_source_selection_and_header_checks_use_the_msg_inventory(self):
        source = self.source()
        summary = validator.summarize_source_email([source])
        record = {"Mail_GUID": "uuid:source", "Subject": escape(summary["subject"]),
                  "messageid": escape(summary["message_id"]), "Sent": summary["sent_norm"],
                  "SizeInBytes": str(summary["size_in_bytes"])}
        for prop in ["To", "From"]:
            record[prop] = "<rdf:Seq>" + "".join(
                "<rdf:li rdf:parseType='Resource'><foaf:mbox xmlns:foaf='http://xmlns.com/foaf/0.1/' rdf:resource='"
                + escape(agent["mbox"]) + "'/></rdf:li>"
                for agent in summary[prop.lower()+"_agents"]) + "</rdf:Seq>"
        report = validator.Report("MSG source binding")
        validator.check_message_metadata(email_metadata([record]), [source], "s", report)
        self.assertTrue(any(row["code"] == "MESSAGE_SOURCE_COVERAGE" and row["level"] == "PASS" for row in report.items))
        self.assertFalse(failed_codes(report), report.items)

    def test_folded_quoted_display_names_preserve_every_recipient(self):
        raw = (b'From: Sender <sender@example.org>\r\n'
               b'Cc: "Wallace,\r\n Stephen" <Stephen@example.org>, "two  spaces"@example.org\r\n'
               b'Subject: Folded recipients\r\n\r\nBody')
        source = {"afrelationship": "Source", "filename": "folded.eml", "subtype": "message/rfc822", "bytes": raw}
        summary = validator.summarize_source_email([source])
        self.assertEqual(["mailto:Stephen@example.org", 'mailto:"two  spaces"@example.org'],
                         [agent["mbox"] for agent in summary["cc_agents"]])
        self.assertIn("\n", summary["header_values"]["cc"][0])

    def test_native_core_detects_definite_mismatches_without_claiming_headers(self):
        source = self.source("2019.10.08 DEP response.msg")
        native = validator.inspect_outlook_source(source["bytes"], native_core_only=True)
        self.assertEqual("2019-10-08T14:07:48.3287079Z", native["Sent"])
        record = {name: str(value) for name, value in native.items() if name in ("Subject","messageid","Sent","SizeInBytes","NumberAttachments")}
        xml_record = {name: escape(value) for name, value in record.items()}
        for field, addresses in native["addresses"].items():
            xml_record[field] = "<rdf:Seq>" + "".join("<rdf:li rdf:parseType='Resource'><foaf:mbox xmlns:foaf='http://xmlns.com/foaf/0.1/' rdf:resource='mailto:" + escape(address) + "'/></rdf:li>" for address in addresses) + "</rdf:Seq>"
        if native.get("reply"):
            xml_record["In-Reply-To"]="<rdf:Bag><rdf:li>"+escape(native["reply"])+"</rdf:li></rdf:Bag>"
        record = validator.flatten_email_records(email_metadata([xml_record]))[0]
        report = validator.Report("Native MSG core")
        validator.check_message_metadata(email_metadata([{**xml_record, "Mail_GUID": "uuid:source"}]), [source], "s", report)
        self.assertFalse(failed_codes(report))
        self.assertTrue(any(row["code"] == "MESSAGE_METADATA_MATCH_SOURCE" and row["level"] == "WARN"
                            and row.get("details", {}).get("field") == "From" for row in report.items))
        self.assertTrue(any(row["code"] == "MESSAGE_SOURCE_COVERAGE" and row["level"] == "PASS" for row in report.items))
        for field, value in [("Subject", "Wrong"), ("Sent", "2019-10-16T12:40:52Z"),
                             ("Sent", "2019-10-08T14:07:48Z"), ("SizeInBytes", "1"),
                             ("NumberAttachments", "1")]:
            with self.subTest(field=field, value=value):
                report = validator.Report("Native MSG mismatch")
                validator.check_native_outlook_core(dict(record, **{field: value}), source, report)
                self.assertIn(field, failed_source_fields(report))

    def test_native_core_does_not_borrow_headers_or_guess_a_nested_owner(self):
        for name in ["TxtSampleEmail.msg", "EmailWithMsgAttachment.msg", "contact-unicode.msg"]:
            with self.subTest(name=name):
                self.assertIsNone(validator.inspect_outlook_source(self.source(name)["bytes"], native_core_only=True))

    def test_missing_headers_nested_messages_and_invalid_storage_remain_unavailable(self):
        for name in ["not-a-MSG-file.msg"]:
            with self.subTest(name=name):
                self.assertEqual([], validator.summarize_source_emails([self.source(name)]))


    def native_record(self, name="message.msg"):
        source = self.source(name)
        native = validator.inspect_outlook_source(source["bytes"], native_core_only=True)
        self.assertIsNotNone(native)
        xml = {name: escape(str(value)) for name, value in native.items() if name in ("Subject","messageid","Sent","SizeInBytes","NumberAttachments")}
        for field, addresses in native["addresses"].items():
            xml[field] = "<rdf:Seq>" + "".join("<rdf:li rdf:parseType='Resource'><foaf:mbox xmlns:foaf='http://xmlns.com/foaf/0.1/' rdf:resource='mailto:" + escape(address) + "'/></rdf:li>" for address in addresses) + "</rdf:Seq>"
        if native.get("reply"):
            xml["In-Reply-To"]="<rdf:Bag><rdf:li>"+escape(native["reply"])+"</rdf:li></rdf:Bag>"
        return source, native, validator.flatten_email_records(email_metadata([xml]))[0]

    def test_native_addresses_reject_missing_changed_and_duplicate_occurrences(self):
        source, native, record = self.native_record()
        good = validator.Report("Native addresses")
        validator.check_native_outlook_core(record, source, good)
        self.assertFalse(failed_codes(good))
        for changed in ([], record["To"] * 2, [{"mbox": "mailto:wrong@example.org"}]):
            with self.subTest(value=changed):
                report = validator.Report("Wrong native recipient")
                validator.check_native_outlook_core(dict(record, To=changed), source, report)
                self.assertIn('To', failed_source_fields(report))
        changed = validator.Report("Wrong native author")
        validator.check_native_outlook_core(dict(record, From=[{"mbox": "mailto:wrong@example.org"}]), source, changed)
        self.assertIn('From', failed_source_fields(changed))

    def test_native_attachment_source_metadata_does_not_claim_stored_byte_agreement(self):
        source, native, record = self.native_record()
        self.assertEqual(1, native["NumberAttachments"])
        original = native["attachments"][0]
        attachment = {"Filename": original["filename"], "SizeInBytes": str(original["size_in_bytes"]),
            "CheckSum": original["checksum"], "Content-Type": original["content_type"], "Mail_GUID": "uuid:source"}
        record["Mail_GUID"] = "uuid:source"
        good = validator.Report("Native attachment")
        validator.check_native_outlook_core(record, source, good, [attachment])
        self.assertFalse(failed_codes(good))
        for key, value in (("CheckSum", "0" * 32), ("SizeInBytes", "1"), ("Filename", "wrong.bin")):
            with self.subTest(field=key):
                report = validator.Report("Wrong native attachment")
                validator.check_native_outlook_core(record, source, report, [dict(attachment, **{key: value})])
                if key == "Filename":
                    self.assertIn("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", failed_codes(report))
                else:
                    self.assertFalse(failed_codes(report))
                    self.assertFalse(any(row["code"] in {"ATTACHMENT_XMP_SIZE_MATCH", "ATTACHMENT_XMP_CHECKSUM_MATCH"}
                                         for row in report.items))

    def test_native_address_comparison_preserves_local_case(self):
        source, native, record = self.native_record()
        local, domain = native["addresses"]["To"][0].rsplit("@", 1)
        good = validator.Report("Domain case")
        validator.check_native_outlook_core(dict(record, To=[{"mbox": "mailto:" + local + "@" + domain.upper()}]), source, good)
        self.assertFalse(failed_codes(good))
        bad = validator.Report("Local case")
        validator.check_native_outlook_core(dict(record, To=[{"mbox": "mailto:" + local.swapcase() + "@" + domain}]), source, bad)
        self.assertIn('To', failed_source_fields(bad))

    def test_unknown_recipient_type_does_not_prove_absent_addresses(self):
        import io, struct, olefile
        source = self.source("message.msg")
        stream = io.BytesIO(source["bytes"])
        with olefile.OleFileIO(stream, write_mode=True) as storage:
            name = ["__recip_version1.0_#00000000", "__properties_version1.0"]
            data = bytearray(storage.openstream(name).read())
            for index in range(8, len(data) - 15, 16):
                if struct.unpack_from("<I", data, index)[0] == 0x0c150003:
                    struct.pack_into("<I", data, index + 8, 99)
            storage.write_stream(name, bytes(data))
            modified = stream.getvalue()
        native = validator.inspect_outlook_source(modified, native_core_only=True)
        self.assertIsNotNone(native)
        self.assertTrue(all(field not in native["addresses"] for field in ("To", "Cc", "Bcc")))
        self.assertIn("From", native["addresses"])

    def test_delegate_smtp_is_not_assigned_to_an_unknown_represented_author(self):
        native = validator.inspect_outlook_source(self.source("2019.10.08 DEP response.msg")["bytes"], native_core_only=True)
        self.assertNotIn("From", native["addresses"])

    def test_empty_raw_text_cannot_replace_known_native_mailboxes(self):
        source, native, record = self.native_record()
        report = validator.Report("Empty Raw-To")
        validator.compare_native_outlook_raw(dict(record, **{"Raw-To":""}), native, "To", report)
        self.assertIn('Raw-To', failed_source_fields(report))

    def test_nested_messages_have_content_evidence_and_cid_is_not_always_inline(self):
        nested = validator.summarize_source_email([self.source("EmailWithInnerMailAndAttachments.MSG")])
        self.assertTrue(nested["attachment_inventory_available"])
        self.assertTrue(any(a.get("nested_content") for a in nested["attachments"]))
        ordinary = validator.summarize_source_email([self.source("2019.10.07 Weary comments.msg")])
        self.assertTrue(ordinary["attachment_inventory_available"])
        self.assertGreater(ordinary["attachment_count"], 0)
