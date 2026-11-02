"""Upstream execution integrity and applicability; no local ISO predicates."""
from copy import deepcopy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, validator as v


def upstream(compliant=True):
    pin = v.ISO32005_PROFILE
    versions = [{'id': k, 'version': value} for k, value in pin['reported_versions'].items()]
    return dict(status='PASS' if compliant else 'FAIL', ran=True, compliant=compliant,
                profile_name=pin['profile_name'], profile_allowed=True, job_end_status='normal',
                passed_rules=1636 if compliant else 1635, failed_rules=0 if compliant else 1,
                exit_code=0, stderr_severity='', raw_json={'report': {
                    'buildInformation': {'releaseDetails': versions},
                    'jobs': [{'validationResult': [{}]}]}})


class Iso32005ExecutionTests(unittest.TestCase):
    def test_pinned_execution_accepts_positive_and_negative_results(self):
        for compliant, expected in ((True, 'PASS'), (False, 'FAIL')):
            with self.subTest(compliant=compliant), patch.object(v, 'run_verapdf', return_value=upstream(compliant)) as run:
                result = v.run_iso32005('probe.pdf')
                self.assertEqual(expected, result['status'])
                self.assertTrue(result['profile_verified'])
                self.assertEqual(ROOT / 'config/verapdf/ISO-32005.xml', run.call_args.kwargs['profile_path'])

    def test_missing_or_changed_profile_cannot_execute(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(v, 'ROOT', Path(directory)), \
                patch.object(v, 'run_verapdf') as run:
            self.assertEqual('ERROR', v.run_iso32005('probe.pdf')['status'])
            profile = Path(directory) / 'config/verapdf/ISO-32005.xml'
            profile.parent.mkdir(parents=True)
            profile.write_text('changed')
            self.assertEqual('ERROR', v.run_iso32005('probe.pdf')['status'])
            run.assert_not_called()

    def test_incomplete_wrong_version_profile_or_inconsistent_results_are_unverified(self):
        probes = []
        for key, value in (('status', 'ERROR'), ('ran', False), ('profile_allowed', False), ('profile_name', 'PDF/UA-2'),
                           ('job_end_status', 'aborted'), ('passed_rules', 1635),
                           ('failed_rules', 1), ('exit_code', 137), ('stderr_severity', 'ERROR')):
            item = upstream(); item[key] = value; probes.append((key, item))
        item = upstream(); item['raw_json']['report']['buildInformation']['releaseDetails'][0]['version'] = 'other'
        probes.append(('version', item))
        item = upstream(); item['raw_json']['report']['jobs'].append(deepcopy(item['raw_json']['report']['jobs'][0]))
        probes.append(('multiple jobs', item))
        for label, item in probes:
            with self.subTest(label=label), patch.object(v, 'run_verapdf', return_value=item):
                result = v.run_iso32005('probe.pdf')
                self.assertEqual('ERROR', result['status'])
                self.assertFalse(result['profile_verified'])

    def test_required_unavailable_execution_prevents_pass_and_actual_failure_fails(self):
        for status, verified, expected in (('PASS', True, 'PASS'), ('FAIL', True, 'FAIL'),
                                          ('ERROR', False, 'INCOMPLETE'), ('SKIP', False, 'INCOMPLETE'),
                                          ('PASS', False, 'INCOMPLETE')):
            with self.subTest(status=status, verified=verified), pikepdf.Pdf.new() as pdf:
                pdf.Root.Version = pikepdf.Name('/2.0')
                pdf.Root.StructTreeRoot = pikepdf.Dictionary()
                report = v.Report('probe.pdf')
                with patch.object(v, 'run_iso32005', return_value=dict(status=status, profile_verified=verified)):
                    v.check_iso32005_upstream(pdf, report, upstream())
                self.assertEqual(expected, report.summary()['status'])

    def test_custom_only_failed_prerequisite_and_inapplicable_calls_do_not_execute(self):
        with pikepdf.Pdf.new() as pdf, patch.object(v, 'run_iso32005') as run:
            for has_structure, pdfa in ((False, upstream()), (True, None), (True, {'status': 'FAIL'})):
                pdf.Root.Version = pikepdf.Name('/2.0')
                if has_structure:
                    pdf.Root.StructTreeRoot = pikepdf.Dictionary()
                report = v.Report('probe.pdf')
                v.check_iso32005_upstream(pdf, report, pdfa)
                self.assertEqual('N/A', report.items[0]['level'])
            run.assert_not_called()

    def test_profile_is_available_to_daemon_and_isolated_job(self):
        pdf = ROOT / 'examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'
        profile = ROOT / 'config/verapdf/ISO-32005.xml'
        with patch.object(v.shutil, 'which', return_value='/usr/bin/docker'), \
                patch.object(v, 'ensure_verapdf_daemon', return_value=(True, 'running')), \
                patch.dict(os.environ, {'VERAPDF_JOB_CONTAINER': ''}):
            cmd = v._verapdf_command(pdf, 'json', profile)
            self.assertEqual('exec', cmd[1])
            self.assertEqual(v._docker_work_path(profile), cmd[cmd.index('--profile') + 1])
        with patch.object(v.shutil, 'which', return_value='/usr/bin/docker'), \
                patch.dict(os.environ, {'VERAPDF_JOB_CONTAINER': 'eapdf-web-' + 'a' * 32}):
            cmd = v._verapdf_command(pdf, 'json', profile)
            self.assertEqual('run', cmd[1])
            self.assertIn(str(profile.parent) + ':/eapdf-profile:ro', cmd)
            self.assertEqual('/eapdf-profile/ISO-32005.xml', cmd[cmd.index('--profile') + 1])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for independent PDF/A and ISO corpus execution')
    def test_frozen_declaration_and_inclusion_corpus(self):
        import hashlib
        import json
        root = ROOT / 'tests/fixtures/eapdf/validator-declarations'
        for case in json.loads((root / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = root / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(sorted(case['expected_failures']), sorted({row['code'] for row in result['items'] if row['level'] == 'FAIL'}))
                iso = next(row for row in result['items'] if row['code'] == 'ISO32005_UPSTREAM')
                self.assertTrue(iso['details']['profile_verified'])
                self.assertEqual('FAIL' if case['expected_failures'] else 'PASS', result['summary']['status'])


if __name__ == '__main__':
    unittest.main()
