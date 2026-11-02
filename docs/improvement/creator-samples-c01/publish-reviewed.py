"""Publish the reviewed C01 candidates, preserving their actual V85 statuses."""
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import pikepdf
import validate_eapdf as validator

sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
baseline = json.loads((HERE / 'resumed/inputs.json').read_text())
rows = json.loads((HERE / 'reviewed/results.json').read_text())
assert [row['profile'] for row in rows] == ['1s', '1si', '1m', '1mi', '1c', '1ci']
for name, expected in baseline['inputs'].items():
    assert sha(ROOT / name) == expected, name
common_limits = {
    'Some Content Set tag counterparts remain unavailable',
    'Some Art Content Set counterparts remain unavailable',
    'Message content/GUID ownership remains unavailable',
    'Content Group type correspondence remains unavailable',
    'Some active subtype properties could not be compared',
}
outputs = []
preservation = []
children = []
for row in rows:
    profile = row['profile']
    report = json.loads((HERE / f'reviewed/{profile}-validation.json').read_text())
    assert report['summary']['status'] == row['status']
    assert not any(item['level'] == 'FAIL' for item in report['items'])
    assert row['pdfa'] == row['iso'] == 'PASS' and row['iso_profile_verified']
    reasons = Counter(item['message'] for item in row['incomplete'])
    if profile in ('1m', '1mi'):
        assert row['status'] == 'INCOMPLETE'
        expected = {reason: 1 for reason in common_limits}
        if profile == '1m':
            expected['Non-SMTP source address preservation could not be verified'] = 130
        else:
            expected['Attachment native-byte consistency could not be fully verified'] = 1
        assert reasons == Counter(expected), (profile, reasons)
    else:
        assert row['status'] == 'PASS' and not reasons
    candidate = ROOT / row['candidate']
    assert sha(candidate) == row['sha256']
    destination = ROOT / f'examples/sample_{profile}.pdf'
    def sources(path):
        with pikepdf.Pdf.open(path) as pdf:
            return sorted((s['filename'], s['sha256']) for s in validator.get_af_specs(pdf)
                          if s['afrelationship'] == 'Source')
    previous, current = sources(destination), sources(candidate)
    assert previous == current, profile
    preservation.append(dict(profile=profile, sources=len(current), payloads_identical=True))
    with pikepdf.Pdf.open(candidate) as pdf:
        assert b'<xmp:CreatorTool>EmailToPDFA 1.4.0</xmp:CreatorTool>' in pdf.Root.Metadata.read_bytes()
        if profile in ('1m', '1mi'):
            assert any(s['filename'].endswith('_index.xlsx') for s in validator.get_af_specs(pdf))
    if profile in ('1c', '1ci'):
        checks = [item for item in report['items'] if item['code'] == 'COLLECTION_CHILD_VALIDATION']
        assert len(checks) == 1 and checks[0]['level'] == 'PASS'
        child = checks[0]['details']
        assert child['status'] == child['pdfa_status'] == 'PASS'
        children.append({key: child[key] for key in ('child', 'sha256', 'status', 'pdfa_status')})
        assert {item['suffix'] for item in row['sidecars']} == {'_finding_aid.pdf', '_index.xlsx'}
    outputs.append((candidate, destination, row['sha256']))
    for sidecar in row['sidecars']:
        path = ROOT / sidecar['candidate']
        assert sha(path) == sidecar['sha256']
        outputs.append((path, ROOT / f"examples/sample_{profile}{sidecar['suffix']}", sidecar['sha256']))
    if profile == '1s' and (ROOT / 'output/sample_1s.pdf').is_file():
        outputs.append((candidate, ROOT / 'output/sample_1s.pdf', row['sha256']))
for source, destination, expected in outputs:
    old = sha(destination) if destination.is_file() else None
    assert old in (baseline['before_files'].get(str(destination.relative_to(ROOT))), expected), destination
for source, destination, expected in outputs:
    with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.sample-', delete=False) as stream:
        temporary = Path(stream.name)
        try:
            with source.open('rb') as original:
                shutil.copyfileobj(original, stream)
            stream.flush()
            os.fsync(stream.fileno())
            stream.close()
            assert sha(temporary) == expected
            temporary.replace(destination)
        finally:
            temporary.unlink(missing_ok=True)
    assert sha(destination) == expected
tests = dict(tests=0, failures=0, errors=0, skipped=0)
for path in Path('/tmp/eapdf-creator-c01-samples-build/surefire-reports').glob('TEST-*.xml'):
    suite = ET.parse(path).getroot()
    for key in tests:
        tests[key] += int(suite.attrib.get(key, 0))
assert tests == dict(tests=165, failures=0, errors=0, skipped=0)
shutil.copyfile('/tmp/eapdf-creator-c01-samples-build.log', HERE / 'maven-package.log')
evidence = dict(date='2026-10-22', task='Complete interrupted sample regeneration',
                recovered_thread=baseline['recovered_thread'], creator_checkpoint='C01',
                creator_jar=baseline['creator_jar'], creator_jar_sha256=baseline['creator_jar_sha256'],
                java=tests, validator_baseline='V85', validator_unchanged=True,
                publication='All six samples regenerated; two PST archive results remain INCOMPLETE.',
                profiles=[dict(profile=row['profile'], status=row['status'], pdfa=row['pdfa'], iso=row['iso'],
                               incomplete_reasons=dict(Counter(item['message'] for item in row['incomplete']))) for row in rows],
                collection_children=children, source_preservation=preservation,
                published={str(destination.relative_to(ROOT)): sha(destination) for _, destination, _ in outputs},
                standard_regeneration='Unchanged strict PASS gate stopped at 1m; failed receipt retained in resumed/.',
                review='Explicit publication of user-requested regenerated examples with honest INCOMPLETE archive reports. No validator result was altered; this is not full archive acceptance.')
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print(json.dumps(evidence, indent=2))
