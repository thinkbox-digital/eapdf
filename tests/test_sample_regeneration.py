import copy
import importlib.util
from pathlib import Path
import unittest
import json
import tempfile
from types import SimpleNamespace
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("regenerate_email_samples", ROOT / "scripts/regenerate_email_samples.py")
samples = importlib.util.module_from_spec(spec)
spec.loader.exec_module(samples)


class SampleRegenerationTests(unittest.TestCase):
    def report(self, profile="1mi", incomplete=True):
        return {
            "summary": {"verification_spec": profile, "status": "INCOMPLETE" if incomplete else "PASS"},
            "pdfa": {"status": "PASS"},
            "items": [{"code": "ISO32005_UPSTREAM", "level": "PASS"}] + ([{
                "code": "VALIDATION_INCOMPLETE", "level": "WARN",
                "message": "Attachment native-byte consistency could not be fully verified",
            }] if incomplete else []),
        }

    def test_incomplete_isolated_result_is_preserved_and_rejected(self):
        result = self.report()
        original = copy.deepcopy(result)
        with self.assertRaises(RuntimeError):
            samples.require_sample_validation("1mi", result)
        self.assertEqual(original, result)

    def test_all_six_correct_passing_profiles_are_accepted(self):
        for profile in ("1s", "1si", "1m", "1mi", "1c", "1ci"):
            samples.require_sample_validation(profile, self.report(profile, incomplete=False))

    def test_passing_summary_cannot_hide_a_failure_or_unavailable_check(self):
        for item in ({"code": "MESSAGE_SENT_FORMAT", "level": "FAIL"}, {
            "code": "VALIDATION_INCOMPLETE", "level": "WARN", "message": "Source comparison unavailable",
        }):
            result = self.report(incomplete=False)
            result["items"].append(item)
            with self.assertRaises(RuntimeError):
                samples.require_sample_validation("1mi", result)

    def test_incomplete_results_are_rejected_for_every_profile_and_reason(self):
        for profile in ("1s", "1si", "1m", "1c", "1ci"):
            with self.assertRaises(RuntimeError):
                samples.require_sample_validation(profile, self.report(profile))
        result = self.report()
        result["items"][-1]["message"] = "Structure inspection budget exhausted"
        with self.assertRaises(RuntimeError):
            samples.require_sample_validation("1mi", result)

    def test_missing_failed_or_unavailable_external_checks_and_wrong_profile_are_rejected(self):
        for status in ("FAIL", "ERROR", "INCOMPLETE"):
            result = self.report()
            result["pdfa"]["status"] = status
            with self.assertRaises(RuntimeError):
                samples.require_sample_validation("1mi", result)
        for iso in ([], [{"code": "ISO32005_UPSTREAM", "level": "WARN"}],
                    [{"code": "ISO32005_UPSTREAM", "level": "FAIL"}]):
            result = self.report()
            result["items"] = iso + result["items"][1:]
            with self.assertRaises(RuntimeError):
                samples.require_sample_validation("1mi", result)
        with self.assertRaises(RuntimeError):
            samples.require_sample_validation("1si", self.report("1s", incomplete=False))

    def test_java_engine_is_invoked_and_its_result_is_retained(self):
        with tempfile.TemporaryDirectory(dir="/tmp") as directory:
            def run(command, **kwargs):
                self.assertEqual("java", command[0])
                self.assertIn("eapdf.JavaValidation", command)
                Path(command[-1]).write_text(json.dumps(self.report("1m", incomplete=False)))
                return SimpleNamespace(returncode=0)
            with patch.object(samples.subprocess, "run", side_effect=run):
                result = samples.validate_sample(Path("candidate.pdf"), "1m", Path(directory))
            self.assertEqual("PASS", result["summary"]["status"])
            self.assertEqual("Java", result["sample_execution"]["validator"])

    def test_java_crash_cannot_reuse_an_old_passing_report(self):
        with tempfile.TemporaryDirectory(dir="/tmp") as directory:
            path = Path(directory) / "sample_1s.json"
            path.write_text(json.dumps(self.report("1s", incomplete=False)))
            with patch.object(samples.subprocess, "run", return_value=SimpleNamespace(returncode=1)):
                with self.assertRaises(RuntimeError):
                    samples.validate_sample(Path("candidate.pdf"), "1s", Path(directory))
            self.assertFalse(path.exists())

    def test_java_exit_and_report_status_must_agree(self):
        with tempfile.TemporaryDirectory(dir="/tmp") as directory:
            def run(command, **kwargs):
                Path(command[-1]).write_text(json.dumps(self.report("1s", incomplete=False)))
                return SimpleNamespace(returncode=2)
            with patch.object(samples.subprocess, "run", side_effect=run):
                with self.assertRaises(RuntimeError):
                    samples.validate_sample(Path("candidate.pdf"), "1s", Path(directory))
