"""Verify final frozen inputs and complete-pipeline evidence before indexing it."""
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
FROZEN = ROOT / '.eapdf-verification-v13'


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def tests(path):
    match = re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK(?: \(skipped=(\d+)\))?', path.read_text())
    assert match, path
    total, seconds, skipped = match.groups()
    skipped = int(skipped or 0)
    return {'status': 'PASS', 'run': int(total), 'passed': int(total)-skipped,
            'skipped': skipped, 'seconds': float(seconds), 'log': str(path.relative_to(ROOT))}


full = tests(HERE.parent / 'validator-11/full-tests.log')
unit = tests(HERE / 'unit-tests.log')
rows = read(HERE / 'retained-corpus-results.json')
assert len(rows) == 108
assert all(row['pdfa'] == 'PASS' and row['failures'] == row['expected']
           and row['status'] == row['expected_status'] and row.get('clauses_match', True) for row in rows)
levels = Counter()
checked_cases = 0
for manifest in (FROZEN / 'tests/fixtures/eapdf').rglob('expectations.json'):
    for case in read(manifest)['cases']:
        path = manifest.parent / case['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
        report = read(HERE / 'retained-corpus' / (manifest.parent.name + '--' + path.name + '.json'))
        iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
        if iso['level'] == 'N/A':
            with pikepdf.open(path) as pdf:
                version = str(pdf.Root.get('/Version', pdf.pdf_version)).lstrip('/')
                assert version != '2.0' or pdf.Root.get('/StructTreeRoot') is None, path
        else:
            assert iso['level'] == ('FAIL' if 'ISO32005_UPSTREAM' in case.get('expected_upstream_failures', []) + case['expected_failures'] else 'PASS'), path
            assert iso['details']['profile_verified'], path
            assert iso['details']['passed_rules'] + iso['details']['failed_rules'] == 1636, path
        for code in case.get('required_unavailable_codes', []):
            assert any(item['code'] == code and item['level'] == 'WARN' for item in report['items']), (path, code)
        levels[iso['level']] += 1
        checked_cases += 1
assert checked_cases == 108
entries = read(HERE / 'entry-points.json')
assert len(entries) == 8 and all(row['pdfa'] == row['iso'] == 'PASS' for row in entries)
before = read(HERE / 'before-final-fixtures.json')
manifest = read(FROZEN / 'tests/fixtures/eapdf/validator-attachment-filenames/expectations.json')
assert {row['file']: row['sha256'] for row in before} == {case['file']: case['sha256'] for case in manifest['cases']}
hashes = read(HERE / 'verification-inputs.json')
capsule = read(HERE / 'runtime-capsule.json')
assert hashlib.sha256((HERE / capsule['archive']).read_bytes()).hexdigest() == capsule['sha256']
with zipfile.ZipFile(HERE / capsule['archive']) as archive:
    assert not any(Path(name).name == 'credentials.json' for name in archive.namelist())
changed_runtime, changed_frozen = [], []
for name, expected in hashes.items():
    current, frozen = ROOT / name, FROZEN / name
    if not frozen.is_file() or hashlib.sha256(frozen.read_bytes()).hexdigest() != expected:
        changed_frozen.append(name)
    if (not name.startswith('docs/') and current.suffix != '.md'
            and (not current.is_file() or hashlib.sha256(current.read_bytes()).hexdigest() != expected)):
        changed_runtime.append(name)
assert not changed_frozen and not changed_runtime, (changed_frozen, changed_runtime)
integrity = {'frozen_inputs_checked': len(hashes), 'changed_frozen_inputs': changed_frozen,
             'changed_runtime_inputs': changed_runtime,
             'boundary': 'Documentation-only updates after freezing are allowed and do not change runtime/test inputs.'}
write(HERE / 'verification-integrity.json', integrity)
new_rows = [row for row in rows if '/validator-attachment-filenames/' in row['file']]
assert len(new_rows) == 10
write(HERE / 'corpus-results.json', new_rows)
profiles = []
for mode in ('normal', 'accessibility'):
    for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
        path = HERE / 'profiles' / (profile + '.json') if mode == 'normal' else HERE / 'profiles/accessibility' / (profile + '.json')
        report = read(path)
        assert report['pdfa']['status'] == report['summary']['status'] == 'PASS', path
        iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
        assert iso['level'] == 'PASS' and iso['details']['profile_verified'], path
        profiles.append({'mode': mode, 'profile': profile, 'pdfa': 'PASS', 'eapdf': 'PASS', 'iso': 'PASS'})
profile_tests = tests(HERE / 'profile-tests.log')
result = {'batch': 'V13', 'authority': 'validator_rule_text.json',
    'scope': ['Original versus native PDF attachment filenames', 'Optional checksum source-assisted association',
              'Unknown source-omitted association remains unavailable', 'Zero-byte policy placeholders and integrity/visibility gates'],
    'verification': {'current_unit': unit, 'prior_full_packaged_v11': full, 'generated_profile_tests': profile_tests,
        'profiles': profiles, 'retained_corpus': {'cases': 108, 'pdfa_passes': 108, 'exact_outcomes': 108,
            'iso_levels': dict(levels), 'results': 'retained-corpus-results.json'},
        'new_pdf_probes': {'cases': 10, 'same_bytes_before_and_after': True, 'results': 'corpus-results.json'},
        'cli_worker_checks': {'cases': 8, 'results': 'entry-points.json'}},
    'acceptance_boundary': 'V11 full suite predates V12/V13. V13 has current unit tests, all retained complete-pipeline cases, twelve generated profiles, and actual CLI/isolated-worker checks. No V13 full-suite result is claimed.',
    'inputs': {'snapshot': '.eapdf-verification-v13', 'manifest': 'verification-inputs.json', 'capsule': 'runtime-inputs.zip', 'integrity': 'verification-integrity.json'},
    'before': ['before-tests.log', 'before-final-fixtures.json', 'before-reports'],
    'fixture_correction': 'fixture-construction-correction.json', 'patch': 'application.patch',
    'creator': {'unchanged_from_161_test_java_build': True,
        'sha256': '272ad741590552489dfb6d096f7ee4b01035fb3faf1c76d817394c0c2ad5e16a', 'evidence': '../creator-iso-structure/evidence.json'},
    'inventory': {'records': 301, 'file_records': 249, 'codes': 224,
        'unmapped_mandatory_conditional_file_records': 0, 'open_expert_dependency_questions': 11},
    'limits': ['Mapping and observed results are not implementation completeness or certification.',
        'Source-assisted alias association reuses the existing bounded unique-source selector; no source completeness, occurrence identity or global assignment proof.',
        'MD5 is a prescribed consistency value, not a security identity claim.',
        'Eleven expert/dependency questions and broader ownership review remain open.',
        'Optional official PREMIS schema remains unavailable; broader preservation B13 remains deferred.']}
write(HERE / 'evidence.json', result)
print(json.dumps({'unit': unit, 'profile_tests': profile_tests, 'profiles': len(profiles), 'corpus': len(rows), 'entry_points': len(entries)}, indent=2))
