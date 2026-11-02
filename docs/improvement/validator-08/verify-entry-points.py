"""Run from the frozen V08 snapshot; preserve CLI and isolated worker reports."""
from pathlib import Path
import json
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path.cwd()))
import validator_web as web

out = Path('../docs/improvement/validator-08/entry-points')
out.mkdir(exist_ok=True)
rows = []
for name, expected in [('valid', []), ('legacy-https-namespace', ['LOGICAL_TS32005_DECLARATION']),
                       ('paragraph-in-paragraph', ['ISO32005_UPSTREAM'])]:
    path = Path('tests/fixtures/eapdf/validator-declarations') / (name + '.pdf')
    for entry in ('cli', 'web-worker'):
        if entry == 'cli':
            proc = subprocess.run([sys.executable, 'validate_eapdf.py', str(path), '--json', '--html',
                                   str(out / (name + '.html'))], capture_output=True, text=True, timeout=240)
            (out / (name + '.stderr')).write_text(proc.stderr)
            assert proc.returncode in (0, 2), (proc.returncode, proc.stdout, proc.stderr)
            result = json.loads(proc.stdout)
        else:
            result = web.run_pdf_validation(path.resolve())
            if result.get('html_output'):
                shutil.copy2(result['html_output'], out / (name + '-worker.html'))
        (out / (name + '-' + entry + '.json')).write_text(json.dumps(result, indent=2, default=str))
        failures = sorted({x['code'] for x in result['items'] if x['level'] == 'FAIL'})
        iso = next(x for x in result['items'] if x['code'] == 'ISO32005_UPSTREAM')
        row = dict(file=path.name, entry=entry, pdfa=result['pdfa']['status'],
                   status=result['summary']['status'], failures=failures, iso=iso['level'])
        rows.append(row)
        print(row, flush=True)
        assert row['pdfa'] == 'PASS' and failures == expected and iso['details']['profile_verified'], row
Path('../docs/improvement/validator-08/entry-points.json').write_text(json.dumps(rows, indent=2) + '\n')
