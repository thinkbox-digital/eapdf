"""Accept canonical DPart identity selection without restricting generic metadata."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v59'
read=lambda n:json.loads((HERE/n).read_text());digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json');assert len(manifest)==1411
for name,value in manifest.items():
 assert digest(SNAPSHOT/name)==value,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):assert digest(ROOT/name)==value,name
assert re.search(r'Ran 936 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 10 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
assert 'FAILED (failures=13)' in (HERE/'before-focused-tests.log').read_text()
assert read('prior-alias-census.json')==dict(cases_reviewed=532,matching_cases=0,cases=[])
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-58/custom-regressions.json').read_text())};assert len(custom)==540 and len(prior)==532
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['before']==row['after']==prior[row['file']]['after']
assert sum(r['before']!=r['after'] for r in custom)==5
pipeline=read('pdf-results.json');assert len(pipeline)==30
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8 and sum('reused_before_report' in r for r in pipeline)==8
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-dpm-extension-identity/expectations.json').read_text())['cases']
current={Path(r['file']).stem:r for r in custom if '/validator-dpm-extension-identity/' in r['file']}
corrected=[]
for case in cases:
 key=Path(case['file']).stem;a=after[key];b=before[key];c=current[key];native=read('control-native-evidence/'+key+'.json')
 assert a['sha256']==b['sha256']==c['sha256']==case['sha256']==native['sha256']
 assert dict(status=a['status'],failures=a['failures'])==c['after']==dict(status=case['expected_status'],failures=case['expected_failures'])
 assert dict(status=b['status'],failures=b['failures'])==c['before']
 report=read('pdf-reports/'+key+'-after.json');levels=lambda code:{i['level'] for i in report['items'] if i['code']==code}
 if key!='canonical-guid-mismatch':
  assert a['status']=='PASS'
  for code in ('DPM_BODY_MEDIA_TYPE_MATCH_SOURCE','ATTACHMENT_ANNOTATIONS_PRESENT','DPM_MAIL_GUID_MATCH','MAIL_MESSAGE_GUID_MATCH_DPM_XMP'):assert levels(code)=={'PASS'}
 else:
  assert a['status']==b['status']=='FAIL' and a['failures']==b['failures']
  assert levels('DPM_MAIL_GUID_MATCH')==levels('MAIL_MESSAGE_GUID_MATCH_DPM_XMP')=={'FAIL'}
 if b['status']!=a['status']:
  assert b['status']=='FAIL' and b['failures']==['ATTACHMENT_ANNOTATIONS_PRESENT'] and a['status']=='PASS'
  assert all(not item['identity']['Mail_GUID'] for item in native['before_body_identities'])
  corrected.append(key)
assert len(corrected)==5
assert len({r['sha256'] for r in after.values()})==8
assert sum(before[k]['status']!=after[k]['status'] for k in before)==5
assert sum(before[k]['failures']!=after[k]['failures'] for k in before)==5
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
 if row['entry'] in ('cli','web-worker'):
  case=next(c for c in cases if Path(c['file']).stem==row['file']);assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'};baseline={r['file']:r for r in read('creator-baseline.json')['profiles']};assert len(retained)==len(baseline)==12
for key,row in retained.items():assert row['sha256']==baseline[key]['sha256'] and row['status']=='FAIL' and row['failures']==baseline[key]['failures']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());spec=rules['VALIDATION_INCOMPLETE']['specification_review'];assert len(rules)==224 and len(spec['inventory_records'])==301 and len(spec['expert_questions'])==11
obs=read('observation-matrix.json');assert obs['summary']['custom_pdf_cases']==540 and obs['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-58/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V59 exact Table7 DPart identity keys without generic extension alias substitution',
 verification=dict(unit_tests=dict(run=936,passed=896,skipped=40),focused_tests=10,before_focused=dict(failures=13),
  custom_comparisons=dict(cases=540,prior_hashes_statuses_failure_sets_retained=532,false_failures_corrected=5),
  pdf_probes=dict(cases=8,distinct_pdf_hashes=8,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=8,reused_exact_v58_before_runs=8,fresh_after_runs=8,overall_status_changes=5),
  entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(fresh_engine_runs=12,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v59',files=len(manifest),manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=obs['summary'],matrix='observation-matrix.json'),
 limits=['Only the DPart identity selector is narrowed to Table7 keys. Generic metadata remains stored; shared XMP/source alias and current whitespace handling are preserved.',
 'Known invalid canonical-owner annotation behavior remains separately unverified. A canonical GUID mismatch still retains its existing mandatory failures.',
 'Forty integration/schema skips, eleven listed questions, broader source-owner/occurrence review, namespace/Table9 scope, XMP group binding and known creator failures remain open.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V59 accepted: 896 unit passes; 540 custom cases, eight controls, two entry points and twelve fresh creator diagnoses match.')
