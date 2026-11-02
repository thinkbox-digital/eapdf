import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_preservation_safety import ROOT, validator, web


@unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"), "Set EAPDF_GENERATOR_CLASSPATH for packaged conformance regressions")
class SpecRepairIntegrationTests(unittest.TestCase):
    def test_original_review_probes_have_correct_positive_and_negative_outcomes(self):
        jar = Path(os.environ["EAPDF_GENERATOR_CLASSPATH"])
        if not jar.is_file():
            self.skipTest("Review probe requires the packaged generator JAR")
        report_root = os.environ.get("EAPDF_INTEGRATION_REPORT_DIR")
        with tempfile.TemporaryDirectory(prefix="eapdf-spec-repairs-") as temporary:
            if report_root:
                Path(report_root).mkdir(parents=True, exist_ok=True)
                output = Path(tempfile.mkdtemp(prefix="spec-repairs-", dir=Path(report_root).resolve())) / "probes"
            else:
                output = Path(temporary) / "probes"
            command = [sys.executable, "-B", str(ROOT / "docs/reimplementation/review_probes.py"),
                       "--jar", str(jar.resolve()), "--output", str(output), "--pdfa"]
            schema = os.environ.get("EAPDF_PREMIS_SCHEMA")
            if schema:
                command += ["--premis-schema", schema]
            process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=300)
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            results = {item["case"]: item for item in json.loads((output / "results.json").read_text())["results"]}
            self.assertGreaterEqual(results["image_only"]["imageObjects"], 2)
            self.assertEqual(["text/plain; charset=UTF-8; format=flowed"], results["body_parameters"]["bodySubtypes"])
            self.assertTrue(any("alternative text" in warning for warning in results["image_with_text"]["conversionWarnings"]))
            self.assertTrue(all(results["image_with_text"]["figureAlternatives"]))
            for name in ("image_only", "image_with_text", "body_parameters", "premis", "colon:source"):
                if results[name].get("skipped"):
                    continue
                expected = "PASS" if results[name]["validation"]["pdfaStatus"] == "PASS" else "INCOMPLETE"
                self.assertEqual(expected, results[name]["validation"]["status"], results[name])
            for name, code in (("nfsm", "PROFILE_NFSM_SINGLE_NO_ATTACHMENTS"), ("missing-subject", "MESSAGE_SUBJECT_XMP")):
                self.assertEqual("FAIL", results[name]["validation"]["status"])
                self.assertIn(code, results[name]["validation"]["failures"])
            if schema:
                self.assertTrue(results["premis"]["premisSchemaValid"], results["premis"])
            for result in results.values():
                if not result.get("skipped"):
                    status = result["validation"]["pdfaStatus"]
                    self.assertIn(status, ("PASS", "SKIP"), result)

    def test_explicit_missing_configuration_is_an_error(self):
        jar = Path(os.environ["EAPDF_GENERATOR_CLASSPATH"])
        if not jar.is_file():
            self.skipTest("Configuration regression requires the packaged generator JAR")
        with tempfile.TemporaryDirectory(prefix="eapdf-config-repair-") as temporary:
            workspace = Path(temporary)
            source = workspace / "source.eml"
            source.write_bytes(b"From: Alice <alice@example.org>\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\n\r\nBody")
            process = subprocess.run(["java", "-Djava.awt.headless=true", "-jar", str(jar.resolve()),
                "--out", str(workspace / "out"), str(source)], cwd=ROOT,
                env=dict(os.environ, EAPDF_CONFIG=str(workspace / "missing.json")),
                capture_output=True, text=True, timeout=30)
            self.assertNotEqual(0, process.returncode)
            self.assertIn("failed to load config", process.stdout + process.stderr)
            self.assertEqual([], list(workspace.rglob("*.pdf")))

    @unittest.skipUnless(os.environ.get("EAPDF_PREMIS_SCHEMA"), "Set EAPDF_PREMIS_SCHEMA to the official PREMIS 3 XSD")
    def test_premis_schema_for_all_profiles_sidecars_and_embedded_children(self):
        from lxml import etree
        import pikepdf
        import io

        schema = etree.XMLSchema(etree.parse(os.environ["EAPDF_PREMIS_SCHEMA"]))
        classpath = os.environ["EAPDF_GENERATOR_CLASSPATH"]
        with tempfile.TemporaryDirectory(prefix=".eapdf-premis-repairs-", dir=ROOT) as temporary:
            workspace = Path(temporary)
            sources = workspace / "inputs"
            sources.mkdir()
            for index in (1, 2):
                filename = "<999>source.eml" if index == 2 and os.name == "posix" else str(index) + ".eml"
                (sources / filename).write_bytes((
                    "From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n"
                    "Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: Message " + str(index)
                    + "\r\nMessage-ID: <" + str(index) + "@example.org>\r\n\r\nBody").encode())
            config = workspace / "config.json"
            config.write_text(json.dumps({"branding": {"enabled": False}, "findingAid": {"pdfEnabled": False}, "premis": {"enabled": True}}))
            report_root = Path(os.environ.get("EAPDF_INTEGRATION_REPORT_DIR", workspace / "reports")) / "premis"
            report_root.mkdir(parents=True, exist_ok=True)
            schema_checks = []

            def check_xml(data, name):
                self.assertTrue(schema.validate(etree.fromstring(data)), str(schema.error_log))
                schema_checks.append(name)

            def check_embedded(document, prefix):
                for file in validator.embedded_file_candidates(document, validator.get_embedded_files(document), validator.get_af_specs(document)):
                    name = file.get("ufilename") or file.get("filename") or "unnamed"
                    if name.endswith("_premis.xml"):
                        check_xml(file["bytes"], prefix + "/" + name)
                    if file.get("subtype") == "application/pdf" and file["bytes"].startswith(b"%PDF-"):
                        with pikepdf.open(io.BytesIO(file["bytes"])) as child:
                            check_embedded(child, prefix + "/" + name)

            for profile in ("1s", "1si", "1m", "1mi", "1c", "1ci"):
                output = workspace / profile
                command = ["java", "-Djava.awt.headless=true", "-Xmx512m", "-cp", classpath,
                    "eapdf.EmailToPDFA", "--out", str(output)]
                archive_name = "colon:archive.pdf" if os.name == "posix" else "archive.pdf"
                command += web.generation_profile_args(profile, str(output / archive_name))
                command.append(str(sources / "1.eml" if profile in ("1s", "1si") else sources))
                process = subprocess.run(command, cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config)),
                    capture_output=True, text=True, timeout=120)
                (report_root / (profile + ".log")).write_text(process.stdout + process.stderr)
                self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                sidecar = next(output.glob("*_premis.xml"))
                check_xml(sidecar.read_bytes(), profile + "/sidecar")
                pdf = next(output.glob("*.pdf"))
                with pikepdf.open(pdf) as document:
                    check_embedded(document, profile)
                report = validator.validate_one(str(pdf), html_path=str(report_root / (profile + ".html")))
                (report_root / (profile + ".json")).write_text(json.dumps(report, default=str, indent=2))
                self.assertEqual([], [item for item in report["items"] if item["level"] == "FAIL"])
            (report_root / "schema-checks.json").write_text(json.dumps(schema_checks, indent=2))
            self.assertGreaterEqual(len(schema_checks), 9)

    def test_real_mailbox_exports_preserve_body_mime_and_document_the_boundary(self):
        import pikepdf

        with tempfile.TemporaryDirectory(prefix=".eapdf-mailbox-repairs-", dir=ROOT) as temporary:
            workspace = Path(temporary)
            config = workspace / "config.json"
            config.write_text(json.dumps({"branding": {"enabled": False}, "findingAid": {"pdfEnabled": False}}))
            report_root = Path(os.environ.get("EAPDF_INTEGRATION_REPORT_DIR", workspace / "reports")) / "mailboxes"
            report_root.mkdir(parents=True, exist_ok=True)
            for name in ("sample.mbox", "attached-message.pst"):
                source = ROOT / "src/test/resources/eapdf/email" / name
                output = workspace / name
                command = ["java", "-Djava.awt.headless=true", "-Xmx512m", "-cp", os.environ["EAPDF_GENERATOR_CLASSPATH"],
                    "eapdf.EmailToPDFA", "--out", str(output), "--archive-profile", "c", str(source)]
                process = subprocess.run(command, cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config)),
                    capture_output=True, text=True, timeout=120)
                (report_root / (name + ".log")).write_text(process.stdout + process.stderr)
                self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                pdf = next(output.glob("*.pdf"))
                report = validator.validate_one(str(pdf), html_path=str(report_root / (name + ".html")))
                (report_root / (name + ".json")).write_text(json.dumps(report, default=str, indent=2))
                self.assertEqual([], [item for item in report["items"] if item["level"] == "FAIL"])
                with pikepdf.open(pdf) as document:
                    self.assertIn("preprocessing:", document.Root.Metadata.read_bytes().decode("utf-8"))
