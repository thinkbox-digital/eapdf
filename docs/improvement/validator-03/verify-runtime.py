"""Verify frozen V03 expectations through the engine, CLI and local web worker."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
FIXTURES = ROOT / 'tests/fixtures/eapdf/validator-xmp-identity'
sys.path.insert(0, str(ROOT / 'tests'))
from test_preservation_safety import validator, web


cases = json.loads((FIXTURES / 'expectations.json').read_text())['cases']
results = []
for case in cases:
    path = FIXTURES / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    result = validator.validate_one(path, write_html=False)
    (OUT / (case['file'] + '.json')).write_text(json.dumps(result, default=str, indent=2) + '\n')
    failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
    assert result['pdfa']['status'] == 'PASS', (case['file'], result['pdfa']['message'])
    assert result['summary']['status'] == case['expected_eapdf_status'], (case['file'], result['summary'])
    assert failures == sorted(case['expected_failures']), (case['file'], failures)
    results.append(dict(file=case['file'], entry='engine', pdfa=result['pdfa']['status'],
                        overall=result['summary']['status'], custom_failures=failures))
    print(results[-1], flush=True)

for name in ('guid-qualified.pdf', 'structure-guid-whitespace-mismatch.pdf'):
    case = next(case for case in cases if case['file'] == name)
    path = FIXTURES / name
    for entry in ('cli', 'web'):
        if entry == 'cli':
            command = [sys.executable, '-B', str(ROOT / 'validate_eapdf.py'), str(path), '--json', '--no-html']
            process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False, timeout=180)
            assert process.returncode == (2 if case['expected_failures'] else 0), process.stderr
            result = json.loads(process.stdout)
        else:
            result = web.run_pdf_validation(path)
        (OUT / (name + '.' + entry + '.json')).write_text(json.dumps(result, default=str, indent=2) + '\n')
        failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
        assert result['pdfa']['status'] == 'PASS', (name, entry, result['pdfa']['message'])
        assert result['summary']['status'] == case['expected_eapdf_status']
        assert failures == sorted(case['expected_failures']), (name, entry, failures)
        rendered = web.render_comparison_table([result])
        assert "data-rule-code='MAIL_GUID_XMP' data-pdfa-covered='false'" in rendered
        assert "data-rule-code='MAIL_MESSAGE_GUID_MATCH_DPM_XMP' data-pdfa-covered='false'" in rendered
        (OUT / (name + '.' + entry + '.html')).write_text(rendered)
        results.append(dict(file=name, entry=entry, pdfa=result['pdfa']['status'],
                            overall=result['summary']['status'], custom_failures=failures))
        print(results[-1], flush=True)
(OUT / 'runtime-summary.json').write_text(json.dumps(results, indent=2) + '\n')
