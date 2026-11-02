"""Verify source parameter reporting on real PDFs, entry points and profiles."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v26'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
import validator_web as web
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v25/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
directory = SNAPSHOT / 'tests/fixtures/eapdf/validator-source-parameters'
cases = json.loads((directory / 'expectations.json').read_text())['cases']
for case in cases:
    case['path'] = directory / case['file']
for case in json.loads((SNAPSHOT / 'tests/fixtures/eapdf/validator-ambiguous-sources/expectations.json').read_text())['cases']:
    if case['file'] in ('compatible-parameters.pdf', 'malformed-parameters.pdf'):
        cases.append(dict(case, path=SNAPSHOT / 'tests/fixtures/eapdf/validator-ambiguous-sources' / case['file'], expected_parameter_level='WARN'))
assert len(cases) == 11
OUT = HERE / 'pdf-reports'
OUT.mkdir(exist_ok=True)
rows = json.loads((HERE / 'pdf-results.json').read_text()) if '--resume' in sys.argv else []
CODE = 'SOURCE_MEDIATYPE_PARAMETERS_MATCH'


def record(path, result, label, key=None):
    key = key or path.stem
    (OUT / (key + '-' + label + '.json')).write_text(json.dumps(result, indent=2, default=str) + '\n')
    iso = next(i for i in result['items'] if i['code'] == 'ISO32005_UPSTREAM')
    order = {'N/A': 0, 'PASS': 1, 'WARN': 2, 'FAIL': 3}
    row = dict(file=key, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), entry=label,
               status=result['summary']['status'], pdfa=result['pdfa']['status'], iso=iso['level'],
               profile_verified=iso.get('details', {}).get('profile_verified'),
               parameter_level=max((i['level'] for i in result['items'] if i['code'] == CODE), key=order.get),
               failures=sorted({i['code'] for i in result['items'] if i['level'] == 'FAIL'}))
    rows.append(row)
    (HERE / 'pdf-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(row, flush=True)
    assert row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'], row
    return row


def existing(path, label, key=None):
    row = next((r for r in rows if r['file'] == (key or path.stem) and r['entry'] == label), None)
    if row:
        assert row['sha256'] == hashlib.sha256(path.read_bytes()).hexdigest()
        assert row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified']
    return row


for case in cases:
    path = case['path']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    previous = existing(path, 'before') or record(path, before.validate_one(str(path), write_html=False), 'before')
    row = existing(path, 'after') or record(path, after.validate_one(str(path), write_html=False), 'after')
    assert row['status'] == previous['status'] == case['expected_status']
    assert row['failures'] == previous['failures'] == case['expected_failures']
    assert row['parameter_level'] == case['expected_parameter_level'], row

for name in ('ambiguous-verified.pdf', 'unknown-no-attachments.pdf', 'unique-names-shared-checksum.pdf', 'definite-failure.pdf'):
    case = next(c for c in cases if c['file'] == name)
    path = case['path']
    for entry in ('cli', 'web-worker'):
        if existing(path, entry):
            continue
        if entry == 'cli':
            proc = subprocess.run([sys.executable, '-B', 'validate_eapdf.py', str(path), '--json',
                                   '--html', str(OUT / (path.stem + '.html'))], cwd=SNAPSHOT,
                                  capture_output=True, text=True, timeout=240)
            (OUT / (path.stem + '.stderr')).write_text(proc.stderr)
            assert proc.returncode in (0, 2), (proc.returncode, proc.stderr)
            result = json.loads(proc.stdout)
        else:
            result = web.run_pdf_validation(path.resolve())
        row = record(path, result, entry)
        assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures']
        assert row['parameter_level'] == case['expected_parameter_level'], row

prior = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())
for profile in prior['profiles']:
    original = json.loads((ROOT / profile['report']).read_text())
    path = Path(original['summary']['file'])
    key = profile['mode'] + '-' + profile['profile']
    row = existing(path, 'retained-profile', key) or record(path, after.validate_one(str(path), write_html=False), 'retained-profile', key)
    assert row['failures'] == profile['failures'] and row['status'] == 'FAIL', row
assert len(rows) == 42
print('Eleven before/after PDF probes, eight entry points and twelve retained profile outcomes match.', flush=True)
