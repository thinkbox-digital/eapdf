"""Retain all 566 accepted PDF outcomes and verify ten new owner controls."""
import hashlib,importlib.util,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v75'
sys.path.insert(0,str(SNAPSHOT));import validate_eapdf as after
s=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v74/validate_eapdf.py');before=importlib.util.module_from_spec(s);s.loader.exec_module(before)
prior=json.loads((ROOT/'docs/improvement/validator-74/custom-regressions.json').read_text());rows=[]
def outcome(report):return dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
for old in prior:
 path=SNAPSHOT/old['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==old['sha256']
 report=after.validate(str(path));result=outcome(report);assert result==old['after'],(old['file'],old['after'],result)
 rows.append(dict(file=old['file'],sha256=old['sha256'],before=old['after'],after=result,observed_levels={c:sorted({i['level'] for i in report.items if i['code']==c}) for c in report.seen_codes}))
 if len(rows)%50==0:print('Verified',len(rows),flush=True)
d=SNAPSHOT/'tests/fixtures/eapdf/validator-source-date-availability'
for case in json.loads((d/'expectations.json').read_text())['cases']:
 path=d/case['file'];report=after.validate(str(path));result=outcome(report)
 assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
 assert result==dict(status=case['expected_status'],failures=case['expected_failures'])
 levels={c:sorted({i['level'] for i in report.items if i['code']==c}) for c in report.seen_codes}

 rows.append(dict(file=str(path.relative_to(SNAPSHOT)),sha256=case['sha256'],before=outcome(before.validate(str(path))),after=result,observed_levels=levels))
assert len(rows)==685
(HERE/'custom-regressions.json').write_text(json.dumps(rows,indent=2,default=str)+'\n')
print('All 675 prior hashes/statuses/failure sets and ten source-date controls match.',flush=True)
