"""Compare the curated fixture corpus with the accepted V20 engine."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT if '--preflight' in sys.argv else ROOT/'.eapdf-verification-v21-final'
sys.path.insert(0,str(FROZEN))
import validate_eapdf as after
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v20/validate_eapdf.py')
before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
assert hashlib.sha256(Path(before.__file__).read_bytes()).digest()==hashlib.sha256((HERE/'before-validate_eapdf.py').read_bytes()).digest()
rows=[]
for manifest in sorted((FROZEN/'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path=manifest.parent/case['file']
        row=dict(file=str(path.relative_to(FROZEN)),sha256=hashlib.sha256(path.read_bytes()).hexdigest())
        for label,engine in [('before',before),('after',after)]:
            report=engine.validate(str(path))
            row[label]=dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
        rows.append(row)
        if row['before']!=row['after']:print(row,flush=True)
        if manifest.parent.name in ('validator-ambiguous-bytes','validator-attachment-equivalence'):
            assert row['after']==dict(status=case['expected_status'],failures=sorted(case['expected_failures'])),row
(HERE/('preflight-regressions.json' if '--preflight' in sys.argv else 'custom-regressions.json')).write_text(json.dumps(rows,indent=2)+'\n')
changed=[r for r in rows if r['before']!=r['after']]
expected={'all-sizes-wrong.pdf','one-size-wrong.pdf','distinct-sizes-impossible.pdf',
          'all-checksums-wrong.pdf','distinct-checksums-impossible.pdf','isolated-ambiguous-size.pdf','ambiguous-records.pdf'}
assert len(rows)==184,(len(rows),len(changed))
assert {Path(row['file']).name for row in changed}==expected,changed
prior=json.loads((ROOT/'docs/improvement/validator-20/custom-regressions.json').read_text())
lookup={row['file']:row for row in rows}
for old in prior:
    current=lookup[old['file']]
    assert hashlib.sha256((ROOT/'.eapdf-verification-v20'/old['file']).read_bytes()).hexdigest()==current['sha256']
    if Path(old['file']).name not in ('isolated-ambiguous-size.pdf','ambiguous-records.pdf'):assert old['after']==current['after'],current
print('184 outcomes checked; 174 prior PDF hashes unchanged; 172 prior outcomes unchanged; seven reviewed changes.',flush=True)
