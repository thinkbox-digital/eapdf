"""Verify all accepted V85 receipts and bind them to frozen runtime inputs."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda name:json.loads((HERE/name).read_text())
manifest=read('verification-inputs.json')
assert sha(ROOT/'validate_eapdf.py')==manifest['validate_eapdf.py']
assert sha(ROOT/'validator_rule_text.json')==manifest['validator_rule_text.json']
assert re.search(r'Ran 1155 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
custom=read('custom-regressions.json');pipeline=read('pdf-results.json')
assert len(custom)==774 and len(pipeline)==26
assert read('prior-differences.json')==[]
assert all(c['before']==c['after'] for c in custom[:766])
assert all(p['pdfa']==p['iso']=='PASS' and p['profile_verified'] for p in pipeline)
assert sum(p['entry']=='retained-profile' and p['status']=='FAIL' for p in pipeline)==12
browser=json.loads((ROOT/'docs/improvement/rule-browser-verification.json').read_text())
assert len(browser['checks'])==16 and not browser['javascript_errors'] and not browser['external_requests']
assert sha(ROOT/browser['artifact'])==browser['sha256']
for name,value in json.loads((ROOT/'docs/improvement/validator-84/verification-inputs.json').read_text()).items():
 if name.startswith('src/main/java/'):assert sha(ROOT/name)==value
new=custom[766:]
evidence=dict(checkpoint='V85',date='2026-10-22',status='accepted',verification=dict(unit_tests=dict(run=1155,passed=1115,skipped=40),custom_cases=774,prior_hashes_statuses_failure_sets_retained=766,complete_pipeline_controls=12,production_entry_points=2,retained_creator_known_failures=12,offline_browser_checks=16,incomplete_cases_now_definite_failures=sum(c['before']['status']=='INCOMPLETE' and c['after']['status']=='FAIL' for c in new),false_comparison_failures_removed=sum(c['before']['status']=='FAIL' and c['after']['status']=='PASS' for c in new),false_source_comparison_passes_rejected=sum(c['before']['status']=='PASS' and c['after']['status']=='FAIL' for c in new),unsupported_passes_now_incomplete=sum(c['before']['status']=='PASS' and c['after']['status']=='INCOMPLETE' for c in new),unsupported_failures_now_incomplete=sum(c['before']['status']=='FAIL' and c['after']['status']=='INCOMPLETE' for c in new),parser_aborts_now_schema_failures=sum('VALIDATION_ERROR' in c['before']['failures'] and c['after']['status']=='FAIL' for c in new)),inputs=dict(manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',archive_sha256=sha(HERE/'runtime-inputs.zip'),validator_sha256=sha(ROOT/'validate_eapdf.py'),rules_sha256=sha(ROOT/'validator_rule_text.json')),creator=dict(source_unchanged=True,new_build=False,new_samples=False,acceptance=False),limits=['Eleven V61 decisions remain local interpretations, not official errata.','Table 19 source occurrence comparisons permit either understood MIME filename parameter and preserve nonempty original whitespace. Alternative names do not create extra occurrences. Unknown filename encodings, duplicate MIME parameters and complete ownership remain open.','Full obligation coverage, cited draft-edition equivalence, ambiguous occurrence assignment and creator repairs remain open.'],observation_summary=read('observation-matrix.json')['summary'])
evidence['artifacts']={str(p.relative_to(HERE)):sha(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and not any(t.startswith('next-') for t in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V85 acceptance evidence verified.')
