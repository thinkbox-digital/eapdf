"""Bound creator evidence reuse by checking the one changed recommendation."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
from xml.etree import ElementTree as ET
import pikepdf
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / '.eapdf-verification-v32'))
import validate_eapdf as after
spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v31/validate_eapdf.py')
before = importlib.util.module_from_spec(spec); spec.loader.exec_module(before)
prior = json.loads((ROOT / 'docs/improvement/validator-31/retained-name-preconditions.json').read_text())
diagnosis = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())
rows = []
for entry in prior['rows']:
    mode, profile = entry['profile'].split('-', 1)
    record = next(r for r in diagnosis['profiles'] if r['mode'] == mode and r['profile'] == profile)
    report = json.loads((ROOT / record['report']).read_text())
    path = Path(report['summary']['file'])
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert digest == entry['sha256']
    with pikepdf.Pdf.open(path) as pdf:
        metadata = ET.fromstring(after.read_metadata_stream(pdf))
        values = after.xmp_default_language_values(metadata, after.NS_DC, 'title')
        old_value = before.collect_alt_xdefault(metadata)
        assert values == [old_value] and old_value
    rows.append(dict(profile=entry['profile'], sha256=digest, default_title=values[0],
        unchanged_title_predicate=True, reused_v29_pipeline_outcome=entry['reused_v29_pipeline_outcome']))
assert len(rows) == 12
(HERE / 'retained-title-preconditions.json').write_text(json.dumps(dict(
    boundary='Twelve exact V29 runs reused via V31 after checking actual x-default values. No new creator engine execution.', rows=rows), indent=2) + '\n')
print('All twelve actual creator titles satisfy the changed recommendation; known failed acceptance remains.')
