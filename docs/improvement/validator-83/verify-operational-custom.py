"""Recheck identical valid collection bytes with an unusable staging root."""
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / '.eapdf-verification-v83'))
import validate_eapdf as after

spec = importlib.util.spec_from_file_location('before', ROOT / '.eapdf-verification-v82/validate_eapdf.py')
before = importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
folder = ROOT / 'tests/fixtures/eapdf/validator-collection-child-status'
rows = []
with tempfile.TemporaryDirectory(prefix='eapdf-v83-staging-') as directory:
    blocker = Path(directory) / 'not-a-directory'
    blocker.write_bytes(b'EA-PDF child staging operational control\n')
    for case in json.loads((folder / 'expectations.json').read_text())['cases']:
        if case['expected_status'] != 'PASS':
            continue
        path = folder / case['file']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == case['sha256']
        outcomes = {}
        for label, module in [('before', before), ('after', after)]:
            context = module.ChildValidationContext()
            context.temporary_root = blocker
            report = module.validate(str(path), child_context=context)
            outcomes[label] = dict(status=report.summary()['status'],
                                   failures=sorted({i['code'] for i in report.items if i['level'] == 'FAIL'}),
                                   child_findings=[i for i in report.items if i['code'] == 'COLLECTION_CHILD_VALIDATION'])
            assert not context.active and not context.cache
        assert outcomes['before']['status'] == 'FAIL'
        assert outcomes['before']['failures'] == ['COLLECTION_CHILD_VALIDATION']
        assert outcomes['after']['status'] == 'INCOMPLETE' and not outcomes['after']['failures']
        assert all(i['level'] == 'WARN' and i['details']['assessment'] == 'unavailable'
                   for i in outcomes['after']['child_findings'])
        rows.append(dict(file=str(path.relative_to(ROOT)), sha256=case['sha256'],
                         scenario='child_staging_root_is_regular_file', **outcomes))
assert len(rows) == 4
(HERE / 'operational-custom.json').write_text(json.dumps(rows, indent=2) + '\n')
print('Four unchanged valid collection PDFs: unsupported FAIL becomes INCOMPLETE.')
