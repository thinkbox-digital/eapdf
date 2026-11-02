"""Compare all custom fixture outcomes against the accepted V17 engine."""
from pathlib import Path
import hashlib
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v19'
sys.path.insert(0,str(FROZEN))
import validate_eapdf as after
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v18-final/validate_eapdf.py')
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
assert len(rows)==164,(len(rows),len(changed))
assert {Path(row['file']).name for row in changed}=={'two-owners.pdf','two-owners-reversed.pdf','isolated-equivalent.pdf','isolated-wrong-size.pdf','isolated-wrong-media.pdf'},changed
prior=json.loads((ROOT/'docs/improvement/validator-18/custom-regressions.json').read_text())
lookup={row['file']:row for row in rows}
for old in prior:
    current=lookup[old['file']]
    assert old['after']==current['after'],current
    assert hashlib.sha256((ROOT/'.eapdf-verification-v18-final'/old['file']).read_bytes()).hexdigest()==current['sha256']
print('164 custom outcomes checked; all 156 prior bytes/outcomes unchanged; only five reviewed changes.',flush=True)
