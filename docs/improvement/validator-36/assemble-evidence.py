"""Assert V36 optional entry and unavailable-evidence controls, plus fresh original profile diagnoses."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v36'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 621 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 10 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json');prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-35/custom-regressions.json').read_text())}
assert len(custom)==334 and len(prior)==320 and sum(r['before']!=r['after'] for r in custom)==10
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
pipeline=read('pdf-results.json');assert len(pipeline)==42
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==14
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-dpart-entries/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem;a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
assert sum(before[k]['status']=='PASS' and after[k]['status']=='FAIL' for k in before)==8
assert sum(before[k]['status']=='PASS' and after[k]['status']=='INCOMPLETE' for k in before)==2
assert sum('reused_before_report' in r for r in pipeline)==8
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
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==334 and observations['summary']['complete_pipeline_controls']==14
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-35/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V36 supplied optional DPart entry types/counts and explicit unavailable XML-name evidence',
 verification=dict(unit_tests=dict(run=621,passed=581,skipped=40,log='unit-tests.log'),focused_tests=10,
 custom_comparisons=dict(cases=334,prior_pdf_hashes_and_outcomes_unchanged=320,reviewed_outcome_changes=10),
 pdf_probes=dict(cases=14,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=14,false_pipeline_passes_corrected=8,unsupported_complete_passes_now_incomplete=2,reused_exact_v35_before_runs=8,new_control_before_after_engine_runs=20),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(fresh_engine_runs=12,all_six_profiles_both_modes=True,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v36',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['Optional entries may be absent; PDF/VT-specific presence and API ASCII restrictions are not applied.',
 'NMTOKEN-only or undecodable labels remain INCOMPLETE; the narrower inherited wording and encoding review is not closed.',
 'RecordLevel meaning/range and complete DPart metadata errata remain separate.',
 'Initial probe and focused marker failures are retained; final frozen tests and actual worker verify incomplete status.',
 'Forty skips, eleven listed expert questions, broader coverage, source occurrences and creator defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and 'next-dpart-metadata-probes' not in p.parts}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V36 accepted: 581 unit passes; 334 custom cases; fourteen controls; eight false passes fail and two remain explicitly incomplete; twelve fresh creator diagnoses retain known failures.')
