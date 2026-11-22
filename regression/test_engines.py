import os
import json
import subprocess
import sys
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import validate_eapdf as validator
from eapdf_validation.engines import EngineOutputLimit, run_with_output_limit


class EngineCompatibilityTests(unittest.TestCase):
    def test_command_uses_live_root_settings_and_daemon_callback(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            pdf = root / "message.pdf"
            with patch.object(validator, "VERAPDF_MOUNT_ROOT", root), \
                    patch.object(validator, "VERAPDF_CONTAINER_NAME", "test-daemon"), \
                    patch.object(validator, "VERAPDF_TIMEOUT", 17), \
                    patch.object(validator.shutil, "which", return_value="/usr/bin/docker"), \
                    patch.object(validator, "ensure_verapdf_daemon", return_value=(True, "running")) as daemon, \
                    patch.dict(os.environ, {"VERAPDF_JOB_CONTAINER": "", "VERAPDF_JOB_CONTAINER_PREFIX": "",
                                            "EAPDF_VERAPDF_HEAP_MB": "512"}):
                command = validator._verapdf_command(pdf, "json")
            daemon.assert_called_once_with()
            self.assertEqual(["/usr/bin/docker", "exec"], command[:2])
            self.assertIn("test-daemon", command)
            self.assertIn("JAVA_OPTS=-Xmx512m -XX:ActiveProcessorCount=4", command)
            self.assertEqual("17", command[command.index("timeout") + 3])
            self.assertEqual("/work/message.pdf", command[-1])

    def test_missing_docker_retains_root_result_callback(self):
        with patch.object(validator.shutil, "which", return_value=None), \
                patch.object(validator, "_verapdf_default_result", return_value={"sentinel": True}) as result:
            self.assertEqual({"sentinel": True}, validator.run_verapdf("message.pdf"))
        result.assert_called_once()

    def engine_report(self, profile, expected_profile=None):
        payload = {"report": {"jobs": [{"validationResult": [{"profileName": profile,
            "compliant": True, "jobEndStatus": "normal", "details": {}}]}]}}
        process = subprocess.CompletedProcess([], 0, json.dumps(payload), "")
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(validator.shutil, "which", return_value="/usr/bin/docker"), \
                patch.object(validator, "_verapdf_command", return_value=["docker", "exec", "test-daemon"]), \
                patch("eapdf_validation.engines.run_with_output_limit", return_value=process):
            pdf = Path(directory, "message.pdf")
            pdf.write_bytes(b"%PDF-")
            return validator.run_verapdf(pdf, expected_profile=expected_profile)

    def test_engine_capture_drains_both_streams_and_stops_at_either_limit(self):
        command = [sys.executable, "-c", "import sys; print('report'); print('diagnostic', file=sys.stderr)"]
        completed = run_with_output_limit(subprocess, command, 5, 7, 11)
        self.assertEqual((0, "report\n", "diagnostic\n"), (completed.returncode, completed.stdout, completed.stderr))
        for limits in ((6, 11), (7, 10)):
            with self.assertRaises(EngineOutputLimit):
                run_with_output_limit(subprocess, command, 5, *limits)

    def test_upstream_label_case_retains_the_exact_allowed_flavours(self):
        for canonical in validator.ALLOWED_VERAPDF_PROFILES:
            for label in (canonical, canonical.replace("3A", "3a").replace("3U", "3u").replace("4F", "4f").replace("4E", "4e")):
                with self.subTest(label=label):
                    result = self.engine_report(label)
                    self.assertEqual("PASS", result["status"])
                    self.assertEqual(canonical, result["profile_name"])
                    self.assertEqual(label, result["raw_json"]["report"]["jobs"][0]["validationResult"][0]["profileName"])
        for label in ("PDF/A-3b validation profile", "PDF/A-4f validation profile extra", "unknown"):
            with self.subTest(label=label):
                self.assertEqual("FAIL", self.engine_report(label)["status"])

    def test_pinned_custom_profile_still_requires_exact_name(self):
        self.assertEqual("PASS", self.engine_report("Pinned profile", "Pinned profile")["status"])
        self.assertEqual("FAIL", self.engine_report("pinned profile", "Pinned profile")["status"])

    def test_iso_profile_keeps_public_engine_callback_and_rejects_unverified_results(self):
        with patch.object(validator, "run_verapdf", return_value={"status": "SKIP"}) as run:
            result = validator.run_iso32005("message.pdf")
        pin = validator.ISO32005_PROFILE
        run.assert_called_once_with("message.pdf", profile_path=validator.ROOT / pin["profile"],
                                    expected_profile=pin["profile_name"])
        self.assertFalse(result["profile_verified"])
        self.assertEqual("ERROR", result["status"])


if __name__ == "__main__":
    unittest.main()
