"""Accept V23 only after immutable inputs and every retained gate agree."""
from pathlib import Path
import difflib
import hashlib
import json
import re
import zipfile

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v23'
PREVIOUS=ROOT/'.eapdf-verification-v22'
PARAMS='SOURCE_MEDIATYPE_PARAMETERS_MATCH'


def read(name):return json.loads((HERE/name).read_text())
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def parameter_level(report):
    order={'N/A':0,'PASS':1,'WARN':2,'FAIL':3}
    return max((i['level'] for i in report['items'] if i['code']==PARAMS),key=order.get,default='N/A')


manifest=read('verification-inputs.json');old_manifest=read('../validator-22/verification-inputs.json')
for name,value in manifest.items():
    assert digest(Path(name) if Path(name).is_absolute() else FROZEN/name)==value,name
    if Path(name).is_absolute() or not name.startswith('docs/'):
        assert digest(Path(name) if Path(name).is_absolute() else ROOT/name)==value,name
    if Path(name).is_absolute() or name.startswith('src/'):assert value==old_manifest[name],name
unit=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',(HERE/'unit-tests.log').read_text())
assert unit and (int(unit[1]),int(unit[3]))==(487,40)
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
cases={c['file']:c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-unmatched-parameters/expectations.json').read_text())['cases']}
prior_case=next(c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-streams/expectations.json').read_text())['cases'] if c['file']=='unknown-alias.pdf')
cases[prior_case['file']]=prior_case
before_levels={'unknown-alias-parameters.pdf':'N/A','unknown-alias-malformed.pdf':'N/A',
    'mixed-verified-and-unknown.pdf':'PASS','mixed-failure-and-unknown.pdf':'FAIL','checksum-known-alias.pdf':'PASS',
    'no-parameters-control.pdf':'N/A','alternative-only-control.pdf':'N/A','mixed-bare-and-unknown-parameters.pdf':'N/A',
    'unknown-alias.pdf':'N/A'}
pdfs=read('pdf-results.json');assert len(pdfs)==26
assert len({(r['file'],r['entry']) for r in pdfs})==26
for row in pdfs:
    assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
    assert row['sha256']==cases[row['file']]['sha256']
    report=read('pdf-reports/'+Path(row['file']).stem+'-'+row['entry']+'.json')
    assert report['summary']['status']==row['status']==cases[row['file']]['expected_status']
    assert sorted({i['code'] for i in report['items'] if i['level']=='FAIL'})==row['failures']==cases[row['file']]['expected_failures']
    assert parameter_level(report)==(before_levels[row['file']] if row['entry']=='before' else cases[row['file']]['expected_parameter_level'])
    if row['status']=='INCOMPLETE':
        assert any(i['code']=='VALIDATION_INCOMPLETE' and i['level']=='WARN' for i in report['items'])
comparison=[]
for name in cases:
    old=next(r for r in pdfs if r['entry']=='before' and r['file']==name)
    new=next(r for r in pdfs if r['entry']=='after' and r['file']==name)
    comparison.append(dict(file=name,sha256=old['sha256'],before_status=old['status'],before_failures=old['failures'],
        before_parameter_level=before_levels[name],after_status=new['status'],after_failures=new['failures'],
        after_parameter_level=cases[name]['expected_parameter_level']))
(HERE/'before-after-comparison.json').write_text(json.dumps(comparison,indent=2)+'\n')
regressions=read('custom-regressions.json');assert len(regressions)==202
assert all(r['before']==r['after'] for r in regressions)
expected={'original-path.pdf','original-padding.pdf','native-double-spaces.pdf','source-absent-alias-no-checksum.pdf',
    'renamed-wrong-checksum.pdf','unknown-alias.pdf','unknown-alias-parameters.pdf','unknown-alias-malformed.pdf',
    'mixed-verified-and-unknown.pdf','mixed-bare-and-unknown-parameters.pdf'}
assert {Path(r['file']).name for r in regressions if r['before_media_rules']!=r['after_media_rules']}==expected
for row in regressions:
    assert row['before_media_rules']['EMBEDDED_SUBTYPE_MATCH_XMP']==row['after_media_rules']['EMBEDDED_SUBTYPE_MATCH_XMP']
    if Path(row['file']).name in expected:
        assert row['after_media_rules'][PARAMS]=='WARN'
        assert row['before_media_rules'][PARAMS]==('PASS' if Path(row['file']).name=='mixed-verified-and-unknown.pdf' else 'N/A')
prior=read('../validator-22/custom-regressions.json');assert len(prior)==194
lookup={r['file']:r for r in regressions}
for old in prior:
    current=lookup[old['file']]
    assert digest(PREVIOUS/old['file'])==current['sha256']
    assert current['before']==current['after']==old['after']
    assert current['before_media_rules']==old['after_media_rules']
assert '202 outcomes verified;' in (HERE/'custom-regressions.log').read_text()
archive=HERE/'runtime-inputs-final.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as bundle:
    for name in sorted(manifest):
        if not Path(name).is_absolute() and not name.startswith(('docs/','examples/')) and Path(name).suffix!='.md':
            assert Path(name).name!='credentials.json';bundle.write(FROZEN/name,name)
patch=[]
for name in sorted(manifest):
    if Path(name).is_absolute() or name.startswith(('docs/','examples/')) or Path(name).suffix not in ('.py','.json','.md'):continue
    old=(PREVIOUS/name).read_text() if (PREVIOUS/name).exists() else '';new=(FROZEN/name).read_text()
    if old!=new:patch.extend(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='v22/'+name,tofile='v23/'+name))
(HERE/'application.patch').write_text(''.join(patch))
evidence=dict(scope='V23 unavailable parameter evidence when native association is missing',
 verification=dict(unit_tests=dict(run=487,passed=447,skipped=40,seconds=float(unit[2]),log='unit-tests.log'),
    pdf_probes=dict(cases=9,new_cases=8,retained_case=1,pdfa_passes=9,iso_passes=9,same_bytes_before_and_after=True,results='before-after-comparison.json'),
    generated_profiles=profiles,
    custom_comparisons=dict(total=202,prior_pdf_hashes_unchanged=194,prior_status_failure_outcomes_unchanged=194,
        all_status_failure_changes=0,parameter_reporting_changes=10,na_to_warn=9,pass_to_warn=1,results='custom-regressions.json'),
    entry_points=dict(checks=8,cli=4,isolated_web_workers=4,all_expected_outcomes_match=True,results='pdf-results.json')),
 inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs.json',files=len(manifest),archive=archive.name,archive_sha256=digest(archive)),
 creator=dict(unchanged=True,jar_sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')],new_java_build=False),
 limits=['Eleven expert/dependency questions remain open.',
    'The existing native-file comparison scope and association candidates are unchanged. No new native-copy requirement is introduced.',
    'This does not select owners/aliases, assign occurrences or prove original-inventory completeness.',
    'Source-asset association and shared veraPDF runtime coverage remain open.',
    'No new Maven build or complete packaged-suite acceptance is claimed. Integration/schema skips remain explicit.'],
 artifacts={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.glob('*.py'))})
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V23 accepted: 447 unit passes / 40 skips; nine PDF probes; twelve profiles; 202 comparisons; eight entry-point checks.')
