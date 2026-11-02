"""Assert the bounded V33 title recommendation evidence."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.eapdf-verification-v33'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 595 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 10 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json')
prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-32/custom-regressions.json').read_text())}
assert len(custom)==296 and len(prior)==284 and sum(r['before']!=r['after'] for r in custom)==7
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:
        assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
assert all(r['title_before']==r['title_after'] for r in custom)
pipeline=read('pdf-results.json')
assert len(pipeline)==26 and all(r['pdfa']==r['iso']=='PASS' and r['profile_verified'] for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'}
after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==12
for key in before:assert before[key]['sha256']==after[key]['sha256']
assert sum(before[k]['status']=='PASS' and after[k]['status']=='FAIL' for k in before)==7
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-required-metadata/expectations.json').read_text())['cases']
for case in cases:
    row=after[Path(case['file']).stem]
    assert row['status']==case['expected_status'] and row['failures']==case['expected_failures']
assert sum('reused_before_report' in r for r in pipeline)==4
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
retained=read('retained-metadata-preconditions.json')
assert len(retained['rows'])==12 and all(r['added_guards'] and r['reused_v29_pipeline_outcome']['status']=='FAIL' for r in retained['rows'])
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text())
assert len(rules)==224 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['inventory_records'])==301
assert len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions'])==11
observations=read('observation-matrix.json')
assert observations['summary']['custom_pdf_cases']==296 and observations['summary']['complete_pipeline_controls']==12
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar'
assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-32/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V33 required standard XMP creator, producer and creation-date value types',
 verification=dict(unit_tests=dict(run=595,passed=555,skipped=40,log='unit-tests.log'),focused_tests=10,
 custom_comparisons=dict(cases=296,prior_pdf_hashes_and_outcomes_unchanged=284,reviewed_outcome_changes=7),
 pdf_probes=dict(cases=12,identical_bytes_before_after=True,pdfa_and_iso_pass=12,false_pipeline_passes_corrected=7,reused_exact_v32_before_runs=4,new_before_after_engine_runs=20),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),
 retained_creator_profiles=dict(native_predicates_checked=12,reused_exact_v29_engine_runs=12,new_engine_runs=0,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v33',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False),
 observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['Required standard fields only; general XMP serialization and arbitrary RDF retain separate coverage.',
 'Reduced precision and unknown timezones remain valid; no product-name grammar or timestamp equality rule is introduced.',
 'Public XMP definitions support this bounded review; no complete ISO 16684-1:2019 equivalence is claimed.',
 'Forty skips, eleven decisions, crosswalk review, broader obligation/shared coverage and creator defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and 'next-pdfa3-crosswalk-probes' not in p.parts}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V33 accepted: 555 unit passes; 296 custom cases; twelve exact-byte controls; seven false pipeline passes corrected.')
