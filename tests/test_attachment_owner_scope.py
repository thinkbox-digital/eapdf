"""Native byte comparisons use independently declared annotation page owners."""
import unittest
import pikepdf
from test_preservation_safety import ROOT, validator as v, failed_codes


class AttachmentOwnerScopeTests(unittest.TestCase):
    # Fixed native GIF/URL-placeholder evidence; release examples may use other mailboxes.
    def model(self, pdf):
        root, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report("metadata"))
        return v.flatten_email_records(root), v.flatten_attachments(root)

    def assess(self, pdf, change=None):
        emails, attachments = self.model(pdf)
        if change:
            change(attachments)
        files = v.embedded_file_candidates(pdf, v.get_embedded_files(pdf), v.get_af_specs(pdf))
        report = v.Report("owner-scoped native bytes")
        v.check_attachment_stream_consistency(attachments, files, report, emails,
                                            owner_scopes=v.native_attachment_owner_scopes(pdf, emails))
        return report

    def url_annotation(self, pdf):
        for page in pdf.pages:
            for annotation in page.obj.get("/Annots", []):
                fs = annotation.get("/FS")
                if fs is not None and str(fs.get("/UF", fs.get("/F", ""))).startswith("_~DLNK"):
                    return page, annotation
        self.fail("Expected a real renamed URL placeholder")

    def test_ordinary_isolated_archive_completes_native_comparisons(self):
        with pikepdf.Pdf.open(ROOT / "tests/quality/fixtures/pst_native_attachment_scope_legacy.pdf") as pdf:
            report = self.assess(pdf)
        self.assertFalse(failed_codes(report))
        self.assertNotIn("VALIDATION_INCOMPLETE", report.seen_codes)
        self.assertTrue(any(row["code"] == "ATTACHMENT_XMP_SIZE_MATCH" and row["level"] == "PASS" for row in report.items))

    def test_missing_page_owner_keeps_the_association_unavailable(self):
        with pikepdf.Pdf.open(ROOT / "tests/quality/fixtures/pst_native_attachment_scope_legacy.pdf") as pdf:
            page, _ = self.url_annotation(pdf)
            del page.obj.DPart
            report = self.assess(pdf)
        self.assertIn("VALIDATION_INCOMPLETE", report.seen_codes)

    def test_an_additional_unknown_reference_prevents_a_false_scoped_pass(self):
        with pikepdf.Pdf.open(ROOT / "tests/quality/fixtures/pst_native_attachment_scope_legacy.pdf") as pdf:
            _, annotation = self.url_annotation(pdf)
            page = pdf.add_blank_page()
            page.obj.Annots = pikepdf.Array([pdf.make_indirect(pikepdf.Dictionary(
                Type=pikepdf.Name.Annot, Subtype=pikepdf.Name.FileAttachment, FS=annotation.FS))])
            report = self.assess(pdf)
        self.assertIn("VALIDATION_INCOMPLETE", report.seen_codes)

    def test_wrong_size_and_checksum_remain_failures(self):
        for field, value in [("SizeInBytes", "1"), ("CheckSum", "0" * 32)]:
            with self.subTest(field=field), pikepdf.Pdf.open(ROOT / "tests/quality/fixtures/pst_native_attachment_scope_legacy.pdf") as pdf:
                def change(attachments):
                    record = next(a for a in attachments if a["Filename"] == "image001.gif")
                    record[field] = value
                report = self.assess(pdf, change)
                self.assertIn("ATTACHMENT_XMP_SIZE_MATCH" if field=="SizeInBytes" else
                              "ATTACHMENT_XMP_CHECKSUM_MATCH", failed_codes(report))
