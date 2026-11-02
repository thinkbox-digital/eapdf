"""Reuse fresh V37 upstream diagnoses only after native guards and current custom execution."""
import hashlib
import json
from pathlib import Path
import sys

import pikepdf

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / '.eapdf-verification-v38'))
import validate_eapdf as v

digest = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
prior = json.loads((ROOT / 'docs/improvement/validator-37/pdf-results.json').read_text())
diagnosis = json.loads((ROOT / 'docs/improvement/validator-24/profile-diagnosis.json').read_text())
rows = []
for profile in diagnosis['profiles']:
    key = profile['mode'] + '-' + profile['profile']
    receipt = next(r for r in prior if r['file'] == key and r['entry'] == 'retained-profile')
    original = json.loads((ROOT / profile['report']).read_text())
    path = Path(original['summary']['file'])
    assert digest(path) == receipt['sha256']
    with pikepdf.Pdf.open(path) as pdf:
        root, node, nodes = v.get_dpart_nodes(pdf)
        assert isinstance(root, pikepdf.Dictionary) and all(isinstance(i['node'], pikepdf.Dictionary) for i in nodes)
        ranges = [v.page_range_from_dpart(i, v.page_objgen_map(pdf))[:2] for i in nodes if i['child_count'] == 0]
        assert ranges and ranges[0][0] == 1 and ranges[-1][1] == len(pdf.pages)
        assert all(a[1] + 1 == b[0] for a, b in zip(ranges, ranges[1:]))
    report = v.validate(str(path))
    failures = sorted({i['code'] for i in report.items if i['level'] == 'FAIL'})
    assert report.summary()['status'] == 'FAIL' and failures == profile['failures']
    assert all(next(i for i in report.items if i['code'] == code)['level'] == 'PASS'
               for code in ('DPART_OBJECT_STRUCTURE', 'PAGE_DPART_PRESENT'))
    saved = ROOT / 'docs/improvement/validator-37/pdf-reports' / (key + '-retained-profile.json')
    rows.append(dict(profile=key, sha256=digest(path), ranges=ranges, current_custom_status='FAIL',
                     current_custom_failures=failures, native_guards_pass=True,
                     reused_upstream_report=str(saved.relative_to(ROOT)), reused_report_sha256=digest(saved),
                     pdfa=receipt['pdfa'], iso=receipt['iso'], profile_verified=receipt['profile_verified']))
assert len(rows) == 12
(HERE / 'retained-profile-preconditions.json').write_text(json.dumps(dict(
    boundary='Twelve fresh V37 upstream reports reused on exact original bytes after independent dictionary/range/order guards and twelve current custom executions. No new creator engine runs, Java build or sample generation.',
    rows=rows), indent=2) + '\n')
print('Twelve exact-byte native guards/current custom diagnoses match; fresh V37 upstream receipts retained.')
