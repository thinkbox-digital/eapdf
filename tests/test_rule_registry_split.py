"""Keep runtime rules small while preserving the complete review record."""
import hashlib
import json
from pathlib import Path
import unittest

from scripts.eapdf_rule_review import REVIEW_PATH, load_review_rules, merge_review

ROOT = Path(__file__).resolve().parents[1]


class RuleRegistrySplitTests(unittest.TestCase):
    def test_runtime_contains_only_used_fields(self):
        rules = json.loads((ROOT / 'validator_rule_text.json').read_text())
        self.assertEqual(229, len(rules))
        self.assertEqual(list(range(1, len(rules) + 1)), [rule['rule_number'] for rule in rules.values()])
        for code, rule in rules.items():
            with self.subTest(code=code):
                self.assertEqual({'rule_number', 'spec_section', 'spec_excerpt',
                                  'spec_excerpt_kind', 'requirements_review', 'validation_scope'}, set(rule))
                self.assertEqual({'requirement_ids', 'assertions'}, set(rule['requirements_review']))
                self.assertEqual(rule['requirements_review']['requirement_ids'],
                                 [row['id'] for row in rule['requirements_review']['assertions']])
                self.assertLessEqual(set(rule['validation_scope']),
                                     {'decision', 'version', 'profile_rule_references', 'external_profile', 'prerequisite', 'check', 'source_order'})
                self.assertNotEqual('delegate_to_verapdf', rule['validation_scope']['decision'])
        self.assertNotIn('ISO32005_UPSTREAM', rules)

    def test_split_checks_separate_strength_and_evidence_from_source_context(self):
        rules = json.loads((ROOT / 'validator_rule_text.json').read_text())
        contract = json.loads((ROOT / 'docs/improvement/rule-refinement-2026-10-31/top-level-rule-contract.json').read_text())
        for row in contract['new_rules']:
            code = row['code']
            self.assertEqual({'strength', 'applies_when', 'assertion', 'evidence_needed'},
                             set(rules[code]['validation_scope']['check']), code)
        for code in ('EMBEDDED_SIZE_RECOMMENDED', 'EMBEDDED_MD5_RECOMMENDED',
                     'ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED',
                     'ATTACHMENT_XMP_INVENTORY_RECOMMENDED'):
            self.assertEqual('SHOULD', rules[code]['validation_scope']['check']['strength'])
        self.assertEqual('required_if_present',
                         rules['EMBEDDED_SIZE_MATCH']['validation_scope']['check']['strength'])
        self.assertEqual(rules['EMBEDDED_SIZE_MATCH']['requirements_review']['assertions'],
                         rules['EMBEDDED_SIZE_RECOMMENDED']['requirements_review']['assertions'])
        self.assertNotEqual(rules['EMBEDDED_SIZE_MATCH']['validation_scope']['check'],
                            rules['EMBEDDED_SIZE_RECOMMENDED']['validation_scope']['check'])

    def test_all_rules_follow_source_pages_with_pageless_diagnostics_last(self):
        rules = json.loads((ROOT / 'validator_rule_text.json').read_text())
        pages = [rule['validation_scope']['source_order']['pdf_page'] for rule in rules.values()]
        self.assertEqual(sorted(pages, key=lambda page: page if page is not None else 999), pages)
        for rule in rules.values():
            anchor = rule['validation_scope']['source_order']
            if anchor['pdf_page'] is not None:
                self.assertEqual(anchor['printed_page'] + 7, anchor['pdf_page'])
                self.assertIn(anchor['requirement_id'], rule['requirements_review']['requirement_ids'])
            else:
                self.assertEqual('operational_only', rule['validation_scope']['decision'])

    def test_review_split_preserves_the_original_document(self):
        review = json.loads(REVIEW_PATH.read_text())
        restored = load_review_rules()
        digest = hashlib.sha256(json.dumps(restored, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        self.assertEqual(review.get('review_document_semantic_sha256', review['source_semantic_sha256']), digest)

    def test_review_cannot_silently_override_runtime_data(self):
        with self.assertRaises(ValueError):
            merge_review({'rule_number': 1}, {'rule_number': 2})


if __name__ == '__main__':
    unittest.main()
