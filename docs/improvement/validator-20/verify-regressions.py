"""Compare all custom fixture outcomes against the accepted V19 engine."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v20'
sys.path.insert(0,str(FROZEN))
import validate_eapdf as after
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v19/validate_eapdf.py')
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
        if row['before']!=row['after']: print(row,flush=True)
(HERE/'custom-regressions.json').write_text(json.dumps(rows,indent=2)+'\n')
changed=[row for row in rows if row['before']!=row['after']]
assert len(rows)==174,(len(rows),len(changed))
assert {Path(row['file']).name for row in changed}=={'extra-empty-parameter.pdf','nameless-parameter.pdf','unquoted-space.pdf','quoted-source.pdf','quoted-source-cleaned.pdf','malformed-source-preserved.pdf','malformed-source-repaired.pdf'},changed
prior=json.loads((ROOT/'docs/improvement/validator-19/custom-regressions.json').read_text())
lookup={row['file']:row for row in rows}
for old in prior:
    current=lookup[old['file']]
    assert old['after']==current['after'],current
    assert hashlib.sha256((ROOT/'.eapdf-verification-v19'/old['file']).read_bytes()).hexdigest()==current['sha256']
print('174 custom outcomes checked; all 164 prior bytes/outcomes unchanged; only seven reviewed changes.',flush=True)
