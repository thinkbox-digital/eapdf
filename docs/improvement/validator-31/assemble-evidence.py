"""Assert V31 acceptance from exact retained evidence, including independent PDF/A failures."""
import hashlib
import json
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v31'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 577 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
assert re.search(r'Ran 9 tests in [0-9.]+s\s+OK', (HERE / 'focused-tests.log').read_text())
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-30/custom-regressions.json').read_text())}
custom = read('custom-regressions.json')
assert len(prior) == 265 and len(custom) == 276
assert sum(r['before'] != r['after'] for r in custom) == 9
for row in custom:
    assert digest(SNAPSHOT / row['file']) == row['sha256']
    if row['file'] in prior:
        assert row['sha256'] == prior[row['file']]['sha256'] and row['after'] == row['before'] == prior[row['file']]['after']
cases = json.loads((SNAPSHOT / 'tests/fixtures/eapdf/validator-version-schema-names/expectations.json').read_text())['cases']
assert len(cases) == 11
pipeline = read('pdf-results.json')
assert len(pipeline) == 25
before = {r['file']: r for r in pipeline if r['entry'] == 'before'}
after = {r['file']: r for r in pipeline if r['entry'] == 'after'}
assert len(before) == len(after) == 11
for case in cases:
    key = Path(case['file']).stem
    a, b = after[key], before[key]
    assert a['sha256'] == b['sha256'] == case['sha256']
    assert a['status'] == case['expected_pipeline_status']
    assert a['pdfa'] == b['pdfa'] == case['expected_pdfa_status']
    assert a['failures'] == case['expected_failures']
    if a['pdfa'] == 'PASS':
        assert a['iso'] == b['iso'] == 'PASS' and a['profile_verified'] and b['profile_verified']
    else:
        assert a['iso'] == b['iso'] == 'N/A'
assert sum(b['status'] == 'PASS' and after[k]['status'] == 'FAIL' for k, b in before.items()) == 5
assert sum(r['pdfa'] == 'PASS' for r in after.values()) == 8
assert sum(r['entry'] == 'cli' for r in pipeline) == 1
assert sum(r['entry'] == 'web-worker' for r in pipeline) == 2
retained = read('retained-name-preconditions.json')
assert len(retained['rows']) == 12 and all(r['added_guards'] and r['reused_v29_pipeline_outcome']['status'] == 'FAIL' for r in retained['rows'])
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
assert len(rules) == 224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['inventory_records']) == 301
assert len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions']) == 11
observations = read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases'] == 276 and observations['summary']['complete_pipeline_controls'] == 11
jar = ROOT / '.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert digest(jar) == json.loads((ROOT / 'docs/improvement/validator-30/evidence.json').read_text())['creator']['jar_sha256']
evidence = dict(scope='V31 native Catalog Version/Collection Subtype, effective-version precedence and EA-PDF header restriction',
    recovered_thread='01a15a98-5d15-7373-8fe5-1356234612b2',
    verification=dict(unit_tests=dict(run=577, passed=537, skipped=40, log='unit-tests.log'), focused_tests=9,
        custom_comparisons=dict(cases=276, prior_pdf_hashes_and_outcomes_unchanged=265, reviewed_outcome_changes=9),
        pdf_probes=dict(cases=11, identical_bytes_before_after=True, pdfa_and_iso_pass=8,
            independent_pdfa_failures_retained=3, iso_not_run_after_pdfa_failure=3, false_pipeline_passes_corrected=5),
        entry_points=dict(cli=1, isolated_workers=2, all_expected_outcomes=True),
        retained_creator_profiles=dict(native_preconditions_checked=12, reused_exact_v29_engine_runs=12, new_engine_runs=0, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v31', manifest='verification-inputs.json', files=len(manifest),
        runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=digest(jar), new_java_build=False),
    observations=dict(summary=observations['summary'], matrix='observation-matrix.json', review='observation-review.md'),
    limits=['Forty integration/schema skips and eleven review decisions remain.',
        'PDF/A-4 independently forbids a lower 1.x Catalog Version; the three PDF/A-negative controls are not EA-PDF accepts.',
        'The initial expectation error and copied verifier path error are preserved separately.',
        'Twelve historical creator diagnoses are reused after checking all changed predicates on actual inputs; creator conformance remains failed.',
        'Broader obligation/shared coverage, source occurrence assignment and creator repairs remain open.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
    if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V31 accepted: 537 unit passes; 276 custom cases; eleven exact-byte engine probes; five false pipeline passes corrected.')
