"""Actual PDF/A/ISO checks plus reproducible child-staging unavailability."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v83'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as v
import validator_web as web

OUT = HERE / 'pdf-reports'
OUT.mkdir(exist_ok=True)
rows = json.loads((HERE / 'pdf-results.json').read_text()) if (HERE / 'pdf-results.json').exists() else []


def record(path, result, entry='after', key=None):
    key = key or path.stem
    (OUT / (key + '-' + entry + '.json')).write_text(json.dumps(result, indent=2, default=str) + '\n')
    iso = next(i for i in result['items'] if i['code'] == 'ISO32005_UPSTREAM')
    row = dict(file=str(path.relative_to(ROOT)), key=key,
               sha256=hashlib.sha256(path.read_bytes()).hexdigest(), entry=entry,
               status=result['summary']['status'], pdfa=result['pdfa']['status'], iso=iso['level'],
               profile_verified=(iso.get('details') or {}).get('profile_verified'),
               failures=sorted({i['code'] for i in result['items'] if i['level'] == 'FAIL'}))
    rows.append(row)
    (HERE / 'pdf-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(row, flush=True)
    assert row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'], row
    return row


def complete(key, entry='after'):
    return any(row['key'] == key and row['entry'] == entry for row in rows)


folder = ROOT / 'tests/fixtures/eapdf/validator-collection-child-status'
cases = json.loads((folder / 'expectations.json').read_text())['cases']
with tempfile.TemporaryDirectory(prefix='eapdf-v83-pipeline-') as directory:
    blocker = Path(directory) / 'not-a-directory'
    blocker.write_bytes(b'EA-PDF child staging operational control\n')
    for case in cases:
        path = folder / case['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
        if not complete(path.stem):
            row = record(path, v.validate_one(str(path), write_html=False))
            assert row['status'] == case['expected_status'] and row['failures'] == case['expected_failures'], row
        if case['expected_status'] != 'PASS':
            continue
        key = path.stem + '-staging-unavailable'
        if complete(key):
            continue
        context = v.ChildValidationContext(include_pdfa=True)
        context.temporary_root = blocker
        result = v.validate_one(str(path), write_html=False, child_context=context)
        row = record(path, result, key=key)
        assert row['status'] == 'INCOMPLETE' and not row['failures'], row
        children = [i for i in result['items'] if i['code'] == 'COLLECTION_CHILD_VALIDATION']
        assert children and all(i['level'] == 'WARN' and i['details']['assessment'] == 'unavailable' for i in children)
    path = folder / 'normal-1c-valid.pdf'
    if not complete('staging-unavailable', 'cli'):
        process = subprocess.run([sys.executable, '-B', str(SNAPSHOT / 'validate_eapdf.py'), str(path), '--json', '--no-html'],
                                 cwd=ROOT, env=dict(os.environ, VALIDATOR_CHILD_TEMP_ROOT=str(blocker)),
                                 capture_output=True, text=True, timeout=240)
        assert process.returncode == 2, (process.returncode, process.stderr)
        row = record(path, json.loads(process.stdout), 'cli', 'staging-unavailable')
        assert row['status'] == 'INCOMPLETE' and not row['failures'], row
    if not complete('staging-unavailable', 'web-worker'):
        run_worker = web.bounded_subprocess

        def unavailable_staging(command, timeout, env=None):
            # Inject the same real filesystem failure immediately before launch.
            # The actual worker command, engine and result handling still run.
            Path(env['VALIDATOR_CHILD_TEMP_ROOT']).write_bytes(b'EA-PDF child staging operational control\n')
            return run_worker(command, timeout, env=env)

        with patch.object(web, 'bounded_subprocess', side_effect=unavailable_staging):
            result = web.run_pdf_validation(path.resolve())
        row = record(path, result, 'web-worker', 'staging-unavailable')
        assert row['status'] == 'INCOMPLETE' and not row['failures'], row

prior = {r.get('key', r['file']): r for r in json.loads((ROOT / 'docs/improvement/validator-82/pdf-results.json').read_text())
         if r['entry'] == 'retained-profile'}
for p in json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']:
    key = p['mode'] + '-' + p['profile']
    path = Path(json.loads((ROOT / p['report']).read_text())['summary']['file'])
    assert hashlib.sha256(path.read_bytes()).hexdigest() == prior[key]['sha256']
    if complete(key, 'retained-profile'):
        continue
    row = record(path, v.validate_one(str(path), write_html=False), 'retained-profile', key)
    assert row['status'] == 'FAIL' and row['failures'] == prior[key]['failures'], row
assert len(rows) == 28
print('Ten collection pipelines, four staging failures, CLI/worker and twelve retained creator diagnoses matched.', flush=True)
