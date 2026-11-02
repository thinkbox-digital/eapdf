"""Assemble bounded V16 acceptance after immutable verification completes."""
from pathlib import Path
import hashlib
import json
import re

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v16'


def read(name):return json.loads((HERE/name).read_text())
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


manifest=read('verification-inputs.json')
for name,digest in manifest.items():
    assert sha(Path(name) if Path(name).is_absolute() else FROZEN/name)==digest,name
assert sha(HERE/'runtime-inputs.zip')==read('runtime-capsule.json')['sha256']
unit=(HERE/'unit-tests.log').read_text();match=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',unit);assert match
run,seconds,skipped=int(match[1]),float(match[2]),int(match[3])
corpus=read('corpus-results.json');before=read('before-results.json')
assert len(corpus)==len(before)==8
assert all(row['pdfa']==row['iso']=='PASS' and row['profile_verified'] for row in corpus+before)
assert all(row['status']==row['expected_status'] and row['failures']==row['expected_failures'] and row['unavailable_match'] for row in corpus)
prior={row['file']:row for row in before}
comparison=[dict(file=row['file'],sha256=row['sha256'],before_status=prior[row['file']]['status'],before_failures=prior[row['file']]['failures'],after_status=row['status'],after_failures=row['failures']) for row in corpus]
assert all(row['sha256']==prior[row['file']]['sha256'] for row in corpus)
(HERE/'before-after-comparison.json').write_text(json.dumps(comparison,indent=2)+'\n')
profiles=[]
for mode,folder in [('normal',HERE/'profiles'),('accessibility',HERE/'profiles/accessibility')]:
    for profile in ('1s','1si','1m','1mi','1c','1ci'):
        result=json.loads((folder/(profile+'.json')).read_text());iso=next(item for item in result['items'] if item['code']=='ISO32005_UPSTREAM')
        assert result['pdfa']['status']==result['summary']['status']==iso['level']=='PASS' and iso['details']['profile_verified']
        profiles.append(dict(mode=mode,profile=profile,pdfa='PASS',eapdf='PASS',iso='PASS'))
for name in ('generated-profiles.log','accessibility-profiles.log'):
    assert re.search(r'Ran 1 test in [\d.]+s\s+OK',(HERE/name).read_text()),name
entries=read('entry-points-results.json');assert len(entries)==12
cases={case['file']:case for case in json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-evidence/expectations.json').read_text())['cases']}
assert all(row['pdfa']==row['iso']=='PASS' and row['status']==cases[row['file']]['expected_status'] and row['failures']==cases[row['file']]['expected_failures'] for row in entries)
regressions=read('custom-regressions.json');changed=[row for row in regressions if row['before']!=row['after']]
assert len(regressions)==141 and len(changed)==6
assert {Path(row['file']).name for row in changed}=={name+'.pdf' for name in ('exact-double-spaces','original-path','original-padding','native-double-spaces','source-name-differs','alternative-annotation')}
legacy=[]
for family,rows in [('v14',json.loads((HERE.parent/'validator-14/retained-corpus-accepted-results.json').read_text())),('v15',json.loads((HERE.parent/'validator-15/corpus-final-results.json').read_text()))]:
    for row in rows:
        name=row['file'] if family=='v14' else 'tests/fixtures/eapdf/validator-attachment-identity/'+row['file']
        assert sha(FROZEN/name)==row['sha256'],name
        result=next(item for item in regressions if item['file']==name);assert result['before']==result['after']
        legacy.append(name)
assert len(legacy)==133
old=json.loads((HERE.parent/'validator-15/verification-inputs-final.json').read_text())
for name,digest in manifest.items():
    if name.startswith(('src/main/java/','src/main/resources/')) or Path(name).is_absolute() or name in ('validator_web.py','config/verapdf/ISO-32005.xml'):
        assert digest==old[name],name
(HERE/'verification-integrity.json').write_text(json.dumps(dict(snapshot=str(FROZEN.relative_to(ROOT)),frozen_files=len(manifest),capsule_verified=True,unchanged_legacy_pdf_bytes=133,creator_unchanged=True),indent=2)+'\n')
evidence=dict(scope='V16 literal attachment filename and native/Alternative annotation evidence',
    verification=dict(unit_tests=dict(run=run,passed=run-skipped,skipped=skipped,seconds=seconds,log='unit-tests.log'),
        new_pdf_probes=dict(cases=8,pdfa_passes=8,exact_outcomes=8,same_bytes_before_and_after=True,results='before-after-comparison.json'),
        generated_profiles=profiles,custom_regressions=dict(cases=141,unchanged=135,expected_changes=6,results='custom-regressions.json'),
        cli_worker_checks=dict(cases=12,results='entry-points-results.json'),
        inherited_legacy=dict(cases=133,pdf_bytes_unchanged=True,custom_outcomes_unchanged=True,evidence=['../validator-14/evidence.json','../validator-15/evidence.json'])),
    inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs.json',capsule='runtime-inputs.zip',integrity='verification-integrity.json'),
    patch='application.patch',before_tests='before-tests.log',
    initial_unit_corrections='The initial raw-name union accepted a compatibility F value despite a different preferred UF. The correction preserves the existing UF precedence. Legacy direct helper callers supplying only annotation.filename retain that interface; real parsed file names use raw values.',
    acceptance_boundary='Current V16 unit tests, eight complete before/after PDF/A/custom/ISO cases, twelve newly generated normal/accessibility profiles, all 141 custom-only fixtures and twelve actual CLI/isolated-worker checks. The older 133 upstream PDF outcomes are reused only with identical PDF bytes and unchanged custom results. No full V16 packaged-suite/Java-build claim.',
    creator=dict(unchanged=True,sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')]),
    inventory=dict(records=301,codes=224,open_expert_dependency_questions=11),
    limits=['No global attachment occurrence/owner assignment or original-source completeness claim.',
            'Alternate stored filenames remain permitted with independent payload evidence; unresolved associations remain INCOMPLETE.',
            'Eleven expert/dependency questions and inherited-edition boundaries remain open. Broader preservation B13 remains deferred.'])
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('Accepted V16:',run-skipped,'unit passes, eight PDF probes, twelve profiles, 141 custom-only cases, twelve entry-point checks')
