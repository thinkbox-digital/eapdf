"""Accept typed unique parent MessageID binding while retaining required GUID errors."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v60'
read=lambda n:json.loads((HERE/n).read_text());digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json');assert len(manifest)==1436
for name,value in manifest.items():
 assert digest(SNAPSHOT/name)==value,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):assert digest(ROOT/name)==value,name
assert re.search(r'Ran 945 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 9 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
assert 'FAILED (failures=5)' in (HERE/'final-before-focused-tests.log').read_text()
review=read('prior-owner-census.json');assert review['cases_reviewed']==540 and review['changed_cases']==3
reviewed={r['file']:r for r in review['cases']}
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-59/custom-regressions.json').read_text())};assert len(custom)==548 and len(prior)==540
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] not in prior:continue
 old=prior[row['file']];assert row['sha256']==old['sha256'] and row['before']==old['after']
 assert row['after']['status']==old['after']['status']
 if row['file'] in reviewed:
  proof=reviewed[row['file']];assert row['sha256']==proof['sha256'] and row['before']==proof['before'] and row['after']==proof['expected_after']
  assert row['after']==dict(status='FAIL',failures=['DPM_MAIL_GUID_PRESENT'])
  assert row['observed_levels']['ATTACHMENT_ANNOTATIONS_PRESENT']==['PASS']
  assert all(x['old_body_nodes']==[] and x['new_body_nodes'] and all(b['visible_matching_annotations'] and all(n>0 for n in b['native_sizes']) for b in x['proof']) for x in proof['changes'])
 else:assert row['after']==old['after']
assert sum(r['before']!=r['after'] for r in custom)==7
assert all(r['before']['status']==r['after']['status'] for r in custom)
pipeline=read('pdf-results.json');assert len(pipeline)==30
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8 and sum('reused_before_report' in r for r in pipeline)==8
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-attachment-owner-mid/expectations.json').read_text())['cases']
current={Path(r['file']).stem:r for r in custom if '/validator-attachment-owner-mid/' in r['file']}
for case in cases:
 key=Path(case['file']).stem;a=after[key];b=before[key];c=current[key];native=read('control-native-evidence/'+key+'.json')
 assert a['sha256']==b['sha256']==c['sha256']==case['sha256']==native['sha256']
 assert dict(status=a['status'],failures=a['failures'])==c['after']==dict(status=case['expected_status'],failures=case['expected_failures'])
 assert dict(status=b['status'],failures=b['failures'])==c['before']
 report=read('pdf-reports/'+key+'-after.json');items=[i for i in report['items'] if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT']
 assert len(items)==1 and items[0]['level']==case['expected_annotation']
 if key in ('empty-guid','name-guid','decoded-empty-guid','integer-guid'):
  assert b['failures']==['ATTACHMENT_ANNOTATIONS_PRESENT','DPM_MAIL_GUID_PRESENT']
  assert a['failures']==['DPM_MAIL_GUID_PRESENT'] and a['status']=='FAIL'
  assert len(native['email_identities'])==1 and native['email_identities'][0]['Message-ID']
  assert all(not body['identity']['Mail_GUID'] and body['identity']['Message-ID']==native['email_identities'][0]['Message-ID'] for body in native['body_sets'])
 if key=='outside-annotations':
  assert all('content_set_pages' in x for x in items[0]['details']['actual'])
  assert native['annotation_values'] and all(not x['within_page_box'] for x in native['annotation_values'])
 if key=='unreadable-guid':
  assert a['status']=='INCOMPLETE'
  assert any(i['code']=='VALIDATION_INCOMPLETE' and 'ATTACHMENT_ANNOTATIONS_PRESENT' in i['details'].get('rules',[]) for i in report['items'])
 if key=='conflicting-guid':assert a['failures']==b['failures'] and 'DPM_MAIL_GUID_MATCH' in a['failures']
assert len({r['sha256'] for r in after.values()})==8
assert sum(before[k]['status']!=after[k]['status'] for k in before)==0
assert sum(before[k]['failures']!=after[k]['failures'] for k in before)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
 if row['entry'] in ('cli','web-worker'):
  case=next(c for c in cases if Path(c['file']).stem==row['file']);assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'};baseline={r['file']:r for r in read('creator-baseline.json')['profiles']};assert len(retained)==len(baseline)==12
for key,row in retained.items():assert row['sha256']==baseline[key]['sha256'] and row['status']=='FAIL' and row['failures']==baseline[key]['failures']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());spec=rules['VALIDATION_INCOMPLETE']['specification_review'];assert len(rules)==224 and len(spec['inventory_records'])==301 and len(spec['expert_questions'])==11
obs=read('observation-matrix.json');assert obs['summary']['custom_pdf_cases']==548 and obs['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-59/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V60 typed unique parent MessageID binding for GUID-owned attachment annotations',
 verification=dict(unit_tests=dict(run=945,passed=905,skipped=40),focused_tests=9,before_focused=dict(failures=5),
  custom_comparisons=dict(cases=548,prior_hashes_and_statuses_retained=540,prior_failure_sets_unchanged=537,prior_annotation_failures_removed=3,new_annotation_failures_removed=4,required_guid_failures_retained=True),
  pdf_probes=dict(cases=8,distinct_pdf_hashes=8,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=8,reused_exact_v59_before_runs=8,fresh_after_runs=8,overall_status_changes=0),
  entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(fresh_engine_runs=12,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v60',files=len(manifest),manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=obs['summary'],matrix='observation-matrix.json'),
 limits=['Only a normal text MessageID unique across email records activates the new GUID-bound-parent path. Existing no-GUID handling and the current lexical policy remain unchanged.',
 'Known missing geometry failures and readable GUID contradictions retain their force. Unreadable GUID identity does not fall back to this parent MessageID.',
 'Unbound, ambiguous and typed-invalid owner diagnostics remain separately unverified; negative guard tests assert absence of a new PASS rather than certifying legacy failure severity.',
 'Forty integration/schema skips, eleven listed questions, broader source-owner/occurrence review, namespace/Table9 scope, XMP group binding and known creator failures remain open.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V60 accepted: 905 unit passes; 548 custom cases, eight controls, two entry points and twelve fresh creator diagnoses match.')
