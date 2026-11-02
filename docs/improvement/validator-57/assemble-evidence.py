"""Accept guarded DPM description decoding with explicit unknown evidence."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v57-r2';CODE='DPM_MAIL_MESSAGEID_MATCH'
read=lambda n:json.loads((HERE/n).read_text());digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json');assert len(manifest)==1357
for name,value in manifest.items():
 assert digest(SNAPSHOT/name)==value,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):assert digest(ROOT/name)==value,name
assert re.search(r'Ran 908 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 15 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
assert 'FAILED (errors=7)' in (HERE/'final-before-focused-tests.log').read_text()
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-56/custom-regressions.json').read_text())};assert len(custom)==522 and len(prior)==514
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['before']==row['after']==prior[row['file']]['after']
assert sum(r['before']!=r['after'] for r in custom)==3
pipeline=read('pdf-results.json');assert len(pipeline)==30
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8 and sum('reused_before_report' in r for r in pipeline)==8
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-messageid-decoding/expectations.json').read_text())['cases']
current={Path(r['file']).stem:r for r in custom if '/validator-messageid-decoding/' in r['file']}
for case in cases:
 key=Path(case['file']).stem;a=after[key];b=before[key];c=current[key];native=read('control-native-evidence/'+key+'.json')
 assert a['sha256']==b['sha256']==c['sha256']==case['sha256']==native['sha256']
 assert dict(status=a['status'],failures=a['failures'])==c['after']==dict(status=case['expected_status'],failures=case['expected_failures'])
 assert dict(status=b['status'],failures=b['failures'])==c['before']
 report=read('pdf-reports/'+key+'-after.json');items=[i for i in report['items'] if i['code']==CODE]
 assert len(items)==1 and items[0]['level']==case['expected_messageid']
 assert {i['level'] for i in report['items'] if i['code']=='DPM_BODY_MEDIA_TYPE_MATCH_SOURCE'}=={'PASS'}
 if case['expected_messageid']=='N/A':assert native['present'] is False
 if case['expected_messageid']=='WARN':
  assert native['decode_error']=='UnicodeDecodeError' and 'VALIDATION_ERROR' in b['failures']
  assert 'VALIDATION_ERROR' not in a['failures']
  assert any(i['code']=='VALIDATION_INCOMPLETE' and CODE in i['details'].get('rules',[]) for i in report['items'])
assert len({r['sha256'] for r in after.values()})==8
assert sum(before[k]['status']!=after[k]['status'] for k in before)==2
assert sum(before[k]['failures']!=after[k]['failures'] for k in before)==3
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
 if row['entry'] in ('cli','web-worker'):
  case=next(c for c in cases if Path(c['file']).stem==row['file']);assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'};baseline={r['file']:r for r in read('creator-baseline.json')['profiles']};assert len(retained)==len(baseline)==12
for key,row in retained.items():assert row['sha256']==baseline[key]['sha256'] and row['status']=='FAIL' and row['failures']==baseline[key]['failures']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());spec=rules['VALIDATION_INCOMPLETE']['specification_review'];assert len(rules)==224 and len(spec['inventory_records'])==301 and len(spec['expert_questions'])==11
obs=read('observation-matrix.json');assert obs['summary']['custom_pdf_cases']==522 and obs['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-56/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V57 guarded optional DPM MessageID decoding and source identity selection',
 verification=dict(unit_tests=dict(run=908,passed=868,skipped=40),focused_tests=15,before_focused=dict(errors=7),
  custom_comparisons=dict(cases=522,prior_hashes_statuses_failure_sets_retained=514,decoder_exceptions_corrected=3,corrected_incomplete_results=2,independent_revision_failure_retained=1),
  pdf_probes=dict(cases=8,distinct_pdf_hashes=8,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=8,reused_exact_v55_before_runs=8,fresh_after_runs=8,overall_status_changes=2),
  entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(fresh_engine_runs=12,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v57-r2',files=len(manifest),manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=obs['summary'],matrix='observation-matrix.json'),
 limits=['Rejected supplied MessageID text remains unavailable. Optional absence, readable whitespace/global-candidate policy, wrong classes and readable contradictions remain distinct.',
 'Readable GUID precedence retains body-source checks. Unreadable local identity without a readable GUID cannot select an enclosing email.',
 'The first candidate guarded only the mandatory comparison; a real pipeline failure exposed the second consumer. Initial candidate receipts are preserved and do not certify this final runtime.',
 'Forty integration/schema skips, eleven listed questions, DPM GUID decoding, lexical/source ownership review, namespace/Table9 scope, XMP group binding and known creator failures remain open.'])
review=read('candidate-review.json');assert digest(HERE/'initial-candidate/runtime-inputs.zip')==review['initial_archive_sha256']
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V57 accepted: 868 unit passes; 522 custom cases, eight controls, two entry points and twelve fresh creator diagnoses match.')
