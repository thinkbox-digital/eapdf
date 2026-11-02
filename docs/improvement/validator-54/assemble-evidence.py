"""Accept actual counterpart coverage without claiming complete tagging semantics."""
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v54'
CODE = 'CONTENT_SET_MAIL_CONTENTSET_TAGGED'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
assert len(manifest) == 1288
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith(('tests/', 'scripts/')):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 862 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
assert re.search(r'Ran 17 tests in [0-9.]+s\s+OK', (HERE / 'focused-tests.log').read_text())
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-53/custom-regressions.json').read_text())}
custom = read('custom-regressions.json')
assert len(prior) == 494 and len(custom) == 500
for row in custom:
    assert digest(SNAPSHOT / row['file']) == row['sha256']
    if row['file'] in prior:
        old = prior[row['file']]
        assert row['sha256'] == old['sha256'] and row['after'] == old['after']
assert sum(r.get('before', r['after']) != r['after'] for r in custom) == 1
pipeline = read('pdf-results.json')
assert len(pipeline) == 26
assert all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pipeline)
before = {r['file']: r for r in pipeline if r['entry'] == 'before'}
after = {r['file']: r for r in pipeline if r['entry'] == 'after'}
assert len(before) == len(after) == 6
assert sum('reused_before_report' in r for r in pipeline) == 6
cases = json.loads((SNAPSHOT / 'tests/fixtures/eapdf/validator-contentset-coverage/expectations.json').read_text())['cases']
current = {Path(r['file']).stem: r for r in custom if '/validator-contentset-coverage/' in r['file']}
levels = []
for case in cases:
    key = Path(case['file']).stem
    a, b, c = after[key], before[key], current[key]
    assert a['sha256'] == b['sha256'] == c['sha256'] == case['sha256']
    assert a['status'] == b['status'] == case['expected_status']
    assert a['failures'] == case['expected_failures'] == c['after']['failures']
    assert b['status'] == c['before']['status'] and b['failures'] == c['before']['failures']
    report = read('pdf-reports/' + key + '-after.json')
    items = [i for i in report['items'] if i['code'] == CODE]
    assert len(items) == 1 and items[0]['level'] == case['expected_coverage']
    levels.append(items[0]['level'])
    if items[0]['level'] == 'WARN':
        assert any(i['code'] == 'VALIDATION_INCOMPLETE' and i['level'] == 'WARN'
                   and CODE in i['details'].get('rules', []) for i in report['items'])
assert sorted(levels) == ['FAIL', 'FAIL', 'PASS', 'PASS', 'WARN', 'WARN']
assert len({r['sha256'] for r in after.values()}) == 6
assert sum(before[k]['failures'] != after[k]['failures'] for k in before) == 1
assert sum(r['entry'] == 'cli' for r in pipeline) == sum(r['entry'] == 'web-worker' for r in pipeline) == 1
for row in pipeline:
    if row['entry'] in ('cli', 'web-worker'):
        case = next(c for c in cases if Path(c['file']).stem == row['file'])
        assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures']
retained = {r['file']: r for r in pipeline if r['entry'] == 'retained-profile'}
baseline = {r['file']: r for r in read('creator-baseline.json')['profiles']}
assert len(retained) == len(baseline) == 12
for key, expected in baseline.items():
    row = retained[key]
    assert row['sha256'] == expected['sha256'] and row['status'] == 'FAIL'
    assert row['failures'] == expected['failures']
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
review = rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules) == 224 and len(review['inventory_records']) == 301 and len(review['expert_questions']) == 11
observations = read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases'] == 500 and observations['summary']['complete_pipeline_controls'] == 6
jar = ROOT / '.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert digest(jar) == json.loads((ROOT / 'docs/improvement/validator-53/evidence.json').read_text())['creator']['jar_sha256']
evidence = dict(scope='V54 actual Content Set reference coverage instead of tag-count equality',
    verification=dict(unit_tests=dict(run=862, passed=822, skipped=40), focused_tests=17,
        custom_comparisons=dict(cases=500, prior_hashes_statuses_failure_sets_unchanged=494,
            new_statuses_unchanged=6, new_failure_sets_corrected=1),
        pdf_probes=dict(cases=6, distinct_pdf_hashes=6, identical_bytes_before_after=True,
            pdfa_and_verified_iso_pass=6, reused_exact_v53_before_runs=6,
            fresh_after_engine_runs=6, supported_coverage_passes=2, proved_missing_coverage=2,
            unavailable_coverage=2, unsupported_coverage_passes_corrected=3),
        entry_points=dict(cli=1, isolated_workers=1, all_expected_outcomes=True),
        retained_creator_profiles=dict(fresh_engine_runs=12, pdfa_and_iso_pass=12,
            known_failed_conformance_retained=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v54', files=len(manifest), manifest='verification-inputs.json',
        runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=digest(jar), new_java_build=False, new_sample_generation=False),
    observations=dict(summary=observations['summary'], matrix='observation-matrix.json'),
    limits=['Actual independently connected counterparts establish scoped coverage, not every page operation, a tag/Content Set bijection, complete Art semantics or full logical tagging.',
        'The separate explicit-Art count defect and empty-tag STRUCTURE_CORRESPONDENCE failures remain; no overall negative control is certified conformant.',
        'Forty integration/schema tests are skipped; all eleven specification questions, source occurrence and XMP group binding limits remain open.',
        'Public issue review records proposals and comments; no published normative replacement was verified.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
    if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'
    and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V54 accepted: 822 unit passes, 500 custom cases, six controls, two entry points and twelve fresh creator diagnoses match.')
