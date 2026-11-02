"""Bind the fixture-only coverage checkpoint to executed frozen inputs."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
read = lambda path: json.loads(path.read_text())
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
manifest = read(HERE / 'verification-inputs.json')
prior = read(ROOT / 'docs/improvement/validator-64/verification-inputs.json')
for name, value in manifest.items():
    assert sha(ROOT / '.eapdf-verification-v65' / name) == value, name
    if name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py') or name.startswith('src/main/java/'):
        assert value == prior[name] == sha(ROOT / name), name
assert re.search(r'Ran 974 tests in [0-9.]+s\s+OK \(skipped=40\)', (HERE / 'unit-tests.log').read_text())
custom = read(HERE / 'custom-regressions.json')
pipeline = read(HERE / 'pdf-results.json')
assert len(custom) == 603 and len(pipeline) == 26
assert sum('inherited_from' in row for row in custom) == 591
assert all(row['before'] == row['after'] for row in custom)
assert all(row['pdfa'] == 'PASS' for row in pipeline)
assert sum(row['iso'] == 'N/A' for row in pipeline) == 2
assert all(row['iso'] == 'N/A' or row['iso'] == 'PASS' and row['profile_verified'] for row in pipeline)
assert sum(row['entry'] == 'retained-profile' and row['status'] == 'FAIL' for row in pipeline) == 12
observations = read(HERE / 'observation-matrix.json')
assert observations['summary']['local_without_non_na'] == []
browser = read(ROOT / 'docs/improvement/rule-browser-verification.json')
assert len(browser['checks']) == 16 and sha(ROOT / browser['artifact']) == browser['sha256']
evidence = dict(checkpoint='V65', date='2026-10-21', status='accepted', scope='Independent fixture branch coverage; runtime and authoritative rules unchanged.',
    verification=dict(unit_tests=dict(run=974, passed=934, skipped=40), custom_cases=603,
        inherited_unchanged_runtime_hash_verified_cases=591, freshly_executed_custom_controls=12,
        actual_pdfa_controls=12, verified_iso_controls=10, pdf17_iso_not_applicable_controls=2,
        production_entry_points=2, retained_creator_known_failures=12,
        offline_browser_checks_reused_unchanged_artifact=16, local_assertions_without_non_na_observation=0),
    inputs=dict(manifest='verification-inputs.json', archive='runtime-inputs.zip', archive_sha256=sha(HERE / 'runtime-inputs.zip')),
    limits=['Code observations do not certify every linked obligation or every legal representation.',
        'Two PDF1.7 collection controls deliberately omit navigation, DPart tree and collection members; all independent failures are retained.',
        'Full obligation-level review, normative-edition equivalence and source inventory completeness remain open.',
        'Creator header proposal is deferred and not applied. Experimental Java tests are separate evidence, not creator acceptance.'],
    observation_summary=observations['summary'])
evidence['artifacts'] = {str(path.relative_to(HERE)): sha(path) for path in sorted(HERE.rglob('*')) if path.is_file() and path.name != 'evidence.json'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('V65 fixture-only acceptance verified.')
