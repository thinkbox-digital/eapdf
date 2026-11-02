"""Accept guarded GUID evidence without masking definite correspondence failures."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v50'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 811 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 12 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-49/custom-regressions.json').read_text())}
assert len(custom)==470 and len(prior)==462
assert sum(r['before']!=r['after'] for r in custom)==4
changed=[]
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:
        assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
    elif row['before']!=row['after']:
        assert row['before']==dict(status='FAIL',failures=['VALIDATION_ERROR'])
        assert row['after']==(dict(status='FAIL',failures=['MAIL_MESSAGE_GUID_MATCH_DPM_XMP']) if Path(row['file']).stem=='unreadable-guid-and-wrong' else dict(status='INCOMPLETE',failures=[]))
        changed.append(Path(row['file']).stem)
assert set(changed)=={'unreadable-guid','unreadable-guid-before-valid','unreadable-guid-after-valid','unreadable-guid-and-wrong'}
pipeline=read('pdf-results.json');assert len(pipeline)==30
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-guid-decoding/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem;a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
    assert 'VALIDATION_ERROR' not in a['failures']
assert sum(before[k]['status']!=after[k]['status'] for k in before)==3
assert len({r['sha256'] for r in after.values()})==8
assert sum('reused_before_report' in r for r in pipeline)==8
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
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==470 and observations['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-49/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V50 unreadable required GUID evidence stays unavailable without masking definite mismatches',
 verification=dict(unit_tests=dict(run=811,passed=771,skipped=40,log='unit-tests.log'),focused_tests=12,
 custom_comparisons=dict(cases=470,prior_pdf_hashes_statuses_and_failure_sets_unchanged=462,operational_failures_replaced_by_unavailable_assessments=3,operational_failures_replaced_by_definite_mismatch=1),
 pdf_probes=dict(cases=8,distinct_pdf_hashes=8,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=8,operational_failures_replaced=4,reused_exact_v49_before_runs=8,new_control_after_engine_runs=8),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True,worker_retains_incomplete_status=True),
 retained_creator_profiles=dict(fresh_engine_runs=12,all_six_profiles_both_modes=True,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v50',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),
 observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['Unreadable GUID text does not establish identity, absence or a new encoding/type prohibition. Mandatory unknowns retain VALIDATION_INCOMPLETE; known readable contradictions still fail.',
 'This covers Mail_Message GUID presence and every active correspondence occurrence, not all DPM/XMP GUID decoders or general String V encoding validation.',
 'Other unreadable String V fields, source-owner duplicate policies, source occurrences, logical coverage, XMP group binding, forty skips, eleven questions and known creator defects remain open.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V50 accepted: 771 unit passes, 470 custom cases; all 462 prior outcomes remain, three operational GUID failures become incomplete and one reports a definite mismatch; eight controls, two entry points and twelve fresh creator runs match.')
