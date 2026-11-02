import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import pikepdf

from test_preservation_safety import ROOT, validator, web


@unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"), "Set EAPDF_GENERATOR_CLASSPATH to check generated accessibility structures")
class AccessibilityProfileTests(unittest.TestCase):
    def test_semantic_content_and_annotations_survive_all_profiles(self):
        with tempfile.TemporaryDirectory(prefix=".eapdf-accessibility-", dir=ROOT) as directory:
            workspace = Path(directory)
            sources = workspace / "inputs"
            sources.mkdir()
            first = sources / "first.eml"
            first.write_bytes(
                b"From: Sender <sender@example.org>\r\nTo: Reader <reader@example.org>\r\n"
                b"Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Accessible message\r\n"
                b"Message-ID: <accessible@example.org>\r\nMIME-Version: 1.0\r\n"
                b"Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                b"--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
                b"<h1>Heading</h1><p>Paragraph <a href='https://example.org'>link</a></p>"
                b"<table><tr><th>Column</th></tr><tr><td>Value</td></tr></table>\r\n"
                b"--parts\r\nContent-Type: text/plain\r\nContent-Disposition: attachment; filename=original.txt\r\n\r\n"
                b"Original attachment\r\n--parts--\r\n")
            (sources / "second.eml").write_bytes(
                b"From: Sender <sender@example.org>\r\nTo: Reader <reader@example.org>\r\n"
                b"Date: Fri, 16 Oct 2026 12:01:00 +0000\r\nSubject: Second message\r\n\r\nSecond body\r\n")
            config = json.loads((ROOT / "config/eapdf_config.json").read_text())
            config["accessibility"]["addPdfUaFeatures"] = True
            config_path = workspace / "semantic-config.json"
            config_path.write_text(json.dumps(config))
            environment = dict(os.environ, EAPDF_CONFIG=str(config_path))
            report_root = Path(os.environ.get("EAPDF_INTEGRATION_REPORT_DIR", workspace / "reports")) / "accessibility"
            report_root.mkdir(parents=True, exist_ok=True)
            for profile in ("1s", "1si", "1m", "1mi", "1c", "1ci"):
                with self.subTest(profile=profile):
                    output = workspace / profile
                    command = ["java", "-Djava.awt.headless=true", "-Xmx256m", "-cp",
                               os.environ["EAPDF_GENERATOR_CLASSPATH"], "eapdf.EmailToPDFA", "--out", str(output)]
                    command.extend(web.generation_profile_args(profile, str(output / (profile + ".pdf"))))
                    command.append(str(first if profile in ("1s", "1si") else sources))
                    process = subprocess.run(command, cwd=ROOT, env=environment, text=True, capture_output=True, timeout=120)
                    (report_root / (profile + ".log")).write_text(process.stdout + process.stderr)
                    self.assertEqual(0, process.returncode, process.stdout + process.stderr)
                    pdf_path = next(output.glob("*.pdf"))
                    report = validator.validate_one(str(pdf_path), html_path=str(report_root / (profile + ".html")))
                    (report_root / (profile + ".json")).write_text(json.dumps(report, default=str, indent=2))
                    failures = [item for item in report["items"] if item["level"] == "FAIL"]
                    self.assertEqual([], failures, json.dumps(failures, default=str))
                    if report["pdfa"]["status"] == "PASS":
                        self.assertEqual("PASS", report["summary"]["status"], json.dumps(
                            [item for item in report["items"] if item["level"] == "WARN"], default=str))
                    correspondence = [item for item in report["items"] if item["code"] == "STRUCTURE_CORRESPONDENCE"]
                    self.assertTrue(correspondence)
                    self.assertTrue(all(item["level"] == "PASS" for item in correspondence), correspondence)
                    with pikepdf.Pdf.open(pdf_path) as document:
                        elements = []
                        validator.walk_struct_elems(document.Root.StructTreeRoot.K, elements, None)
                        roles = {element["type"] for element in elements}
                        self.assertTrue({"H1", "Table", "TH", "TD"}.issubset(roles), roles)


if __name__ == "__main__":
    unittest.main()
