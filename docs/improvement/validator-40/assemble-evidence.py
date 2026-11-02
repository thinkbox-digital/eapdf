"""Assert raw-name/ASCII encoding controls and exact upstream reuse boundaries."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v40'
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda name:json.loads((HERE/name).read_text())
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
 assert digest(SNAPSHOT/name)==expected,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
  assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 666 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 8 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-39/custom-regressions.json').read_text())}
assert len(custom)==377 and len(prior)==367 and sum(r['before']!=r['after'] for r in custom)==5
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
pipeline=read('pdf-results.json');assert len(pipeline)==22
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==10
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-userproperty-names/expectations.json').read_text())['cases']
for case in cases:
 key=Path(case['file']).stem;a,b=after[key],before[key]
 assert a['sha256']==b['sha256']==case['sha256']
 assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
assert all(row['status']=='PASS' for row in before.values())
assert sum(row['status']=='FAIL' for row in after.values())==5
assert sum('reused_before_report' in r for r in pipeline)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
retained=read('retained-profile-preconditions.json')['rows'];assert len(retained)==12
profiles={r['mode']+'-'+r['profile']:r for r in json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']}
def check_guard(guard):
 assert guard['raw_ascii_and_exact_name_guards_pass']
 for child in guard['children']:check_guard(child)
for row in retained:
 check_guard(row['native_guards'])
 assert row['current_custom_status']=='FAIL' and row['current_custom_failures']==profiles[row['profile']]['failures']
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
 assert digest(ROOT/row['reused_upstream_report'])==row['reused_report_sha256']
 original=json.loads((ROOT/profiles[row['profile']]['report']).read_text())
 assert digest(Path(original['summary']['file']))==row['sha256']==row['native_guards']['sha256']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());review=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(review['inventory_records'])==301 and len(review['expert_questions'])==11
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==377 and observations['summary']['complete_pipeline_controls']==10
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-39/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V40 exact UserProperties N names and raw ASCII string encoding',verification=dict(unit_tests=dict(run=666,passed=626,skipped=40),focused_tests=8,custom_comparisons=dict(cases=377,prior_pdf_hashes_and_outcomes_unchanged=367,reviewed_outcome_changes=5),pdf_probes=dict(cases=10,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=10,false_pipeline_passes_corrected=5,legal_controls=5,reused_exact_v39_before_runs=4,new_control_before_after_engine_runs=16),entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(current_custom_runs=12,native_raw_name_guards_include_collection_children=True,reused_fresh_v39_upstream_diagnoses=12,new_creator_engine_runs=0,known_failed_conformance_retained=12,creator_acceptance=False)),inputs=dict(snapshot='.eapdf-verification-v40',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),limits=['Encoding conformance and exact name presence remain separate; no printable-only or closed extension-name policy is imposed.','Unicode V values retain their earlier text comparison policy; this batch does not certify all cross-encoding/GUID semantics.','An attribute O text-string probe already remains INCOMPLETE; it is not counted as a newly corrected false PASS.','The initial freeze attempt expected a different source-manifest key; it stopped before copying, and the successful final archive pins the seed explicitly.','Forty skips, eleven listed questions, broader coverage, source occurrences and known creator defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V40 accepted: 626 unit passes, 377 custom cases; five false passes corrected; ten engine controls and two entry points match; twelve creator diagnoses retain known failures.')
