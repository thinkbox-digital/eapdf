import base64
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock

from pdf_stream_limits import MalformedStreamError, bounded_validation, read_stream


class FilteredStream(dict):
    def __init__(self, name, data):
        super().__init__({"/Filter": name})
        self.data = data

    def get_raw_stream_buffer(self):
        return self.data


class StreamAndWorkerLimitsTests(unittest.TestCase):
    def test_ascii_filters_preserve_valid_final_groups(self):
        self.assertEqual(b"A\x20", read_stream(FilteredStream("/AHx", b"41 2>"), 16))
        data = b"hello world"
        encoded = base64.a85encode(data) + b"~>"
        self.assertEqual(data, read_stream(FilteredStream("/A85", encoded), 16))

    def test_ascii_filters_require_complete_end_markers(self):
        @bounded_validation
        def inspect(stream, report):
            read_stream(stream, 16)

        for name, data in (("/AHx", b"41"), ("/A85", b"!!!!!"), ("/A85", b"!!!!!~")):
            with self.subTest(name=name, data=data):
                stream = FilteredStream(name, data)
                with self.assertRaisesRegex(MalformedStreamError, "end marker"):
                    read_stream(stream, 16)
                report = Mock()
                self.assertIs(report, inspect(stream, report))
                self.assertEqual("PDF_STREAM_MALFORMED", report.fail.call_args.args[0])

    def test_run_length_distinguishes_end_marker_from_literal_data(self):
        stream = FilteredStream("/RL", b"\x01A\x80\xffB\x80")
        self.assertEqual(b"A\x80BB", read_stream(stream, 16))
        for data in (b"", b"\x01A\x80", b"\x01A", b"\xff"):
            with self.subTest(data=data), self.assertRaises(MalformedStreamError):
                read_stream(FilteredStream("/RunLengthDecode", data), 16)

    def test_invalid_supported_filter_data_is_reported_as_malformed(self):
        for name, data in (("/Fl", b"invalid"), ("/AHx", b"Z>"), ("/A85", b"!~>")):
            with self.subTest(name=name), self.assertRaises(MalformedStreamError):
                read_stream(FilteredStream(name, data), 32)

    @unittest.skipUnless(os.name == "posix", "requires POSIX worker resource limits")
    def test_worker_respects_both_inherited_hard_limits(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "worker.py"
            script.write_text(
                "import json, os, resource\n"
                "print(json.dumps([resource.getrlimit(resource.RLIMIT_AS), "
                "resource.getrlimit(resource.RLIMIT_FSIZE), os.environ['VALIDATOR_JOB_MEMORY_MB']]))\n"
            )
            controller = """
import resource
import sys
from validation_worker import run_cli

for key, requested in ((resource.RLIMIT_AS, 128 * 1024**2), (resource.RLIMIT_FSIZE, 1024**2)):
    _, inherited = resource.getrlimit(key)
    limit = requested if inherited == resource.RLIM_INFINITY else min(requested, inherited)
    resource.setrlimit(key, (limit, limit))
code, output, errors, reason = run_cli(sys.argv[1], [])
assert code == 0 and reason is None, (code, errors, reason)
print(output.decode(), end='')
"""
            env = dict(os.environ, PATH=directory, VALIDATOR_TEMP_ROOT=directory,
                       VALIDATOR_JOB_MEMORY_MB="256", VALIDATOR_DOCKER_CLIENT_MEMORY_MB="512",
                       VALIDATOR_MAX_WORK_FILE_BYTES=str(2 * 1024**2))
            result = subprocess.run([sys.executable, "-B", "-c", controller, str(script)],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        memory, file_size, memory_mb = json.loads(result.stdout)
        self.assertEqual(memory[0], memory[1])
        self.assertLessEqual(memory[1], 128 * 1024**2)
        self.assertEqual(file_size[0], file_size[1])
        self.assertLessEqual(file_size[1], 1024**2)
        self.assertEqual(int(memory_mb), memory[0] // 1024**2)
