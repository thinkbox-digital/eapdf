"""Check that the added type guards preserve the twelve retained creator runs.

V29's complete-pipeline runs are reused; this is independent native-object
inspection, not twelve new V30 engine runs.
"""
import hashlib
import json
from pathlib import Path

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
old = json.loads((ROOT / 'docs/improvement/validator-24/evidence.json').read_text())
generated = {row['mode'] + '-' + row['profile']: row for row in old['creator']['generated']}
reports = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())['profiles']
prior = {row['file']: row for row in json.loads((ROOT / 'docs/improvement/validator-29/pdf-results.json').read_text())
         if row['entry'] == 'retained-profile'}
rows = []
for record in reports:
    key = record['mode'] + '-' + record['profile']
    report = json.loads((ROOT / record['report']).read_text())
    path = Path(report['summary']['file'])
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == generated[key]['sha256'] == prior[key]['sha256']
    assert prior[key]['status'] == 'FAIL' and prior[key]['failures'] == record['failures']
    with pikepdf.Pdf.open(path) as pdf:
        assert isinstance(pdf.Root.PageMode, pikepdf.Name)
        assert isinstance(pdf.Root.ViewerPreferences.NonFullScreenPageMode, pikepdf.Name)
        modes = dict(PageMode=str(pdf.Root.PageMode),
                     NonFullScreenPageMode=str(pdf.Root.ViewerPreferences.NonFullScreenPageMode))
    rows.append(dict(profile=key, sha256=digest, native_modes=modes,
                     added_type_guards=True, reused_v29_pipeline_outcome=prior[key]))
assert len(rows) == 12
(HERE / 'retained-name-preconditions.json').write_text(json.dumps(dict(
    boundary='Twelve exact V29 pipeline outcomes reused; actual native-name preconditions checked. No new V30 creator engine execution.',
    rows=rows), indent=2) + '\n')
print('Twelve retained creator files have actual name entries; all added guards are true. V29 failed acceptance outcomes remain applicable.')
