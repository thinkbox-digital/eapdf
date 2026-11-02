"""Document-wide ownership is proved from content and DPart ancestry, not a label."""
import tempfile
import unittest
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v

CODE = 'MAIL_CONTENTSET_NESTED_UNDER_MESSAGE'
HERE = ROOT / 'tests/fixtures/eapdf/validator-contentset-parent'


class ContentSetParentScopeTests(unittest.TestCase):
    def check(self, name, level, mutate=None):
        path = HERE / (name + '.pdf')
        if mutate is None:
            report = v.validate(str(path))
        else:
            with pikepdf.open(path) as pdf:
                document = pdf.Root.StructTreeRoot.K
                target = next(e for e in document.K if e.get('/S') == pikepdf.Name('/Mail_ContentSet'))
                prop = next(p for p in target.A.P if str(p.N) == 'Mail_ContentSetType')
                mutate(target, prop)
                with tempfile.TemporaryDirectory(prefix='eapdf-parent-scope-') as directory:
                    changed = Path(directory) / 'focused.pdf'
                    pdf.save(changed, static_id=True, fix_metadata_version=False)
                    report = v.validate(str(changed))
        failures = {i['code'] for i in report.items if i['level'] == 'FAIL'}
        self.assertNotIn('VALIDATION_ERROR', failures)
        self.assertEqual({level}, {i['level'] for i in report.items if i['code'] == CODE})
        return report

    def test_nested_multi_baseline_conforms(self):
        self.assertEqual('PASS', self.check('nested-multi', 'PASS').summary()['status'])

    def test_nested_subtype_baseline_conforms(self):
        self.assertEqual('PASS', self.check('nested-subtype', 'PASS').summary()['status'])

    def test_nested_single_baseline_conforms(self):
        self.assertEqual('PASS', self.check('nested-single', 'PASS').summary()['status'])

    def test_proven_document_frontmatter_is_exempt_in_noncollections(self):
        report = self.check('orphan-front-noncollection', 'PASS')
        self.assertEqual('PASS', report.summary()['status'])

    def test_empty_self_value_does_not_select_nesting_policy(self):
        self.check('orphan-empty-self', 'PASS')

    def test_unreadable_self_value_does_not_abort_ancestry(self):
        self.check('orphan-unreadable-self', 'PASS')

    def test_collection_frontmatter_has_a_document_level_parent_exception(self):
        report = self.check('orphan-front-collection', 'N/A')
        self.assertFalse(any(i['code'] == 'VALIDATION_INCOMPLETE' and CODE in (i.get('details') or {}).get('rules', []) for i in report.items))
        # Custom-only validation still lacks independent contained-PDF validation.
        self.assertEqual('INCOMPLETE', report.summary()['status'])

    def test_other_collection_content_uses_the_same_profile_exception(self):
        self.assertEqual('INCOMPLETE', self.check('orphan-other-collection', 'N/A').summary()['status'])

    def test_missing_optional_self_type_does_not_change_required_parent(self):
        self.check('orphan-front-noncollection', 'PASS', lambda target, prop: target.A.P.remove(prop))

    def test_padded_frontmatter_value_does_not_exempt_parent(self):
        self.check('orphan-front-noncollection', 'PASS', lambda target, prop: setattr(prop, 'V', ' FrontMatter '))

    def test_duplicate_self_values_cannot_select_parent_policy(self):
        self.check('orphan-front-noncollection', 'PASS', lambda target, prop:
            target.A.P.insert(0, pikepdf.Dictionary(N='Mail_ContentSetType', V='Other')))

    def test_wrong_self_type_retains_its_separate_failure(self):
        report = self.check('orphan-front-noncollection', 'PASS', lambda target, prop:
            setattr(prop, 'V', pikepdf.Name.FrontMatter))
        self.assertEqual({'USERPROPS_TYPE'}, {i['code'] for i in report.items if i['level'] == 'FAIL'})

    def test_a_frontmatter_tag_cannot_exempt_email_rendering_content(self):
        self.check('orphan-front-noncollection', 'FAIL', lambda target, prop:
            setattr(target.Pg.DPart.DPM, 'Mail_ContentSetType', pikepdf.Name.BodyRendering))

    def test_a_frontmatter_part_with_an_email_identity_is_not_global(self):
        self.check('orphan-front-noncollection', 'FAIL', lambda target, prop:
            setattr(target.Pg.DPart.DPM, 'Mail_GUID', 'email-owner'))

    def test_unconnected_content_is_unavailable_not_a_proved_exception(self):
        self.check('orphan-front-noncollection', 'WARN', lambda target, prop:
            setattr(target, 'K', 999999))

    def test_inconsistent_dpart_parent_cannot_prove_global_ownership(self):
        self.check('orphan-front-noncollection', 'FAIL', lambda target, prop:
            setattr(target.Pg.DPart, 'Parent', target.Pg.DPart.Parent.Parent))


if __name__ == '__main__':
    unittest.main()
