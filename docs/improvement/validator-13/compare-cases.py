"""Compare identical final fixture bytes under the old and final engines."""
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
before = json.loads((HERE / 'before-final-fixtures.json').read_text())
rows = []
for old in before:
    report = json.loads((HERE / 'retained-corpus-final' /
        ('validator-attachment-filenames--' + old['file'] + '.json')).read_text())
    rows.append({'file': old['file'], 'sha256': old['sha256'],
        'before_pdfa': old['pdfa'], 'after_pdfa': report['pdfa']['status'],
        'before_status': old['status'], 'after_status': report['summary']['status'],
        'before_failures': old['failures'],
        'after_failures': sorted({item['code'] for item in report['items'] if item['level'] == 'FAIL'})})
assert len(rows) == 12 and all(row['before_pdfa'] == row['after_pdfa'] == 'PASS' for row in rows)
(HERE / 'before-after-comparison.json').write_text(json.dumps(rows, indent=2) + '\n')
for row in rows:
    print(row['file'], row['before_status'], '->', row['after_status'])
