"""Exact title-language engine controls with explicitly bounded before-result reuse."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v33'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
import validator_web as web
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v32/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
assert Path(before.__file__).read_bytes() == (HERE / 'before-validate_eapdf.py').read_bytes()
directory = SNAPSHOT / 'tests/fixtures/eapdf/validator-required-metadata'
cases = json.loads((directory / 'expectations.json').read_text())['cases']
OUT = HERE / 'pdf-reports'
OUT.mkdir(exist_ok=True)
rows = json.loads((HERE / 'pdf-results.json').read_text()) if '--resume' in sys.argv else []
old_inputs = {Path(c['file']).stem: c for c in json.loads((ROOT / 'docs/improvement/validator-32/next-metadata-type-probes/results.json').read_text())}

def record(path, result, label, reused=None):
    (OUT / (path.stem + '-' + label + '.json')).write_text(json.dumps(result, indent=2, default=str) + '\n')
    iso = next(i for i in result['items'] if i['code'] == 'ISO32005_UPSTREAM')
    fields = {i['code']: i for i in result['items'] if i['code'] in ('XMP_CREATOR', 'XMP_PRODUCER', 'XMP_CREATEDATE')}
    row = dict(file=path.stem, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), entry=label,
        status=result['summary']['status'], pdfa=result['pdfa']['status'], iso=iso['level'],
        profile_verified=iso.get('details', {}).get('profile_verified'), metadata_fields=fields, failures=sorted({i['code'] for i in result['items'] if i['level'] == 'FAIL'}))
    if reused:
        row['reused_before_report'] = reused
    rows.append(row)
    (HERE / 'pdf-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(row, flush=True)
    return row

def existing(path, label):
    row = next((r for r in rows if r['file'] == path.stem and r['entry'] == label), None)
    if row:
        assert row['sha256'] == hashlib.sha256(path.read_bytes()).hexdigest()
    return row

for case in cases:
    path = directory / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    if not existing(path, 'before'):
        old = old_inputs.get(path.stem)
        if old:
            assert old['sha256'] == case['sha256']
            saved = ROOT / 'docs/improvement/validator-32/next-metadata-type-probes' / path.with_suffix('.json').name
            record(path, json.loads(saved.read_text()), 'before', str(saved.relative_to(ROOT)))
        else:
            record(path, before.validate_one(str(path), write_html=False), 'before')
    row = existing(path, 'after') or record(path, after.validate_one(str(path), write_html=False), 'after')
    assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row
    assert row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'], row

for name, entry in [('creator-bag.pdf', 'cli'), ('createdate-reduced.pdf', 'web-worker')]:
    path = directory / name
    case = next(c for c in cases if c['file'] == name)
    if existing(path, entry):
        continue
    if entry == 'cli':
        proc = subprocess.run([sys.executable, '-B', 'validate_eapdf.py', str(path), '--json',
            '--html', str(OUT / (path.stem + '.html'))], cwd=SNAPSHOT, capture_output=True, text=True, timeout=240)
        (OUT / (path.stem + '.stderr')).write_text(proc.stderr)
        assert proc.returncode == (2 if case['expected_status'] == 'FAIL' else 0), (proc.returncode, proc.stderr)
        result = json.loads(proc.stdout)
    else:
        result = web.run_pdf_validation(path.resolve())
    row = record(path, result, entry)
    assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row
assert len(rows) == 26
print('Twelve exact-byte engine probes and two entry points match; four exact V32 before-runs reused.', flush=True)
