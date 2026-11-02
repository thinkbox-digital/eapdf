"""Prove that the independent oracle rejects deliberate losses in a real PDF."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import pikepdf
import unittest
import fitz

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('quality', ROOT / 'scripts/check_conversion_quality.py')
quality = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality)

class ConversionQualityGateTests(unittest.TestCase):
    def setUp(self):
        self.case = json.loads((ROOT / 'tests/quality/corpus.json').read_text())['cases'][1]
        self.source = ROOT / 'tests/quality/fixtures/eml_quality.pdf'
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
        with self.assertRaisesRegex(AssertionError, 'visual change'):
            quality.compare_visual(self.change(edit), ROOT / 'tests/quality/visual/test3', Path(self.temporary.name))

class FormatQualityGateTests(unittest.TestCase):
    def setUp(self):
        self.cases = {c['format']: c for c in json.loads((ROOT / 'tests/quality/corpus.json').read_text())['cases'] if 'format' in c}
        self.temporary = tempfile.TemporaryDirectory(prefix='eapdf-format-oracle-')
        self.addCleanup(self.temporary.cleanup)
        self.output = Path(self.temporary.name) / 'changed.pdf'

    def source(self, kind):
        return ROOT / 'tests/quality/fixtures' / (kind + '_quality.pdf')

    def change(self, kind, edit):
        with pikepdf.open(self.source(kind)) as pdf:
            pdf.save(self.output)
        with fitz.open(self.output) as pdf:
            edit(pdf)
            pdf.saveIncr()
        return self.output

    def test_real_sources_and_outputs(self):
        for kind, pages in [('msg', 2), ('mbox', 3), ('pst', 13)]:
            with self.subTest(kind=kind):
                quality.check_original(self.cases[kind])
                self.assertEqual(pages, quality.check_pdf(self.source(kind), self.cases[kind]))

    def test_mbox_missing_message_record(self):
        def edit(pdf):
            import xml.etree.ElementTree as ET
            xml = ET.fromstring(pdf.get_xml_metadata())
            bag = xml.find('.//' + quality.MAIL + 'email/' + quality.RDF + 'Bag')
            bag.remove(list(bag)[0])
            pdf.set_xml_metadata(ET.tostring(xml, encoding='unicode'))
        with self.assertRaisesRegex(AssertionError, 'Message count'):
            quality.check_pdf(self.change('mbox', edit), self.cases['mbox'])

    def test_mbox_page_assigned_to_wrong_message(self):
        with pikepdf.open(self.source('mbox')) as pdf:
            pdf.pages[1].obj['/DPart'], pdf.pages[2].obj['/DPart'] = pdf.pages[2].obj['/DPart'], pdf.pages[1].obj['/DPart']
            pdf.save(self.output)
        with self.assertRaisesRegex(AssertionError, 'own pages|Body text'):
            quality.check_pdf(self.output, self.cases['mbox'])

    def test_msg_wrong_recipient(self):
        def edit(pdf):
            pdf.set_xml_metadata(pdf.get_xml_metadata().replace('mailto:kees.van.spelde@achmea.nl', 'mailto:lost@example.org'))
        with self.assertRaisesRegex(AssertionError, 'Recipients'):
            quality.check_pdf(self.change('msg', edit), self.cases['msg'])

    def test_msg_changed_native_attachment(self):
        def edit(pdf):
            for page in pdf:
                for annotation in page.annots() or ():
                    if annotation.type[0] == fitz.PDF_ANNOT_FILE_ATTACHMENT:
                        annotation.update_file(buffer_=b'damaged native document')
                        return
            self.fail('Fixture lacks attachment')
        with self.assertRaisesRegex(AssertionError, 'Native attachment bytes'):
            quality.check_pdf(self.change('msg', edit), self.cases['msg'])

    def test_mbox_wrong_sent_date(self):
        def edit(pdf):
            import xml.etree.ElementTree as ET
            xml = ET.fromstring(pdf.get_xml_metadata())
            xml.find('.//' + quality.MAIL + 'Sent').text = '2000-01-01T00:00:00Z'
            pdf.set_xml_metadata(ET.tostring(xml, encoding='unicode'))
        with self.assertRaisesRegex(AssertionError, 'Sent date'):
            quality.check_pdf(self.change('mbox', edit), self.cases['mbox'])

    def test_mbox_missing_derivative_source(self):
        def edit(pdf):
            pdf.embfile_del(pdf.embfile_names()[0])
        with self.assertRaisesRegex(AssertionError, 'Derivative source message set'):
            quality.check_pdf(self.change('mbox', edit), self.cases['mbox'])

    def test_pst_missing_folder_label(self):
        def edit(pdf):
            page = pdf[1]
            for box in page.search_for('Début du fichier de données Outlook'):
                page.add_redact_annot(box)
            page.apply_redactions()
        with self.assertRaisesRegex(AssertionError, 'folder membership'):
            quality.check_pdf(self.change('pst', edit), self.cases['pst'])

    def test_pst_attachment_assigned_to_wrong_message(self):
        with pikepdf.open(self.source('pst')) as pdf:
            pdf.pages[1].obj['/Annots'] = pdf.pages[12].obj['/Annots']
            del pdf.pages[12].obj['/Annots']
            pdf.save(self.output)
        with self.assertRaisesRegex(AssertionError, 'Native attachment names/count'):
            quality.check_pdf(self.output, self.cases['pst'])

    def test_pst_nested_document_corruption(self):
        def edit(pdf):
            import base64
            from email import policy
            from email.parser import BytesParser
            page = pdf[12]
            annotation = next(page.annots())
            message = BytesParser(policy=policy.default).parsebytes(annotation.get_file())
            document = next(message.iter_attachments())
            document.set_payload(base64.b64encode(b'damaged nested docx').decode('ascii'))
            annotation.update_file(buffer_=message.as_bytes())
        with self.assertRaisesRegex(AssertionError, 'Native attachment bytes changed: attachment.docx'):
            quality.check_pdf(self.change('pst', edit), self.cases['pst'])

    def test_pst_invalid_nested_message_encoding(self):
        import base64
        from email import policy
        from email.parser import BytesParser
        changed = False
        with pikepdf.open(self.source('pst')) as pdf:
            names = pdf.Root.Names.EmbeddedFiles.Names
            for index in range(1, len(names), 2):
                stream = names[index].EF.F
                message = BytesParser(policy=policy.default).parsebytes(stream.read_bytes())
                if message['Subject'] == 'FW: First email':
                    attachment = next(message.iter_attachments())
                    data = attachment.get_payload()[0].as_bytes()
                    attachment.set_payload(base64.b64encode(data).decode('ascii'))
                    attachment.replace_header('Content-Transfer-Encoding', 'base64')
                    stream.write(message.as_bytes())
                    changed = True
            self.assertTrue(changed, 'Fixture lacks reconstructed parent')
            pdf.save(self.output)
        with self.assertRaisesRegex(AssertionError, 'Invalid attached-message transfer encoding'):
            quality.check_pdf(self.output, self.cases['pst'])

if __name__ == '__main__':
    unittest.main()
