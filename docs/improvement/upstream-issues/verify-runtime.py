"""Verify upstream-issue probes and actual CLI/web worker behavior sequentially."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
FIXTURES = ROOT / 'tests/fixtures/eapdf/upstream-issues'
sys.path.insert(0, str(ROOT / 'tests'))
from test_preservation_safety import validator, web

cases = json.loads((FIXTURES / 'expectations.json').read_text())['cases']
summary = []
for case in cases:
    path = FIXTURES / case['file']
    assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
    result = validator.validate_one(path, write_html=False)
    (OUT / (case['file'] + '.json')).write_text(json.dumps(result, default=str, indent=2) + '\n')
    failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
    assert result['pdfa']['status'] == 'PASS', (case['file'], result['pdfa']['message'])
    assert result['summary']['status'] == case['expected_custom_status'], (case['file'], result['summary'])
    assert failures == case['expected_failures'], (case['file'], failures)
    dates = [item for item in result['items'] if item['code'] == 'XMP_CREATEDATE_SEMANTICS']
    assert dates and all(item['level'] == 'WARN' and item['details']['assessment'] == 'unavailable' for item in dates)
    summary.append(dict(file=case['file'], entry='engine', pdfa=result['pdfa']['status'],
                        overall=result['summary']['status'], failures=failures, provenance='unavailable'))
    print(summary[-1], flush=True)

for name in ('createdate-equal.pdf', 'mcaf-unlisted.pdf'):
    case = next(case for case in cases if case['file'] == name)
    path = FIXTURES / name
    for entry in ('cli', 'web'):
        if entry == 'cli':
            process = subprocess.run([sys.executable, '-B', str(ROOT / 'validate_eapdf.py'), str(path), '--json', '--no-html'],
                                     cwd=ROOT, capture_output=True, text=True, check=False, timeout=180)
            assert process.returncode == (2 if case['expected_failures'] else 0), process.stderr
            result = json.loads(process.stdout)
        else:
            result = web.run_pdf_validation(path)
        (OUT / (name + '.' + entry + '.json')).write_text(json.dumps(result, default=str, indent=2) + '\n')
        failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
        assert result['pdfa']['status'] == 'PASS', (name, entry, result['pdfa']['message'])
        assert result['summary']['status'] == case['expected_custom_status']
        assert failures == case['expected_failures'], (name, entry, failures)
        rendered = web.render_comparison_table([result])
        assert "data-rule-code='XMP_CREATEDATE_SEMANTICS' data-pdfa-covered='false'" in rendered
        assert "data-rule-code='AF_IN_NAME_TREE' data-pdfa-covered='false'" in rendered
        (OUT / (name + '.' + entry + '.html')).write_text(rendered)
        summary.append(dict(file=name, entry=entry, pdfa=result['pdfa']['status'],
                            overall=result['summary']['status'], failures=failures))
        print(summary[-1], flush=True)
(OUT / 'runtime-summary.json').write_text(json.dumps(summary, indent=2) + '\n')
