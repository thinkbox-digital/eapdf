"""Exercise reviewed raw-header, RF, URL and extension branches on real PDFs."""
import hashlib
import json
import unittest

from test_preservation_safety import ROOT, validator, failed_source_fields


class CoverageBranchFixtureTests(unittest.TestCase):
    def test_reviewed_branch_controls(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-coverage-branches'
        cases = json.loads((directory / 'expectations.json').read_text())['cases']
        self.assertEqual(12, len(cases))
        for case in cases:
            with self.subTest(case=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                report = validator.validate(str(path))
                self.assertEqual(case['expected_status'], report.summary()['status'])
                failures = sorted({item['code'] for item in report.items if item['level'] == 'FAIL'})
                self.assertEqual(case['expected_failures'], failures)
                self.assertEqual(set(case.get('expected_source_field_failures', [])), failed_source_fields(report))
                for code, expected in case['expected_levels'].items():
                    levels = {item['level'] for item in report.items if item['code'] == code}
                    self.assertIn(expected, levels, (code, levels))
                    if expected != 'FAIL':
                        self.assertNotIn('FAIL', levels, (code, levels))
