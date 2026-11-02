"""Bind C01 creator acceptance to unchanged validator inputs and saved reports."""
import difflib
import hashlib
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
BUILD = Path('/tmp/eapdf-creator-01-build')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
baseline = json.loads((HERE / 'before-inputs.json').read_text())
for name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py'):
    assert sha(ROOT / name) == baseline[name], name
v85 = json.loads((ROOT / 'docs/improvement/validator-85/verification-inputs.json').read_text())
changed = [name for name, value in v85.items() if name.startswith('src/main/java/') and sha(ROOT / name) != value]
assert set(changed) == {'src/main/java/eapdf/' + name for name in
                        ('SemanticStructure.java', 'PdfMailWriter.java', 'PdfDocumentParts.java')}, changed
for name in changed:
    before = HERE / ('before-' + Path(name).name)
    assert sha(before) == v85[name]
    patch = ''.join(difflib.unified_diff(before.read_text().splitlines(True),
                                        (ROOT / name).read_text().splitlines(True),
                                        fromfile='before/' + name, tofile='after/' + name))
    (HERE / (Path(name).name + '.patch')).write_text(patch)

test_dir = HERE / 'java-test-reports'
test_dir.mkdir(exist_ok=True)
totals = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (BUILD / 'surefire-reports').glob('TEST-*.xml'):
    root = ET.parse(path).getroot()
    for key in totals:
        totals[key] += int(root.get(key, 0))
    shutil.copyfile(path, test_dir / path.name)
assert totals == dict(tests=165, failures=0, errors=0, skipped=0), totals
assert 'BUILD SUCCESS' in (HERE / 'maven-package.log').read_text()
before_tests = [ET.parse(HERE / name).getroot() for name in
                ('before-header-tests.xml', 'before-archive-tests.xml')]
assert sum(int(item.get('failures', 0)) for item in before_tests) == 3

profiles = json.loads((HERE / 'profile-results.json').read_text())
assert len(profiles) == 12
assert {(r['mode'], r['profile']) for r in profiles} == {
    (mode, profile) for mode in ('normal', 'accessibility')
    for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci')}
child_count = 0
for row in profiles:
    path = ROOT / row['file']
    assert sha(path) == row['sha256']
    assert row['status'] == row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'] and not row['failures']
    report = json.loads((path.parent / 'validation.json').read_text())
    assert report['summary']['status'] == 'PASS' and report['pdfa']['status'] == 'PASS'
    children = [i for i in report['items'] if i['code'] == 'COLLECTION_CHILD_VALIDATION' and i['level'] == 'PASS']
    if row['profile'] in ('1c', '1ci'):
        assert children
        for item in children:
            assert item['details']['status'] == item['details']['pdfa_status'] == 'PASS'
        child_count += len(children)
        sidecar = json.loads((path.parent / 'finding-aid-pdfa.json').read_text())
        assert sidecar['status'] == 'PASS'
preserved = json.loads((HERE / 'preservation-comparison.json').read_text())
assert sum(r['corrected_header_operands'] for r in preserved['pages']) == 10
manifest = {name: sha(ROOT / name) for name in changed + [
    'src/test/java/eapdf/HeaderOperandTests.java', 'src/test/java/eapdf/ArchiveStructureTests.java',
    'pom.xml', 'config/eapdf_config.json', 'validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py']}
(HERE / 'accepted-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
with zipfile.ZipFile(HERE / 'creator-overlay.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
    for name in manifest:
        archive.write(ROOT / name, name)
evidence = dict(checkpoint='C01', date='2026-10-22', status='accepted',
    direction='User stopped validator development at V85 and authorized creator improvements.',
    repairs=['Stream-local header tag operands agree with structure owners.',
             'Noncollection archive FrontMatter shares the first email owner in logical and DPart trees.'],
    verification=dict(java=totals, baseline_failing_regressions=3, focused_tests=4,
                      freshly_generated_profiles=12, actual_collection_child_passes=child_count,
                      collection_finding_aid_pdfa_passes=4, collection_xlsx_sidecars=4,
                      archive_embedded_xlsx_indexes=4, retained_header_painting_and_source=True),
    inputs=dict(validator_baseline='V85', validator_unchanged=True, changed_creator_sources=changed,
                manifest='accepted-inputs.json', source_overlay='creator-overlay.zip',
                base_runtime_archive='docs/improvement/validator-85/runtime-inputs.zip',
                jar=str(BUILD / 'emailtopdfa-1.4.0.jar'), jar_sha256=sha(BUILD / 'emailtopdfa-1.4.0.jar')),
    limits=['Acceptance covers these repairs and retained fixtures, not arbitrary-input preservation or full rendering equivalence.',
            'Accessibility fixture mode does not certify PDF/UA conformance.',
            'V85 validator obligation gaps and local interpretation limits remain; no validator repair is included.',
            'Historical creator failures and existing examples are retained; no sample publication or release acceptance is claimed.'])
evidence['artifacts'] = {str(path.relative_to(HERE)): sha(path) for path in sorted(HERE.rglob('*'))
                         if path.is_file() and path != HERE / 'evidence.json'}
(HERE / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
print('C01 accepted:', totals, '12 generated profile passes;', child_count, 'actual collection child passes.')
