"""Real ISO failure plus missing/corrupt profile acceptance boundaries.

The two unavailable cases inject an isolated configuration root, not mocked
engine responses. Actual PDF/A execution and custom checks still run.
"""
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v25'
os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
sys.path.insert(0, str(SNAPSHOT))
import validate_eapdf as v

output = HERE / 'iso-boundaries'
output.mkdir(exist_ok=True)
rows = []


def record(label, path, expected_status, expected_iso, verified):
    report = v.validate_one(str(path), write_html=False)
    (output / (label + '.json')).write_text(json.dumps(report, indent=2, default=str) + '\n')
    iso = next(i for i in report['items'] if i['code'] == 'ISO32005_UPSTREAM')
    row = dict(case=label, file=str(path.relative_to(ROOT)), sha256=hashlib.sha256(path.read_bytes()).hexdigest(),
               status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
               upstream_status=iso['details'].get('status'), profile_verified=iso['details'].get('profile_verified', False),
               failures=sorted({i['code'] for i in report['items'] if i['level'] == 'FAIL'}))
    rows.append(row)
    (output / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(row, flush=True)
    assert row['pdfa'] == 'PASS' and row['status'] == expected_status and row['iso'] == expected_iso
    assert row['profile_verified'] is verified
    return row


negative = SNAPSHOT / 'tests/fixtures/eapdf/validator-declarations/paragraph-in-paragraph.pdf'
row = record('invalid-inclusion', negative, 'FAIL', 'FAIL', True)
assert row['failures'] == ['CUSTOM_TAG_OPERAND_MATCH', 'ISO32005_UPSTREAM']
positive = SNAPSHOT / 'tests/fixtures/eapdf/validator-ambiguous-sources/unique-name.pdf'
record('available', positive, 'PASS', 'PASS', True)
original_root = v.ROOT
try:
    with tempfile.TemporaryDirectory(prefix='eapdf-iso-unavailable-') as directory:
        v.ROOT = Path(directory)
        row = record('missing-profile', positive, 'INCOMPLETE', 'WARN', False)
        assert row['upstream_status'] == 'ERROR' and not row['failures']
        profile = v.ROOT / v.RULE_TEXT['ISO32005_UPSTREAM']['validation_scope']['external_profile']['profile']
        profile.parent.mkdir(parents=True)
        profile.write_text('Deliberately invalid profile bytes for the hash guard')
        row = record('corrupt-profile', positive, 'INCOMPLETE', 'WARN', False)
        assert row['upstream_status'] == 'ERROR' and not row['failures']
finally:
    v.ROOT = original_root
assert len(rows) == 4
print('Real ISO failure and isolated missing/corrupt profile configurations preserve exact acceptance boundaries.', flush=True)
