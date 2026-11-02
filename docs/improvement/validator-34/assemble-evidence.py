"""Assert V34 crosswalk evidence across actual PDF/A-3A and 3U profiles."""
import hashlib,json,re
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v34'
read=lambda n:json.loads((HERE/n).read_text())
digest=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
manifest=read('verification-inputs.json')
for name,expected in manifest.items():
    assert digest(SNAPSHOT/name)==expected,name
    if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('tests/'):
        assert digest(ROOT/name)==expected,name
assert re.search(r'Ran 603 tests in [0-9.]+s\s+OK \(skipped=40\)',(HERE/'unit-tests.log').read_text())
assert re.search(r'Ran 8 tests in [0-9.]+s\s+OK',(HERE/'focused-tests.log').read_text())
custom=read('custom-regressions.json')
prior={r['file']:r for r in json.loads((ROOT/'docs/improvement/validator-33/custom-regressions.json').read_text())}
assert len(custom)==308 and len(prior)==296 and sum(r['before']!=r['after'] for r in custom)==9
for row in custom:
    assert digest(SNAPSHOT/row['file'])==row['sha256']
    if row['file'] in prior:assert row['sha256']==prior[row['file']]['sha256'] and row['after']==prior[row['file']]['after']
pipeline=read('pdf-results.json')
assert len(pipeline)==26 and all(r['pdfa']=='PASS' and r['iso']=='N/A' and r['detected_profile'] in ('PDF/A-3A validation profile','PDF/A-3U validation profile') for r in pipeline)
before={r['file']:r for r in pipeline if r['entry']=='before'};after={r['file']:r for r in pipeline if r['entry']=='after'}
assert len(before)==len(after)==12
cases=json.loads((SNAPSHOT/'tests/fixtures/eapdf/validator-docinfo-crosswalk/expectations.json').read_text())['cases']
for case in cases:
    key=Path(case['file']).stem;a,b=after[key],before[key]
    assert a['sha256']==b['sha256']==case['sha256']
    assert a['status']==case['expected_status'] and a['failures']==case['expected_failures']
    assert a['detected_profile']==b['detected_profile']==case['expected_pdfa_profile']
assert sum(before[k]['status']=='PASS' and after[k]['status']=='FAIL' for k in before)==3
assert sum(before[k]['status']=='FAIL' and after[k]['status']=='PASS' for k in before)==3
assert sum(before[k]['status']=='INCOMPLETE' and after[k]['status']=='PASS' for k in before)==2
assert sum(r['entry']=='cli' for r in pipeline)==sum(r['entry']=='web-worker' for r in pipeline)==1
for row in pipeline:
    report=read('pdf-reports/'+row['file']+'-'+row['entry']+'.json')
    versions=report['pdfa']['raw_json']['report']['buildInformation']['releaseDetails']
    assert next(v['version'] for v in versions if v['id']=='apps')=='1.31.18'
retained=read('retained-pdfa4-preconditions.json')
assert len(retained['rows'])==12 and all(r['changed_branch_inapplicable'] and r['pdfa_part']=='4' and r['reused_v29_pipeline_outcome']['status']=='FAIL' for r in retained['rows'])
rules=json.loads((SNAPSHOT/'validator_rule_text.json').read_text());records=rules['VALIDATION_INCOMPLETE']['specification_review']['inventory_records']
assert len(rules)==224 and len(records)==301 and len(rules['VALIDATION_INCOMPLETE']['specification_review']['expert_questions'])==11
assert 'DOCINFO_KEYWORDS' in next(r for r in records if r['id']=='INFO-XMP-CROSSWALK')['candidate_rule_codes']
observations=read('observation-matrix.json');assert observations['summary']['custom_pdf_cases']==308 and observations['summary']['complete_pipeline_controls']==12
jar=ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar';assert digest(jar)==json.loads((ROOT/'docs/improvement/validator-33/evidence.json').read_text())['creator']['jar_sha256']
evidence=dict(scope='V34 PDF/A-3 Info/XMP target language, Keywords synchronization and optional-key applicability',
 verification=dict(unit_tests=dict(run=603,passed=563,skipped=40,log='unit-tests.log'),focused_tests=8,
 custom_comparisons=dict(cases=308,prior_pdf_hashes_and_outcomes_unchanged=296,reviewed_outcome_changes=9),
 pdf_probes=dict(cases=12,identical_bytes_before_after=True,pdfa_pass=12,actual_profiles=['PDF/A-3A','PDF/A-3U'],iso32005_inapplicable=12,false_pipeline_passes_corrected=3,false_comparison_failures_corrected=3,false_incomplete_results_corrected=2,new_before_after_engine_runs=24),
 entry_points=dict(cli=1,isolated_workers=1,all_expected_outcomes=True),retained_creator_profiles=dict(pdfa4_branch_inapplicability_checked=12,reused_exact_v29_engine_runs=12,new_engine_runs=0,creator_acceptance=False)),
 inputs=dict(snapshot='.eapdf-verification-v34',manifest='verification-inputs.json',files=len(manifest),runtime_archive='runtime-inputs.zip',runtime_archive_sha256=digest(HERE/'runtime-inputs.zip')),
 creator=dict(unchanged=True,jar_sha256=digest(jar),new_java_build=False),observations=dict(summary=observations['summary'],matrix='observation-matrix.json',review='observation-review.md'),
 limits=['ISO32005 is inapplicable to the PDF1.7 controls; no positive ISO run is inferred from N/A.',
 'The Table7 primary-document content was reviewed in the browser, with source/edition and local corroboration retained; binary download failed HTTP403 and no source hash is invented.',
 'Initial failed PDF/A-3 fixture attempts and the null-ISO-details verifier error remain separately preserved.',
 'Generic XMP order, text whitespace/date policy, forty skips, eleven decisions, broader coverage and creator defects remain.'])
evidence['artifacts']={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.rglob('*')) if p.is_file() and p.name!='evidence.json' and p.suffix!='.zip' and 'next-info-type-probes' not in p.parts}
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V34 accepted: 563 unit passes; 308 custom cases; twelve actual PDF/A-3A/3U controls; eight false pass/fail/incomplete results corrected.')
