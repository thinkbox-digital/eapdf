"""Accept V17 only after all frozen checks finish."""
from pathlib import Path
import hashlib
import json
import re

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v17-final'

def read(name): return json.loads((HERE/name).read_text())
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

manifest=read('verification-inputs-final.json')
for name,digest in manifest.items():
    assert sha(Path(name) if Path(name).is_absolute() else FROZEN/name)==digest,name
assert sha(HERE/'runtime-inputs-final.zip')==read('runtime-capsule-final.json')['sha256']
match=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',(HERE/'unit-tests-final.log').read_text())
assert match
run,seconds,skipped=int(match[1]),float(match[2]),int(match[3])
assert (run,skipped)==(430,34)
corpus=read('corpus-final-results.json'); before=read('before-final-results.json')
assert len(corpus)==len(before)==9
assert all(row['pdfa']==row['iso']=='PASS' and row['profile_verified'] for row in corpus+before)
assert all(row['status']==row['expected_status'] and row['failures']==row['expected_failures'] and row['unavailable_match'] for row in corpus)
prior={row['file']:row for row in before}
comparison=[]
for row in corpus:
    old=prior[row['file']]
    assert row['sha256']==old['sha256']
    comparison.append(dict(file=row['file'],sha256=row['sha256'],baseline=old['baseline'],
        before_status=old['status'],before_failures=old['failures'],after_status=row['status'],after_failures=row['failures']))
for name in ('source-omitted-missing-count.pdf','source-omitted-zero-count.pdf'):
    row=next(row for row in comparison if row['file']==name)
    assert row['before_status']=='PASS' and row['after_failures']==['MESSAGE_ATTACHMENT_COUNT_XMP']
(HERE/'before-after-comparison.json').write_text(json.dumps(comparison,indent=2)+'\n')
profiles=[]
for mode,folder in [('normal',HERE/'profiles-final'),('accessibility',HERE/'profiles-final/accessibility')]:
    for profile in ('1s','1si','1m','1mi','1c','1ci'):
        result=json.loads((folder/(profile+'.json')).read_text())
        iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
        assert result['pdfa']['status']==result['summary']['status']==iso['level']=='PASS' and iso['details']['profile_verified']
        profiles.append(dict(mode=mode,profile=profile,pdfa='PASS',eapdf='PASS',iso='PASS'))
for name in ('generated-profiles-final.log','accessibility-profiles-final.log'):
    assert re.search(r'Ran 1 test in [\d.]+s\s+OK',(HERE/name).read_text()),name
entries=read('entry-points-final-results.json'); assert len(entries)==16
cases={case['file']:case for case in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-owners/expectations.json').read_text())['cases']}
assert all(row['pdfa']==row['iso']=='PASS' and row['status']==cases[row['file']]['expected_status'] and row['failures']==cases[row['file']]['expected_failures'] for row in entries)
regressions=read('custom-regressions-final.json')
changed=[row for row in regressions if row['before']!=row['after']]
assert len(regressions)==150 and len(changed)==7
assert {Path(row['file']).name for row in changed}=={name+'.pdf' for name in ('message-id-only','optional-dpm-id-omitted','message-id-only-native-alias','wrong-owner','source-omitted-message-id','source-omitted-missing-count','source-omitted-zero-count')}
legacy=[]
for family,filename,prefix in [
    ('validator-14','retained-corpus-accepted-results.json',''),
    ('validator-15','corpus-final-results.json','tests/fixtures/eapdf/validator-attachment-identity/'),
    ('validator-16','corpus-results.json','tests/fixtures/eapdf/validator-attachment-evidence/')]:
    for row in json.loads((HERE.parent/family/filename).read_text()):
        name=prefix+row['file']
        assert sha(FROZEN/name)==row['sha256'],name
        result=next(item for item in regressions if item['file']==name)
        assert result['before']==result['after'],name
        legacy.append(name)
assert len(legacy)==141
old=json.loads((HERE.parent/'validator-16/verification-inputs.json').read_text())
for name,digest in manifest.items():
    if name.startswith(('src/main/java/','src/main/resources/')) or Path(name).is_absolute() or name in ('validator_web.py','config/verapdf/ISO-32005.xml'):
        assert digest==old[name],name
integrity=dict(snapshot=str(FROZEN.relative_to(ROOT)),frozen_files=len(manifest),capsule_verified=True,
    unchanged_legacy_pdf_bytes=141,creator_unchanged=True)
(HERE/'verification-integrity.json').write_text(json.dumps(integrity,indent=2)+'\n')
evidence=dict(scope='V17 unique attachment email owners and positive attachment counts',
    verification=dict(unit_tests=dict(run=run,passed=run-skipped,skipped=skipped,seconds=seconds,log='unit-tests-final.log'),
        new_pdf_probes=dict(cases=9,pdfa_passes=9,exact_outcomes=9,same_bytes_before_and_after=True,results='before-after-comparison.json',
            baseline='V16 for six original owner cases; initial V17 for three source-omitted cases to isolate the count defect.'),
        generated_profiles=profiles,
        custom_regressions=dict(cases=150,unchanged=143,expected_changes=7,baseline='V16',results='custom-regressions-final.json'),
        cli_worker_checks=dict(cases=16,results='entry-points-final-results.json'),
        inherited_legacy=dict(cases=141,pdf_bytes_unchanged=True,custom_outcomes_unchanged=True,
            evidence=['../validator-14/evidence.json','../validator-15/evidence.json','../validator-16/evidence.json'])),
    inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs-final.json',capsule='runtime-inputs-final.zip',integrity='verification-integrity.json'),
    patch='application-final.patch',requirements_review='requirements-review.json',
    earlier_runs='Initial six-case owner checks and the initial snapshot are retained. They predate the count repair; only final logs and the final snapshot support acceptance.',
    acceptance_boundary='396 unit passes, nine complete before/after PDF cases, twelve fresh generated normal/accessibility profiles, 150 custom-only comparisons and sixteen actual CLI/isolated-worker checks. Prior upstream outcomes are reused only for identical legacy PDF bytes and unchanged custom results. No new full packaged-suite or Java-build claim.',
    creator=dict(unchanged=True,sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')]),
    inventory=dict(records=301,codes=224,open_expert_dependency_questions=11),
    limits=['The count check proves a positive lower bound, not an exact total or complete original attachment inventory.',
            'Ambiguous owners remain unavailable. General RFC canonicalization and global attachment occurrence assignment remain outside this repair.',
            'Eleven expert/dependency questions remain open. Broader preservation B13 remains deferred.'])
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('Accepted V17: 396 unit passes; 9 PDF cases; 12 profiles; 150 comparisons; 16 CLI/worker checks.')
