"""Accept required GUID decoding and unavailable ownership without false absence."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v58'
read=lambda n:json.loads((HERE/n).read_text());digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json');assert len(manifest)==1386
for name,value in manifest.items():
 assert digest(SNAPSHOT/name)==value,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):assert digest(ROOT/name)==value,name
assert re.search(r'Ran 926 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 18 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
assert 'FAILED (failures=5, errors=5)' in (HERE/'final-before-focused-tests.log').read_text()
assert read('prior-unknown-body-census.json')==dict(cases=0,findings=[])
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-57/custom-regressions.json').read_text())};assert len(custom)==532 and len(prior)==522
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['before']==row['after']==prior[row['file']]['after']
assert sum(r['before']!=r['after'] for r in custom)==4
pipeline=read('pdf-results.json');assert len(pipeline)==34
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==10 and sum('reused_before_report' in r for r in pipeline)==10
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-dpm-guid-decoding/expectations.json').read_text())['cases']
current={Path(r['file']).stem:r for r in custom if '/validator-dpm-guid-decoding/' in r['file']}
for case in cases:
 key=Path(case['file']).stem;a=after[key];b=before[key];c=current[key];native=read('control-native-evidence/'+key+'.json')
 assert a['sha256']==b['sha256']==c['sha256']==case['sha256']==native['sha256']
 assert dict(status=a['status'],failures=a['failures'])==c['after']==dict(status=case['expected_status'],failures=case['expected_failures'])
 assert dict(status=b['status'],failures=b['failures'])==c['before']
 report=read('pdf-reports/'+key+'-after.json')
 levels=lambda code:sorted({i['level'] for i in report['items'] if i['code']==code})
 if key=='unreadable-with-other-owner-mismatch':present=['PASS','WARN'];matched=['FAIL','PASS','WARN']
 elif key.startswith('unreadable'):present=['WARN'];matched=['WARN']
 elif key in ('empty','wrong-name','utf16-decoded-empty'):present=['FAIL'];matched=['WARN']
 elif key=='readable-mismatch':present=['PASS'];matched=['FAIL']
 else:present=['PASS'];matched=['PASS']
 assert levels('DPM_MAIL_GUID_PRESENT')==present and levels('DPM_MAIL_GUID_MATCH')==matched
 if key.startswith('unreadable'):
  assert native['identities'][0]['decode_error']=='UnicodeDecodeError'
  assert 'VALIDATION_ERROR' in b['failures'] and 'VALIDATION_ERROR' not in a['failures']
  assert levels('ATTACHMENT_ANNOTATIONS_PRESENT')==['WARN']
  assert any(i['code']=='VALIDATION_INCOMPLETE' and 'ATTACHMENT_ANNOTATIONS_PRESENT' in i['details'].get('rules',[]) for i in report['items'])
  assert any(i['code']=='VALIDATION_INCOMPLETE' and {'DPM_MAIL_GUID_PRESENT','DPM_MAIL_GUID_MATCH'}<=set(i['details'].get('rules',[])) for i in report['items'])
 if key=='unreadable-with-other-owner-mismatch':
  item=next(i for i in report['items'] if i['code']=='MAIL_MESSAGE_GUID_MATCH_DPM_XMP')
  assert item['level']=='FAIL' and len(item['details']['actual'])==1 and item['details']['unavailable']
assert len({r['sha256'] for r in after.values()})==10
assert sum(before[k]['status']!=after[k]['status'] for k in before)==2
assert sum(before[k]['failures']!=after[k]['failures'] for k in before)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
 if row['entry'] in ('cli','web-worker'):
  case=next(c for c in cases if Path(c['file']).stem==row['file']);assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'};baseline={r['file']:r for r in read('creator-baseline.json')['profiles']};assert len(retained)==len(baseline)==12
for key,row in retained.items():assert row['sha256']==baseline[key]['sha256'] and row['status']=='FAIL' and row['failures']==baseline[key]['failures']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());spec=rules['VALIDATION_INCOMPLETE']['specification_review'];assert len(rules)==224 and len(spec['inventory_records'])==301 and len(spec['expert_questions'])==11
obs=read('observation-matrix.json');assert obs['summary']['custom_pdf_cases']==532 and obs['summary']['complete_pipeline_controls']==10
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-57/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V58 required DPM GUID decoding and unavailable body/structure/global identity evidence',
 verification=dict(unit_tests=dict(run=926,passed=886,skipped=40),focused_tests=18,before_focused=dict(failures=5,errors=5),
  custom_comparisons=dict(cases=532,prior_hashes_statuses_failure_sets_retained=522,decoder_exceptions_corrected=4,corrected_incomplete_results=2,retained_revision_failure=1,proved_other_owner_failure=1),
  pdf_probes=dict(cases=10,distinct_pdf_hashes=10,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=10,reused_exact_v57_before_runs=10,fresh_after_runs=10,overall_status_changes=2),
  entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(fresh_engine_runs=12,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v58',files=len(manifest),manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=obs['summary'],matrix='observation-matrix.json'),
 limits=['Rejected required identity remains unavailable; it does not select a lower-priority MessageID or enclosing email. A partial GUID inventory does not prove absence. Readable contradictions retain their force.',
 'Unknown body owners leave possible annotation applicability unavailable, including beside known missing-annotation failures. Existing known empty/wrong-class/mismatching-owner annotation behavior in four controls remains separately unverified and is retained here.',
 'The first missing-annotation unit probe made the sole attachment owner unreadable and correctly received WARN. The corrected probe makes another owner unreadable so the attachment owner remains known; initial log is retained.',
 'Forty integration/schema skips, eleven listed questions, lexical/source ownership review, namespace/Table9 scope, source occurrences, XMP group binding and known creator failures remain open.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V58 accepted: 886 unit passes; 532 custom cases, ten controls, two entry points and twelve fresh creator diagnoses match.')
