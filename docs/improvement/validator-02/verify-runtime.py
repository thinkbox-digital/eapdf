"""Reproduce V02 CLI and local web-worker checks using frozen V01 fixtures."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'tests'))
from test_preservation_safety import validator, web


def hidden(rendered, code):
    match = re.search(r"<tr class='rule-title-row' data-rule-code='" + code
                      + r"' data-pdfa-covered='(true|false)'", rendered)
    assert match, code
    return match[1] == 'true'


results = []
for name, status, failures in (
    ('valid', 'PASS', set()),
    ('content-set-field-name', 'FAIL', {'MAIL_CONTENTSET_CHILDREN'}),
):
    path = ROOT / 'tests/fixtures/eapdf/validator-table11' / (name + '.pdf')
    for entry in ('cli', 'web'):
        if entry == 'cli':
            command = [sys.executable, '-B', str(ROOT / 'validate_eapdf.py'), str(path), '--no-html', '--json']
            process = subprocess.run(command, capture_output=True, text=True, check=False, cwd=ROOT, timeout=180)
            assert process.returncode == (0 if status == 'PASS' else 2), process.stderr
            result = json.loads(process.stdout)
        else:
            result = web.run_pdf_validation(path)
        assert result['summary']['status'] == status, result['summary']
        assert result['pdfa']['status'] == 'PASS', result['pdfa']['message']
        assert {item['code'] for item in result['items'] if item['level'] == 'FAIL'} == failures
        delegated = [item for item in result['items'] if item['code'] in validator.PDFA_COVERED_RULES]
        assert len(delegated) == 11
        assert all(item['level'] == 'N/A' and item['details']['state'] == 'delegated' for item in delegated)
        rendered = web.render_comparison_table([result])
        assert hidden(rendered, 'OUTPUT_INTENT')
        for code in ('PDFA_PART', 'PDF_EFFECTIVE_VERSION', 'DOCINFO_TITLE_MATCH', 'CATALOG_PIECEINFO',
                     'MAIL_CONTENTSET_CHILDREN', 'PDFA3_EAPDF_XMP_EXTENSION_SCHEMA'):
            assert not hidden(rendered, code), code
        (OUT / (name + '-' + entry + '.json')).write_text(json.dumps(result, indent=2) + '\n')
        (OUT / (name + '-' + entry + '.html')).write_text(rendered)
        results.append(dict(fixture=path.name, entry=entry, overall=status, pdfa=result['pdfa']['status'],
                            delegated_rows=len(delegated), custom_failures=sorted(failures),
                            pdf_sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
(OUT / 'runtime-summary.json').write_text(json.dumps(results, indent=2) + '\n')
print(json.dumps(results, indent=2))
