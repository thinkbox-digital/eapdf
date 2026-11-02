"""Assemble V14 acceptance only after all immutable verification lanes finish."""
from collections import Counter
from pathlib import Path
import hashlib
import json
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
FROZEN = ROOT / '.eapdf-verification-v14-accepted'


def read(name):
    return json.loads((HERE / name).read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


manifest = read('verification-inputs-accepted.json')
checks = []
for name, digest in manifest.items():
    path = Path(name) if Path(name).is_absolute() else FROZEN / name
    assert sha(path) == digest, name
    checks.append({'file': name, 'sha256': digest})
assert sha(HERE / 'runtime-inputs-accepted.zip') == read('runtime-capsule-accepted.json')['sha256']
verified_unit = read('unit-verified-inputs.json')
assert [name for name in manifest if manifest[name] != verified_unit[name]] == ['tests/test_remaining_file_requirements.py']
assert verified_unit['validate_eapdf.py'] == manifest['validate_eapdf.py']
assert verified_unit['validator_rule_text.json'] == manifest['validator_rule_text.json']
unit = (HERE / 'unit-verified.log').read_text()
match = re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)', unit)
assert match, 'Unit verification did not pass'
run, seconds, skipped = int(match[1]), float(match[2]), int(match[3])
profiles=[]
for mode, folder in [('normal', HERE/'profiles-accepted'), ('accessibility', HERE/'profiles-accepted/accessibility')]:
    for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
        result = json.loads((folder / (profile + '.json')).read_text())
        iso = next(item for item in result['items'] if item['code'] == 'ISO32005_UPSTREAM')
        assert result['pdfa']['status'] == result['summary']['status'] == iso['level'] == 'PASS'
        assert iso['details']['profile_verified']
        profiles.append(dict(mode=mode, profile=profile, pdfa='PASS', eapdf='PASS', iso='PASS'))
for name in ('profile-tests-accepted.log', 'accessibility-tests-accepted.log'):
    assert re.search(r'Ran 1 test in [\d.]+s\s+OK', (HERE/name).read_text()), name
corpus = read('retained-corpus-accepted-results.json')
assert len(corpus) == 125
assert all(row['pdfa'] == 'PASS' and row['failures'] == row['expected'] and row['status'] == row['expected_status']
           and row['clauses_match'] and row['unavailable_match'] for row in corpus)
entries = read('entry-points-accepted-results.json')
assert len(entries) == 10 and all(row['pdfa'] == row['iso'] == 'PASS' for row in entries)
before = read('recovered-before-results.json')
assert len(before) == 15 and all(row['pdfa'] == row['iso'] == 'PASS' for row in before)
after_by_path = {row['file']:row for row in corpus}
comparison = [dict(file=row['file'], sha256=row['sha256'], before_snapshot=row['snapshot'],
                   before_status=row['status'], before_failures=row['failures'],
                   after_status=after_by_path[row['file']]['status'], after_failures=after_by_path[row['file']]['failures'])
              for row in before]
assert all(row['sha256'] == after_by_path[row['file']]['sha256'] for row in before)
(HERE/'before-after-comparison.json').write_text(json.dumps(comparison, indent=2)+'\n')
(HERE/'verification-integrity.json').write_text(json.dumps({'snapshot':str(FROZEN.relative_to(ROOT)),
    'verified_files':len(checks), 'unit_overlay':'unit-test-profile-correction.patch',
    'capsule_verified':True, 'application_unchanged_across_unit_and_integration_gates':True}, indent=2)+'\n')
evidence = dict(scope='V14 native attachment size/checksum/media consistency without BodyRendering; collection and Alternative roles',
    verification=dict(unit_tests=dict(run=run,passed=run-skipped,skipped=skipped,seconds=seconds,log='unit-verified.log'),
        generated_profiles=profiles,
        retained_corpus=dict(cases=len(corpus),pdfa_passes=len(corpus),exact_outcomes=len(corpus),
            iso_levels=dict(Counter(row['iso'] for row in corpus)),results='retained-corpus-accepted-results.json'),
        new_pdf_probes=dict(cases=15,same_bytes_before_and_after=True,results='before-after-comparison.json'),
        cli_worker_checks=dict(cases=len(entries),results='entry-points-accepted-results.json')),
    inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs-accepted.json',
        capsule='runtime-inputs-accepted.zip',integrity='verification-integrity.json',
        verified_unit_manifest='unit-verified-inputs.json',verified_unit_capsule='runtime-inputs-unit-verified.zip'),
    acceptance_boundary='V14 current unit, all 125 retained complete-pipeline cases, twelve generated profiles and ten actual CLI/isolated-worker checks. No V14 full packaged-suite or new Java-build result is claimed.',
    recovery=dict(thread='01a1553a-2130-75e0-b322-6eb87f1c81aa',initial_snapshot='.eapdf-verification-v14',
        initial_boundary='Wrong daemon mount and container role/completion errors are retained in original logs; no original acceptance claim.',
        provisional_snapshot='.eapdf-verification-v14-final',provisional_corpus='retained-corpus-final-results.json',
        provisional_profile_logs=['profile-tests-final.log','accessibility-tests-final.log'],
        additional_scope_probes='scope-before-tests.log',
        test_corrections=['The native media-parameter unit fixture now uses si instead of the historical ci shortcut.',
                          'The direct scope probe supplies an actual PDF path for independent text extraction; recursive PDF/A is verified in the complete pipeline.']),
    patch='application-accepted.patch',
    creator=dict(unchanged_from_161_test_java_build=True,sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')],
                 evidence='../creator-iso-structure/evidence.json'),
    inventory=dict(records=301,file_records=249,codes=224,unmapped_mandatory_conditional_file_records=0,open_expert_dependency_questions=11),
    limits=['Mapping and observed results are not implementation completeness or certification.',
            'Association is bounded evidence; broader owner/occurrence assignment and original-source completeness remain open.',
            'The prescribed MD5 comparison is not a security identity claim.',
            'Eleven expert/dependency questions remain; broader preservation B13 is deferred.'])
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('Accepted V14:', run-skipped, 'unit passes,', len(corpus), 'complete-pipeline cases,', len(profiles), 'profiles,', len(entries), 'CLI/worker checks')
