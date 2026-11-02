"""Fresh execution, with every prior outcome change retained for review."""
import hashlib,importlib.util,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v77'
sys.path.insert(0,str(SNAPSHOT));import validate_eapdf as after
spec=importlib.util.spec_from_file_location('before',ROOT/'.eapdf-verification-v76/validate_eapdf.py');before=importlib.util.module_from_spec(spec);spec.loader.exec_module(before)
prior=json.loads((ROOT/'docs/improvement/validator-76/custom-regressions.json').read_text());rows=[];differences=[]
def outcome(report):return dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
for old in prior:
 path=SNAPSHOT/old['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==old['sha256']
 report=after.validate(str(path));result=outcome(report)
 if result!=old['after']:
  differences.append(dict(file=old['file'],before=old['after'],after=result,findings=[i for i in report.items if i['level'] in ('FAIL','WARN') and (i['code'].startswith('MESSAGE_') or i['code']=='VALIDATION_INCOMPLETE')]))
 rows.append(dict(file=old['file'],sha256=old['sha256'],before=old['after'],after=result,execution='fresh-V77',observed_levels={c:sorted({i['level'] for i in report.items if i['code']==c}) for c in report.seen_codes}))
 if len(rows)%50==0:print('Verified',len(rows),flush=True)
folder=SNAPSHOT/'tests/fixtures/eapdf/validator-ambiguous-source-headers'
for case in json.loads((folder/'expectations.json').read_text())['cases']:
 path=folder/case['file'];assert hashlib.sha256(path.read_bytes()).hexdigest()==case['sha256']
 report=after.validate(str(path));result=outcome(report)
 assert result==dict(status=case['expected_status'],failures=case['expected_failures']),(case,result)
 rows.append(dict(file=str(path.relative_to(SNAPSHOT)),sha256=case['sha256'],before=outcome(before.validate(str(path))),after=result,execution='fresh-V77',observed_levels={c:sorted({i['level'] for i in report.items if i['code']==c}) for c in report.seen_codes}))
assert len(rows)==707
(HERE/'prior-differences.json').write_text(json.dumps(differences,indent=2)+'\n')
(HERE/'custom-regressions.json').write_text(json.dumps(rows,indent=2)+'\n')
print('707 fresh custom outcomes; prior differences requiring review:',len(differences),flush=True)
