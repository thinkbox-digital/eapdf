"""Recommendation evidence is distinct from applicability and execution errors."""
from scripts.eapdf_rule_review import load_review_rules

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, validator, web


SOURCE_RECOMMENDATIONS = {
    'UNSPECIFIED_COLOR_SRGB_ASSUMED', 'REFERENCED_ASSETS_AS_ASSOCIATED_FILES',
    'REFERENCED_ASSETS_ASSOCIATED_TO_APPROPRIATE_OBJECT', 'LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED',
}
PAGE_RECOMMENDATIONS = {
    'PAGE_CONTEXT_PRESENT_RECOMMENDED', 'PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED',
    'ACTIONABLE_URLS_LINK_ANNOTATIONS',
}


class RecommendationApplicabilityTests(unittest.TestCase):
    def page(self, number, candidates=()):
        return dict(page=number, text='\n'.join(candidates), lines=list(candidates),
                    candidate_lines=list(candidates), urls=[], links=[])

    def check(self, pdf, analysis=None, error=None):
        report = validator.Report('recommendations.pdf')
        with patch.object(validator, 'collect_page_text_analysis', return_value=analysis, side_effect=error):
            validator.check_additional_rule_coverage(pdf, None, b'', [], [], 'c', 4, report)
        return report

    def rows(self, report, codes):
        return {row['code']: row for row in report.items if row['code'] in codes}

    def test_missing_source_policy_evidence_is_not_nonapplicability(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            rows = self.rows(self.check(pdf, [self.page(1, ['Page 1'])]), SOURCE_RECOMMENDATIONS)
            self.assertEqual(SOURCE_RECOMMENDATIONS, set(rows))
            for code, row in rows.items():
                with self.subTest(code=code):
                    self.assertEqual('WARN', row['level'])
                    self.assertEqual('unavailable', row['details']['assessment'])
                    self.assertTrue(row['details']['needed_evidence'])
                    self.assertEqual('SHOULD', row['details']['strength'])
            bounded = validator.Report('recommendations-only.pdf')
            for row in rows.values():
                bounded.add(row['level'], row['code'], row['message'], row['details'])
            self.assertEqual('PASS', bounded.summary()['status'])

    def test_extraction_exception_cannot_make_page_rules_na(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            report = self.check(pdf, error=RuntimeError('extractor probe failed'))
            rows = self.rows(report, PAGE_RECOMMENDATIONS)
            for code, row in rows.items():
                with self.subTest(code=code):
                    self.assertEqual('WARN', row['level'])
                    self.assertEqual('unverified', row['details']['state'])
                    self.assertIn('extractor probe failed', row['details']['reason'])
            self.assertTrue(any(row['code']=='VALIDATION_INCOMPLETE' and row['level']=='WARN'
                                for row in report.items))

    def test_incomplete_or_repeated_page_analysis_cannot_prove_absence(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.add_blank_page()
            for analysis in ([], [self.page(1)], [self.page(1), self.page(1)],
                             [self.page(2), self.page(3)]):
                with self.subTest(pages=[page['page'] for page in analysis]):
                    report = self.check(pdf, analysis)
                    rows = self.rows(report, PAGE_RECOMMENDATIONS)
                    self.assertEqual({'WARN'}, {row['level'] for row in rows.values()})
                    self.assertTrue(all(row['details']['state']=='unverified' for row in rows.values()))
                    self.assertTrue(any(row['code']=='VALIDATION_INCOMPLETE' and row['level']=='WARN'
                                        for row in report.items))

    def test_true_zero_pages_are_distinct_from_missing_extraction_results(self):
        with pikepdf.Pdf.new() as pdf:
            rows = self.rows(self.check(pdf, []), PAGE_RECOMMENDATIONS)
            self.assertEqual({'N/A'}, {row['level'] for row in rows.values()})

    def test_unidentified_context_is_unavailable_instead_of_na(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            report = self.check(pdf, [self.page(1)])
            row = self.rows(report, {'PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED'})['PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED']
            self.assertEqual('WARN', row['level'])
            self.assertEqual('unavailable', row['details']['assessment'])

    def test_partial_context_does_not_pass_all_page_text_recommendation(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.add_blank_page()
            report = self.check(pdf, [self.page(1, ['Page 1']), self.page(2)])
            row = self.rows(report, {'PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED'})['PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED']
            self.assertEqual('WARN', row['level'])
            self.assertEqual([2], row['details']['pages_unassessed'])

    def test_unavailable_logical_equivalence_cannot_be_skipped_as_absent_structure(self):
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            ctx = validator.build_validation_context(pdf, None, 'si', 4, [], [])
            self.assertIsNone(validator.na_reason_for_code('LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED', ctx))

    def test_web_warning_text_explains_unavailable_assessment(self):
        for code in SOURCE_RECOMMENDATIONS | PAGE_RECOMMENDATIONS:
            with self.subTest(code=code):
                rule_text = load_review_rules()[code]
                row = dict(code=code, level='WARN', message='Assessment unavailable: source policy required.',
                           details=dict(assessment='unavailable',reason='Source policy required.'))
                self.assertIn('Assessment unavailable', web.render_plain_language_from_template(rule_text, row))

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for generated recommendation fixtures')
    def test_creator_generated_recommendation_assessments(self):
        with tempfile.TemporaryDirectory(prefix='.eapdf-recommendation-fixtures-',dir=ROOT) as directory:
            process = subprocess.run(['python3','-B','scripts/build_eapdf_recommendation_fixtures.py'],cwd=ROOT,
                env=dict(os.environ,EAPDF_RECOMMENDATION_FIXTURE_DIR=directory),
                capture_output=True,text=True,timeout=180)
            if os.environ.get('EAPDF_INTEGRATION_REPORT_DIR'):
                shutil.copytree(directory,Path(os.environ['EAPDF_INTEGRATION_REPORT_DIR'])/'recommendation-corpus',
                                dirs_exist_ok=True)
            self.assertEqual(0,process.returncode,process.stdout+process.stderr)
            results=json.loads((Path(directory)/'results.json').read_text())
            self.assertEqual(6,len(results))
            self.assertTrue(all(case['expected_result_observed'] for case in results))
