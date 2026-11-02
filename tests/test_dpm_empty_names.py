"""An empty PDF Name is present; field syntax remains an independent constraint."""
import unittest

import pikepdf
from test_preservation_safety import ROOT, validator as v

TYPE = 'DPM_CONTENTSETTYPE_PRESENT'
SUBTYPE = 'DPM_SUBTYPE_REQUIRED'
FORMAT = 'DPM_SUBTYPE_MEDIA_TYPE_NAME'


class DpmEmptyNameTests(unittest.TestCase):
    def document(self):
        return pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf')

    def leaf(self, pdf):
        return next(item['node'] for item in v.get_dpart_nodes(pdf)[2]
                    if not item['child_count'] and item['dpm'].get('Mail_ContentSetType') == pikepdf.Name.BodyRendering)

    def null_field(self, leaf, key):
        fields = pikepdf.Object.parse(('<< /' + key + ' null >>').encode('ascii'))
        for name, value in leaf.DPM.items():
            if name != '/' + key:
                fields[name] = value
        leaf.DPM = fields

    def levels(self, pdf, code):
        report = v.Report('dpm-name-focused.pdf')
        v.validate_pdf(pdf, report)
        return {item['level'] for item in report.items if item['code'] == code}

    def test_empty_extension_name_is_present(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_ContentSetType = pikepdf.Name('/')
            self.assertEqual({'PASS'}, self.levels(pdf, TYPE))

    def test_other_unlisted_names_remain_present(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_ContentSetType = pikepdf.Name('/Vendor value')
            self.assertEqual({'PASS'}, self.levels(pdf, TYPE))

    def test_missing_content_set_type_fails_presence(self):
        with self.document() as pdf:
            del self.leaf(pdf).DPM.Mail_ContentSetType
            self.assertIn('FAIL', self.levels(pdf, TYPE))

    def test_null_content_set_type_is_absent(self):
        with self.document() as pdf:
            self.null_field(self.leaf(pdf), 'Mail_ContentSetType')
            self.assertIn('FAIL', self.levels(pdf, TYPE))

    def test_empty_required_subtype_is_present(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_Subtype = pikepdf.Name('/')
            self.assertEqual({'PASS'}, self.levels(pdf, SUBTYPE))

    def test_empty_required_subtype_fails_media_type_syntax(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_Subtype = pikepdf.Name('/')
            self.assertEqual({'FAIL'}, self.levels(pdf, FORMAT))

    def test_empty_optional_subtype_still_fails_media_type_syntax(self):
        with self.document() as pdf:
            leaf = self.leaf(pdf)
            leaf.DPM.Mail_ContentSetType = pikepdf.Name.Other
            leaf.DPM.Mail_Subtype = pikepdf.Name('/')
            self.assertEqual({'FAIL'}, self.levels(pdf, FORMAT))

    def test_absent_required_subtype_still_fails_presence(self):
        with self.document() as pdf:
            del self.leaf(pdf).DPM.Mail_Subtype
            self.assertIn('FAIL', self.levels(pdf, SUBTYPE))

    def test_null_required_subtype_is_absent(self):
        with self.document() as pdf:
            self.null_field(self.leaf(pdf), 'Mail_Subtype')
            self.assertIn('FAIL', self.levels(pdf, SUBTYPE))

    def test_empty_extension_name_proves_no_declared_body_rendering(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_ContentSetType = pikepdf.Name('/')
            self.assertEqual({'N/A'}, self.levels(pdf, 'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE'))

    def test_missing_type_cannot_prove_no_declared_body_rendering(self):
        with self.document() as pdf:
            del self.leaf(pdf).DPM.Mail_ContentSetType
            self.assertEqual({'WARN'}, self.levels(pdf, 'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE'))

    def test_text_type_cannot_prove_no_declared_body_rendering(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_ContentSetType = pikepdf.String('')
            self.assertEqual({'WARN'}, self.levels(pdf, 'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE'))

    def test_non_name_values_retain_their_separate_type_failure(self):
        with self.document() as pdf:
            self.leaf(pdf).DPM.Mail_ContentSetType = pikepdf.String('')
            self.assertIn('FAIL', self.levels(pdf, 'DPM_NAME_TYPES'))


if __name__ == '__main__':
    unittest.main()
