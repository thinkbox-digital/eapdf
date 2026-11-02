"""Accept V22 only after every retained gate and input hash is verified."""
from pathlib import Path
import difflib
import hashlib
import json
import re
import zipfile

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v22'
PREVIOUS=ROOT/'.eapdf-verification-v21-final'


def read(name):return json.loads((HERE/name).read_text())
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()


manifest=read('verification-inputs.json')
old_manifest=read('../validator-21/verification-inputs.json')
for name,value in manifest.items():
    assert digest(Path(name) if Path(name).is_absolute() else FROZEN/name)==value,name
    if Path(name).is_absolute() or not name.startswith('docs/'):
        assert digest(Path(name) if Path(name).is_absolute() else ROOT/name)==value,name
    if Path(name).is_absolute() or name.startswith('src/'):
        assert value==old_manifest[name],name
unit=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',(HERE/'unit-tests.log').read_text())
assert unit and (int(unit[1]),int(unit[3]))==(479,39)
profiles=[]
for mode,folder in [('normal',HERE/'profiles'),('accessibility',HERE/'profiles/accessibility')]:
    for profile in ('1s','1si','1m','1mi','1c','1ci'):
        result=json.loads((folder/(profile+'.json')).read_text())
        iso=next(i for i in result['items'] if i['code']=='ISO32005_UPSTREAM')
        assert result['summary']['status']==result['pdfa']['status']==iso['level']=='PASS'
        assert iso['details']['profile_verified']
        profiles.append(dict(mode=mode,profile=profile,status='PASS',pdfa='PASS',iso='PASS'))
for log in ('generated-profiles.log','accessibility-profiles.log'):
    assert re.search(r'Ran 1 test in [\d.]+s\s+OK',(HERE/log).read_text()),log
cases={c['file']:c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-ambiguous-media/expectations.json').read_text())['cases']}
prior_case=next(c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-equivalence/expectations.json').read_text())['cases'] if c['file']=='isolated-ambiguous-media.pdf')
cases[prior_case['file']]=prior_case
prior_case=next(c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-streams/expectations.json').read_text())['cases'] if c['file']=='ambiguous-records.pdf')
cases[prior_case['file']]=prior_case
pdfs=read('pdf-results.json');assert len(pdfs)==32
assert len({(r['file'],r['entry']) for r in pdfs})==32
for row in pdfs:
    assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
    assert row['sha256']==cases[row['file']]['sha256']
    report=read('pdf-reports/'+Path(row['file']).stem+'-'+row['entry']+'.json')
    assert report['summary']['status']==row['status']
    assert sorted({i['code'] for i in report['items'] if i['level']=='FAIL'})==row['failures']
    if row['file']!='legal-parameters-control.pdf':
        assert any(i['code']=='VALIDATION_INCOMPLETE' and i['level']=='WARN' for i in report['items'])
    if row['entry']=='before':
        expected_before=('PASS' if row['file']=='legal-parameters-control.pdf' else
                         'FAIL' if row['file']=='ambiguous-records.pdf' else 'INCOMPLETE')
        assert row['status']==expected_before
        assert row['failures']==(['ATTACHMENT_XMP_MATCH'] if row['file']=='ambiguous-records.pdf' else [])
    else:
        assert row['status']==cases[row['file']]['expected_status']
        assert row['failures']==cases[row['file']]['expected_failures']
        if cases[row['file']].get('required_parameter_warning'):
            assert any(i['code']=='SOURCE_MEDIATYPE_PARAMETERS_MATCH' and i['level']=='WARN' for i in report['items'])
comparison=[]
for name in cases:
    old=next(r for r in pdfs if r['entry']=='before' and r['file']==name)
    new=next(r for r in pdfs if r['entry']=='after' and r['file']==name)
    comparison.append(dict(file=name,sha256=old['sha256'],before_status=old['status'],before_failures=old['failures'],
                           after_status=new['status'],after_failures=new['failures']))
(HERE/'before-after-comparison.json').write_text(json.dumps(comparison,indent=2)+'\n')
regressions=read('custom-regressions.json')
changed=[r for r in regressions if r['before']!=r['after']]
expected={'one-type-wrong.pdf','all-parameters-wrong.pdf','distinct-types-impossible.pdf',
          'distinct-parameters-wrong.pdf','malformed-parameters-wrong.pdf','matching-parameters-wrong-type.pdf',
          'isolated-ambiguous-media.pdf','ambiguous-records.pdf'}
assert len(regressions)==194 and {Path(r['file']).name for r in changed}==expected
severity_changed={Path(r['file']).name for r in regressions if r['before_media_rules']!=r['after_media_rules']}
assert severity_changed==expected|{'parameters-compatible.pdf','malformed-parameters-compatible.pdf'}
prior=read('../validator-21/custom-regressions.json');assert len(prior)==184
lookup={r['file']:r for r in regressions}
for row in prior:
    current=lookup[row['file']]
    assert digest(PREVIOUS/row['file'])==current['sha256']
    assert current['before']==row['after']
    if Path(row['file']).name not in ('isolated-ambiguous-media.pdf','ambiguous-records.pdf'):
        assert current['after']==row['after']
        assert current['before_media_rules']==current['after_media_rules']
assert '194 outcomes verified;' in (HERE/'custom-regressions.log').read_text()
archive=HERE/'runtime-inputs-final.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as bundle:
    for name in sorted(manifest):
        if not Path(name).is_absolute() and not name.startswith(('docs/','examples/')) and Path(name).suffix!='.md':
            assert Path(name).name!='credentials.json'
            bundle.write(FROZEN/name,name)
patch=[]
for name in sorted(manifest):
    if Path(name).is_absolute() or name.startswith(('docs/','examples/')) or Path(name).suffix not in ('.py','.json','.md'):
        continue
    old=(PREVIOUS/name).read_text() if (PREVIOUS/name).exists() else ''
    new=(FROZEN/name).read_text()
    if old!=new:patch.extend(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='v21/'+name,tofile='v22/'+name))
(HERE/'application.patch').write_text(''.join(patch))
evidence=dict(scope='V22 media and parameter contradictions across ambiguous attachment candidates',
 verification=dict(unit_tests=dict(run=479,passed=440,skipped=39,seconds=float(unit[2]),log='unit-tests.log'),
                   pdf_probes=dict(cases=12,new_cases=10,prior_changed_cases=2,pdfa_passes=12,iso_passes=12,
                                   same_bytes_before_and_after=True,results='before-after-comparison.json'),
                   generated_profiles=profiles,
                   custom_comparisons=dict(total=194,prior_pdf_hashes_unchanged=184,prior_outcomes_unchanged=182,
                                           prior_outcomes_intentionally_changed=2,all_changed=8,media_rule_severity_changes=10,unavailable_parameter_improvements=2,results='custom-regressions.json'),
                   entry_points=dict(checks=8,cli=4,isolated_web_workers=4,all_expected_outcomes_match=True,results='pdf-results.json')),
 inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs.json',files=len(manifest),
             archive=archive.name,archive_sha256=digest(archive)),
 creator=dict(unchanged=True,jar_sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')],new_java_build=False),
 limits=['Eleven expert/dependency questions remain open.',
         'Only universally contradicted media/parameter values fail. Ambiguous declared-parameter comparisons remain unavailable, not N/A.',
         'A matching or unreadable candidate blocks that field failure; no owner, unique occurrence or original inventory is inferred.',
         'No new Maven build or full packaged-suite acceptance is claimed. Integration/schema skips remain explicit.'],
 artifacts={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.glob('*.py'))})
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V22 accepted: 440 unit passes / 39 skips; twelve PDF probes; twelve profiles; 194 comparisons; eight entry-point checks.')
