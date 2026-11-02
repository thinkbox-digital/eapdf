"""Recheck retained PDF bytes; record every changed expectation for review."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
import validate_eapdf as v
prior=json.loads((ROOT/'docs/improvement/validator-60/custom-regressions.json').read_text())
rows=[]
for n,old in enumerate(prior,1):
 path=ROOT/old['file'];digest=hashlib.sha256(path.read_bytes()).hexdigest();assert digest==old['sha256']
 report=v.validate(str(path));after=dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
 levels={code:sorted({i['level'] for i in report.items if i['code']==code}) for code in report.seen_codes}
 rows.append(dict(file=old['file'],sha256=digest,before=old['after'],after=after,observed_levels=levels,changed_items=[i for i in report.items if i['code'] in ('ANNOTATION_CONTENTS_REQUIRED','MESSAGE_INREPLYTO_XMP') and i['level']=='FAIL']))
 if n%50==0:print('Verified',n,flush=True)
(HERE/'custom-regressions.json').write_text(json.dumps(rows,indent=2,default=str)+'\n')
changes=[r for r in rows if r['before']!=r['after']]
(HERE/'custom-changes.json').write_text(json.dumps(changes,indent=2,default=str)+'\n')
print('TOTAL',len(rows),'CHANGED',len(changes),flush=True)
for r in changes:print(r['file'],r['before'],'=>',r['after'],flush=True)
