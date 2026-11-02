"""Reconcile dated exit evidence without replacing the immutable V23 census."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
from eapdf_rule_review import REVIEW_PATH, load_review_rules

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/improvement/coverage-exit-review'
read = lambda path: json.loads(path.read_text())
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('checkpoint', type=int)
    args = parser.parse_args()
    checkpoint = ROOT / ('docs/improvement/validator-' + str(args.checkpoint))
    accepted = read(checkpoint / 'evidence.json')
    assert accepted['status'] == 'accepted'
    rules = load_review_rules()
    review = rules['VALIDATION_INCOMPLETE']['specification_review']
    observations = read(checkpoint / 'observation-matrix.json')
    codes = {row['code']: row for row in observations['rows']}
    assert set(codes) == set(rules)
    profiles = read(ROOT / 'docs/improvement/upstream-profile-execution/results.json')
    for row in profiles:
        assert row['ran'] and row['actual_profile'] == row['profile'] and row['actual'] == row['expected_pdfa']
        assert sha(ROOT / 'tests/fixtures/eapdf/upstream-profile-controls' / row['file']) == row['sha256']
    assert len({row['profile'] for row in profiles}) == 5
    boundaries = read(ROOT / 'docs/improvement/upstream-profile-execution/iso-boundaries/results.json')
    assert {row['case'] for row in boundaries} == {'available', 'invalid-inclusion', 'missing-profile', 'corrupt-profile'}
    questions = Counter(item['status'] for item in review['expert_questions'])
    obligations = []
    for record in review['inventory_records']:
        linked = [codes[code] for code in record['candidate_rule_codes']]
        obligations.append(dict(id=record['id'], section=record['section'], table=record['table'],
            strength=record['strength'], scope=record['scope'], applies_when=record['applies_when'],
            assertion=record['assertion'], effect=record['assessment']['conformance_effect'],
            codes=record['candidate_rule_codes'],
            local_codes=[row['code'] for row in linked if row['local_assertion']],
            linked_code_has_non_na=any(row['observed_non_na'] for row in linked),
            linked_code_has_fail=any(row['observed_fail'] for row in linked),
            requirement_review=record['requirement_review'],
            implementation_disposition=record['implementation_status'],
            limit='Linked code observations are a review aid; each assertion, condition, permitted form and unavailable-evidence branch still needs individual closure.'))
    gates = [
        dict(id='G1', status='open', completed='301 reviewed inventory entries; all 11 questions have explicit local decisions.',
             remaining='Review remaining normative dependencies/errata and obligation dispositions. Cited draft-edition equivalence is not certified.'),
        dict(id='G2', status='open', completed='Actual positive/negative PDF/A controls cover all 5 allowed profiles; real ISO pass/fail/missing/corrupt-profile boundaries exist.',
             remaining='Mapped upstream predicates and each retained custom fragment still need obligation-level closure; geometric/font witnesses do not prove full standards coverage.'),
        dict(id='G3', status='open', completed='All 202 local assertion codes have non-N/A PDF observations in the current cohort.',
             remaining='Review every linked obligation and its positive/negative/permitted/unavailable cases. Broad-code outcomes do not close every mapped clause.'),
        dict(id='G4', status='open', completed='Source/attachment matching and unavailable identity now have bounded repair evidence through the selected checkpoint.',
             remaining='Complete source/attachment occurrence and source-asset association review; original inventory completeness still requires external evidence.'),
        dict(id='G5', status='open', completed='Real CLI/worker checks and actual ISO unavailable/failure transitions retain their recorded boundaries.',
             remaining='Close all 12 operational codes at the obligation level, including parser/extractor/child-worker failure propagation.'),
        dict(id='G6', status='open', completed='Frozen runtime, retained PDF bytes/hashes, independent reports and entry-point receipts are recorded per checkpoint.',
             remaining='40 integration/schema skips remain explicit. Required release dependencies and creator acceptance have separate gates.')]
    data = dict(checkpoint='V' + str(args.checkpoint), historical_census='V23; unchanged',
        counts=dict(requirements=len(obligations), codes=len(rules), questions=dict(questions),
                    local_assertions=observations['summary']['local_assertions'],
                    local_without_non_na=observations['summary']['local_without_non_na']),
        gates=gates, obligations=obligations,
        actual_upstream_profiles=sorted({row['actual_profile'] for row in profiles}),
        creator='Deferred. Header operand diagnosis/proposal is recorded separately; source remains at the accepted pre-repair boundary.',
        evidence={str(path.relative_to(ROOT)): sha(path) for path in [
            ROOT / 'validator_rule_text.json', REVIEW_PATH, checkpoint / 'evidence.json', checkpoint / 'observation-matrix.json',
            ROOT / 'docs/improvement/upstream-profile-execution/results.json',
            ROOT / 'docs/improvement/upstream-profile-execution/iso-boundaries/results.json']})
    (OUT / 'current-status.json').write_text(json.dumps(data, indent=2) + '\n')
    lines = ['# Current validator exit review', '',
        'Latest accepted runtime evidence: **' + data['checkpoint'] + '**. The V23 census and its matrices remain historical.', '',
        '301 requirements; 225 codes; eleven locally resolved questions; no pending project interpretation decisions. '
        'All 202 local assertion codes have non-N/A observations. Full obligation coverage remains unverified.', '',
        '| Gate | Completed evidence | Remaining work |', '| --- | --- | --- |']
    lines.extend('| ' + gate['id'] + ' — open | ' + gate['completed'] + ' | ' + gate['remaining'] + ' |' for gate in gates)
    lines += ['', 'The [detailed view](current-status.json) links all 301 obligations to current code observations without treating those observations as closure. '
              'The [Adobe reference review](../reference-review/adbe-l5/README.md) identifies the cited June 2009 publication. '
              'The [creator proposal](../creator-header-operands/README.md) remains deferred. No source or profile acceptance gate is weakened.', '',
              'Next: review unresolved obligation branches, differing occurrence assignments and operational propagation; then complete the creator phase. '
              'External edition/inventory/human review limits remain explicit.']
    (OUT / 'current-status.md').write_text('\n'.join(lines) + '\n')
    print(data['checkpoint'], len(obligations), 'obligations reconciled; six gates remain open.')


if __name__ == '__main__':
    main()
