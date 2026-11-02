"""Fast differential custom-only preflight; no PDF/A or ISO execution claim."""
from pathlib import Path
import importlib.util
import json
import sys

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT))
import validate_eapdf as current
spec=importlib.util.spec_from_file_location('baseline_v13',ROOT/'.eapdf-verification-v13-final/validate_eapdf.py')
before=importlib.util.module_from_spec(spec)
spec.loader.exec_module(before)
rows=[]
for manifest in sorted((ROOT/'tests/fixtures/eapdf').rglob('expectations.json')):
 for case in json.loads(manifest.read_text())['cases']:
  path=manifest.parent/case['file']
  results={}
  for name,engine in [('before',before),('after',current)]:
   report=engine.validate(str(path))
   results[name]={'status':report.summary()['status'],'failures':sorted({r['code'] for r in report.items if r['level']=='FAIL'})}
  row={'file':str(path.relative_to(ROOT)),**results}
  rows.append(row)
  if results['before']!=results['after']: print(row,flush=True)
Path(__file__).with_name('custom-preflight.json').write_text(json.dumps(rows,indent=2)+'\n')
print('Compared',len(rows),'custom-only cases; full upstream verification still required',flush=True)
