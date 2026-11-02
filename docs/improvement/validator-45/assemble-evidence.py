"""Accept V45 recommendation observations without weakening conformance outcomes."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v45'
CODE='MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 744 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 10 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-44/custom-regressions.json').read_text())}
assert len(custom)==427 and len(prior)==419 and all(r['before']==r['after'] for r in custom)
prior_changes=[];new_changes=[]
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:
        assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
    if row['recommendation_before']!=row['recommendation_after']:
        assert row['recommendation_before']==['PASS'] and row['recommendation_after']==['WARN']
        (prior_changes if row['file'] in prior else new_changes).append(row['file'])
assert len(new_changes)==4 and prior_changes
pipeline=read('pdf-results.json');assert len(pipeline)==30
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-subtype-recommendation/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem;a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==b['status']==case['expected_status'] and a['failures']==b['failures']==case['expected_failures']
    assert a['recommendation']==[case['expected_recommendation']]
assert sum(before[k]['recommendation']==['PASS'] and after[k]['recommendation']==['WARN'] for k in before)==4
assert sum('reused_before_report' in r for r in pipeline)==8
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
    if row['entry'] in ('cli','web-worker'):
        case=next(c for c in cases if Path(c['file']).stem==row['file'])
        assert row['status']==case['expected_status'] and row['failures']==case['expected_failures'] and row['recommendation']==[case['expected_recommendation']]
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'}
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text());assert len(retained)==12
for profile in diagnosis['profiles']:
    key=profile['mode']+'-'+profile['profile'];row=retained[key]
    assert row['status']=='FAIL' and row['failures']==profile['failures']
    original=json.loads((ROOT/profile['report']).read_text());assert digest(Path(original['summary']['file']))==row['sha256']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());review=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(review['inventory_records'])==301 and len(review['expert_questions'])==11
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==427 and observations['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-44/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V45 Mail_ContentSet occurrence defines the subtype SHOULD recommendation; conformance strength is preserved',
 verification=dict(unit_tests=dict(run=744,passed=704,skipped=40,log='unit-tests.log'),focused_tests=10,
 custom_comparisons=dict(cases=427,prior_pdf_hashes_statuses_and_failure_sets_unchanged=419,new_case_conformance_changes=0,prior_recommendation_passes_now_warn=len(prior_changes),new_recommendation_passes_now_warn=len(new_changes)),
 pdf_probes=dict(cases=8,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=8,conformance_statuses_and_failure_sets_unchanged=True,recommendation_passes_now_warn=4,reused_exact_v44_before_runs=8,new_control_after_engine_runs=8),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(fresh_engine_runs=12,all_six_profiles_both_modes=True,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v45',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),
 observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 recommendation_changes=dict(prior=prior_changes,new=new_changes),
 limits=['This SHOULD guidance neither requires a DPM subtype where Table8 permits omission nor creates a new conformance failure. Supplied property names, byte encoding, value type/matching and unknown attributes retain separate assessments.',
 'Optional FrontMatter controls establish internal metadata correspondence, not source/vendor fidelity or complete logical coverage.',
 'Other scalar consumers, media-specific semantics, property-name operational decoding, XMP group binding, source occurrence assignment, forty skips, eleven listed questions and known creator defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V45 accepted: 704 unit passes, 427 custom cases; all conformance outcomes preserved,',len(prior_changes),'prior and four new recommendation passes now warn; eight controls, two entry points and twelve fresh creator runs match.')
