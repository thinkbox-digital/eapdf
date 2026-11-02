import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from zipfile import ZipFile

import pikepdf

from test_preservation_safety import ROOT, validator


@unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"), "Set EAPDF_GENERATOR_CLASSPATH for supervised CLI tests")
class SupervisedConversionIntegrationTests(unittest.TestCase):
    def test_one_message_collections_publish_only_conforming_members_and_separate_sidecars(self):
        with tempfile.TemporaryDirectory(prefix="eapdf-collection-sidecars-") as temporary:
            directory = Path(temporary)
            source = directory / "source.eml"
            source.write_bytes(b"From: Alice <alice@example.org>\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: Sidecars\r\n\r\nBody")
            config = directory / "config.json"
            for pdf_sidecar in (False, True):
                config.write_text(json.dumps({"branding": {"enabled": False},
                    "findingAid": {"pdfEnabled": True, "excelEnabled": not pdf_sidecar,
                        "embedExcel": pdf_sidecar, "embedPdfInCollections": pdf_sidecar},
                    "premis": {"enabled": pdf_sidecar}}))
                for profile in ("c", "ci"):
                    with self.subTest(profile=profile, pdf_sidecar=pdf_sidecar):
                        output = directory / (profile + str(pdf_sidecar))
                        process = subprocess.run(["java", "-Djava.awt.headless=true", "-Xmx256m", "-cp",
                            os.environ["EAPDF_GENERATOR_CLASSPATH"], "eapdf.EmailToPDFA", "--out", str(output),
                            "--archive-profile", profile, "--archive-output", str(output / "collection.pdf"), str(source)],
                            cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config), EAPDF_MAX_MESSAGES="1"),
                            capture_output=True, text=True, timeout=45)
                        self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                        expected = {"collection.pdf", "collection_index.xlsx"}
                        if pdf_sidecar:
                            expected |= {"collection_finding_aid.pdf", "collection_premis.xml"}
                        self.assertEqual(expected, {path.name for path in output.iterdir() if path.is_file()})
                        for name in expected:
                            self.assertGreater((output / name).stat().st_size, 0)
                        with ZipFile(output / "collection_index.xlsx") as workbook:
                            self.assertIn("xl/workbook.xml", workbook.namelist())
                        with pikepdf.Pdf.open(output / "collection.pdf") as document:
                            files = validator.get_embedded_files(document)
                            self.assertFalse(any(file["filename"].endswith((".xlsx", "_finding_aid.pdf", "_premis.xml")) for file in files))
                            for file in files:
                                if file["afrelationship"] != "Source":
                                    with pikepdf.Pdf.open(io.BytesIO(file["bytes"])) as child:
                                        self.assertIn(b"pdfmailid:conformance", child.Root.Metadata.read_bytes())
                        if pdf_sidecar:
                            with pikepdf.Pdf.open(output / "collection_finding_aid.pdf") as finding:
                                self.assertEqual([], validator.embedded_file_candidates(finding,
                                    validator.get_embedded_files(finding), validator.get_af_specs(finding)))
                                self.assertNotIn(b"pdfmailid:conformance", finding.Root.Metadata.read_bytes())

    def command(self, source, output, environment):
        jar = os.environ["EAPDF_GENERATOR_CLASSPATH"]
        return subprocess.run(["java", "-Djava.awt.headless=true", "-Xmx256m", "-cp", jar,
            "eapdf.EmailToPDFA", "--out", str(output), str(source)], cwd=ROOT,
            env=dict(os.environ, **environment), capture_output=True, text=True, timeout=45)

    def test_sidecar_publication_failure_preserves_existing_pdf_and_sidecar_destination(self):
        with tempfile.TemporaryDirectory(prefix="eapdf-transaction-") as temporary:
            directory = Path(temporary)
            source = directory / "source.eml"
            source.write_bytes(b"From: Alice <alice@example.org>\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: Protected\r\n\r\nBody")
            config = directory / "config.json"
            config.write_text(json.dumps({"branding": {"enabled": False}, "premis": {"enabled": False}}))
            output = directory / "out"
            process = self.command(source, output, {"EAPDF_CONFIG": str(config)})
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            pdf = next(output.glob("*.pdf"))
            original = pdf.read_bytes()
            # A repeat conversion now keeps the existing PDF and chooses _2.
            # Block that new output's sidecar to exercise publication rollback.
            next_pdf = pdf.with_name(pdf.stem + "_2.pdf")
            sidecar = next_pdf.with_name(next_pdf.stem + "_premis.xml")
            sidecar.mkdir()
            config.write_text(json.dumps({"branding": {"enabled": False}, "premis": {"enabled": True}}))
            process = self.command(source, output, {"EAPDF_CONFIG": str(config)})
            self.assertNotEqual(0, process.returncode)
            self.assertEqual(original, pdf.read_bytes())
            self.assertFalse(next_pdf.exists())
            self.assertTrue(sidecar.is_dir())
            self.assertEqual([], list(output.glob(".eapdf-*")))

    def test_tiny_output_budget_never_replaces_an_existing_pdf(self):
        with tempfile.TemporaryDirectory(prefix="eapdf-output-budget-") as temporary:
            directory = Path(temporary)
            source = directory / "source.eml"
            source.write_bytes(b"From: Alice <alice@example.org>\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: Limited\r\n\r\nBody")
            config = directory / "config.json"
            config.write_text(json.dumps({"branding": {"enabled": False}}))
            output = directory / "out"
            process = self.command(source, output, {"EAPDF_CONFIG": str(config)})
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            pdf = next(output.glob("*.pdf"))
            checksum = hashlib.sha256(pdf.read_bytes()).hexdigest()
            process = self.command(source, output, {"EAPDF_CONFIG": str(config), "EAPDF_MAX_OUTPUT_BYTES": "1"})
            self.assertNotEqual(0, process.returncode)
            self.assertEqual(checksum, hashlib.sha256(pdf.read_bytes()).hexdigest())

    def test_packaged_artifacts_preserve_font_notices_and_merged_services(self):
        jar = Path(os.environ["EAPDF_GENERATOR_CLASSPATH"])
        if not jar.is_file():
            self.skipTest("Packaging check requires a JAR")
        gui = jar.with_name(jar.stem + "-gui.jar")
        for artifact, entry in ((jar, "eapdf.EmailToPDFA"), (gui, "eapdf.EapdfGui")):
            with ZipFile(artifact) as archive:
                names = archive.namelist()
                self.assertEqual(len(names), len(set(names)))
                self.assertIn("Main-Class: " + entry, archive.read("META-INF/MANIFEST.MF").decode())
                self.assertIn(b"Tavmjong", archive.read("META-INF/eapdf/third_party_licenses/DejaVu-LICENSE.txt"))
                self.assertIn(b"SIL OPEN FONT LICENSE", archive.read("META-INF/eapdf/third_party_licenses/Liberation-OFL-1.1.txt"))
                self.assertIn(b"PDFBox", archive.read("META-INF/NOTICE"))
                providers = archive.read("META-INF/services/org.apache.poi.extractor.ExtractorProvider").decode()
                self.assertGreater(len([line for line in providers.splitlines() if line and not line.startswith("#")]), 1)
