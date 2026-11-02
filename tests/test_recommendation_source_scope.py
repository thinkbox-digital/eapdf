"""Source attribution follows accepted ownership across downstream checks."""
import hashlib
import json
from pathlib import Path
import unittest

from test_preservation_safety import validator as v, failed_codes
from rule_refinement_fixtures import corrected_rendered_count
import test_source_attachment_references as reference_tests


class RecommendationSourceScopeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.folder = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-recommendation-source-scope'
        cls.cases = json.loads((cls.folder / 'expectations.json').read_text())['cases']
        cls.reports = {}
        for case in cls.cases:
            path = cls.folder / case['file']
            assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
            cls.reports[path.stem] = v.validate(str(path))

    def test_native_controls_retain_conformance_and_independent_type_results(self):
        for case in self.cases:
            with self.subTest(file=case['file']):
                report = self.reports[Path(case['file']).stem]
                self.assertEqual(case['expected_status'], report.summary()['status'])
                self.assertEqual(set(case['expected_failures']), failed_codes(report))

    def test_unknown_ownership_cannot_prove_attribute_equality_or_alteration(self):
        for name in ('unassigned-equal', 'unassigned-different', 'unassigned-invalid-type'):
            rows = [i for i in self.reports[name].items if i['code'] == 'USERPROPS_VALUES_UNMODIFIED']
            self.assertTrue(rows)
            self.assertEqual({'WARN'}, {i['level'] for i in rows})
            self.assertEqual({'unavailable'}, {i['details']['comparison'] for i in rows})
            self.assertTrue(all('source_sha256' not in i['details'] for i in rows))

    def test_known_source_recommendations_still_distinguish_equal_and_modified(self):
        for name, level, comparison in [('assigned-equal', 'PASS', 'equal'),
                                         ('assigned-different', 'WARN', 'different')]:
            rows = [i for i in self.reports[name].items if i['code'] == 'USERPROPS_VALUES_UNMODIFIED']
            self.assertEqual({level}, {i['level'] for i in rows})
            self.assertEqual({comparison}, {i['details']['comparison'] for i in rows})
            self.assertTrue(all(i['details'].get('source_sha256') for i in rows))

    def test_recommendation_does_not_fail_after_correcting_unrelated_count(self):
        for name in ('assigned-equal','assigned-different'):
            with self.subTest(name=name), corrected_rendered_count(
                    self.folder/(name+'.pdf'),'represented.eml',2,1) as path:
                self.assertEqual('PASS',v.validate(str(path)).summary()['status'])

    def test_unassigned_header_inventory_does_not_attribute_outer_occurrences(self):
        for name in ('unassigned-equal', 'unassigned-different', 'unassigned-invalid-type'):
            inventory = next(i for i in self.reports[name].items
                             if i['code'] == 'HEADER_FIELDS_TAGGED_RECOMMENDED')['details']['source_header_inventory']
            self.assertTrue(inventory)
            for item in inventory:
                self.assertEqual('unavailable', item['assessment'])
                self.assertEqual([], item['occurrences'])
                self.assertNotIn('source_sha256', item)

    def test_known_header_inventory_retains_ordered_original_occurrences(self):
        inventory = next(i for i in self.reports['assigned-equal'].items
                         if i['code'] == 'HEADER_FIELDS_TAGGED_RECOMMENDED')['details']['source_header_inventory']
        self.assertEqual(['available'], [i['assessment'] for i in inventory])
        self.assertTrue(inventory[0]['occurrences'])
        self.assertTrue(inventory[0]['source_sha256'])

    def attachment_inputs(self):
        raw = reference_tests.SourceAttachmentReferenceTests().source_bytes()
        source = v.summarize_source_email([dict(afrelationship='Source', filename='source.eml', bytes=raw)])
        record = {'Mail_GUID': 'uuid:first', 'messageid': '<duplicate@example.org>',
                  'Subject': 'Source A', 'SizeInBytes': str(len(raw))}
        attachment = {'Mail_GUID': 'uuid:first', 'Filename': 'notes.txt', 'SizeInBytes': '7',
                      'Content-Type': 'text/plain'}
        return record, source, attachment, {'identity': {'Mail_GUID': 'uuid:first'}}

    def test_unknown_binding_cannot_supply_a_source_derived_alias_checksum(self):
        record, source, attachment, body = self.attachment_inputs()
        self.assertEqual(v.md5hex(b'payload'), v.attachment_source_checksum(
            attachment, body, [record], [source]))
        for bindings in ({1: None}, {}):
            self.assertIsNone(v.attachment_source_checksum(attachment, body, [record], [source], bindings))

    def test_accepted_binding_preserves_source_derived_alias_checksum(self):
        record, source, attachment, body = self.attachment_inputs()
        self.assertEqual(v.md5hex(b'payload'), v.attachment_source_checksum(
            attachment, body, [record], [source], {1: source}))

    def test_binding_index_follows_the_email_record_order(self):
        record, source, _, _ = self.attachment_inputs()
        other = dict(record, Mail_GUID='uuid:other')
        self.assertIs(source, v.source_email_for_record(other, [record, other], [source], {1: None, 2: source}))
        self.assertIsNone(v.source_email_for_record(record, [record, other], [source], {1: None, 2: source}))

    def test_record_outside_the_binding_inventory_cannot_reselect_a_source(self):
        record, source, _, _ = self.attachment_inputs()
        self.assertIsNone(v.source_email_for_record(dict(record), [record], [source], {1: source}))
