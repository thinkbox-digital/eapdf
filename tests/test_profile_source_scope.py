from test_preservation_safety import failed_source_fields
"""Viewer-mode attachment conditions follow the represented original email."""
import hashlib
import json
from pathlib import Path
import unittest

from test_preservation_safety import validator as v, failed_codes
from rule_refinement_fixtures import corrected_rendered_count


class ProfileSourceScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        folder=Path(__file__).resolve().parent/'fixtures/eapdf/validator-profile-source-scope'
        cls.folder=folder
        cls.reports={}
        for case in json.loads((folder/'expectations.json').read_text())['cases']:
            path=folder/case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
            cls.reports[path.stem]=v.validate(str(path))

    def levels(self,name,code):
        return {i['level'] for i in self.reports[name].items if i['code']==code}

    def test_unrelated_original_does_not_change_the_no_attachment_condition(self):
        self.assertEqual('PASS',self.reports['plain-positive'].summary()['status'])
        for name in ('extra-attached','extra-attached-reversed','extra-attached-s'):
            with self.subTest(name=name):
                # The old fixture claims one rendered message for an unrendered
                # extra source. Its viewer-mode decisions are still correct.
                self.assertEqual({'ASSET_NUMBERMESSAGES_MATCH_RENDERED'},failed_codes(self.reports[name]))
                for code in ('PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS','PROFILE_NFSM_SINGLE_NO_ATTACHMENTS'):
                    self.assertEqual({'PASS'},self.levels(name,code))

    def test_correct_extra_source_count_restores_the_positive(self):
        for name in ('extra-attached','extra-attached-reversed','extra-attached-s'):
            with self.subTest(name=name), corrected_rendered_count(
                    self.folder/(name+'.pdf'),'unrendered.eml',1,0) as path:
                self.assertEqual('PASS',v.validate(str(path)).summary()['status'])

    def test_reversing_originals_preserves_profile_branch(self):
        for code in ('PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS','PROFILE_NFSM_SINGLE_NO_ATTACHMENTS'):
            self.assertEqual({'PASS'},self.levels('extra-attached',code))
            self.assertEqual({'PASS'},self.levels('extra-attached-reversed',code))

    def test_actual_matched_original_remains_identified(self):
        for name in ('extra-attached','extra-attached-reversed','extra-attached-s'):
            owners=[i['details']['source'] for i in self.reports[name].items
                    if i['code']=='MESSAGE_SOURCE_COVERAGE' and i['level']=='PASS']
            self.assertEqual(['represented.eml'],owners)

    def test_unrelated_original_does_not_make_attachment_branch_applicable(self):
        for code in ('PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS','PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS'):
            self.assertEqual({'N/A'},self.levels('extra-attached',code))

    def test_known_incorrect_page_mode_still_fails(self):
        for name in ('wrong-page-mode','extra-wrong-page-mode'):
            expected={'PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS'}
            if name.startswith('extra-'): expected.add('ASSET_NUMBERMESSAGES_MATCH_RENDERED')
            self.assertEqual(expected,failed_codes(self.reports[name]))

    def test_known_incorrect_nfsm_still_fails(self):
        for name in ('wrong-nfsm','extra-wrong-nfsm'):
            expected={'PROFILE_NFSM_SINGLE_NO_ATTACHMENTS'}
            if name.startswith('extra-'): expected.add('ASSET_NUMBERMESSAGES_MATCH_RENDERED')
            self.assertEqual(expected,failed_codes(self.reports[name]))

    def test_disagreeing_source_counts_do_not_invent_the_viewer_branch(self):
        for name in ('ambiguous-mixed','ambiguous-mixed-attachments-mode'):
            self.assertEqual('INCOMPLETE',self.reports[name].summary()['status'])
            self.assertFalse(failed_codes(self.reports[name]))

    def test_unavailable_attachment_condition_is_reported_in_both_branches(self):
        for name in ('ambiguous-mixed','ambiguous-mixed-attachments-mode','opaque-original'):
            for code in ('PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS','PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS',
                         'PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS','PROFILE_NFSM_SINGLE_NO_ATTACHMENTS'):
                self.assertEqual({'WARN'},self.levels(name,code),(name,code))

    def test_native_core_failure_does_not_certify_the_attachment_viewer_branch(self):
        self.assertEqual('FAIL',self.reports['opaque-original'].summary()['status'])
        self.assertEqual({'Subject','SizeInBytes','Sent','From','To','Raw-To','Raw-From'},failed_source_fields(self.reports['opaque-original']))
        self.assertNotIn('PASS',self.levels('opaque-original','PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS'))

    def test_modes_forbidden_under_every_count_still_fail_without_an_owner(self):
        for name,part in [('ambiguous-invalid-page-mode','PAGEMODE'),('ambiguous-invalid-nfsm','NFSM')]:
            report=self.reports[name]
            self.assertEqual({'PROFILE_'+part+'_SINGLE_NO_ATTACHMENTS',
                              'PROFILE_'+part+'_SINGLE_WITH_ATTACHMENTS'},failed_codes(report))
            for item in report.items:
                if item['level']=='FAIL':
                    self.assertEqual('unassigned',item['details']['source_ownership'])
                    self.assertEqual('all_branches_contradicted',item['details']['assessment'])

    def test_matched_attachment_positive_retains_existing_viewer_mode(self):
        path=Path(__file__).resolve().parent/'fixtures/eapdf/validator-source-mailbox-data/from-exact.pdf'
        report=v.validate(str(path))
        self.assertEqual('PASS',report.summary()['status'])
        for code in ('PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS','PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS'):
            self.assertTrue(any(i['code']==code and i['level']=='PASS' for i in report.items))
