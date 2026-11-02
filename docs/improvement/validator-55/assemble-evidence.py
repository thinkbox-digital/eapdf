"""Accept scoped Art correspondence, retaining independent failures and unknowns."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v55-r2';CODE='MAIL_CONTENTSET_ART_ONLY'
read=lambda n:json.loads((HERE/n).read_text());digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json');assert len(manifest)==1309
initial=read('initial-review/verification-inputs.json')
assert set(initial)==set(manifest)
assert sorted(k for k in initial if initial[k]!=manifest[k])==['docs/improvement/validator-55/prior-art-review.json','docs/improvement/validator-55/rule-review.json']
assert digest(HERE/'initial-review/runtime-inputs.zip')==read('review-correction.json')['initial_runtime_archive_sha256']
for name,value in manifest.items():
 assert digest(SNAPSHOT/name)==value,name
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):assert digest(ROOT/name)==value,name
assert re.search(r'Ran 883 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 21 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
assert 'FAILED (failures=16, errors=1)' in (HERE/'final-before-focused-tests.log').read_text()
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-54/custom-regressions.json').read_text())}
review=read('prior-art-review.json');native={r['file']:r for r in review['cases']}
assert len(custom)==506 and len(prior)==len(native)==500
assert review['summary']==dict(cases=500,changed_outcomes=4,changed_art_levels=7,levels=dict(PASS=490,FAIL=0,WARN=5,**{'N/A':5}))
for row in custom:
 assert digest(SNAPSHOT/row['file'])==row['sha256']
 if row['file'] not in prior:continue
 old=prior[row['file']];proof=native[row['file']]
 assert row['sha256']==old['sha256']==proof['sha256']
 assert row['before']==old['after']==proof['before'] and row['after']==proof['after']
 assert row['after']['status']==old['after']['status']
 assert row['observed_levels'][CODE]==[proof['expected_art']]
 if row['before']!=row['after']:
  assert row['after']['status']=='FAIL'
  assert set(row['before']['failures'])-{CODE}==set(row['after']['failures'])
assert sum(r['before']!=r['after'] for r in custom)==8
pipeline=read('pdf-results.json');assert len(pipeline)==26
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==6 and sum('reused_before_report' in r for r in pipeline)==6
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-art-usage/expectations.json').read_text())['cases']
current={Path(r['file']).stem:r for r in custom if '/validator-art-usage/' in r['file']}
for case in cases:
 key=Path(case['file']).stem;a=after[key];b=before[key];c=current[key]
 assert a['sha256']==b['sha256']==c['sha256']==case['sha256']==read('control-native-evidence/'+key+'.json')['sha256']
 assert dict(status=a['status'],failures=a['failures'])==c['after']==dict(status=case['expected_status'],failures=case['expected_failures'])
 assert dict(status=b['status'],failures=b['failures'])==c['before']
 report=read('pdf-reports/'+key+'-after.json');items=[i for i in report['items'] if i['code']==CODE]
 assert len(items)==1 and items[0]['level']==case['expected_art']
 if case['expected_art']=='WARN':assert any(i['code']=='VALIDATION_INCOMPLETE' and CODE in i['details'].get('rules',[]) for i in report['items'])
assert len({r['sha256'] for r in after.values()})==6
assert sum(before[k]['status']!=after[k]['status'] for k in before)==2
assert sum(before[k]['failures']!=after[k]['failures'] for k in before)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
 if row['entry'] in ('cli','web-worker'):
  case=next(c for c in cases if Path(c['file']).stem==row['file']);assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'};baseline={r['file']:r for r in read('creator-baseline.json')['profiles']};assert len(retained)==len(baseline)==12
for key,row in retained.items():assert row['sha256']==baseline[key]['sha256'] and row['status']=='FAIL' and row['failures']==baseline[key]['failures']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());spec=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(spec['inventory_records'])==301 and len(spec['expert_questions'])==11
obs=read('observation-matrix.json');assert obs['summary']['custom_pdf_cases']==506 and obs['summary']['complete_pipeline_controls']==6
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-54/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V55 actual Art Content Set counterparts, including implicit Mail_ContentSet Art',
 verification=dict(unit_tests=dict(run=883,passed=843,skipped=40),focused_tests=21,before_focused=dict(failures=16,errors=1),
  custom_comparisons=dict(cases=506,prior_hashes_and_statuses_retained=500,prior_failure_sets_unchanged=496,unsupported_art_failures_removed=4,prior_art_levels_reviewed=7),
  pdf_probes=dict(cases=6,distinct_pdf_hashes=6,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=6,reused_exact_v54_before_runs=6,fresh_after_runs=6,overall_status_changes=2),
  entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(fresh_engine_runs=12,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v55-r2',files=len(manifest),manifest='verification-inputs.json',runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=obs['summary'],matrix='observation-matrix.json'),
 limits=['Actual counterparts do not certify complete logical encapsulation, all operations, outline semantics or a tag-count bijection. Other direct role aliases and unsupported namespaces remain unavailable.',
 'Standard-only controls retain the pre-existing missing ROLEMAP_MAIL_TAGS/ROLEMAPNS_PRESENT applicability decisions. The open SPEC-ROLEMAPNS-SCOPE question forbids inventing a blanket PDF/A-4 namespace exception.',
 'Initial standard-conversion controls failed ISO because field roles were replaced incorrectly; preserved in excluded V54 next-art-probes/initial-standard-conversion. Final controls preserve the existing field roles and all pass PDF/A/verified ISO.',
 'Forty integration/schema skips, eleven listed questions, source occurrences, XMP group binding and known creator failures remain open.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V55 accepted: 843 unit passes; 506 custom cases, six controls, two entry points and twelve fresh creator diagnoses match.')
