"""Freeze C02 acceptance and publish the two checked PST sample PDFs."""
from collections import Counter
import difflib
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
BUILD = Path('/tmp/eapdf-creator-02-build')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
baseline = json.loads((HERE / 'before-inputs.json').read_text())
changed_sources = [name for name, value in baseline.items()
                   if name.startswith('src/main/') and sha(ROOT / name) != value]
assert changed_sources == ['src/main/java/eapdf/PstReader.java'], changed_sources
for name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py',
             'pom.xml', 'config/eapdf_config.json', 'in/email/test.pst'):
    assert sha(ROOT / name) == baseline[name], name
profiles = json.loads((HERE / 'profile-results.json').read_text())
assert len(profiles) == 12
children = []
for row in profiles:
    assert row['status'] == row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified']
    path = ROOT / row['file']
    assert sha(path) == row['sha256']
    report = json.loads((path.parent / 'validation.json').read_text())
    assert not any(item['level'] == 'FAIL' for item in report['items'])
    for item in report['items']:
        if item['code'] == 'COLLECTION_CHILD_VALIDATION' and row['profile'] in ('1c', '1ci'):
            assert item['level'] == 'PASS'
            details = item['details']
            assert details['status'] == details['pdfa_status'] == 'PASS'
            children.append({key: details[key] for key in ('child', 'sha256', 'status', 'pdfa_status')})
    if row['profile'] in ('1c', '1ci'):
        sidecar_report = json.loads((path.parent / 'finding-aid-pdfa.json').read_text())
        assert sidecar_report['status'] == 'PASS'
assert len(children) == 10
tests = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (BUILD / 'surefire-reports').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in tests:
        tests[key] += int(suite.attrib.get(key, 0))
    (HERE / 'java-test-reports').mkdir(exist_ok=True)
    shutil.copyfile(path, HERE / 'java-test-reports' / path.name)
assert tests == dict(tests=167, failures=0, errors=0, skipped=0), tests
pst = json.loads((HERE / 'pst-results.json').read_text())
assert [row['profile'] for row in pst] == ['1m', '1mi']
assert pst[0]['messages_compared'] == 71
assert pst[0]['fictitious_headers_removed'] == dict(Cc=59, Bcc=71)
assert pst[0]['other_headers_and_decoded_payloads_preserved']
for row in pst:
    assert row['status'] == 'INCOMPLETE' and row['pdfa'] == row['iso'] == 'PASS' and not row['failures']
    assert 'Non-SMTP source address preservation could not be verified' not in row['incomplete_reasons']
    assert sha(ROOT / row['file']) == row['sha256']
    destination = ROOT / f"examples/sample_{row['profile']}.pdf"
    assert sha(destination) in (baseline[str(destination.relative_to(ROOT))], row['sha256'])
for row in pst:
    source = ROOT / row['file']
    destination = ROOT / f"examples/sample_{row['profile']}.pdf"
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.sample-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            with source.open('rb') as original:
                shutil.copyfileobj(original, stream)
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
            assert sha(temporary) == row['sha256']
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    assert sha(destination) == row['sha256']
for name, value in baseline.items():
    if name.startswith('examples/') and name not in ('examples/sample_1m.pdf', 'examples/sample_1mi.pdf'):
        assert sha(ROOT / name) == value, name
shutil.copyfile('/tmp/eapdf-creator-02-package.log', HERE / 'maven-package.log')
before = (HERE / 'before-PstReader.java').read_text().splitlines(keepends=True)
after = (ROOT / changed_sources[0]).read_text().splitlines(keepends=True)
(HERE / 'PstReader.java.patch').write_text(''.join(difflib.unified_diff(
    before, after, fromfile='before/PstReader.java', tofile='after/PstReader.java')))
inputs = changed_sources + ['src/test/java/eapdf/PstHeaderExportTests.java', 'pom.xml',
                          'config/eapdf_config.json', 'validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py']
(HERE / 'accepted-inputs.json').write_text(json.dumps({name: sha(ROOT / name) for name in inputs}, indent=2) + '\n')
with ZipFile(HERE / 'creator-overlay.zip', 'w') as archive:
    for name in changed_sources + ['src/test/java/eapdf/PstHeaderExportTests.java']:
        archive.write(ROOT / name, name)
trace = json.loads((HERE / 'before-tracepoints.json').read_text())
assert len(trace['tracepointOutputsTail']) == 213 and not trace['breakpointErrorsTail']
assert (HERE / 'after/summary.txt').read_text().strip() == 'messages=71 nulCc=0 nulBcc=0'
evidence = dict(checkpoint='C02', date='2026-10-22', status='accepted',
                repair='PST recipient display fallbacks discard trailing MAPI storage terminators; supplied transport headers are preserved.',
                verification=dict(java=tests, baseline_failing_tests=1, focused_tests=2,
                                  debugger_events=213, debugger_errors=0, generated_profiles=12,
                                  collection_children=10, collection_finding_aid_pdfa_passes=4,
                                  collection_xlsx_sidecars=4, actual_pst_archives=2,
                                  compared_messages=71, fictitious_headers_removed=dict(Cc=59, Bcc=71),
                                  decoded_bodies_and_attachments_preserved=True),
                jar=str(BUILD / 'emailtopdfa-1.4.0.jar'), jar_sha256=sha(BUILD / 'emailtopdfa-1.4.0.jar'),
                changed_creator_sources=changed_sources, validator_baseline='V85', validator_unchanged=True,
                pst_results=pst, collection_children=children,
                published={f"examples/sample_{row['profile']}.pdf": row['sha256'] for row in pst},
                limits=['The actual 71-message PST archives retain INCOMPLETE structure assessments under V85 traversal limits.',
                        'The isolated archive also retains unavailable native attachment consistency.',
                        'MAPI fallback normalization does not alter supplied transport-header values or prove arbitrary PST/source fidelity.',
                        'Normal/accessibility fixtures do not certify full PDF/UA, rendering or validator coverage.'],
                recipe_note='The final verification recipe points to the retained C01 baseline, proved byte-identical to the saved baseline used during execution before sample publication.')
evidence['artifacts'] = {str(path.relative_to(HERE)): sha(path) for path in sorted(HERE.rglob('*'))
                         if path.is_file() and path.name != 'evidence.json'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print(json.dumps({key: evidence[key] for key in ('checkpoint', 'status', 'verification', 'published')}, indent=2))
