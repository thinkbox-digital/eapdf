"""Assert the frozen V29 Collection naming gates and their execution boundaries."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v29'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 564 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
rows = read('custom-regressions.json')
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-28/custom-regressions.json').read_text())}
assert len(rows) == 259 and len(prior) == 245
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['after'] == r['before'] == prior[r['file']]['after']
           for r in rows if r['file'] in prior)
assert sum(r['before'] != r['after'] for r in rows) == 11
pdfs = read('pdf-results.json')
assert len(pdfs) == 46 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
before = {r['file']: r for r in pdfs if r['entry'] == 'before'}
after = {r['file']: r for r in pdfs if r['entry'] == 'after'}
assert len(before) == len(after) == 14
assert all(before[key]['sha256'] == after[key]['sha256'] for key in before)
assert sum(before[key]['status'] == 'PASS' and after[key]['status'] == 'FAIL' for key in before) == 8
assert sum(before[key]['status'] == 'FAIL' and after[key]['status'] == 'PASS' for key in before) == 3
entries = [r for r in pdfs if r['entry'] in ('cli', 'web-worker')]
assert len(entries) == 6 and {r['status'] for r in entries} == {'PASS', 'FAIL', 'INCOMPLETE'}
profiles = [r for r in pdfs if r['entry'] == 'retained-profile']
assert len(profiles) == 12 and all(r['status'] == 'FAIL' for r in profiles)
old = json.loads((ROOT / 'docs/improvement/validator-24/evidence.json').read_text())
generated = {r['mode'] + '-' + r['profile']: r for r in old['creator']['generated']}
diagnosis = {r['mode'] + '-' + r['profile']: r for r in
             json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']}
assert all(r['sha256'] == generated[r['file']]['sha256'] and r['failures'] == diagnosis[r['file']]['failures'] for r in profiles)
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
assert len(rules) == 224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions']) == 11
evidence = dict(scope='V29 exact Collection naming values, name-tree aliases and folder-specific case uniqueness',
    verification=dict(unit_tests=dict(run=564, passed=524, skipped=40, log='unit-tests.log'), focused_tests=13,
                      custom_comparisons=dict(cases=259, prior_pdf_hashes_and_outcomes_unchanged=245,
                                              reviewed_outcome_changes=11, results='custom-regressions.json'),
                      pdf_probes=dict(cases=14, identical_bytes_before_after=True, pdfa_passes=14, iso_passes=14,
                                      false_pipeline_passes_corrected=8, false_pipeline_failures_corrected=3),
                      entry_points=dict(cli=3, isolated_workers=3, all_expected_outcomes=True),
                      retained_creator_profiles=dict(cases=12, pdfa_passes=12, iso_passes=12,
                                                      unchanged_operand_or_child_failures=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v29', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=old['creator']['jar_sha256'], new_java_build=False, reused_exact_profile_outputs=True),
    limits=['Names are checked as actual name-tree entries; aliases sharing one Filespec are retained and AF-only discoveries do not invent names.',
            'Custom-only collection controls are INCOMPLETE because child PDF/A is skipped; their complete-pipeline controls PASS.',
            'Full Collection folder graph/ID integrity and Unicode-version coverage are not certified; casefold policy is retained.',
            'V24 creator conformance remains failed. Forty skips, eleven decisions and wider obligation coverage remain open.'])
observations = read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases'] == 259 and observations['summary']['complete_pipeline_controls'] == 14
evidence['observations'] = dict(summary=observations['summary'], matrix='observation-matrix.json', review='observation-review.md', boundary=observations['boundary'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V29 accepted: eight missed naming violations fail; three false failures pass; prior outcomes retained.')
