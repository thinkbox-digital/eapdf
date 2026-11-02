"""Assemble the bounded V15 schema acceptance and its exact-byte inherited gates."""
from pathlib import Path
import hashlib
import json
import re

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v15-final'


def read(name):
    return json.loads((HERE/name).read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


manifest=read('verification-inputs-final.json')
for name,digest in manifest.items():
    path=Path(name) if Path(name).is_absolute() else FROZEN/name
    assert sha(path)==digest,name
assert sha(HERE/'runtime-inputs-final.zip')==read('runtime-capsule-final.json')['sha256']
old_source=(ROOT/'.eapdf-verification-v14-accepted/validate_eapdf.py').read_text()
old_branch='        if "Message-ID" in att and not is_xmp_text(att["Message-ID"]):'
new_branch='        if "Mail_GUID" in att and not is_xmp_text(att["Mail_GUID"]):\n            report.fail("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID","Supplied attachment Mail_GUID must be normal text even when Message-ID permits its omission",{"attachment":att})\n        elif "Message-ID" in att and not is_xmp_text(att["Message-ID"]):'
assert (FROZEN/'validate_eapdf.py').read_text()==old_source.replace(old_branch,new_branch)
old_rules=json.loads((ROOT/'.eapdf-verification-v14-accepted/validator_rule_text.json').read_text())
new_rules=json.loads((FROZEN/'validator_rule_text.json').read_text())
assert [code for code in old_rules if old_rules[code]!=new_rules[code]]==['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID']
initial=read('verification-inputs.json')
assert all(initial[name]==manifest[name] for name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py'))
unit=(HERE/'unit-tests-final.log').read_text()
match=re.search(r'Ran (\d+) tests in ([\d.]+)s\s+OK \(skipped=(\d+)\)',unit)
assert match
run,seconds,skipped=int(match[1]),float(match[2]),int(match[3])
corpus=[row for row in read('corpus-results.json') if row['file']!='attribute.pdf']
corpus.append(read('attribute-correction-results.json'))
corpus.sort(key=lambda row:row['file'])
cases=json.loads((FROZEN/'tests/fixtures/eapdf/validator-attachment-identity/expectations.json').read_text())['cases']
expected={case['file']:case for case in cases}
assert len(corpus)==8
for row in corpus:
    case=expected[row['file']]
    assert row['sha256']==case['sha256']
    assert row['before']['status']=='PASS' and not row['before']['failures']
    assert row['after']['status']==case['expected_status'] and row['after']['failures']==case['expected_failures']
    assert all(row[label]['pdfa']==row[label]['iso']=='PASS' and row[label]['profile_verified'] for label in ('before','after'))
(HERE/'corpus-final-results.json').write_text(json.dumps(corpus,indent=2)+'\n')
regressions=read('custom-regressions-final.json')
assert len(regressions)==133
changed=[row for row in regressions if row['before']!=row['after']]
assert {row['file'] for row in changed}=={'tests/fixtures/eapdf/validator-attachment-identity/'+name+'.pdf' for name in ('uri','array','structure')}
assert all(row['after']['failures']==['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID'] for row in changed)
entries=read('entry-points-final-results.json')
assert len(entries)==16 and all(row['pdfa']==row['iso']=='PASS' for row in entries)
for row in entries:
    failures=['ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID'] if row['file'] in ('uri.pdf','array.pdf','structure.pdf') else []
    assert row['failures']==failures and row['status']==('FAIL' if failures else 'PASS')
prior=ROOT/'docs/improvement/validator-14'
legacy=json.loads((prior/'retained-corpus-accepted-results.json').read_text())
assert len(legacy)==125
for row in legacy:
    assert sha(FROZEN/row['file'])==row['sha256'],row['file']
    assert row['pdfa']=='PASS' and row['failures']==row['expected'] and row['status']==row['expected_status']
    regression=next(item for item in regressions if item['file']==row['file'])
    assert regression['before']==regression['after']
prior_manifest=json.loads((prior/'verification-inputs-accepted.json').read_text())
for name in ('validator_web.py','config/verapdf/ISO-32005.xml'):
    assert manifest[name]==prior_manifest[name]
for name,digest in manifest.items():
    if name.startswith('src/main/java/') or name.startswith('src/main/resources/') or Path(name).is_absolute():
        assert digest==prior_manifest[name],name
(HERE/'verification-integrity.json').write_text(json.dumps({'frozen_files':len(manifest),'snapshot':str(FROZEN.relative_to(ROOT)),
    'capsule_verified':True,'application_same_across_initial_and_final_V15':True,
    'legacy_pdf_bytes_verified':len(legacy),'creator_unchanged_from_V14':True,
    'corrected_fixture':'fixture-construction-correction.json'},indent=2)+'\n')
evidence=dict(scope='V15 supplied attachment Mail_GUID type when Message-ID permits omission',
    verification=dict(unit_tests=dict(run=run,passed=run-skipped,skipped=skipped,seconds=seconds,log='unit-tests-final.log'),
        new_pdf_probes=dict(cases=8,pdfa_passes=8,exact_outcomes=8,iso_passes=8,same_bytes_before_and_after=True,results='corpus-final-results.json'),
        custom_regressions=dict(cases=133,unchanged=130,expected_new_schema_failures=3,results='custom-regressions-final.json'),
        cli_worker_checks=dict(cases=16,results='entry-points-final-results.json'),
        inherited_V14=dict(evidence='../validator-14/evidence.json',complete_pipeline_cases=125,
            all_legacy_pdf_bytes_unchanged=True,all_legacy_custom_results_unchanged=True,
            generated_profiles=12,java_passes_from_unchanged_creator_build=161)),
    acceptance_boundary='Current V15 unit tests, eight exact-byte before/after complete PDF/A/custom/ISO probes, all 133 custom-only fixtures and sixteen CLI/isolated-worker checks. V14 supplies the separately bounded prior 125-case complete gate and twelve generated profiles; no new V15 full packaged suite or twelve-profile run is claimed.',
    inputs=dict(snapshot=str(FROZEN.relative_to(ROOT)),manifest='verification-inputs-final.json',capsule='runtime-inputs-final.zip',integrity='verification-integrity.json'),
    before_tests='before-tests.log',patch='application-final.patch',
    fixture_correction='fixture-construction-correction.json',
    supporting_citation_correction='fixture-authority-citation-correction.json',
    initial_entry_failure='The original isolated-worker ISO invocation could not launch the Docker Go client: pthread_create resource unavailable. The final run starts after larger lanes finish and sets GOMAXPROCS=2 for the client without relaxing validation limits. The failure remains in entry-points.log and entry-points/uri-web-worker.json.',
    creator=dict(unchanged=True,sha256=manifest[str(ROOT/'.eapdf-build-creator-structure/emailtopdfa-1.4.0.jar')]),
    inventory=dict(records=301,file_records=249,codes=224,open_expert_dependency_questions=11),
    limits=['No lexical Mail_GUID format or new presence requirement is introduced.',
        'Optional-field shape is checked; broader owner/occurrence assignment and original-source completeness remain open.',
        'Eleven expert/dependency questions and the inherited-edition boundary remain explicit.',
        'Broader preservation B13 is deferred; mapping and passing fixtures do not establish complete standards coverage.'])
(HERE/'evidence.json').write_text(json.dumps(evidence,indent=2)+'\n')
print('Accepted V15:',run-skipped,'unit passes, eight complete-pipeline probes, 133 custom-only fixtures, sixteen CLI/worker checks')
