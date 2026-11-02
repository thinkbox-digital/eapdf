import json
import os
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

import pikepdf

from test_preservation_safety import ROOT, validator, web


@unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"), "Set EAPDF_GENERATOR_CLASSPATH to run generated-profile integration checks")
class GeneratedProfileIntegrationTests(unittest.TestCase):
    def test_all_six_profiles_and_nested_email_attachment(self):
        classpath = os.environ["EAPDF_GENERATOR_CLASSPATH"]
        with tempfile.TemporaryDirectory(prefix=".eapdf-integration-", dir=ROOT) as directory:
            workspace = Path(directory)
            sources = workspace / "inputs"
            first = sources / "Inbox" / "first.eml"
            second = sources / "inbox" / "second.eml"
            first.parent.mkdir(parents=True)
            second.parent.mkdir(parents=True)
            first.write_bytes(
                b"From: Sender <sender@example.org>\r\nTo: Recipient <recipient@example.org>\r\n"
                b"Subject: Attached email\r\nMessage-ID: <duplicate@example.org>\r\n"
                b"Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nMIME-Version: 1.0\r\n"
                b"Content-Type: multipart/mixed; boundary=outer\r\n\r\n"
                b"--outer\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nFirst body.\r\n"
                b"--outer\r\nContent-Type: message/rfc822\r\n"
                b"Content-Disposition: attachment; filename=child.eml\r\n\r\n"
                b"From: Nested <nested@example.org>\r\nSubject: Child email\r\n"
                b"Content-Type: multipart/mixed; boundary=inner\r\n\r\n"
                b"--inner\r\nContent-Type: text/plain\r\n\r\nChild body.\r\n"
                b"--inner\r\nContent-Type: application/octet-stream\r\n"
                b"Content-Disposition: attachment; filename=nested.bin\r\n"
                b"Content-Transfer-Encoding: base64\r\n\r\ncGF5bG9hZA==\r\n"
                b"--inner--\r\n\r\n--outer--\r\n")
            second.write_bytes(
                b"From: Other <other@example.org>\r\nTo: Recipient <recipient@example.org>\r\n"
                b"Date: Wed, 29 Jan 2003 21:20:54 -0500\r\n"
                b"Message-ID: <duplicate@example.org>\r\nSubject: Second message\r\n"
                b"Content-Type: text/plain; charset=UTF-8\r\n\r\nSecond body.\r\n")
            (second.parent / "missing-optional-headers.eml").write_bytes(
                b"From: Sender <sender@example.org>\r\nDate: Thu, 30 Jan 2003 12:00:00 +0000\r\n"
                b"Content-Type: text/plain; charset=UTF-8\r\n\r\nNo subject, recipient, or original message ID.\r\n")
            alternate_config = json.loads((ROOT / "config" / "eapdf_config.json").read_text())
            alternate_config["branding"]["enabled"] = False
            alternate_config["premis"]["enabled"] = True
            config_path = workspace / "alternate-config.json"
            config_path.write_text(json.dumps(alternate_config))
            report_root = Path(os.environ.get("EAPDF_INTEGRATION_REPORT_DIR", workspace / "reports"))
            report_root.mkdir(parents=True, exist_ok=True)
            for profile in ("1s", "1si", "1m", "1mi", "1c", "1ci"):
                with self.subTest(profile=profile):
                    output = workspace / profile
                    archive = output / (profile + ".pdf")
                    source = first if profile in ("1s", "1si") else sources
                    command = ["java", "-Xmx512m", "-cp", classpath, "eapdf.EmailToPDFA",
                               "--out", str(output), "--debug-dir", str(workspace / "scratch")]
                    command.extend(web.generation_profile_args(profile, str(archive)))
                    command.append(str(source))
                    environment = dict(os.environ)
                    if profile.endswith("i"):
                        environment["EAPDF_CONFIG"] = str(config_path)
                    generated = subprocess.run(command, cwd=ROOT, env=environment, text=True, capture_output=True, timeout=120)
                    (report_root / (profile + ".log")).write_text(generated.stdout + generated.stderr)
                    self.assertEqual(0, generated.returncode, generated.stdout + generated.stderr)
                    paths = list(output.rglob("*.pdf"))
                    paths = [path for path in paths if path.name != "Finding_Aid.pdf" and not path.name.endswith("_finding_aid.pdf")]
                    self.assertEqual(1, len(paths), paths)
                    result = validator.validate_one(str(paths[0]), html_path=str(report_root / (profile + ".html")))
                    (report_root / (profile + ".json")).write_text(json.dumps(result, default=str, indent=2))
                    failures = [item for item in result["items"] if item["level"] == "FAIL"]
                    self.assertEqual([], failures, json.dumps(failures, default=str))
                    if result["pdfa"]["status"] == "PASS":
                        self.assertEqual("PASS", result["summary"]["status"], json.dumps(
                            [item for item in result["items"] if item["level"] == "WARN"], default=str))
                    if profile in ("1s", "1m"):
                        finding = next(item for item in result["items"] if item["code"] == "ATTACHMENT_ANNOTATIONS_PRESENT")
                        self.assertEqual("PASS", finding["level"])
                        self.assertGreater(finding["details"]["annotations"], 0)
                        with pikepdf.Pdf.open(paths[0]) as document:
                            for annotation in validator.collect_file_attachment_annotations(document):
                                fileinfo = annotation["file"]
                                self.assertGreater(fileinfo["size"], 0)
                                if annotation["contents"].startswith("Open embedded source email:"):
                                    self.assertEqual("Source", fileinfo["afrelationship"])
                                    self.assertEqual("message/rfc822", fileinfo["subtype"])
                    if profile in ("1m", "1mi"):
                        with pikepdf.Pdf.open(paths[0]) as document:
                            self.assertTrue(any(file["filename"].endswith("_index.xlsx")
                                for file in validator.get_embedded_files(document)))
                    if profile in ("1c", "1ci"):
                        with pikepdf.Pdf.open(paths[0]) as document:
                            self.assertFalse(any(file["filename"].endswith(("_index.xlsx", "_finding_aid.pdf"))
                                for file in validator.get_embedded_files(document)))
                        self.assertTrue(archive.with_name(archive.stem + "_index.xlsx").is_file())
                        self.assertTrue(archive.with_name(archive.stem + "_finding_aid.pdf").is_file())
                        children = [item for item in result["items"] if item["code"] == "COLLECTION_CHILD_VALIDATION"]
                        self.assertEqual(3, len(children))
                    if result["pdfa"]["status"] != "PASS":
                        self.assertEqual("INCOMPLETE", result["summary"]["status"])
                    if profile == "1s":
                        with pikepdf.Pdf.open(paths[0]) as document:
                            metadata = bytes(document.Root.Metadata.read_bytes())
                            metadata, changed = re.subn(br"(<pdfmailmeta:Sent>)[^<]*(</pdfmailmeta:Sent>)",
                                                        br"\g<1>2026-99-99T99:99:99Z\g<2>", metadata)
                            self.assertEqual(1, changed)
                            document.Root.Metadata = document.make_stream(metadata)
                            corrupted = workspace / "corrupted.pdf"
                            document.save(corrupted)
                        report = validator.validate(str(corrupted))
                        self.assertTrue(any(item["code"] == "MESSAGE_SENT_FORMAT" and item["level"] == "FAIL" for item in report.items))
