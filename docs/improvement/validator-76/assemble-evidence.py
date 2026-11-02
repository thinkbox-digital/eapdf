"""Bind fresh collection checks and explicitly inherited V75 receipts."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda name:json.loads((HERE/name).read_text())
manifest=read('verification-inputs.json');prior_path=ROOT/'docs/improvement/validator-75'
prior_manifest=json.loads((prior_path/'verification-inputs.json').read_text())
assert all(sha(ROOT/name)==manifest[name]==prior_manifest[name] for name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py'))
checked=[]
for name,value in prior_manifest.items():
 if name.startswith(('src/main/java/','scripts/','tests/')):
  assert manifest[name]==value==sha(ROOT/name),(name,value)
  checked.append(name)
assert not {name for name in manifest if name.startswith('tests/') and name.endswith('.py')}-set(prior_manifest)
assert re.search(r'Ran 1060 tests in [0-9.]+s\s+OK \(skipped=40\)',(prior_path/'unit-tests.log').read_text())
custom=read('custom-regressions.json');pipeline=read('pdf-results.json');native=read('native-preservation-review.json')
assert len(custom)==695 and len(pipeline)==12 and len(native)==10
assert sum(c['execution'].startswith('fresh') for c in custom)==10
assert all(c['execution'].startswith('inherited-V75') for c in custom[:685])
assert all(p['pdfa']==p['iso']=='PASS' and p['profile_verified'] for p in pipeline)
assert sum(p['entry']=='after' and p['status']=='PASS' for p in pipeline)==4
assert sum(p['entry']=='after' and p['status']=='INCOMPLETE' for p in pipeline)==2
assert sum(p['entry']=='after' and p['status']=='FAIL' for p in pipeline)==4
browser_path=ROOT/'docs/improvement/rule-browser-verification.json';browser=json.loads(browser_path.read_text())
assert len(browser['checks'])==16 and not browser['javascript_errors'] and not browser['external_requests']
assert sha(ROOT/browser['artifact'])==browser['sha256']
prior_evidence=json.loads((prior_path/'evidence.json').read_text())
assert prior_evidence['inputs']['validator_sha256']==sha(ROOT/'validate_eapdf.py') and prior_evidence['inputs']['rules_sha256']==sha(ROOT/'validator_rule_text.json')
prior_profiles=[r for r in json.loads((prior_path/'pdf-results.json').read_text()) if r['entry']=='retained-profile']
assert len(prior_profiles)==12 and all(r['status']=='FAIL' and sha(ROOT/r['file'])==r['sha256'] for r in prior_profiles)
reuse=dict(source_checkpoint='V75',boundary='Unit tests, browser checks, retained creator diagnoses and 685 custom observations are inherited, not freshly executed in V76.',
           source_evidence_sha256=sha(prior_path/'evidence.json'),unit_log_sha256=sha(prior_path/'unit-tests.log'),browser_report_sha256=sha(browser_path),browser_artifact_sha256=browser['sha256'],
           unchanged_runtime={name:manifest[name] for name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py')},unchanged_prior_source_script_test_and_fixture_inputs=checked,
           retained_creator_profile_hashes={r['key']:r['sha256'] for r in prior_profiles})
(HERE/'inherited-evidence.json').write_text(json.dumps(reuse,indent=2)+'\n')
evidence=dict(checkpoint='V76',date='2026-10-21',status='accepted',verification=dict(
 unit_tests=dict(run=1060,passed=1020,skipped=40,execution='inherited-V75'),custom_cases=695,custom_cases_fresh=10,custom_cases_inherited=685,
 prior_hashes_statuses_failure_sets_retained=685,complete_pipeline_controls=10,production_entry_points=2,retained_creator_known_failures=12,
 retained_creator_execution='inherited-V75',offline_browser_checks=16,browser_execution='inherited-V75-exact-artifact',
 positive_normal_and_accessibility_collections=4,incomplete_child_controls=2,failed_or_corrupt_child_controls=4,native_preservation_reviews=10),
 inputs=dict(manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',archive_sha256=sha(HERE/'runtime-inputs.zip'),validator_sha256=sha(ROOT/'validate_eapdf.py'),rules_sha256=sha(ROOT/'validator_rule_text.json')),
 creator=dict(source_unchanged=True,new_build=False,new_samples=False,acceptance=False),
 limits=['Independent native fixture edits do not repair or accept creator output.','Custom-only collections remain incomplete without child PDF/A checks; complete pipeline results retain independent parent/child engine evidence.','Four constructed normal/accessibility c/ci parents pass; full preservation provenance, human accessibility, all obligation branches and cited draft-edition equivalence remain unverified.'],
 observation_summary=read('observation-matrix.json')['summary'])
evidence['artifacts']={str(p.relative_to(HERE)):sha(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json'}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V76 fresh collection receipts and bounded V75 inheritance verified.')
