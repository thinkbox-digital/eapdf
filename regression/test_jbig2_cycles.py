"""Native crash regression for https://github.com/pikepdf/pikepdf/issues/753.

Write the malformed PDFs directly: saving them with an affected pikepdf can
itself crash. All native decoding, including validation, runs in disposable
workers so a dependency regression cannot bring down the test runner.
"""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from validation_worker import run_cli


ROOT = Path(__file__).resolve().parents[1]


def cyclic_jbig2_pdf(two_streams=False, page_content=False):
    objects = [
        b'<< /Type /Catalog /Pages 2 0 R >>',
        b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1 1] '
        b'/Resources << /XObject << /Im0 4 0 R >> >> '
        + (b'/Contents 4 0 R ' if page_content else b'') + b'>>',
    ]
    # Self-reference 4 -> 4, or the indirect cycle 4 -> 5 -> 4.
    for target in ([5, 4] if two_streams else [4]):
        objects.append(
            b'<< /Type /XObject /Subtype /Image /Width 1 /Height 1 '
            b'/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /JBIG2Decode '
            b'/DecodeParms << /JBIG2Globals %d 0 R >> /Length 1 >>\n'
            b'stream\nx\nendstream' % target)
    data = bytearray(b'%PDF-1.7\n')
    offsets = []
    for number, body in enumerate(objects, 1):
        offsets.append(len(data))
        data += b'%d 0 obj\n%s\nendobj\n' % (number, body)
    xref = len(data)
    data += b'xref\n0 %d\n0000000000 65535 f \n' % (len(objects) + 1)
    data += b''.join(b'%010d 00000 n \n' % offset for offset in offsets)
    data += (b'trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n'
             % (len(objects) + 1, xref))
    return bytes(data)


class CyclicJbig2Tests(unittest.TestCase):
    def run_worker(self, source, two_streams, page_content=False):
        with tempfile.TemporaryDirectory(prefix='eapdf-jbig2-test-') as directory:
            root = Path(directory)
            pdf = root / 'cyclic.pdf'
            pdf.write_bytes(cyclic_jbig2_pdf(two_streams, page_content))
            script = root / 'probe.py'
            script.write_text('import sys\nsys.path.insert(0, ' + repr(str(ROOT)) + ')\n' + source)
            with patch.dict(os.environ, {'VALIDATOR_JOB_TIMEOUT': '20', 'VALIDATOR_JOB_MEMORY_MB': '512'}), \
                    patch('validation_worker.shutil.which', return_value=None):
                return run_cli(script, [str(pdf), '--json', '--no-html'])

    def test_native_read_check_and_save_survive_cycles(self):
        source = '''
import io
import json
import pikepdf
with pikepdf.open(sys.argv[1]) as pdf:
    try:
        pdf.get_object((4, 0)).read_bytes()
    except pikepdf.PdfError:
        rejected = True
    else:
        rejected = False
    pdf.check_pdf_syntax()
    saved = io.BytesIO()
    pdf.save(saved)
    print(json.dumps({'rejected': rejected, 'saved': saved.getvalue().startswith(b'%PDF-')}))
'''
        for two_streams in (False, True):
            with self.subTest(two_streams=two_streams):
                code, output, errors, reason = self.run_worker(source, two_streams)
                self.assertIsNone(reason, (reason, errors.decode('utf-8', 'replace')))
                self.assertEqual(0, code)
                self.assertEqual({'rejected': True, 'saved': True}, json.loads(output))

    def test_validator_worker_reports_findings_without_crashing(self):
        source = '''
import validate_eapdf as validator
# Keep this native-parser regression independent of Docker/veraPDF availability.
validator.run_verapdf = lambda path: validator._verapdf_default_result(
    path, 'ERROR', 'External PDF/A validation unavailable in this regression')
validator.main()
'''
        for two_streams in (False, True):
            for page_content in (False, True):
                with self.subTest(two_streams=two_streams, page_content=page_content):
                    code, output, errors, reason = self.run_worker(source, two_streams, page_content)
                    self.assertIsNone(reason, (reason, errors.decode('utf-8', 'replace')))
                    self.assertEqual(2, code)
                    result = json.loads(output)
                    self.assertNotEqual('PASS', result['summary']['status'])
                    self.assertGreater(result['summary']['fail'], 0)
                    codes = {item['code'] for item in result['items']}
                    self.assertIn('OPEN', codes)
                    if page_content:
                        # The intentionally invalid content stream raises a
                        # catchable parser/decoder error, not a native crash.
                        finding = next(item for item in result['items'] if item['code'] == 'VALIDATION_ERROR')
                        self.assertEqual('FAIL', finding['level'])
                        self.assertTrue(finding['details']['error'])
                    else:
                        self.assertIn('TEXT_CONTENT_PDF_TEXT_OBJECTS', codes)
                        self.assertNotIn('VALIDATION_ERROR', codes)
