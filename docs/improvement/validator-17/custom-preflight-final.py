"""Compare all 150 custom-only outcomes against immutable V16; upstream is separate."""
from pathlib import Path
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
import validate_eapdf as after
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v16/validate_eapdf.py')
before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
rows=[]
for manifest in sorted((ROOT/'tests/fixtures/eapdf').rglob('expectations.json')):
    for case in json.loads(manifest.read_text())['cases']:
        path=manifest.parent/case['file'];row=dict(file=str(path.relative_to(ROOT)))
        for label,engine in [('before',before),('after',after)]:
            report=engine.validate(str(path))
            row[label]=dict(status=report.summary()['status'],failures=sorted({item['code'] for item in report.items if item['level']=='FAIL'}))
        rows.append(row)
        if row['before']!=row['after']:print(row,flush=True)
(HERE/'custom-preflight.json').write_text(json.dumps(rows,indent=2)+'\n')
changed=[row for row in rows if row['before']!=row['after']]
assert len(rows)==150 and len(changed)==7,(len(rows),len(changed))
assert {Path(row['file']).name for row in changed}=={name+'.pdf' for name in ('message-id-only','optional-dpm-id-omitted','message-id-only-native-alias','wrong-owner','source-omitted-message-id','source-omitted-missing-count','source-omitted-zero-count')}
print('All',len(rows),'custom-only cases checked; only the seven reviewed owner/count outcomes changed',flush=True)
