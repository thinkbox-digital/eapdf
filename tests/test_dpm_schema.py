"""Table 7/8 field types, permitted extensions and decoded DPM name values."""
import json
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes, email_metadata


class DpmSchemaTests(unittest.TestCase):
    def document(self):
        pdf = pikepdf.Pdf.new()
        page = pdf.add_blank_page()
        root = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPartRoot))
        top = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=root))
        message = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=top,
            DPM=pikepdf.Dictionary(Mail_GUID='42', Mail_MessageID='17')))
        leaf = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=message, Start=page.obj,
            DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.BodyRendering,
                Mail_Subtype=pikepdf.Name('/text/plain'), Mail_Desc='Body')))
        root.DPartRootNode = top
        top.DParts = pikepdf.Array([pikepdf.Array([message])])
        message.DParts = pikepdf.Array([pikepdf.Array([leaf])])
        page.DPart = leaf
        pdf.Root.DPartRoot = root
        return pdf, message, leaf

    def check(self, pdf):
        report = v.Report('schema.pdf')
        v.check_dpart_and_content_sets(pdf, email_metadata([{'Mail_GUID': '42', 'messageid': '17'}]),
                                     's', report, set())
        return report

    def test_descriptions_are_text_strings(self):
        for value in (17, True, pikepdf.Name('/Body'), pikepdf.Array(['Body']), pikepdf.Dictionary(Value='Body')):
            pdf, _, leaf = self.document()
            with self.subTest(value=value), pdf:
                leaf.DPM.Mail_Desc = value
                self.assertIn('DPM_DESC_REQUIRED', failed_codes(self.check(pdf)))

    def test_guid_presence_requires_text_string(self):
        for value in (42, True, pikepdf.Name('/42'), pikepdf.Array(['42'])):
            pdf, message, _ = self.document()
            with self.subTest(value=value), pdf:
                message.DPM.Mail_GUID = value
                self.assertIn('DPM_MAIL_GUID_PRESENT', failed_codes(self.check(pdf)))

    def test_messageid_requires_text_string_even_if_coercion_matches(self):
        pdf, message, _ = self.document()
        with pdf:
            message.DPM.Mail_MessageID = 17
            self.assertIn('DPM_MAIL_MESSAGEID_MATCH', failed_codes(self.check(pdf)))

    def test_unrelated_extension_keys_are_permitted(self):
        pdf, _, leaf = self.document()
        with pdf:
            leaf.DPM.Acme_Job = 42
            leaf.DPM.Desc = 'An unrelated extension key does not replace Mail_Desc'
            self.assertNotIn('DPM_KEY_PREFIX', failed_codes(self.check(pdf)))
            del leaf.DPM.Mail_Desc
            self.assertIn('DPM_DESC_REQUIRED', failed_codes(self.check(pdf)))

    def test_invalid_utf8_content_set_name_is_rejected_and_reportable(self):
        pdf, _, leaf = self.document()
        with pdf:
            leaf.DPM.Mail_ContentSetType = pikepdf.Object.parse(b'/Vendor#FF')
            report = self.check(pdf)
            self.assertIn('DPM_NAME_TYPES', failed_codes(report))
            json.dumps(report.items, ensure_ascii=False).encode('utf-8')
            v.render_html(report).encode('utf-8')

    def test_valid_escaped_unicode_name_and_utf16_text_are_accepted(self):
        pdf, message, leaf = self.document()
        with pdf:
            leaf.DPM.Mail_ContentSetType = pikepdf.Object.parse(b'/Vendor#C3#A9')
            leaf.DPM.Mail_Desc = pikepdf.String(b'\xfe\xff' + 'résumé'.encode('utf-16be'))
            message.DPM.Mail_GUID = pikepdf.String(b'\xfe\xff\x004\x002')
            self.assertFalse(failed_codes(self.check(pdf)) &
                             {'DPM_NAME_TYPES', 'DPM_DESC_REQUIRED', 'DPM_MAIL_GUID_PRESENT', 'DPM_MAIL_GUID_MATCH'})

    def test_leaf_dpm_must_be_a_dictionary(self):
        for value in (17, pikepdf.Name('/Metadata'), pikepdf.Array(['Metadata'])):
            pdf, _, leaf = self.document()
            with self.subTest(value=value), pdf:
                leaf.DPM = value
                self.assertIn('DPM_LEAF_REQUIRED', failed_codes(self.check(pdf)))

    def test_no_body_set_distinguishes_known_nonbody_from_unknown_type(self):
        path = ROOT / 'tests/fixtures/eapdf/upstream-issues/valid.pdf'
        for kind, expected in ((pikepdf.Name.EmailHeaderRendering, 'N/A'),
                               (pikepdf.Object.parse(b'/Vendor#FF'), 'WARN')):
            with self.subTest(expected=expected), pikepdf.Pdf.open(path) as pdf:
                _, _, nodes = v.get_dpart_nodes(pdf)
                for node in nodes:
                    if v.safe_name(node['dpm'].get('Mail_ContentSetType')) == 'BodyRendering':
                        node['node'].DPM.Mail_ContentSetType = kind
                report = v.Report('body-applicability.pdf')
                root, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), report)
                v.check_additional_rule_coverage(pdf, root, v.read_metadata_stream(pdf),
                    v.get_embedded_files(pdf), v.get_af_specs(pdf), 's', 4, report)
                rows = [r for r in report.items if r['code'] == 'ATTACHMENT_ANNOTATIONS_PRESENT']
                self.assertEqual([expected], [r['level'] for r in rows])


if __name__ == '__main__':
    unittest.main()
