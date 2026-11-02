"""Assert V39 message content/GUID consistency controls and fresh creator diagnoses."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v39'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 658 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 17 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-38/custom-regressions.json').read_text())}
assert len(custom)==367 and len(prior)==358 and sum(r['before']!=r['after'] for r in custom)==4
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
pipeline=read('pdf-results.json');assert len(pipeline)==32
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==9
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-message-owners/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem;a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
assert sum(before[k]['status']=='PASS' and after[k]['status']=='FAIL' for k in before)==3
assert sum(before[k]['status']=='PASS' and after[k]['status']=='INCOMPLETE' for k in before)==0
assert sum('reused_before_report' in r for r in pipeline)==2
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'}
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text())
assert len(retained)==12
for r in diagnosis['profiles']:
    key=r['mode']+'-'+r['profile'];row=retained[key]
    assert row['status']=='FAIL' and row['failures']==r['failures']
    original=json.loads((ROOT/r['report']).read_text())
    assert digest(Path(original['summary']['file']))==row['sha256']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());review=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(review['inventory_records'])==301 and len(review['expert_questions'])==11
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==367 and observations['summary']['complete_pipeline_controls']==9
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-38/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V39 Mail_Message GUID consistency with actual connected DPart email content',
 verification=dict(unit_tests=dict(run=658,passed=618,skipped=40,log='unit-tests.log'),focused_tests=17,
 custom_comparisons=dict(cases=367,prior_pdf_hashes_and_outcomes_unchanged=358,reviewed_outcome_changes=4),
 pdf_probes=dict(cases=9,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=9,false_pipeline_passes_corrected=3,unsupported_complete_passes_now_incomplete=0,reused_exact_v38_before_runs=2,new_control_before_after_engine_runs=16),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(fresh_engine_runs=12,all_six_profiles_both_modes=True,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v39',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['Owner correspondence is an explicit reviewed inference from EA-PDF9.6/9.7 Tables7/13, not a new literal sentence or GUID grammar.',
 'Missing/ambiguous/unsupported content and traversal limits remain unverified; known universal contradictions still fail.',
 'Shared Form and annotation cases have focused helper/production-rule evidence, not standalone complete PDF/A fixture certification.',
 'Internal content identity does not prove original-source rendering fidelity or occurrence assignment.',
 'Forty skips, eleven listed expert questions, broader coverage and known creator defects remain.'])
assert before['swapped-dpm-guids']['failures']==['ATTACHMENT_ANNOTATIONS_PRESENT']
assert after['swapped-dpm-guids']['failures']==['ATTACHMENT_ANNOTATIONS_PRESENT','MAIL_MESSAGE_GUID_MATCH_DPM_XMP']
assert sum(r['status']=='PASS' for r in after.values())==5

evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V39 accepted: 618 unit passes, 367 custom cases; three global-membership false passes fail; nine engine controls, two entry points and twelve fresh retained creator diagnoses match.')
