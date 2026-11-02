"""Actual upstream, CLI and worker gates on frozen operand controls."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v24'
os.environ['VERAPDF_MOUNT_ROOT'] = str(ROOT)
os.environ['GOMAXPROCS'] = '2'
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as after
import validator_web as web
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v23/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
directory = SNAPSHOT / 'tests/fixtures/eapdf/validator-operands'
cases = json.loads((directory / 'expectations.json').read_text())['cases']
OUT = HERE / 'pdf-reports'
OUT.mkdir(exist_ok=True)
rows = []


def record(path, result, label):
    (OUT / (path.stem + '-' + label + '.json')).write_text(json.dumps(result, indent=2, default=str) + '\n')
    iso = next(i for i in result['items'] if i['code'] == 'ISO32005_UPSTREAM')
    row = dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), entry=label,
               status=result['summary']['status'], pdfa=result['pdfa']['status'], iso=iso['level'],
               profile_verified=iso.get('details', {}).get('profile_verified'),
               failures=sorted({i['code'] for i in result['items'] if i['level'] == 'FAIL'}))
    rows.append(row)
    (HERE / 'pdf-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(row, flush=True)
    assert row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'], row
    return row


for case in cases:
    path = directory / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    record(path, before.validate_one(str(path), write_html=False), 'before')
    row = record(path, after.validate_one(str(path), write_html=False), 'after')
    assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row

# Retain the actual creator defect on identical historical bytes too.
path = SNAPSHOT / 'tests/fixtures/eapdf/validator-table11/valid.pdf'
old = record(path, before.validate_one(str(path), write_html=False), 'retained-before')
new = record(path, after.validate_one(str(path), write_html=False), 'retained-after')
assert old['status'] == 'PASS' and new['failures'] == ['CUSTOM_TAG_OPERAND_MATCH']

for name in ('valid.pdf', 'swapped.pdf', 'standard-only.pdf'):
    case = next(c for c in cases if c['file'] == name)
    path = directory / name
    for entry in ('cli', 'web-worker'):
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
        assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row
assert len(rows) == 18
print('Six before/after PDF probes and six actual CLI/worker checks match.', flush=True)
