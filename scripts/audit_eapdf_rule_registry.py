"""Check active rule ordering and traceability against the reviewed specification.

This verifies inventory consistency, not implementation completeness.
"""
from collections import Counter
import hashlib
import json
from pathlib import Path

from eapdf_rule_review import REVIEW_PATH, load_review_rules

ROOT = Path(__file__).resolve().parents[1]
SPECIFICATION_SHA256 = 'b89e7471afd3aabaa4e58bd6ca1f3241378c1596cf528023e740d9e79183dbae'


def specification_anchor(rule, records):
    anchor = rule['validation_scope']['source_order']
    assert set(anchor) == {'printed_page', 'pdf_page', 'section', 'table',
                           'requirement_id', 'predicate_order', 'basis'}
    assert isinstance(anchor['predicate_order'], int) and anchor['predicate_order'] > 0
    if rule['validation_scope']['decision'] == 'operational_only':
        assert anchor['printed_page'] is None and anchor['pdf_page'] is None
        assert anchor['requirement_id'] is None and anchor['section'] == 'audit-policy'
        return (999, (999,), 0, '', anchor['predicate_order'])
    expected = min((physical, tuple(map(int, record['section'].split('.'))),
                    record['table'] or 0, record['id'], printed)
                   for record in records
                   if record['id'] in rule['requirements_review']['requirement_ids']
                   for printed, physical in zip(record['printed_pages'], record['pdf_pages'], strict=True))
    assert (anchor['pdf_page'], tuple(map(int, anchor['section'].split('.'))),
            anchor['table'] or 0, anchor['requirement_id'], anchor['printed_page']) == expected
    return (*expected[:4], anchor['predicate_order'])


def audit():
    runtime = json.loads((ROOT / 'validator_rule_text.json').read_text())
    rules = load_review_rules()
    review = json.loads(REVIEW_PATH.read_text())
    inventory = rules['VALIDATION_INCOMPLETE']['specification_review']
    source = ROOT / inventory['specification_source']
    assert hashlib.sha256(source.read_bytes()).hexdigest() == inventory['specification_sha256'] == SPECIFICATION_SHA256
    records = inventory['inventory_records']
    by_id = {record['id']: record for record in records}
    assert len(by_id) == len(records)
    assert [rule['rule_number'] for rule in runtime.values()] == list(range(1, len(runtime) + 1))
    ordering = review.get('rule_ordering', {})
    assert ordering.get('rule_count') == len(runtime)
    anchors = [specification_anchor(rule, records) for rule in runtime.values()]
    assert anchors == sorted(anchors), 'Rules are not in global specification-page order'
    assert not set(runtime) & set(review['removed_rules'])
    assert all(rule['validation_scope']['decision'] != 'delegate_to_verapdf' for rule in runtime.values())
    reverse = {ident: [] for ident in by_id}
    for code, rule in runtime.items():
        ids = rule['requirements_review']['requirement_ids']
        assert ids and set(ids) <= set(by_id), code
        assert rule['spec_section'] and rule['spec_excerpt'], code
        kind = rule.get('spec_excerpt_kind', '')
        assert kind.startswith('Reviewed paraphrase') and 'not a verbatim' in kind, code
        expected_prerequisite = ('none' if rule['validation_scope']['decision'] == 'operational_only'
                                 else 'verapdf_pdfa_pass')
        assert rule['validation_scope'].get('prerequisite') == expected_prerequisite, code
        check = rule['validation_scope'].get('check')
        if check is not None:
            assert set(check) == {'strength', 'applies_when', 'assertion', 'evidence_needed'}, code
            assert all(isinstance(value, str) and value.strip() for value in check.values()), code
            assert check['strength'] in {'SHALL', 'SHALL_NOT', 'SHOULD', 'required_if_present',
                                         'creation_requirement', 'conditional_requirement'}, code
        if 'top_level_split_2026_10_31' in review['rules'][code]:
            assert check is not None, code
        assertions = rule['requirements_review'].get('assertions', [])
        assert [row['id'] for row in assertions] == ids, code
        for assertion in assertions:
            assertion_source = by_id[assertion['id']]
            for field in ('strength', 'scope', 'applies_when', 'assertion', 'evidence_needed'):
                assert assertion.get(field) == assertion_source[field], (code, assertion_source['id'], field)
            assert assertion.get('local_interpretation') == assertion_source.get('local_interpretation'), code
        for ident in ids:
            reverse[ident].append(code)
    coverage = []
    for record in records:
        codes = reverse[record['id']]
        upstream = record.get('upstream_rule_codes', [])
        assert set(record['candidate_rule_codes']) == set(codes), record['id']
        assert set(upstream) <= set(review['removed_rules']), record['id']
        if codes:
            disposition = 'active_rule_mapping'
        elif upstream:
            disposition = 'upstream_only'
        elif record['scope'] == 'software':
            disposition = 'software_behavior_outside_pdf_file_validation'
        elif record['scope'] == 'creation':
            disposition = 'creation_definition_or_process_obligation'
        else:
            disposition = 'permission_guidance_or_scope_statement'
        # No mandatory file assertion may vanish during the ownership cleanup.
        if record['scope'] == 'file' and record['strength'] in (
                'SHALL', 'SHALL_NOT', 'required_if_present', 'conditional_requirement',
                'conditional', 'inherited_requirement'):
            assert codes or upstream, record['id']
        coverage.append({'id': record['id'], 'section': record['section'],
                         'strength': record['strength'], 'scope': record['scope'],
                         'disposition': disposition, 'rules': codes, 'upstream_rules': upstream})
    return {'source': str(source.relative_to(ROOT)), 'source_sha256': SPECIFICATION_SHA256,
            'active_rules': len(runtime), 'retired_upstream_rules': list(review['removed_rules']),
            'reviewed_obligations': len(records), 'specification_order_verified': True,
            'numbering_policy': ordering.get('policy', 'Specification order'),
            'source_page_anchors_verified': True,
            'mandatory_file_obligations_accounted_for': True,
            'conditional_file_obligations_accounted_for': True,
            'runtime_assertion_context_verified': True,
            'explicit_rule_predicates': sum('check' in rule['validation_scope'] for rule in runtime.values()),
            'implementation_completeness_verified': False,
            'dispositions': dict(Counter(row['disposition'] for row in coverage)),
            'obligations': coverage}


if __name__ == '__main__':
    print(json.dumps(audit(), indent=2))
