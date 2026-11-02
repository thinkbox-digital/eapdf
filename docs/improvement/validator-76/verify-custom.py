"""Ten fresh custom controls, with 685 exact-byte V75 observations inherited."""
import hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3];HERE=Path(__file__).resolve().parent;SNAPSHOT=ROOT/'.eapdf-verification-v76'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
prior_manifest=json.loads((ROOT/'docs/improvement/validator-75/verification-inputs.json').read_text())
manifest=json.loads((HERE/'verification-inputs.json').read_text())
for name,value in prior_manifest.items():
 if name in ('validate_eapdf.py','validator_rule_text.json','validator_web.py') or name.startswith('src/main/java/') or (name.startswith('tests/') and name.endswith('.py')):
  assert manifest[name]==value==sha(SNAPSHOT/name),(name,value)
assert not {name for name in manifest if name.startswith('tests/') and name.endswith('.py')} - set(prior_manifest)
sys.path.insert(0,str(SNAPSHOT));import validate_eapdf as v
rows=[]
for old in json.loads((ROOT/'docs/improvement/validator-75/custom-regressions.json').read_text()):
 assert sha(SNAPSHOT/old['file'])==old['sha256']
 rows.append(dict(old,execution='inherited-V75-exact-runtime-and-PDF-hashes'))
assert len(rows)==685
folder=SNAPSHOT/'tests/fixtures/eapdf/validator-collection-child-status'
for case in json.loads((folder/'expectations.json').read_text())['cases']:
 path=folder/case['file'];assert sha(path)==case['sha256']
 report=v.validate(str(path));outcome=dict(status=report.summary()['status'],failures=sorted({i['code'] for i in report.items if i['level']=='FAIL'}))
 assert outcome==dict(status=case['expected_custom_status'],failures=case['expected_failures']),(case,outcome)
 rows.append(dict(file=str(path.relative_to(SNAPSHOT)),sha256=case['sha256'],before=outcome,after=outcome,
                  execution='fresh-V76-custom-only',observed_levels={c:sorted({i['level'] for i in report.items if i['code']==c}) for c in report.seen_codes}))
assert len(rows)==695
(HERE/'custom-regressions.json').write_text(json.dumps(rows,indent=2)+'\n')
print('Ten fresh custom controls; 685 prior observations inherited by exact runtime/test/PDF hashes.')
