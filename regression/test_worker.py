import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
from validation_worker import run_cli
import validate_eapdf as validator
import validator_web as web


class WorkerBoundaryTests(unittest.TestCase):
    def run_script(self, source, environment=None, arguments=()):
        with tempfile.TemporaryDirectory(prefix='eapdf-worker-test-') as directory:
            script = Path(directory) / 'script.py'
            script.write_text(source)
            with patch.dict(os.environ, environment or {}), patch('validation_worker.shutil.which', return_value=None):
                return run_cli(script, list(arguments))

    def test_arguments_are_literal_and_normal_output_is_preserved(self):
        code, output, errors, reason = self.run_script('import json,sys;print(json.dumps(sys.argv[1:]))', arguments=['a b', '$(echo unsafe)', '--json'])
        self.assertEqual(0, code)
        self.assertEqual(['a b', '$(echo unsafe)', '--json'], json.loads(output))
        self.assertEqual(b'', errors)
        self.assertIsNone(reason)

    def test_timeout_kills_worker_and_removes_private_storage(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = Path(directory) / 'receipt'
            result = self.run_script('import os,pathlib,time;pathlib.Path(__import__("sys").argv[1]).write_text(os.environ["TMPDIR"]);time.sleep(60)',
                {'VALIDATOR_JOB_TIMEOUT': '1'}, [str(receipt)])
            self.assertIn('time limit', result[3])
            self.assertFalse(Path(receipt.read_text()).exists())
            self.assertEqual(b'', result[1])

    def test_quick_child_cannot_bypass_aggregate_scratch_limit(self):
        result = self.run_script('import os,pathlib; r=pathlib.Path(os.environ["TMPDIR"]);(r/"one").write_bytes(b"x"*80);(r/"two").write_bytes(b"y"*80);print("PASS")',
            {'VALIDATOR_MAX_SCRATCH_BYTES': '128'})
        self.assertIn('scratch/report', result[3])
        self.assertEqual(b'', result[1])

    def test_worker_crash_is_unavailable_even_with_partial_output(self):
        result = self.run_script('import sys;print("PASS");sys.exit(1)')
        self.assertIn('before completion', result[3])
        self.assertEqual(b'', result[1])

    def test_web_quick_child_cannot_bypass_scratch_budget(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(web, 'MAX_JOB_WORK_BYTES', 128):
            with self.assertRaisesRegex(ValueError, 'aggregate scratch/report'):
                web.bounded_subprocess([sys.executable, '-c', 'from pathlib import Path;import sys;(Path(sys.argv[1])/"file").write_bytes(b"x"*256)', directory], 10, work_roots=(directory,))


class MetadataBoundaryTests(unittest.TestCase):
    def test_dtd_entities_are_rejected_in_utf8_and_utf16(self):
        text='<!DOCTYPE x [<!ENTITY value "expanded">]><x>&value;</x>'
        for encoding in ('utf-8', 'utf-16'):
            report=validator.Report('metadata.pdf')
            root, _, _=validator.parse_xmp(text.encode(encoding), report)
            self.assertIsNone(root)
            self.assertTrue(any(item['code']=='VALIDATION_INCOMPLETE' for item in report.items))

    def test_deep_xml_is_unavailable_before_recursive_model_extraction(self):
        report=validator.Report('metadata.pdf')
        root, _, _=validator.parse_xmp(b'<x>'*65+b'</x>'*65, report)
        self.assertIsNone(root)
        self.assertTrue(any(item['code']=='VALIDATION_INCOMPLETE' for item in report.items))
        self.assertFalse(any(item['level']=='FAIL' for item in report.items))

class ValidatorPipelineTests(unittest.TestCase):
    def test_empty_page_completes_without_engine_exception(self):
        import pikepdf
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'empty.pdf'
            with pikepdf.new() as pdf:
                page = pdf.add_blank_page()
                if '/Contents' in page.obj: del page.obj['/Contents']
                pdf.save(path)
            report = validator.validate(path)
            codes = {item['code'] for item in report.items}
            self.assertNotIn('VALIDATION_ERROR', codes)
            self.assertIn('TEXT_CONTENT_PDF_TEXT_OBJECTS', codes)
            self.assertGreater(report.summary()['fail'], 0, 'Ordinary PDF must not pass as EA-PDF')

    def test_unreadable_pdf_is_explicit_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'broken.pdf'; path.write_bytes(b'not a PDF')
            report = validator.validate(path)
            self.assertTrue(any(item['code'] == 'OPEN' and item['level'] == 'FAIL' for item in report.items))
