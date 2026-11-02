"""Assert the frozen V28 Boolean gates and their execution boundaries."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v28'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 551 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
rows = read('custom-regressions.json')
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-27/custom-regressions.json').read_text())}
assert len(rows) == 245 and len(prior) == 232
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['after'] == r['before'] == prior[r['file']]['after']
           for r in rows if r['file'] in prior)
assert sum(r['before'] != r['after'] for r in rows) == 7
pdfs = read('pdf-results.json')
assert len(pdfs) == 44 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
before = {r['file']: r for r in pdfs if r['entry'] == 'before'}
after = {r['file']: r for r in pdfs if r['entry'] == 'after'}
assert len(before) == len(after) == 13
assert all(before[key]['sha256'] == after[key]['sha256'] for key in before)
assert sum(before[key]['status'] == 'PASS' and after[key]['status'] == 'FAIL' for key in before) == 7
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
evidence = dict(scope='V28 exact Boolean values in catalog DisplayDocTitle and Collection CustomField E',
    verification=dict(unit_tests=dict(run=551, passed=511, skipped=40, log='unit-tests.log'), focused_tests=6,
                      custom_comparisons=dict(cases=245, prior_pdf_hashes_and_outcomes_unchanged=232,
                                              reviewed_outcome_changes=7, results='custom-regressions.json'),
                      pdf_probes=dict(cases=13, identical_bytes_before_after=True, pdfa_passes=13, iso_passes=13,
                                      false_pipeline_passes_corrected=7),
                      entry_points=dict(cli=3, isolated_workers=3, all_expected_outcomes=True),
                      retained_creator_profiles=dict(cases=12, pdfa_passes=12, iso_passes=12,
                                                      unchanged_operand_or_child_failures=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v28', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=old['creator']['jar_sha256'], new_java_build=False, reused_exact_profile_outputs=True),
    limits=['The collection controls correct child operands independently; historical creator outputs remain unchanged.',
            'Custom-only collection controls are INCOMPLETE because child PDF/A is skipped; their complete-pipeline controls PASS.',
            'Source-dependent recommendation warnings remain explicit and do not alone imply mandatory failure.',
            'V24 creator conformance remains failed. Forty skips, eleven decisions and wider obligation coverage remain open.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V28 accepted: seven false complete-pipeline Boolean passes now fail; controls and prior outcomes retained.')
