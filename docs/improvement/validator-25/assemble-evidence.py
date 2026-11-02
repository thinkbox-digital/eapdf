"""Accept only frozen V25 gates, preserving the failed creator boundary."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v25'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
log = (HERE / 'unit-tests.log').read_text()
assert re.search(r'Ran 525 tests in [0-9.]+s\s+OK \(skipped=40\)', log)
rows = read('custom-regressions.json')
assert len(rows) == 215 and sum(r['before'] != r['after'] for r in rows) == 4
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-24/custom-regressions.json').read_text())}
assert len(prior) == 207
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['after'] == r['before'] == prior[r['file']]['after'] for r in rows if r['file'] in prior)
pdfs = read('pdf-results.json')
assert len(pdfs) == 36 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
assert sum(r['entry'] in ('before', 'after') for r in pdfs) == 16
assert sum(r['entry'] in ('cli', 'web-worker') for r in pdfs) == 8
profiles = [r for r in pdfs if r['entry'] == 'retained-profile']
assert len(profiles) == 12 and all(r['status'] == 'FAIL' for r in profiles)
old = json.loads((ROOT / 'docs/improvement/validator-24/evidence.json').read_text())
generated = {r['mode'] + '-' + r['profile']: r for r in old['creator']['generated']}
assert all(r['sha256'] == generated[r['file']]['sha256'] for r in profiles)
authority = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
review = authority['VALIDATION_INCOMPLETE']['specification_review']
assert len(authority) == 224 and len(review['inventory_records']) == 301 and len(review['expert_questions']) == 11
evidence = dict(scope='V25 universal source-asset size/media contradictions; no source occurrence assignment',
    verification=dict(unit_tests=dict(run=525, passed=485, skipped=40, log='unit-tests.log'),
                      focused_source_tests=12,
                      custom_comparisons=dict(cases=215, prior_pdf_hashes_and_outcomes_unchanged=207,
                                              new_contradiction_cases=4, results='custom-regressions.json'),
                      pdf_probes=dict(cases=8, identical_bytes_before_after=True, pdfa_passes=8, iso_passes=8),
                      entry_points=dict(cli=4, isolated_workers=4, all_expected_outcomes=True),
                      retained_creator_profiles=dict(cases=12, pdfa_passes=12, iso_passes=12,
                                                      unchanged_operand_or_child_failures=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v25', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=old['creator']['jar_sha256'], new_java_build=False,
                 reused_exact_profile_outputs=True),
    limits=['Compatible or unavailable candidate observations prevent a universal failure for that field.',
            'Candidate selection/consumption and existing checksum behavior are unchanged; no owner/occurrence is selected.',
            'V24 creator conformance remains failed; this gate is not creator acceptance.',
            'Original inventories, source-parameter reporting, shared-predicate coverage and eleven decisions remain open.',
            'Unit/schema skips remain explicit; no new packaged or Java acceptance gate is claimed.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V25 source-asset repair accepted; all prior hashes/outcomes and failed creator boundaries preserved.')
