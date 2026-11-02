"""Assert the V30 navigation name-value repair and bounded reuse evidence."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v30'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 568 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
rows = read('custom-regressions.json')
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-29/custom-regressions.json').read_text())}
assert len(rows) == 265 and len(prior) == 259
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['after'] == r['before'] == prior[r['file']]['after']
           for r in rows if r['file'] in prior)
assert sum(r['before'] != r['after'] for r in rows) == 3
pdfs = read('pdf-results.json')
assert len(pdfs) == 14 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
before = {r['file']: r for r in pdfs if r['entry'] == 'before'}
after = {r['file']: r for r in pdfs if r['entry'] == 'after'}
assert len(before) == len(after) == 6
assert all(before[key]['sha256'] == after[key]['sha256'] for key in before)
assert sum(before[key]['status'] == 'PASS' and after[key]['status'] == 'FAIL' for key in before) == 3
assert sum(r['entry'] == 'cli' for r in pdfs) == sum(r['entry'] == 'web-worker' for r in pdfs) == 1
retained = read('retained-name-preconditions.json')
assert len(retained['rows']) == 12 and all(r['added_type_guards'] for r in retained['rows'])
assert all(r['reused_v29_pipeline_outcome']['status'] == 'FAIL' for r in retained['rows'])
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
assert len(rules) == 224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions']) == 11
observations = read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases'] == 265 and observations['summary']['complete_pipeline_controls'] == 6
old = json.loads((ROOT / 'docs/improvement/validator-24/evidence.json').read_text())
jar = ROOT / '.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert digest(jar) == old['creator']['jar_sha256']
evidence = dict(scope='V30 profile-specific PageMode and NonFullScreenPageMode require actual PDF name objects',
    verification=dict(unit_tests=dict(run=568, passed=528, skipped=40, log='unit-tests.log'), focused_tests=4,
                      focused_scope='All six profiles; s/si with/without attachments and PDF/A parts 3/4; canonical, text, missing and wrong-name controls',
                      custom_comparisons=dict(cases=265, prior_pdf_hashes_and_outcomes_unchanged=259,
                                              reviewed_outcome_changes=3, results='custom-regressions.json'),
                      pdf_probes=dict(cases=6, identical_bytes_before_after=True, pdfa_passes=6, iso_passes=6,
                                      false_pipeline_passes_corrected=3),
                      entry_points=dict(cli=1, isolated_workers=1, all_expected_outcomes=True),
                      retained_creator_profiles=dict(native_type_preconditions_checked=12,
                          reused_exact_v29_pipeline_runs=12, new_v30_creator_engine_runs=0, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v30', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=digest(jar), new_java_build=False),
    observations=dict(summary=observations['summary'], matrix='observation-matrix.json', review='observation-review.md',
                      boundary=observations['boundary']),
    limits=['Only profile-specific named-value checks change; entry-presence checks and attachment/profile selection remain unchanged.',
            'V29 native probes establish both name and text modes pass upstream; no complete PDF/A type certification is inferred.',
            'The twelve V29 creator runs are reused only after independent hash/name-type checks. Their failed conformance remains explicit.',
            'Initial empty test discovery is retained and is not counted as a pass; restored focused tests and frozen full suite ran successfully.',
            'Forty skips, eleven decisions and broader coverage remain open. Catalog Version and Collection Subtype probes require follow-up review.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V30 accepted: three false navigation passes corrected; 259 prior outcomes unchanged; creator evidence reuse bounded.')
