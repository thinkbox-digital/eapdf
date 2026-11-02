"""Accept source-backed parent findings while preserving the collection question."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v53'
CODE = 'MAIL_CONTENTSET_NESTED_UNDER_MESSAGE'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith(('tests/', 'scripts/')):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 845 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
assert re.search(r'Ran 12 tests in [0-9.]+s\s+OK', (HERE / 'focused-tests.log').read_text())
assert re.search(r'Ran 32 tests in [0-9.]+s\s+OK', (HERE / 'related-focused-tests.log').read_text())
assert re.search(r'Ran 2 tests in [0-9.]+s\s+OK', (HERE / 'legacy-assertion-review.log').read_text())
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-52/custom-regressions.json').read_text())}
custom = read('custom-regressions.json')
reviewed = {r['file']: r for r in read('prior-expectation-review.json')['cases']}
native = read('native-census.json')
ancestry = {r['file']: r for r in native['custom']}
assert len(prior) == 486 and len(custom) == len(ancestry) == 494 and len(reviewed) == 149
assert all(not row['errors'] for row in native['custom'] + native['creator'])
assert sum(r['before'] != r['after'] for r in custom) == 122
noncollection = []
collection = []
unchanged = []
for row in custom:
    key = row['file']
    assert digest(SNAPSHOT / key) == row['sha256'] == ancestry[key]['sha256']
    if key not in prior:
        continue
    assert row['sha256'] == prior[key]['sha256']
    assert row['before'] == prior[key]['after']
    proof = ancestry[key]
    if proof['orphans'] and proof.get('profile') in ('s', 'si', 'm', 'mi'):
        expected = dict(status='FAIL', failures=sorted(set(prior[key]['after']['failures']) | {CODE}))
        assert row['after'] == expected == reviewed[key]['after']
        assert row['observed_levels'][CODE] == ['FAIL']
        assert reviewed[key]['profile'] == proof['profile']
        assert reviewed[key]['orphan_objects'] == [r['objgen'] for r in proof['orphans']]
        noncollection.append(row)
    elif proof['orphans'] and proof.get('profile') in ('c', 'ci'):
        assert row['after'] == prior[key]['after'] == reviewed[key]['after']
        assert row['observed_levels'][CODE] == ['WARN']
        assert row['observed_levels']['VALIDATION_INCOMPLETE'] == ['WARN']
        collection.append(row)
    else:
        assert key not in reviewed and row['after'] == prior[key]['after']
        unchanged.append(row)
assert len(noncollection) == 119 and len(collection) == 30 and len(unchanged) == 337
assert sum(r['before']['status'] == 'PASS' for r in noncollection) == 32
assert sum(r['before']['status'] == 'INCOMPLETE' for r in noncollection) == 8
assert sum(r['before']['status'] == 'FAIL' for r in noncollection) == 79
pipeline = read('pdf-results.json')
assert len(pipeline) == 30
assert all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pipeline)
before = {r['file']: r for r in pipeline if r['entry'] == 'before'}
after = {r['file']: r for r in pipeline if r['entry'] == 'after'}
assert len(before) == len(after) == 8
assert sum('reused_before_report' in r for r in pipeline) == 8
cases = json.loads((SNAPSHOT / 'tests/fixtures/eapdf/validator-contentset-parent/expectations.json').read_text())['cases']
current = {Path(r['file']).stem: r for r in custom if '/validator-contentset-parent/' in r['file']}
for case in cases:
    key = Path(case['file']).stem
    a, b = after[key], before[key]
    assert a['sha256'] == b['sha256'] == case['sha256']
    assert a['status'] == case['expected_status'] and a['failures'] == case['expected_failures']
    assert b['failures'] == current[key]['before']['failures']
    if key == 'orphan-front-collection':
        boundary = read('entry-boundary-review.json')
        custom_before = read(boundary['custom_before'])
        engine_before = read(boundary['engine_before'])
        assert boundary['pdf_sha256'] == a['sha256']
        assert custom_before['summary']['status'] == current[key]['before']['status'] == 'INCOMPLETE'
        assert engine_before['summary']['status'] == b['status'] == 'PASS'
        assert next(i for i in custom_before['items'] if i['code']=='COLLECTION_CHILD_VALIDATION')['details']['pdfa_status']=='SKIP'
        assert next(i for i in engine_before['items'] if i['code']=='COLLECTION_CHILD_VALIDATION')['details']['pdfa_status']=='PASS'
    else:
        assert b['status'] == current[key]['before']['status']
    assert 'VALIDATION_ERROR' not in a['failures']
    result = json.loads((HERE / 'pdf-reports' / (key + '-after.json')).read_text())
    parents = [i for i in result['items'] if i['code'] == CODE]
    assert len(parents) == 1 and parents[0]['level'] == case['expected_parent_level']
    if case['expected_parent_level'] == 'WARN':
        assert parents[0]['details']['issue_id'] == 'SPEC-T13-CONTAINER-PARENT'
        assert any(i['code'] == 'VALIDATION_INCOMPLETE' and i['level'] == 'WARN'
                   and CODE in i['details'].get('rules', []) for i in result['items'])
assert len({r['sha256'] for r in after.values()}) == 8
assert sum(before[k]['status'] != after[k]['status'] for k in before) == 3
assert sum(r['entry'] == 'cli' for r in pipeline) == sum(r['entry'] == 'web-worker' for r in pipeline) == 1
for row in pipeline:
    if row['entry'] in ('cli', 'web-worker'):
        case = next(c for c in cases if Path(c['file']).stem == row['file'])
        assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures']
retained = {r['file']: r for r in pipeline if r['entry'] == 'retained-profile'}
creator_review = {(r['mode'], r['profile']): r for r in read('creator-nesting-review.json')['profiles']}
assert len(retained) == len(creator_review) == 12
added_parent_failures = 0
for key, expected in creator_review.items():
    label = '-'.join(key)
    row = retained[label]
    assert row['sha256'] == expected['sha256'] == digest(ROOT / expected['file'])
    assert row['status'] == 'FAIL' and row['failures'] == expected['expected_failures']
    added_parent_failures += row['failures'] != expected['failures_before']
    result = json.loads((HERE / 'pdf-reports' / (label + '-retained-profile.json')).read_text())
    assert {i['level'] for i in result['items'] if i['code'] == CODE} == {expected['expected_parent_level']}
assert added_parent_failures == 4
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
review = rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules) == 224 and len(review['inventory_records']) == 301 and len(review['expert_questions']) == 11
question = next(q for q in review['expert_questions'] if q['finding_id'] == 'SPEC-T13-CONTAINER-PARENT')
assert question['interim_boundary'] == 'Keep the conflict visible. Do not invent a collection exception.'
observations = read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases'] == 494 and observations['summary']['complete_pipeline_controls'] == 8
jar = ROOT / '.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert digest(jar) == json.loads((ROOT / 'docs/improvement/validator-52/evidence.json').read_text())['creator']['jar_sha256']
evidence = dict(scope='V53 actual Content Set ancestry without an invented FrontMatter or collection exception',
    verification=dict(unit_tests=dict(run=845, passed=805, skipped=40, log='unit-tests.log'), focused_tests=12,
        related_focused_tests=32, legacy_assertion_review_tests=2,
        custom_comparisons=dict(cases=494, prior_pdf_hashes_retained=486,
            proved_noncollection_parent_failures=119, previous_passes_corrected=32,
            previous_incomplete_results_with_definite_parent_failure=8, previous_failed_results_gaining_parent_failure=79,
            collection_parent_predicates_now_explicitly_unavailable=30,
            collection_statuses_and_failure_sets_unchanged=30, other_prior_outcomes_unchanged=337),
        pdf_probes=dict(cases=8, distinct_pdf_hashes=8, identical_bytes_before_after=True,
            pdfa_and_verified_iso_pass=8, reused_exact_v52_before_runs=8, fresh_after_engine_runs=8,
            corrected_positive_baselines=3, definite_noncollection_rejections=3, open_collection_assessments=2, unsupported_collection_parent_pipeline_pass_corrected=1,
            custom_before_child_skip_preserved_separately=True),
        entry_points=dict(cli=1, isolated_workers=1, all_expected_outcomes=True,
            cli_rejects_unjustified_frontmatter_exemption=True, worker_preserves_collection_incompleteness=True),
        retained_creator_profiles=dict(fresh_engine_runs=12, all_six_profiles_both_modes=True,
            pdfa_and_iso_pass=12, known_failed_conformance_retained=12,
            original_noncollection_profiles_gaining_proved_parent_failure=4,
            original_collection_parent_assessments_unavailable=4, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v53', manifest='verification-inputs.json', files=len(manifest),
        runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=digest(jar), new_java_build=False, new_sample_generation=False),
    observations=dict(summary=observations['summary'], matrix='observation-matrix.json', review='observation-review.md'),
    limits=['SPEC-T13-CONTAINER-PARENT remains unresolved; no collection exception is certified and all eleven listed questions remain open.',
        'Historical PDFs retain their bytes. Historical builder parameters predate this parent review; current positive focused tests use separately created conformant baselines.',
        'This proves the scoped ancestry predicates, not complete logical tagging, content coverage, encoding, source semantics, source occurrence assignment or XMP group binding. Forty integration/schema skips and known creator defects remain open.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
    if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'
    and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V53 accepted: 805 unit passes; 494 custom cases with all 486 prior PDF hashes retained; 119 proved parent failures, thirty unresolved collection predicates, eight controls, two entry points and twelve fresh creator runs match.')
