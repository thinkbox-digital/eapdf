"""Accept the V20 snapshot only after all required gates pass."""
from pathlib import Path
import difflib
import hashlib
import json
import re
import zipfile

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v20'


def read(name): return json.loads((HERE/name).read_text())
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


manifest=read('verification-inputs.json')
for name,value in manifest.items():
    assert digest(Path(name) if Path(name).is_absolute() else FROZEN/name)==value,name
    if name.startswith(('src/','validate_eapdf.py','validator_rule_text.json','validator_web.py','tests/','scripts/')):
        assert digest(Path(name) if Path(name).is_absolute() else ROOT/name)==value,name
unit=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',(HERE/'unit-tests.log').read_text())
assert unit and (int(unit[1]),int(unit[3]))==(458,37)
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
cases={c['file']:c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-media-parameters/expectations.json').read_text())['cases']}
pdfs=read('pdf-results.json');assert len(pdfs)==28
assert len({(r['file'],r['entry']) for r in pdfs})==28
for row in pdfs:
    assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
    assert row['sha256']==cases[row['file']]['sha256']
    report=read('pdf-reports/'+Path(row['file']).stem+'-'+row['entry']+'.json')
    assert report['summary']['status']==row['status']
    if row['status']=='INCOMPLETE':
        unavailable={i['code'] for i in report['items'] if i['level']=='WARN'}
        assert {'ATTACHMENT_XMP_MATCH','EMBEDDED_SUBTYPE_MATCH_XMP','VALIDATION_INCOMPLETE'}<=unavailable
    if row['entry']=='before':
        assert row['status']==('FAIL' if row['file'] in ('quoted-source.pdf','malformed-source-preserved.pdf') else 'PASS')
        assert row['failures']==(['ATTACHMENT_XMP_CONTENTTYPE_MATCH'] if row['status']=='FAIL' else [])
    if row['entry']!='before':
        assert row['status']==cases[row['file']]['expected_status']
        assert row['failures']==cases[row['file']]['expected_failures']
comparison=[]
for name in cases:
    old=next(r for r in pdfs if r['entry']=='before' and r['file']==name)
    new=next(r for r in pdfs if r['entry']=='after' and r['file']==name)
    comparison.append(dict(file=name,sha256=old['sha256'],before_status=old['status'],before_failures=old['failures'],
                           after_status=new['status'],after_failures=new['failures']))
(HERE/'before-after-comparison.json').write_text(json.dumps(comparison,indent=2)+'\n')
regressions=read('custom-regressions.json')
changed=[r for r in regressions if r['before']!=r['after']]
assert len(regressions)==174 and {Path(r['file']).name for r in changed}=={'extra-empty-parameter.pdf','nameless-parameter.pdf','unquoted-space.pdf','quoted-source.pdf','quoted-source-cleaned.pdf','malformed-source-preserved.pdf','malformed-source-repaired.pdf'}
prior=read('../validator-19/custom-regressions.json')
lookup={r['file']:r for r in regressions}
for row in prior:
    assert lookup[row['file']]['after']==row['after']
    assert digest(ROOT/'.eapdf-verification-v19'/row['file'])==lookup[row['file']]['sha256']
archive=HERE/'runtime-inputs-final.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as bundle:
    for name in sorted(manifest):
        if not Path(name).is_absolute() and not name.startswith(('docs/','examples/')) and Path(name).suffix!='.md':
            assert Path(name).name!='credentials.json'
            bundle.write(FROZEN/name,name)
patch=[]
for name in ('validate_eapdf.py','validator_rule_text.json','tests/test_media_parameter_preservation.py','scripts/build_eapdf_media_parameter_fixtures.py'):
    before=(HERE/('before-'+name)).read_text() if (HERE/('before-'+name)).exists() else ''
    patch.extend(difflib.unified_diff(before.splitlines(True),(ROOT/name).read_text().splitlines(True),fromfile='v19/'+name,tofile='v20/'+name))
(HERE/'application.patch').write_text(''.join(patch))
evidence=dict(scope='V20 original media-parameter text and recovery-safe comparisons',
 verification=dict(unit_tests=dict(run=458,passed=421,skipped=37,seconds=float(unit[2]),log='unit-tests.log'),
                   new_pdf_probes=dict(cases=10,pdfa_passes=10,iso_passes=10,same_bytes_before_and_after=True,results='before-after-comparison.json'),
                   generated_profiles=profiles,custom_comparisons=dict(total=174,prior_unchanged=164,changed=7,results='custom-regressions.json'),
                   entry_points=dict(checks=8,cli=4,isolated_web_workers=4,all_expected_outcomes_match=True,results='pdf-results.json')),
 inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs.json',files=len(manifest),
             archive=archive.name,archive_sha256=digest(archive)),
 creator=dict(unchanged=True,jar_sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')],new_java_build=False),
 limits=['Eleven expert/dependency questions remain open.',
         'Malformed free-text media values remain permitted; syntax recovery cannot establish equality with different text.',
         'This is not exhaustive MIME semantic, unique occurrence or original-inventory coverage.',
         'No new Maven build or full packaged-suite acceptance is claimed. Integration/schema skips remain explicit.'],
 artifacts={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.glob('*.py'))})
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V20 accepted: 421 unit passes / 37 skips; ten PDF probes; twelve profiles; 174 comparisons; eight entry-point checks.')
