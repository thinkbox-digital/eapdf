import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_preservation_safety import web


class MailboxUploadTests(unittest.TestCase):
    def test_mailbox_upload_is_accepted_for_single_message_and_archive_profiles(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mailbox = b"From author@example.org Tue Oct 13 12:00:00 2026\nSubject: Mailbox\n\nBody\n"
            commands = []

            def generate(arguments, cwd=None):
                stage = Path(arguments[-1])
                self.assertEqual(mailbox, (stage / "uploaded.mbox").read_bytes())
                commands.append(arguments)
                return {"stdout": "", "stderr": "", "code": 0}

            with patch.object(web, "ROOT", root), patch.object(web, "OUT_PDF_ROOT", root / "out"), \
                    patch.object(web, "GENERATOR_TMP_ROOT", root / "staging"), \
                    patch.object(web, "run_command", side_effect=generate):
                for profile in ("1s", "1m"):
                    data = {"inputs": (io.BytesIO(mailbox), "uploaded.mbox"), "profile": profile}
                    with web.app.test_request_context("/generate/local", method="POST", data=data):
                        result = web.handle_local_generation()
                    self.assertEqual(0, result["command_result"]["code"])
                    self.assertFalse(Path(commands[-1][-1]).exists())
            self.assertNotIn("--archive-profile", commands[0])
            self.assertEqual("m", commands[1][commands[1].index("--archive-profile") + 1])

    def test_file_picker_advertises_mailbox_uploads(self):
        response = web.app.test_client().get("/generate")
        self.assertEqual(200, response.status_code)
        self.assertIn(b".mbox", response.data)


if __name__ == "__main__":
    unittest.main()
