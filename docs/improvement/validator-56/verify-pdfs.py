"""Exact-byte independent DPart controls and real CLI/worker executions."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v56'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
import validator_web as web

spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v55-r2/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
assert Path(before.__file__).read_bytes() == (HERE / 'before-validate_eapdf.py').read_bytes()
directory = SNAPSHOT / 'tests/fixtures/eapdf/validator-description-decoding'
cases = json.loads((directory / 'expectations.json').read_text())['cases']
OUT = HERE / 'pdf-reports'
OUT.mkdir(exist_ok=True)
PROBES = ROOT / 'docs/improvement/validator-55/next-description-probes'
saved = {Path(case['file']).stem: PROBES / (Path(case['file']).stem + '.json') for case in cases}
rows = json.loads((HERE / 'pdf-results.json').read_text()) if '--resume' in sys.argv and (HERE / 'pdf-results.json').exists() else []


def record(path, result, entry, reused=None, key=None):
    key = key or path.stem
    (OUT / (key + '-' + entry + '.json')).write_text(json.dumps(result, indent=2, default=str) + '\n')
    iso = next(i for i in result['items'] if i['code'] == 'ISO32005_UPSTREAM')
    row = dict(file=key, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), entry=entry,
               status=result['summary']['status'], pdfa=result['pdfa']['status'], iso=iso['level'],
               profile_verified=(iso.get('details') or {}).get('profile_verified'),
               failures=sorted({i['code'] for i in result['items'] if i['level'] == 'FAIL'}))
    row['recommendation'] = sorted({i['level'] for i in result['items'] if i['code'] == 'MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'})
    if reused: row['reused_before_report'] = str(reused.relative_to(ROOT))
    rows.append(row)
    (HERE / 'pdf-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(row, flush=True)
    assert row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'], row
    return row


def existing(path, entry, key=None):
    key = key or path.stem
    row = next((r for r in rows if r['file'] == key and r['entry'] == entry), None)
    if row: assert row['sha256'] == hashlib.sha256(path.read_bytes()).hexdigest()
    return row


for case in cases:
    path = directory / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    if not existing(path, 'before'):
        old = saved.get(path.stem)
        if old:
            assert hashlib.sha256(old.with_suffix('.pdf').read_bytes()).hexdigest() == case['sha256']
            record(path, json.loads(old.read_text()), 'before', reused=old)
        else:
            record(path, before.validate_one(str(path), write_html=False), 'before')
    row = existing(path, 'after') or record(path, after.validate_one(str(path), write_html=False), 'after')
    assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row

for name, entry in [('unreadable-utf8.pdf', 'cli'), ('unreadable-with-revision-failure.pdf', 'web-worker')]:
    path = directory / name
    case = next(c for c in cases if c['file'] == name)
    if existing(path, entry): continue
    if entry == 'cli':
        proc = subprocess.run([sys.executable, '-B', 'validate_eapdf.py', str(path), '--json', '--html',
                               str(OUT / (path.stem + '.html'))], cwd=SNAPSHOT,
                              capture_output=True, text=True, timeout=240)
        (OUT / (path.stem + '.stderr')).write_text(proc.stderr)
        assert proc.returncode == 2, (proc.returncode, proc.stderr)
        result = json.loads(proc.stdout)
    else:
        result = web.run_pdf_validation(path.resolve())
    row = record(path, result, entry)
    assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row
# Freshly exercise all six retained creator profiles in both modes.
diagnosis = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())
creator_baseline = {r['file']:r for r in json.loads((HERE/'creator-baseline.json').read_text())['profiles']}
prior = {r['file']: r for r in json.loads((ROOT / 'docs/improvement/validator-55/pdf-results.json').read_text()) if r['entry'] == 'retained-profile'}
for profile in diagnosis['profiles']:
    key = profile['mode'] + '-' + profile['profile']
    original = json.loads((ROOT / profile['report']).read_text())
    path = Path(original['summary']['file'])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == prior[key]['sha256']
    row = existing(path, 'retained-profile', key) or record(path, after.validate_one(str(path), write_html=False), 'retained-profile', key=key)
    assert row['status'] == 'FAIL' and row['failures'] == creator_baseline[key]['failures'], row
assert len(rows) == 30
assert sum(r['entry'] == 'before' and 'reused_before_report' in r for r in rows) == 8
print('Eight exact-byte description controls, two entry points and twelve fresh creator diagnoses match; eight exact V55 before receipts reused.',flush=True)
