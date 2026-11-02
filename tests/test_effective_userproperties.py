"""ISO 32000-2 14.7.6: whole attributes, classes and revision metadata."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator


class EffectiveUserPropertiesTests(unittest.TestCase):
    def attr(self, values):
        return pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
            P=pikepdf.Array([pikepdf.Dictionary(N=n, V=v) for n, v in values]))

    def document(self, attribute=None, classes=None, mapping=None):
        pdf = pikepdf.Pdf.new()
        pdf.add_blank_page()
        tree = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        if mapping is not None:
            tree.ClassMap = mapping
        root = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name.Document, P=tree))
        message = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name('/Mail_Message'), P=root))
        if attribute is not None:
            message.A = attribute
        if classes is not None:
            message.C = classes
        root.K = message
        tree.K = root
        pdf.Root.StructTreeRoot = tree
        return pdf, message

    def report(self, pdf):
        report = validator.Report('effective.pdf')
        validator.check_logical_structure(pdf, 's', report)
        validator.check_additional_rule_coverage(pdf, None, b'', [], [], 's', 4, report)
        return report

    def levels(self, report, code):
        return {i['level'] for i in report.items if i['code'] == code}

    def test_last_userproperties_p_replaces_entire_earlier_list(self):
        first = self.attr([('Mail_GUID', 'uuid:old')])
        last = self.attr([('Mail_MessageID', '<current@example.invalid>')])
        props = validator.parse_userproperties(pikepdf.Array([first, 5, last, 0]))
        self.assertEqual(['Mail_MessageID'], [str(p.N) for p in props])
        pdf, message = self.document(pikepdf.Array([first, 5, last, 0]))
        with pdf:
            message.R = 5
            self.assertEqual({'FAIL'}, self.levels(self.report(pdf), 'MAIL_MESSAGE_GUID_ATTRIBUTE'))

    def test_class_only_dictionary_or_array_receives_type_checks_and_context(self):
        for array in (False, True):
            for value, expected in (('uuid:one', 'PASS'), (42, 'FAIL')):
                with self.subTest(array=array, value=value):
                    attr = self.attr([('Mail_GUID', value)])
                    mapping = pikepdf.Dictionary(Email=pikepdf.Array([attr]) if array else attr)
                    pdf, _ = self.document(classes=pikepdf.Name.Email, mapping=mapping)
                    with pdf:
                        report = self.report(pdf)
                        self.assertEqual({expected}, self.levels(report, 'USERPROPS_TYPE'))
                        ctx = validator.build_validation_context(pdf, None, 's', 4, [], [])
                        self.assertTrue(ctx['userprops_present'])

    def test_later_classes_then_direct_attributes_take_precedence(self):
        def mapping():
            return pikepdf.Dictionary(First=self.attr([('Mail_GUID', 42)]),
                                     Last=self.attr([('Mail_GUID', 'uuid:class')]))
        for direct in (None, self.attr([('Mail_GUID', 'uuid:direct')])):
            pdf, message = self.document(direct, pikepdf.Array([pikepdf.Name.First, 5, pikepdf.Name.Last, 0]), mapping())
            with pdf:
                message.R = 5
                self.assertEqual({'PASS'}, self.levels(self.report(pdf), 'USERPROPS_TYPE'))
                self.assertEqual({'PASS'}, self.levels(self.report(pdf), 'MAIL_MESSAGE_GUID_ATTRIBUTE'))
        pdf, _ = self.document(self.attr([('Mail_GUID', 42)]), pikepdf.Name.Last, mapping())
        with pdf:
            self.assertEqual({'FAIL'}, self.levels(self.report(pdf), 'USERPROPS_TYPE'))

    def test_array_order_wins_over_revision_numbers(self):
        for first_revision, last_revision in ((5, 0), (0, 5)):
            attrs = pikepdf.Array([self.attr([('Mail_GUID', 42)]), first_revision,
                                  self.attr([('Mail_GUID', 'uuid:active')]), last_revision])
            pdf, message = self.document(attrs)
            with pdf:
                message.R = 5
                self.assertEqual({'PASS'}, self.levels(self.report(pdf), 'USERPROPS_TYPE'))

    def test_missing_class_is_empty_and_properties_do_not_inherit(self):
        pdf, message = self.document(self.attr([('Mail_GUID', 'uuid:one')]), pikepdf.Name.Missing)
        with pdf:
            self.assertEqual({'PASS'}, self.levels(self.report(pdf), 'MAIL_MESSAGE_GUID_ATTRIBUTE'))
        pdf, message = self.document(classes=pikepdf.Name.Missing)
        with pdf:
            message.P.A = self.attr([('Mail_GUID', 'uuid:parent')])
            self.assertEqual({'FAIL'}, self.levels(self.report(pdf), 'MAIL_MESSAGE_GUID_ATTRIBUTE'))

    def test_malformed_attribute_resolution_cannot_report_complete(self):
        cases = [dict(classes=pikepdf.String('Email')),
                 dict(classes=pikepdf.Name.Email, mapping=pikepdf.String('bad')),
                 dict(attribute=pikepdf.Array([0, self.attr([('Mail_GUID', 'uuid:one')])])),
                 dict(attribute=pikepdf.Dictionary(O=pikepdf.Name.UserProperties, P=42)),
                 dict(classes=pikepdf.Name.Email, mapping=pikepdf.Dictionary(Email=42))]
        for case in cases:
            with self.subTest(case=str(case)):
                pdf, _ = self.document(**case)
                with pdf:
                    report = self.report(pdf)
                    self.assertIn('WARN', self.levels(report, 'VALIDATION_INCOMPLETE'))

    def test_userproperties_stream_is_not_a_userproperties_dictionary(self):
        pdf, message = self.document()
        with pdf:
            stream = pdf.make_stream(b'')
            stream.O = pikepdf.Name.UserProperties
            stream.P = pikepdf.Array([pikepdf.Dictionary(N='Mail_GUID', V='uuid:one')])
            message.A = stream
            self.assertIn('WARN', self.levels(self.report(pdf), 'VALIDATION_INCOMPLETE'))

    @unittest.skipUnless(os.environ.get("EAPDF_GENERATOR_CLASSPATH"),
                         "Set EAPDF_GENERATOR_CLASSPATH for generated attribute fixtures")
    def test_creator_generated_effective_attribute_corpus(self):
        with tempfile.TemporaryDirectory(prefix=".eapdf-effective-fixtures-", dir=ROOT) as directory:
            process = subprocess.run(["python3", "-B", "scripts/build_eapdf_rule_fixtures.py"], cwd=ROOT,
                env=dict(os.environ, EAPDF_RULE_FIXTURE_SET="batch05", EAPDF_RULE_FIXTURE_DIR=directory),
                capture_output=True, text=True, timeout=240)
            if os.environ.get("EAPDF_INTEGRATION_REPORT_DIR"):
                shutil.copytree(directory, Path(os.environ["EAPDF_INTEGRATION_REPORT_DIR"]) / "attribute-corpus",
                                dirs_exist_ok=True)
            self.assertEqual(0, process.returncode, process.stdout + process.stderr)
            results = json.loads((Path(directory) / "results.json").read_text())
            cases = json.loads((ROOT / "tests/fixtures/eapdf/batch05/cases.json").read_text())
            self.assertEqual({case["id"] for case in cases}, {case["case"] for case in results})
            self.assertTrue(all(case["expected_result_observed"] for case in results))
