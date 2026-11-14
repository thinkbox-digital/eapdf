"""Prove that the independent oracle rejects deliberate losses in a real PDF."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import pikepdf
import unittest
import pymupdf as fitz

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('quality', ROOT / 'scripts/check_conversion_quality.py')
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)

class ConversionQualityGateTests(unittest.TestCase):
    def setUp(self):
        self.case = json.loads((ROOT / 'regression/quality/oracle-email.json').read_text())
        self.source = ROOT / 'examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'
        self.temporary = tempfile.TemporaryDirectory(prefix='eapdf-oracle-test-')
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / 'changed.pdf'

    def change(self, edit):
        # Normalize the legacy fixture's object stream layout before editing with MuPDF.
        # Its unmodified bytes are still checked independently in the positive test.
        with pikepdf.open(self.source) as original:
            original.save(self.output)
        with fitz.open(self.output) as pdf:
            edit(pdf)
            pdf.saveIncr()
        return self.output

    def test_real_original_and_pdf_match_independent_expectations(self):
        quality.check_original(self.case)
        self.assertEqual(2, quality.check_pdf(self.source, self.case))

    def test_missing_recipient_is_rejected(self):
        def edit(pdf):
            xml = pdf.get_xml_metadata().replace('mailto:oonjoku@us.ibm.com', 'mailto:missing@example.org')
            pdf.set_xml_metadata(xml)
        with self.assertRaisesRegex(AssertionError, 'Recipients'):
            quality.check_pdf(self.change(edit), self.case)

    def test_missing_paragraph_is_rejected(self):
        def edit(pdf):
            for box in pdf[0].search_for('Attached is a basic agenda'):
                pdf[0].add_redact_annot(box)
            pdf[0].apply_redactions()
        with self.assertRaisesRegex(AssertionError, 'Body text missing'):
            quality.check_pdf(self.change(edit), self.case)

    def test_missing_native_attachment_is_rejected(self):
        def edit(pdf):
            for page in pdf:
                for annotation in list(page.annots() or ()):
                    if annotation.type[0] == fitz.PDF_ANNOT_FILE_ATTACHMENT:
                        page.delete_annot(annotation)
        with self.assertRaisesRegex(AssertionError, 'Native attachment'):
            quality.check_pdf(self.change(edit), self.case)

    def test_missing_original_is_rejected(self):
        def edit(pdf):
            for name in pdf.embfile_names():
                pdf.embfile_del(name)
        with self.assertRaisesRegex(AssertionError, 'Exact original'):
            quality.check_pdf(self.change(edit), self.case)

    def test_missing_page_is_rejected(self):
        with self.assertRaises(AssertionError):
            quality.check_pdf(self.change(lambda pdf: pdf.delete_page(0)), self.case)

    def test_oracle_does_not_accept_changed_source_hash(self):
        case = copy.deepcopy(self.case)
        case['sha256'] = '0' * 64
        with self.assertRaisesRegex(AssertionError, 'Original source changed'):
            quality.check_original(case)

    def test_visual_change_is_rejected(self):
        # Same page count and metadata, visibly damaged body.
        def edit(pdf):
            pdf[0].draw_rect(fitz.Rect(40, 150, 550, 350), color=(0, 0, 0), fill=(0, 0, 0))
        baseline = Path(self.temporary.name) / 'baseline'; baseline.mkdir()
        with fitz.open(self.source) as pdf:
            for number, page in enumerate(pdf, 1):
                page.get_pixmap(dpi=72, colorspace=fitz.csGRAY, alpha=False).save(baseline / f'page-{number}.png')
        with self.assertRaisesRegex(AssertionError, 'visual change'):
            quality.compare_visual(self.change(edit), baseline, Path(self.temporary.name))
