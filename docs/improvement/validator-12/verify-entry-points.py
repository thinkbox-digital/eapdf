"""Run from frozen V12; keep exact CLI and isolated worker reports."""
from pathlib import Path
import json
import shutil
import subprocess
import sys

sys.path.insert(0, str(Path.cwd()))
import validator_web as web

output = Path('../docs/improvement/validator-12/entry-points')
output.mkdir(exist_ok=True)
rows = []
for name, expected in [('collision-reversed', []), ('checksum-wrong', ['ASSET_MD5_MATCH']),
                       ('association-ambiguous', ['ASSET_MD5_MATCH'])]:
    path = Path('tests/fixtures/eapdf/validator-asset-ownership') / (name + '.pdf')
    for entry in ('cli', 'web-worker'):
        if entry == 'cli':
            process = subprocess.run([sys.executable, 'validate_eapdf.py', str(path), '--json', '--html',
                                      str(output / (name + '.html'))], capture_output=True, text=True, timeout=240)
            (output / (name + '.stderr')).write_text(process.stderr)
            assert process.returncode in (0, 2), (process.returncode, process.stderr)
            result = json.loads(process.stdout)
        else:
            result = web.run_pdf_validation(path.resolve())
            if result.get('html_output'):
                shutil.copy2(result['html_output'], output / (name + '-worker.html'))
        (output / (name + '-' + entry + '.json')).write_text(json.dumps(result, indent=2, default=str))
        failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
        iso = next(item for item in result['items'] if item['code'] == 'ISO32005_UPSTREAM')
        row = dict(file=path.name, entry=entry, pdfa=result['pdfa']['status'], status=result['summary']['status'],
                   failures=failures, iso=iso['level'])
        rows.append(row)
        print(row, flush=True)
        assert row['pdfa'] == 'PASS' and failures == expected and iso['details']['profile_verified'], row
        assert row['status'] == ('FAIL' if expected else 'PASS'), row
Path('../docs/improvement/validator-12/entry-points.json').write_text(json.dumps(rows, indent=2) + '\n')
