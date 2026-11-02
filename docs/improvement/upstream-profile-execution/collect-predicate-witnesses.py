"""Retain actual successful check contexts instead of equating PASS with coverage."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v25'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as v

output = HERE / 'predicate-witnesses'
output.mkdir(exist_ok=True)
cases = json.loads((ROOT / 'tests/fixtures/eapdf/upstream-profile-controls/cases.json').read_text())['cases']
rules = json.loads((SNAPSHOT / 'validator_rule_text.json').read_text())
rows = []
profile_results = []
for case in cases:
    if not case['file'].endswith('-valid.pdf'):
        continue
    path = ROOT / 'tests/fixtures/eapdf/upstream-profile-controls' / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    command = v._verapdf_command(path, 'json') + ['--success']
    process = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (output / (path.stem + '.stderr')).write_text(process.stderr)
    assert process.returncode == 0, (path.name, process.returncode, process.stderr)
    payload = json.loads(process.stdout[process.stdout.index('{'):])
    (output / (path.stem + '.json')).write_text(json.dumps(payload, indent=2) + '\n')
    report = payload['report']
    versions = {r['id']: r['version'] for r in report['buildInformation']['releaseDetails']}
    pin = rules['ISO32005_UPSTREAM']['validation_scope']['external_profile']['reported_versions']
    assert all(versions.get(k) == value for k, value in pin.items()), versions
    assert len(report['jobs']) == 1
    job = report['jobs'][0]
    validation = job['validationResult'][0]
    assert len(job['validationResult']) == 1 and validation['compliant'] is True and validation['jobEndStatus'] == 'normal'
    assert validation['profileName'] == case['profile']
    summaries = validation['details']['ruleSummaries']
    assert summaries and any(s.get('passedChecks', 0) > 0 for s in summaries)
    profile = 'PDFA-' + case['file'].split('-')[0].upper()
    by_reference = {}
    for summary in summaries:
        spec = re.search(r'19005[-_ ](\d)', summary['specification'])
        assert spec, summary['specification']
        key = ('ISO_19005_' + spec.group(1), str(summary['clause']), str(summary['testNumber']), summary['object'])
        assert key not in by_reference
        by_reference[key] = summary
    for code, rule in rules.items():
        references = rule['validation_scope'].get('profile_rule_references', {}).get(profile, [])
        for reference in references:
            key = tuple(str(reference[k]) for k in ('specification', 'clause', 'test_number', 'object'))
            summary = by_reference.get(key)
            contexts = [c.get('context') for c in (summary or {}).get('checks', []) if c.get('status') == 'passed']
            count = (summary or {}).get('passedChecks', 0)
            rows.append(dict(code=code, ownership=rule['validation_scope']['decision'], profile=profile,
                             reference=reference, passed_checks=count, passed_contexts=contexts,
                             evidence_state='actual-object-witness' if count else 'no-object-witness-in-control',
                             caveat='Generic predicate witnesses do not establish the uncovered EA-PDF assertion or all property/value forms.'))
    profile_results.append(dict(profile=profile, pdf=case['file'], sha256=case['sha256'],
                                summary_rows=len(summaries), object_checks=validation['details']['passedChecks']))
    print(profile, len(summaries), 'predicate summaries retained', flush=True)
assert len(profile_results) == 5
summary = dict(scope='Actual predicate witnesses in five independent PDF/A geometric controls; no coverage certification',
               runtime_snapshot='.eapdf-verification-v25', profiles=profile_results, reference_rows=len(rows),
               evidence_states=dict(Counter(r['evidence_state'] for r in rows)),
               authority_sha256=hashlib.sha256((SNAPSHOT / 'validator_rule_text.json').read_bytes()).hexdigest(), rows=rows)
(output / 'reference-witnesses.json').write_text(json.dumps(summary, indent=2) + '\n')
print('Recorded actual and absent object witnesses for', len(rows), 'profile-rule references.', flush=True)
