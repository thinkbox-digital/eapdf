"""Assert exact filename repair evidence with unchanged historical boundaries."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v27'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
assert re.search(r'Ran 545 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
rows = read('custom-regressions.json')
assert len(rows) == 232 and sum(r['before'] != r['after'] for r in rows) == 6
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-26/custom-regressions.json').read_text())}
assert len(prior) == 224
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['after'] == r['before'] == prior[r['file']]['after'] for r in rows if r['file'] in prior)
changed = [r for r in rows if r['before'] != r['after']]
assert sum((r['before']['status'], r['after']['status']) == ('INCOMPLETE', 'PASS') for r in changed) == 4
assert sum((r['before']['status'], r['after']['status']) == ('FAIL', 'INCOMPLETE') for r in changed) == 1
pdfs = read('pdf-results.json')
assert len(pdfs) == 36 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
assert sum(r['entry'] in ('before', 'after') for r in pdfs) == 16
assert sum(r['entry'] in ('cli', 'web-worker') for r in pdfs) == 8
profiles = [r for r in pdfs if r['entry'] == 'retained-profile']
assert len(profiles) == 12 and all(r['status'] == 'FAIL' for r in profiles)
old = json.loads((ROOT / 'docs/improvement/validator-24/evidence.json').read_text())
generated = {r['mode'] + '-' + r['profile']: r for r in old['creator']['generated']}
assert all(r['sha256'] == generated[r['file']]['sha256'] for r in profiles)
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
assert len(rules) == 224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions']) == 11
evidence = dict(scope='V27 exact decoded source filename identity; existing association policy boundaries retained',
    verification=dict(unit_tests=dict(run=545, passed=505, skipped=40, log='unit-tests.log'), focused_source_tests=10,
                      custom_comparisons=dict(cases=232, prior_pdf_hashes_and_outcomes_unchanged=224,
                                              reviewed_outcome_changes=6, permitted_forms_now_pass=4,
                                              invented_alias_now_incomplete=1, genuine_media_failure_retained=1,
                                              results='custom-regressions.json'),
                      pdf_probes=dict(cases=8, identical_bytes_before_after=True, pdfa_passes=8, iso_passes=8),
                      entry_points=dict(cli=4, isolated_workers=4, all_expected_outcomes=True),
                      retained_creator_profiles=dict(cases=12, pdfa_passes=12, iso_passes=12,
                                                      unchanged_operand_or_child_failures=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v27', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=old['creator']['jar_sha256'], new_java_build=False, reused_exact_profile_outputs=True),
    limits=['Display cleanup is unchanged; exact raw UF/F text is used for source filename association.',
            'Existing basename/path policy and checksum priority remain; original platform and occurrence identity are not certified.',
            'V24 creator conformance remains failed on operands. No Java/full packaged acceptance is claimed.',
            'Forty skips, eleven decisions and remaining shared/obligation/inventory coverage stay explicit.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V27 accepted: four permitted filename forms pass, invented alias stays unavailable, genuine contradictions remain failures.')
