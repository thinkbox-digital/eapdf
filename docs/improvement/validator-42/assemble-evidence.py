"""Accept only the frozen V42 gates, preserving explicit source/creator limits."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]; HERE=Path(__file__).resolve().parent; SNAPSHOT=ROOT/'.eapdf-verification-v42'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith(('tests/','scripts/')):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 707 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
for name,count in [('focused-tests.log',17),('subtype-owner-focused-tests.log',7)]:
    assert re.search(r'Ran '+str(count)+r' tests in [0-9.]+s\s+OK',(HERE/name).read_text())
custom=read('custom-regressions.json'); prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-41/custom-regressions.json').read_text())}
assert len(custom)==403 and len(prior)==387 and sum(r['before']!=r['after'] for r in custom)==9
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior: assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
pipeline=read('pdf-results.json'); assert len(pipeline)==46
assert all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'}; after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==16
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-active-userproperties/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem; a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
assert sum(before[k]['status']=='PASS' and after[k]['status']=='FAIL' for k in before)==6
assert sum(before[k]['status']=='INCOMPLETE' and after[k]['status']=='FAIL' for k in before)==1
assert sum(before[k]['status']=='FAIL' and after[k]['status']=='INCOMPLETE' for k in before)==2
assert sum('reused_before_report' in r for r in pipeline)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
retained={r['file']:r for r in pipeline if r['entry']=='retained-profile'}
diagnosis=json.loads((ROOT/'docs/improvement/validator-24/profile-diagnosis.json').read_text()); assert len(retained)==12
for r in diagnosis['profiles']:
    key=r['mode']+'-'+r['profile']; row=retained[key]
    assert row['status']=='FAIL' and row['failures']==r['failures']
    original=json.loads((ROOT/r['report']).read_text()); assert digest(Path(original['summary']['file']))==row['sha256']
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text()); review=rules['VALIDATION_INCOMPLETE']['specification_review']
assert len(rules)==224 and len(review['inventory_records'])==301 and len(review['expert_questions'])==11
audit=json.loads((HERE/'audit-generation.log').read_text()); assert audit['inventory_records']==301 and audit['current_rule_codes']==224 and audit['unlinked_rule_codes']==[]
observations=read('observation-matrix.json'); assert observations['summary']['custom_pdf_cases']==403 and observations['summary']['complete_pipeline_controls']==16
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'; assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-41/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V42 individual active UserProperties occurrences, actual name owner and connected subtype correspondence',
 verification=dict(unit_tests=dict(run=707,passed=667,skipped=40,log='unit-tests.log'),focused_tests=24,
 custom_comparisons=dict(cases=403,prior_pdf_hashes_and_outcomes_unchanged=387,reviewed_outcome_changes=9),
 pdf_probes=dict(cases=16,identical_bytes_before_after=True,pdfa_and_verified_iso_pass=16,false_pipeline_passes_corrected=6,known_required_owner_failure_previously_incomplete=1,false_comparison_failures_now_incomplete=2,reused_exact_v41_before_runs=4,new_control_before_after_engine_runs=28),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(fresh_engine_runs=12,all_six_profiles_both_modes=True,pdfa_and_iso_pass=12,known_failed_conformance_retained=12,creator_acceptance=False),
 audit_generator=dict(order_only_consistency_assertion_repaired=True,all_301_records_224_codes_linked=True)),
 inputs=dict(snapshot='.eapdf-verification-v42',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False,new_sample_generation=False),
 observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['The subtype formatting normalization policy remains separately reviewable; other scalar consumers, complete logical Content Set coverage, original-source fidelity, source occurrence assignment and FrontMatter nesting interpretation remain open.',
 'Optional FrontMatter subtype controls establish internal declared correspondence, not source/vendor format fidelity.',
 'Whole-P-array replacement across eligible attribute objects/classes/revisions is preserved; no within-array duplicate-name ban is introduced.',
 'XMP group binding remains unavailable where supplied legacy candidate fields have no reviewed mapping.',
 'Forty skips, eleven listed questions, broader coverage and known creator operand/child defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and not any(part.startswith('next-') for part in p.relative_to(HERE).parts)}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V42 accepted: 667 unit passes, 403 custom cases, sixteen controls, two entry points and twelve fresh creator runs; nine reviewed outcomes corrected.')
