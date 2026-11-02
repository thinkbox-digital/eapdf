"""Table13 recommends Mail_Subtype when Mail_ContentSet occurs."""
import unittest
import tempfile
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v

CODE = 'MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'


class ContentSetSubtypeRecommendationTests(unittest.TestCase):
    def document(self):
        return pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-contentset-parent/nested-subtype.pdf')

    def sets(self, pdf):
        elements = []
        v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
        return [e['elem'] for e in elements if e['type'] == 'Mail_ContentSet']

    def body(self, pdf):
        return next(e for e in self.sets(pdf) if any(str(p.N) == 'Mail_ContentSetType'
                    and str(p.V) == 'BodyRendering' for p in e.A.P))

    def check(self, pdf):
        with tempfile.TemporaryDirectory(prefix='.eapdf-subtype-rec-', dir=ROOT) as directory:
            path = Path(directory) / 'focused.pdf'
            pdf.save(path, static_id=True, fix_metadata_version=False)
            report = v.validate(str(path))
        return next(i for i in report.items if i['code'] == CODE), report

    def omit(self, element, *names):
        element.A.P = pikepdf.Array([p for p in element.A.P if str(p.N) not in names])

    def test_all_content_sets_with_subtype_properties_satisfy_recommendation(self):
        with self.document() as pdf:
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_missing_subtype_warns_without_requiring_optional_self_type(self):
        with self.document() as pdf:
            self.omit(self.body(pdf), 'Mail_Subtype', 'Mail_ContentSetType')
            finding, report = self.check(pdf)
            self.assertEqual('WARN', finding['level'])
            self.assertEqual('PASS', report.summary()['status'])

    def test_frontmatter_subtype_is_recommended_too(self):
        with self.document() as pdf:
            front = next(e for e in self.sets(pdf) if any(str(p.N) == 'Mail_ContentSetType'
                         and str(p.V) == 'FrontMatter' for p in e.A.P))
            self.omit(front, 'Mail_Subtype')
            finding, report = self.check(pdf)
            self.assertEqual('WARN', finding['level'])
            self.assertEqual('PASS', report.summary()['status'])

    def test_empty_supplied_value_remains_present_with_separate_matching_failure(self):
        with self.document() as pdf:
            next(p for p in self.body(pdf).A.P if str(p.N) == 'Mail_Subtype').V = ''
            finding, report = self.check(pdf)
            self.assertEqual('PASS', finding['level'])
            self.assertTrue(any(i['code'] == 'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'
                                and i['level'] == 'FAIL' for i in report.items))

    def test_repeated_properties_remain_present(self):
        with self.document() as pdf:
            body = self.body(pdf)
            prop = next(p for p in body.A.P if str(p.N) == 'Mail_Subtype')
            body.A.P.append(pikepdf.Dictionary(N='Mail_Subtype', V=prop.V))
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_wrong_value_type_does_not_erase_property_presence(self):
        with self.document() as pdf:
            next(p for p in self.body(pdf).A.P if str(p.N) == 'Mail_Subtype').V = pikepdf.Name('/text/plain')
            finding, report = self.check(pdf)
            self.assertEqual('PASS', finding['level'])
            self.assertTrue(any(i['code'] == 'USERPROPS_TYPE' and i['level'] == 'FAIL' for i in report.items))

    def test_unknown_attributes_do_not_support_recommendation_pass(self):
        with self.document() as pdf:
            self.body(pdf).A.O = pikepdf.String('UserProperties')
            finding, report = self.check(pdf)
            self.assertEqual('WARN', finding['level'])
            self.assertTrue(finding['details']['unavailable'])
            self.assertEqual('INCOMPLETE', report.summary()['status'])

    def test_later_active_array_can_omit_an_earlier_subtype(self):
        with self.document() as pdf:
            body = self.body(pdf)
            body.A = pikepdf.Array([body.A, pikepdf.Dictionary(O=pikepdf.Name.UserProperties, P=pikepdf.Array())])
            self.assertEqual('WARN', self.check(pdf)[0]['level'])

    def test_known_text_name_presence_is_separate_from_raw_ascii_encoding(self):
        with self.document() as pdf:
            prop = next(p for p in self.body(pdf).A.P if str(p.N) == 'Mail_Subtype')
            prop.N = pikepdf.String(b'\xfe\xff' + 'Mail_Subtype'.encode('utf-16be'))
            finding, report = self.check(pdf)
            self.assertEqual('PASS', finding['level'])
            self.assertTrue(any(i['code'] == 'USERPROPS_NAME_ASCII' and i['level'] == 'FAIL' for i in report.items))

    def test_no_optional_custom_content_sets_keeps_not_applicable(self):
        with pikepdf.Pdf.new() as pdf:
            report = v.Report('standard-structure-focused.pdf')
            v.check_additional_rule_coverage(pdf, None, b'', [], [], 's', 4, report)
            self.assertEqual(['N/A'], [i['level'] for i in report.items if i['code'] == CODE])


if __name__ == '__main__':
    unittest.main()
