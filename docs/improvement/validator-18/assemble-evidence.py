"""Accept the final V18 snapshot only after all required gates pass."""
from pathlib import Path
import difflib
import hashlib
import json
import re
import zipfile

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v18-final'


def read(name): return json.loads((HERE/name).read_text())
def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()


manifest=read('verification-inputs-final.json')
for name,value in manifest.items():
    assert digest(Path(name) if Path(name).is_absolute() else FROZEN/name)==value,name
    if name.startswith(('src/','validate_eapdf.py','validator_rule_text.json','validator_web.py','tests/','scripts/')):
        assert digest(Path(name) if Path(name).is_absolute() else ROOT/name)==value,name
unit=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',(HERE/'unit-tests-final.log').read_text())
assert unit and (int(unit[1]),int(unit[3]))==(439,35)
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
cases={c['file']:c for c in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-occurrences/expectations.json').read_text())['cases']}
pdfs=read('pdf-results.json');assert len(pdfs)==20
for row in pdfs:
    assert row['pdfa']==row['iso']=='PASS' and row['profile_verified']
    assert row['sha256']==cases[row['file']]['sha256']
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
assert len(regressions)==156 and {Path(r['file']).name for r in changed}=={'placeholder-first.pdf','placeholder-empty-checksum.pdf'}
prior=read('../validator-17/custom-regressions-final.json')
lookup={r['file']:r for r in regressions}
for row in prior:
    assert lookup[row['file']]['after']==row['after']
    assert digest(ROOT/'.eapdf-verification-v17-final'/row['file'])==lookup[row['file']]['sha256']
memory=read('memory-probe.json')
assert memory['eager']['matches']==memory['lazy']['matches']==200
assert memory['eager']['peak_python_bytes']>3*memory['lazy']['peak_python_bytes']
archive=HERE/'runtime-inputs-final.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as bundle:
    for name in sorted(manifest):
        if not Path(name).is_absolute() and not name.startswith(('docs/','examples/')) and Path(name).suffix!='.md':
            assert Path(name).name!='credentials.json'
            bundle.write(FROZEN/name,name)
patch=[]
for name in ('validate_eapdf.py','validator_rule_text.json','tests/test_attachment_occurrences.py','scripts/build_eapdf_attachment_occurrence_fixtures.py'):
    before=(HERE/('before-'+name)).read_text() if (HERE/('before-'+name)).exists() else ''
    patch.extend(difflib.unified_diff(before.splitlines(True),(ROOT/name).read_text().splitlines(True),fromfile='v17/'+name,tofile='v18/'+name))
(HERE/'application.patch').write_text(''.join(patch))
evidence=dict(scope='V18 unordered, owner-scoped attachment source comparisons',
 recovered_thread='01a1596d-69b2-73f0-8dc0-215b9a291ea9',
 verification=dict(unit_tests=dict(run=439,passed=404,skipped=35,seconds=float(unit[2]),log='unit-tests-final.log'),
                   new_pdf_probes=dict(cases=6,pdfa_passes=6,iso_passes=6,same_bytes_before_and_after=True,results='before-after-comparison.json'),
                   generated_profiles=profiles,custom_comparisons=dict(total=156,prior_unchanged=150,changed=2,results='custom-regressions.json'),
                   entry_points=dict(checks=8,cli=4,isolated_web_workers=4,all_expected_outcomes_match=True,results='pdf-results.json'),
                   memory_probe=memory),
 inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs-final.json',files=len(manifest),
             archive=archive.name,archive_sha256=digest(archive)),
 creator=dict(unchanged=True,jar_sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')],new_java_build=False),
 limits=['Eleven expert/dependency questions remain open.',
         'Source comparisons establish compatible field assignments, not unique original occurrence identities or complete inventory coverage.',
         'Native annotation/stream matching is unchanged; two absent-checksum controls remain INCOMPLETE.',
         'No new Maven build or full packaged-suite acceptance is claimed. Integration/schema skips remain explicit.',
         'The initial wrong-mount profile attempt and eager-candidate verification are retained separately; final evidence uses the final snapshot.'],
 artifacts={str(p.relative_to(HERE)):digest(p) for p in sorted(HERE.glob('*.py'))})
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('V18 accepted: 404 unit passes / 35 skips; six PDF probes; twelve profiles; 156 comparisons; eight entry-point checks.')
