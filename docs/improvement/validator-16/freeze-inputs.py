"""Freeze V16 runtime/test inputs; prior accepted snapshots stay immutable."""
from pathlib import Path
import difflib
import hashlib
import json
import shutil
import zipfile

ROOT=Path(__file__).resolve().parents[3]
HERE=Path(__file__).resolve().parent
FROZEN=ROOT/'.eapdf-verification-v16'
assert not FROZEN.exists(), 'Never mutate an existing verification snapshot.'
prior=json.loads((HERE.parent/'validator-15/verification-inputs-final.json').read_text())
names=set(prior)
names.update(['tests/test_attachment_name_evidence.py','scripts/build_eapdf_attachment_evidence_fixtures.py'])
names.update(str(p.relative_to(ROOT)) for p in (ROOT/'tests/fixtures/eapdf/validator-attachment-evidence').rglob('*') if p.is_file())
names.update(['tests/test_attachment_identity_schema.py','scripts/build_eapdf_attachment_identity_fixtures.py'])
names.update(str(p.relative_to(ROOT)) for p in (ROOT/'tests/fixtures/eapdf/validator-attachment-identity').rglob('*') if p.is_file())
names.update(str(p.relative_to(ROOT)) for p in (ROOT/'tests/fixtures/eapdf/validator-attachment-streams').rglob('*') if p.is_file())
names.update(['tests/test_attachment_streams.py','scripts/build_eapdf_attachment_stream_fixtures.py',
              'tests/test_attachment_native_scope.py','scripts/build_eapdf_attachment_scope_fixtures.py'])
names.update(str(p.relative_to(ROOT)) for p in (ROOT/'tests/fixtures/eapdf/validator-attachment-scope').rglob('*') if p.is_file())
hashes={}
for name in sorted(names):
 path=ROOT/name
 assert path.name!='credentials.json'
 assert path.is_file(), path
 if not Path(name).is_absolute():
  target=FROZEN/name
  target.parent.mkdir(parents=True,exist_ok=True)
  shutil.copy2(path,target)
 hashes[name]=hashlib.sha256(path.read_bytes()).hexdigest()
(HERE/'verification-inputs.json').write_text(json.dumps(hashes,indent=2)+'\n')
archive=HERE/'runtime-inputs.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED) as bundle:
 for name in sorted(hashes):
  if not Path(name).is_absolute() and not name.startswith(('docs/','examples/')) and Path(name).suffix!='.md':
   bundle.write(FROZEN/name,name)
(HERE/'runtime-capsule.json').write_text(json.dumps({'archive':archive.name,
 'sha256':hashlib.sha256(archive.read_bytes()).hexdigest(),'frozen_files':len(hashes),
 'basis':'V16 exact attachment filename and role evidence; credentials excluded.'},indent=2)+'\n')
patch=[]
for name in ('validate_eapdf.py','validator_rule_text.json','tests/test_attachment_name_evidence.py',
             'scripts/build_eapdf_attachment_evidence_fixtures.py'):
 old=ROOT/'.eapdf-verification-v15-final'/name
 patch.extend(difflib.unified_diff(old.read_text().splitlines(True) if old.exists() else [],
  (FROZEN/name).read_text().splitlines(True),fromfile='v15/'+name,tofile='v16/'+name))
(HERE/'application.patch').write_text(''.join(patch))
print('Frozen',len(hashes),'inputs in',FROZEN)
