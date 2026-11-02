"""Assert retained V24 gates and record their distinct acceptance boundaries."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v24'
read = lambda name: json.loads((HERE / name).read_text())
digest = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read('verification-inputs.json')
for name, expected in manifest.items():
    assert digest(SNAPSHOT / name) == expected, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT / name) == expected, name
log = (HERE / 'unit-tests-frozen.log').read_text()
assert re.search(r'Ran 513 tests in [0-9.]+s\s+OK \(skipped=40\)', log)
coverage = read('new-coverage.json')
assert coverage['tests_run'] == coverage['tests_passed'] == 26 and len(coverage['observations']) == 9
assert coverage['runtime_sha256'] == manifest['validate_eapdf.py']
assert coverage['rules_sha256'] == manifest['validator_rule_text.json']
regressions = read('custom-regressions.json')
assert len(regressions) == 207
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-23/custom-regressions.json').read_text())}
assert len(prior) == 202
assert all(r['sha256'] == prior[r['file']]['sha256'] and r['before'] == prior[r['file']]['after'] for r in regressions if r['file'] in prior)
pdfs = read('pdf-results.json')
assert len(pdfs) == 18 and all(r['pdfa'] == r['iso'] == 'PASS' and r['profile_verified'] for r in pdfs)
assert sum(r['entry'] in ('cli', 'web-worker') for r in pdfs) == 6
diagnosis = read('profile-diagnosis.json')
assert diagnosis['original_test_failures'] == 12 and diagnosis['original_test_errors'] == 0
assert len(diagnosis['profiles']) == 12 and all(r['pdfa'] == r['iso'] == 'PASS' and r['status'] == 'FAIL' for r in diagnosis['profiles'])
generated = []
with zipfile.ZipFile(HERE / 'generated-profile-artifacts.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for row in diagnosis['profiles']:
        report = json.loads((ROOT / row['report']).read_text())
        pdf = Path(report['summary']['file'])
        assert pdf.is_file() and pdf.is_relative_to(SNAPSHOT)
        generated.append(dict(mode=row['mode'], profile=row['profile'], file=str(pdf.relative_to(ROOT)), sha256=digest(pdf)))
        for path in pdf.parent.rglob('*'):
            if path.is_file():
                archive.write(path, str(path.relative_to(SNAPSHOT)))
oracle = read('retained-operand-review.json')
assert len(oracle) == 202 and sum(bool(r['mismatches']) for r in oracle) == 194 and sum(bool(r['children']) for r in oracle) == 8
authority = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
review = authority['VALIDATION_INCOMPLETE']['specification_review']
assert len(authority) == 224 and len(review['inventory_records']) == 301 and len(review['expert_questions']) == 11
evidence = dict(scope='Accepted V24 validator repair; creator conformance diagnosis fails its original gate',
    verification=dict(unit_tests=dict(run=513, passed=473, skipped=40, log='unit-tests-frozen.log'),
                      focused_coverage=dict(tests=26, formerly_unobserved_codes_exercised=9, results='new-coverage.json'),
                      custom_comparisons=dict(cases=207, prior_pdf_hashes_unchanged=202, results='custom-regressions.json'),
                      independent_object_review=dict(pdfs=202, own_operand_mismatches=194, affected_collection_parents=8),
                      pdf_probes=dict(cases=6, same_bytes_before_after=True, pdfa_passes=6, iso_passes=6),
                      entry_points=dict(cli=3, isolated_workers=3, expected_pass_fail_incomplete=True),
                      creator_profiles=dict(cases=12, original_conformance_test_failures=12, unexpected_test_errors=0,
                                            pdfa_passes=12, iso_passes=12, creator_acceptance=False)),
    inputs=dict(snapshot='.eapdf-verification-v24', manifest='verification-inputs.json', files=len(manifest),
                runtime_archive='runtime-inputs.zip', runtime_archive_sha256=digest(HERE / 'runtime-inputs.zip')),
    creator=dict(unchanged=True, jar_sha256=diagnosis['creator_jar_sha256'], generated=generated,
                 generated_archive_sha256=digest(HERE / 'generated-profile-artifacts.zip'), new_java_build=False),
    limits=['Creator conformance is not accepted: original profile gates fail on the independently diagnosed operand defect.',
            'The standard-only control remains INCOMPLETE under the separate open namespace/role-map assessment.',
            'Unit/schema skips remain explicit; no complete packaged-suite or Java gate is claimed.',
            'Nine formerly unobserved codes now have focused evidence; complete obligation coverage is still unproven.',
            'Eleven questions, source/attachment assignments, shared predicate coverage and release gates remain open.'])
evidence['artifacts'] = {str(p.relative_to(HERE)): digest(p) for p in sorted(HERE.rglob('*'))
                         if p.is_file() and p.name != 'evidence.json' and p.suffix != '.zip'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V24 validator repair accepted; twelve creator conformance gates correctly remain failed.')
