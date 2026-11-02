"""Index retained verification results without converting interrupted runs to PASS."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
IMPROVEMENT = HERE.parent


def read(path):
    return json.loads(path.read_text())


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def test_result(path):
    value = path.read_text()
    match = re.search(r'Ran (\d+) tests? in ([\d.]+)s\s+((?:OK|FAILED)[^\n]*)', value)
    if not match:
        return {'status': 'incomplete', 'log': str(path.relative_to(ROOT))}
    total, seconds, footer = match.groups()
    def count(name):
        found = re.search(name + r'=(\d+)', footer)
        return int(found[1]) if found else 0
    failures, errors, skips = (count(key) for key in ('failures', 'errors', 'skipped'))
    return {'status': 'PASS' if footer.startswith('OK') else 'FAIL', 'run': int(total),
            'passed': int(total) - failures - errors - skips, 'failed': failures,
            'errors': errors, 'skipped': skips, 'seconds': float(seconds),
            'log': str(path.relative_to(ROOT))}


full = test_result(HERE / 'full-tests.log')
unit = test_result(HERE / 'unit-tests-final.log')
corpus = read(HERE / 'retained-corpus-results.json')
assert len(corpus) == 92
assert all(row['pdfa'] == 'PASS' and row['failures'] == row['expected']
           and row['status'] == ('FAIL' if row['expected'] else 'PASS')
           and row.get('clauses_match', True) for row in corpus)
manifest = read(HERE / 'verification-inputs.json')
checked, changed_docs, changed_inputs, changed_frozen = [], [], [], []
for name, expected in manifest.items():
    path = ROOT / name
    frozen = ROOT / '.eapdf-verification-v11' / name
    frozen_actual = hashlib.sha256(frozen.read_bytes()).hexdigest() if frozen.exists() else None
    if frozen_actual != expected:
        changed_frozen.append({'file': name, 'frozen_sha256': expected, 'actual_sha256': frozen_actual})
    actual = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
    if actual != expected:
        row = {'file': name, 'frozen_sha256': expected, 'current_sha256': actual}
        if path.suffix == '.md' or name.startswith('docs/'):
            changed_docs.append(row)
        else:
            changed_inputs.append(row)
    else:
        checked.append(name)
integrity = {'frozen_snapshot': '.eapdf-verification-v11', 'manifest': 'verification-inputs.json',
             'unchanged_files': len(checked), 'changed_runtime_inputs': changed_inputs,
             'changed_frozen_inputs': changed_frozen,
             'documentation_changes_after_freeze': changed_docs}
assert not changed_frozen, changed_frozen
write(HERE / 'verification-integrity.json', integrity)

shared = {'batch': 'V11', 'authority': 'validator_rule_text.json',
    'scope': ['first creator item', 'exact-GUID-owned attachment count presence',
              'creator/producer provenance', 'supplied NumberMessages type',
              'size-comparison semantic prerequisite', 'separate Table 11 ISO expectations'],
    'tests': {'full_packaged': full, 'unit': unit},
    'retained_corpus': {'cases': len(corpus), 'pdfa_passes': len(corpus),
                        'exact_outcomes_matched': len(corpus), 'results': 'retained-corpus-results.json'},
    'new_pdf_probes': {'cases': 12, 'results': 'corpus-results-final.json'},
    'entry_points': {'checks': 6, 'results': 'entry-points.json',
                     'boundary': 'First seven-case fixture revision; initial freeze retained separately.'},
    'source_boundary': {'manifest': 'verification-inputs.json', 'capsule': 'runtime-inputs.zip',
                        'integrity': 'verification-integrity.json'},
    'creator_artifact': read(IMPROVEMENT / 'creator-iso-structure/artifact-comparison.json')['new_sha256'],
    'inventory': {'records': 301, 'file_records': 249, 'rule_codes': 224,
                  'unmapped_mandatory_conditional_file_records': 0, 'open_expert_dependency_questions': 11},
    'limits': ['Mapping is not implementation completeness or conformance certification.',
               'Eleven expert/dependency questions and mixed-rule/ownership review remain open.',
               'Optional official PREMIS schema is unavailable.',
               'Earlier incomplete and failed logs remain historical evidence.']}
write(HERE / 'evidence.json', shared)

scopes = {
    '07': ('canonical PDF Declarations namespace and schema',
           ['before-tests.log', 'java-before.log', 'focused-tests.log', 'maven-package.log',
            'sample-regeneration.log', 'active-fixtures-before.zip', 'samples-before.zip']),
    '08': ('verified execution of unchanged upstream ISO 32005 rules',
           ['entry-points.json', 'retained-corpus-results.json', 'packaging-comparison.json',
            'packaging-correction-test.log', 'verification-inputs.json']),
    '09': ('source-independent Table 18 types, dateTime value space and precise source comparison',
           ['before-tests.log', 'comparison-before-tests.log', 'corpus-results.json',
            'focused-tests.log', 'verification-inputs.json']),
    '10': ('semantic Table 15 identification values',
           ['before-tests.log', 'type-diagnostics-initial.log', 'corpus-results.json',
            'focused-tests.log', 'verification-inputs.json'])}
for batch, (scope, links) in scopes.items():
    directory = IMPROVEMENT / ('validator-' + batch)
    historical = directory / ('interrupted-full-tests.log' if batch == '07' else 'full-tests.log')
    write(directory / 'evidence.json', {'batch': 'V' + batch, 'scope': scope,
        'historical_full_run': test_result(historical), 'retained_evidence': links,
        'cumulative_acceptance': {'evidence': '../validator-11/evidence.json', 'result': full},
        'boundary': 'Cumulative V11 uses the corrected complete creator JAR; earlier runs keep their own source/artifact boundaries.',
        'limitations': shared['limits']})

creator = IMPROVEMENT / 'creator-iso-structure'
images = read(creator / 'image-repair-results.json')
assert len(images) == 2 and all(row['after_status'] == row['after_pdfa'] == row['after_iso'] == 'PASS'
    and row['after_iso_passed_rules'] == 1636 and row['same_figures']
    and row['same_image_bytes'] and row['same_page_content_bytes'] for row in images)
write(creator / 'evidence.json', {'scope': 'Imported mixed Div content and ParentTree ownership',
    'runtime_evidence': 'tracepoints.json', 'source_patch': 'application.patch',
    'java': {'passed': 161, 'log': 'maven-package.log'},
    'targeted_correction': test_result(creator / 'correction-tests.log'),
    'images': images, 'artifacts': read(creator / 'artifact-comparison.json'),
    'saved_samples': {'profiles': ['1s', '1si', '1m', '1mi', '1c', '1ci'],
                      'pdfa_and_complete_eapdf': 'PASS', 'log': 'sample-regeneration.log',
                      'before_archive': 'samples-before.zip'},
    'cumulative_acceptance': {'evidence': '../validator-11/evidence.json', 'result': full},
    'limits': shared['limits']})
print(json.dumps({'full': full, 'unit': unit, 'corpus': len(corpus),
                  'changed_runtime_inputs': changed_inputs}, indent=2))
