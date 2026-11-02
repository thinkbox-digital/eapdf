"""User-authorized interpretations use real PDF objects and typed XMP values."""
from scripts.eapdf_rule_review import load_review_rules

import json
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

import pikepdf
from test_preservation_safety import ROOT, validator as v
from test_email_record_schema import metadata

SEED = ROOT / 'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'


class LocalReviewDecisionTests(unittest.TestCase):
    def schema(self, field='', value=''):
        root = metadata(field, value)
        report = v.Report('metadata.pdf')
        v.check_required_email_metadata(root, 'si', report)
        return root, report

    def test_unordered_reply_bag_accepts_text_and_empty_but_rejects_ordered_and_uri_members(self):
        for kind, member, valid in (('Bag', 'literal', True), ('Bag', None, True),
                                     ('Seq', 'literal', False), ('Alt', 'literal', False),
                                     ('Bag', 'uri', False)):
            with self.subTest(kind=kind, member=member):
                root = metadata('In-Reply-To', '')
                node = root.find('.//' + v.qn(v.NS_PDFMAILMETA, 'In-Reply-To'))
                array = ET.SubElement(node, v.qn(v.NS_RDF, kind))
                if member:
                    item = ET.SubElement(array, v.qn(v.NS_RDF, 'li'))
                    if member == 'uri':
                        item.set(v.qn(v.NS_RDF, 'resource'), 'urn:parent')
                    else:
                        item.text = '<parent@example.org>'
                report = v.Report('replies.pdf')
                v.check_required_email_metadata(root, 'si', report)
                self.assertEqual(not valid, any(i['code'] == 'MESSAGE_INREPLYTO_XMP' and i['level'] == 'FAIL' for i in report.items))

    def test_reply_scalar_fails_and_optional_absence_passes(self):
        for field, value, fails in (('In-Reply-To', '<parent@example.org>', True), ('', '', False)):
            _, report = self.schema(field, value)
            self.assertEqual(fails, any(i['code'] == 'MESSAGE_INREPLYTO_XMP' and i['level'] == 'FAIL' for i in report.items))

    def test_identifier_aliases_must_agree_exactly(self):
        for second, fails in (('<a@example.org>', False), ('<b@example.org>', True), (' <a@example.org>', True)):
            root = metadata('Message-ID', '<a@example.org>')
            record = root.find('.//' + v.qn(v.NS_RDF, 'li'))
            ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, 'messageid')).text = second
            report = v.Report('aliases.pdf')
            v.check_required_email_metadata(root, 'si', report)
            self.assertEqual(fails, any(i['code'] == 'MESSAGE_ID_XMP' and i['level'] == 'FAIL' for i in report.items))

    def test_single_identifier_alias_remains_optional_text(self):
        for name in ('messageid', 'Message-ID'):
            _, report = self.schema(name, '<a@example.org>')
            self.assertFalse(any(i['level'] == 'FAIL' for i in report.items))

    def contents(self, value=None, subtype='FileAttachment'):
        with pikepdf.Pdf.new() as pdf:
            page = pdf.add_blank_page()
            fs = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec, F='attachment.txt'))
            annot = pikepdf.Dictionary(Type=pikepdf.Name.Annot, Subtype=pikepdf.Name('/' + subtype),
                                      Rect=[10, 10, 30, 30], FS=fs)
            if value is not None:
                annot.Contents = value
            page.obj.Annots = [pdf.make_indirect(annot)]
            report = v.Report('annotations.pdf')
            v.check_file_attachment_contents(pdf, report)
            return report

    def test_contents_required_on_every_attachment_without_xmp_or_native_body_matching(self):
        for value in (None, pikepdf.Name.Description, 3):
            with self.subTest(value=value):
                report = self.contents(value)
                self.assertEqual({'FAIL'}, {i['level'] for i in report.items if i['code'] == 'ANNOTATION_CONTENTS_REQUIRED'})

    def test_empty_and_unicode_contents_are_valid_text(self):
        for text in ('', 'Read attachment', '添付資料'):
            report = self.contents(pikepdf.String(text))
            self.assertEqual({'PASS'}, {i['level'] for i in report.items})

    def test_unreadable_contents_is_unavailable_and_does_not_abort(self):
        report = self.contents(pikepdf.String(b'\xef\xbb\xbf\xff'))
        self.assertEqual({'WARN'}, {i['level'] for i in report.items})
        self.assertIn('VALIDATION_INCOMPLETE', {i['code'] for i in report.items})

    def test_non_attachment_annotation_does_not_require_contents(self):
        report = self.contents(None, subtype='Link')
        self.assertEqual({'N/A'}, {i['level'] for i in report.items})

    def test_leaf_identifiers_compare_without_becoming_required(self):
        for key, code in (('/Mail_GUID', 'DPM_MAIL_GUID_MATCH'), ('/Mail_MessageID', 'DPM_MAIL_MESSAGEID_MATCH')):
            for state in ('absent', 'matching', 'different', 'empty', 'name', 'unreadable'):
                with self.subTest(key=key, state=state), pikepdf.open(SEED) as pdf:
                    _, _, nodes = v.get_dpart_nodes(pdf)
                    owner = next(n for n in nodes if n['dpm'].get('Mail_GUID') is not None)
                    leaf = next(n for n in nodes if n['start'] is not None)
                    if state != 'absent':
                        original = str(owner['dpm'][key[1:]])
                        value = pikepdf.String(original if state == 'matching' else '' if state == 'empty' else 'different')
                        if state == 'name': value = pikepdf.Name('/' + original)
                        if state == 'unreadable': value = pikepdf.String(b'\xef\xbb\xbf\xff')
                        leaf['node'].DPM[key] = value
                    report = v.Report(str(SEED))
                    _, outline_pages = v.check_outlines(pdf, report)
                    xroot, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), report)
                    v.check_dpart_and_content_sets(pdf, xroot, 's', report, outline_pages)
                    levels = {i['level'] for i in report.items if i['code'] == code and tuple((i.get('details') or {}).get('objgen', ())) == tuple(leaf['objgen'])}
                    if state in ('different', 'empty', 'name'): self.assertIn('FAIL', levels)
                    elif state == 'unreadable': self.assertIn('WARN', levels)
                    else: self.assertNotIn('FAIL', levels)
                    self.assertFalse(any(i['code'] == 'DPM_MAIL_GUID_PRESENT' and i['level'] == 'FAIL' for i in report.items))

    def test_all_eleven_questions_have_explicit_project_decisions(self):
        rules = load_review_rules()
        review = rules['VALIDATION_INCOMPLETE']['specification_review']
        self.assertEqual(11, len(review['expert_questions']))
        self.assertTrue(all(q['status'] == 'locally_resolved' and q['resolution']['decision'] for q in review['expert_questions']))
        edition = next(q for q in review['expert_questions'] if q['finding_id'] == 'DEP-PDFA-EDITION')
        self.assertIn('unverified', edition['resolution']['external_limit'])


if __name__ == '__main__':
    unittest.main()
