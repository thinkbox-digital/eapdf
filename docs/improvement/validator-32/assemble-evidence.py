"""Assert the bounded V32 title recommendation evidence."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v32'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 585 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 8 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json')
prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-31/custom-regressions.json').read_text())}
assert len(custom)==284 and len(prior)==276 and all(r['before']==r['after'] for r in custom)
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:
        assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
assert sum(r['title_before']!=r['title_after'] for r in custom)==3
pipeline=read('pdf-results.json')
assert len(pipeline)==18 and all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] and r['status']=='PASS' and not r['failures'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'}
after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==8
for key in before:assert before[key]['sha256']==after[key]['sha256']
assert sum(before[k]['title_level']=='PASS' and after[k]['title_level']=='WARN' for k in before)==3
assert sum('reused_before_report' in r for r in pipeline)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
retained=read('retained-title-preconditions.json')
assert len(retained['rows'])==12 and all(r['unchanged_title_predicate'] and r['reused_v29_pipeline_outcome']['status']=='FAIL' for r in retained['rows'])
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text())
assert len(rules)==224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['inventory_records'])==301
assert len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions'])==11
observations=read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases']==284 and observations['summary']['complete_pipeline_controls']==8
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-31/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V32 explicit x-default Text in the title recommendation; empty Text remains permitted',
 verification=dict(unit_tests=dict(run=585,passed=545,skipped=40,log='unit-tests.log'),focused_tests=8,
 custom_comparisons=dict(cases=284,prior_pdf_hashes_and_outcomes_unchanged=276,recommendation_levels_corrected=3,status_or_failure_changes=0),
 pdf_probes=dict(cases=8,identical_bytes_before_after=True,pdfa_and_iso_pass=8,recommendation_pass_to_warn=3,reused_exact_v31_before_runs=4,new_before_after_engine_runs=12),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(native_title_predicates_checked=12,reused_exact_v29_engine_runs=12,new_engine_runs=0,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v32',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False),
 observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['Title absence/ambiguity remains a recommendation warning; no mandatory conformance failure added.',
 'The initially assumed empty-default WARN was corrected from the primary XMP Text definition; original expectations remain.',
 'Generic XMP serialization and mandatory Info/XMP crosswalk are separate review boundaries.',
 'Forty skips, eleven decisions, broader obligation/shared coverage and creator defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and 'next-metadata-type-probes' not in p.parts}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V32 accepted: 545 unit passes; 284 custom cases; eight exact-byte title controls; three recommendation PASS results corrected to WARN.')
