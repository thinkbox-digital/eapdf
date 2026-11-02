"""Accept guarded N decoding with data failures replacing operational exceptions."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v47'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 770 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 14 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-46/custom-regressions.json').read_text())}
assert len(custom)==446 and len(prior)==435
assert all(r['before']['status']==r['after']['status'] for r in custom)
assert sum(r['before']!=r['after'] for r in custom)==7
changed=[]
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:
        assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
    elif row['before']!=row['after']:
        assert row['before']['failures']==['VALIDATION_ERROR']
        assert row['after']['failures']==['USERPROPS_TYPE']
        changed.append(Path(row['file']).stem)
assert set(changed)=={'guid-name-value','guid-array-value','subject-name-value','date-name-value','size-name-value','guid-dictionary-value','subject-dictionary-value'}
pipeline=read('pdf-results.json');assert len(pipeline)==36
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==11
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-property-value-diagnostics/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem;a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==b['status']==case['expected_status'] and a['failures']==case['expected_failures']
    assert 'VALIDATION_ERROR' not in a['failures']
assert sum(before[k]['failures']==['VALIDATION_ERROR'] for k in before)==7
assert sum('reused_before_report' in r for r in pipeline)==11
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
    if row['entry'] in ('cli','web-worker'):
        case=next(c for c in cases if Path(c['file']).stem==row['file'])
        assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'}
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text());assert len(retained)==12
for profile in diagnosis['profiles']:
    key=profile['mode']+'-'+profile['profile'];row=retained[key]
    assert row['status']=='FAIL' and row['failures']==profile['failures']
    original=json.loads((ROOT/profile['report']).read_text());assert digest(Path(original['summary']['file']))==row['sha256']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());review=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(review['inventory_records'])==301 and len(review['expert_questions'])==11
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==446 and observations['summary']['complete_pipeline_controls']==11
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-46/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V47 wrong-value diagnostics and guarded owner identity preserve prescribed data failures',
 verification=dict(unit_tests=dict(run=770,passed=730,skipped=40,log='unit-tests.log'),focused_tests=14,
 custom_comparisons=dict(cases=446,prior_pdf_hashes_statuses_and_failure_sets_unchanged=435,all_case_statuses_unchanged=True,operational_failures_replaced_by_prescribed_data_findings=7),
 pdf_probes=dict(cases=11,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=11,all_case_statuses_unchanged=True,operational_failures_replaced_by_data_findings=7,reused_exact_v46_before_runs=11,new_control_after_engine_runs=11),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(fresh_engine_runs=12,all_six_profiles_both_modes=True,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v47',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),
 observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['This fixes definite wrong-value type failures and owner identity coercion, not all unreadable String V paths or complete operational coverage.',
 'Source comparisons retain SHOULD strength and explicit unavailable owners. Other scalar/duplicate/owner policies and non-Mail generic property constraints remain separate.',
 'Raw name checks, field presence, matching uncertainty, Unicode values, source occurrences, logical coverage, media-specific semantics, XMP group binding, forty skips, eleven listed questions and known creator defects retain their separate boundaries.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V47 accepted: 730 unit passes, 446 custom cases; all 435 prior outcomes remain, seven operational failures now report prescribed type violations; eleven controls, two entry points and twelve fresh creator runs match.')
