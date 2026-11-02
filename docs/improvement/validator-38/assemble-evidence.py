"""Assert frozen V38 object/range evidence and exact upstream reuse boundaries."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v38'
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
read=lambda name:json.loads((HERE/name).read_text())
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
 assert digest(SNAPSHOT/name)==expected,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
  assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 641 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 8 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-37/custom-regressions.json').read_text())}
reviewed={r['file']:r for r in read('prior-range-review.json')['rows']}
assert len(reviewed)==5
assert len(custom)==358 and len(prior)==350 and sum(r['before']!=r['after'] for r in custom)==9
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] in prior:
  assert row['sha256']==prior[row['file']]['sha256']
  assert row['after']==(reviewed[row['file']]['after'] if row['file'] in reviewed else prior[row['file']]['after'])
pipeline=read('pdf-results.json');assert len(pipeline)==18
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-dpart-object-ranges/expectations.json').read_text())['cases']
for case in cases:
 key=Path(case['file']).stem;a,b=after[key],before[key]
 assert a['sha256']==b['sha256']==case['sha256']
 assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
assert sum(before[k]['status']=='PASS' and after[k]['status']=='FAIL' for k in before)==4
assert before['leaf-stream-preserved']['failures']==after['leaf-stream-preserved']['failures']==['DPART_OBJECT_STRUCTURE','PAGE_DPART_PRESENT']
assert sum('reused_before_report' in r for r in pipeline)==6
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
prior_pipeline=read('prior-pdf-results.json');assert len(prior_pipeline)==10
for row in prior_pipeline:
 assert row['sha256']==reviewed[row['file']]['sha256']
 assert row['status']=='FAIL' and row['pdfa']=='PASS' and row['iso']=='N/A'
 assert row['failures']==reviewed[row['file']][row['entry']]['failures']
retained=read('retained-profile-preconditions.json')['rows'];assert len(retained)==12
for row in retained:
 assert row['native_guards_pass'] and row['current_custom_status']=='FAIL'
 assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
 assert digest(ROOT/row['reused_upstream_report'])==row['reused_report_sha256']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());review=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(review['inventory_records'])==301 and len(review['expert_questions'])==11
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==358 and observations['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-37/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V38 DPart dictionary object classes, unique page ownership and depth-first leaf order',verification=dict(unit_tests=dict(run=641,passed=601,skipped=40),focused_tests=8,custom_comparisons=dict(cases=358,prior_pdf_hashes_unchanged=350,prior_outcomes_unchanged=345,reviewed_prior_overlap_findings=5,reviewed_outcome_changes=9),pdf_probes=dict(cases=8,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=8,false_pipeline_passes_corrected=4,already_rejected_stream_control_unchanged=1,legal_controls=3,reused_exact_v37_before_runs=6,new_control_before_after_engine_runs=10),prior_negative_pipeline_controls=dict(cases=5,new_before_after_engine_runs=10,all_pdfa_pass=True,iso_inapplicable=True,all_gain_only_proved_page_range_failure=True),entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(current_custom_runs_and_native_guards=12,reused_fresh_v37_upstream_diagnoses=12,new_creator_engine_runs=0,known_failed_conformance_retained=12,creator_acceptance=False)),inputs=dict(snapshot='.eapdf-verification-v38',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip'),initial_runtime_archive_sha256=digest(HERE/'initial-runtime-inputs.zip')),creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),limits=['Five historical container fixture recipes intentionally overlap the same page; native inspection proves their additional PAGE_DPART_PRESENT failures. Their bytes and earlier failures are preserved.',
 'Initial exploratory recipes changed unrelated parent links or Content Set/tag/annotation relationships; all failed attempts remain in V37 next probes and are not accepted positives.','The passing multi-message baseline repairs independently proved operands in fixture copies only; original creator files remain unchanged and failed.','Broader document-part metadata, source ownership/occurrence, forty skips and eleven listed expert questions remain open.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V38 accepted: 601 unit passes, 358 custom cases, four false passes corrected, eight engine controls, two entry points; twelve original creator diagnoses remain failed.')
