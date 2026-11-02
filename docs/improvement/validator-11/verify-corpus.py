"""Run from the frozen V11 root; validate exact custom and upstream expectations."""
from pathlib import Path
import hashlib
import json
import sys

sys.path.insert(0, str(Path.cwd()))
import validate_eapdf as v

output = Path('../docs/improvement/validator-11/retained-corpus')
output.mkdir(exist_ok=True)
rows = []
for manifest in sorted(Path('tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path = manifest.parent / case['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256'], path
        result = v.validate_one(str(path), write_html=False)
        (output / (manifest.parent.name + '--' + path.name + '.json')).write_text(json.dumps(result, indent=2, default=str))
        failures = sorted({item['code'] for item in result['items'] if item['level'] == 'FAIL'})
        expected = sorted(set(case['expected_failures'] + case.get('expected_upstream_failures', [])))
        iso = next(item for item in result['items'] if item['code'] == 'ISO32005_UPSTREAM')
        clauses = sorted(rule['clause'] for rule in (iso.get('details') or {}).get('rule_summaries', []))
        row = dict(file=str(path), pdfa=result['pdfa']['status'], status=result['summary']['status'],
                   failures=failures, expected=expected, iso=iso['level'], iso_clauses=clauses)
        if 'expected_iso_clauses' in case:
            row['clauses_match'] = clauses == sorted(case['expected_iso_clauses'])
        rows.append(row)
        print(row, flush=True)
Path('../docs/improvement/validator-11/retained-corpus-results.json').write_text(json.dumps(rows, indent=2) + '\n')
assert all(row['pdfa'] == 'PASS' and row['failures'] == row['expected']
           and row['status'] == ('FAIL' if row['expected'] else 'PASS') and row.get('clauses_match', True)
           for row in rows)
print('All', len(rows), 'frozen custom/upstream outcomes match', flush=True)
