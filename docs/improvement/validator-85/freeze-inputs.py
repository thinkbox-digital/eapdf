"""Freeze current runtime and all relevant inputs without touching target/debug."""
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
SNAPSHOT = ROOT / '.eapdf-verification-v85'
files = {name for name in json.loads((ROOT / 'docs/improvement/validator-84/verification-inputs.json').read_text())
         if not Path(name).is_absolute()}
files.update(str(p.relative_to(ROOT)) for folder in ('tests', 'scripts') for p in (ROOT / folder).rglob('*')
             if p.is_file() and '__pycache__' not in p.parts)
files.update(['docs/improvement/validator-85/before-validate_eapdf.py',
              'docs/improvement/validator-85/before-validator_rule_text.json'])
files.update(['docs/improvement/validator-34/crosswalk-source.json', 'docs/improvement/validator-33/next-pdfa3-crosswalk-probes/probe.py', 'docs/improvement/validator-33/next-pdfa3-crosswalk-probes/add-schemas.py'])
files.add('tests/fixtures/eapdf/validator-operands/valid.pdf')




files.update(str(p.relative_to(ROOT)) for p in (HERE/'control-native-evidence').glob('*.json'))
manifest = {}
for name in sorted(files):
    source = ROOT / name
    if not source.is_file():
        continue
    assert 'target' not in source.parts and not any(part.lower().startswith('debug') for part in source.relative_to(ROOT).parts)
    destination = SNAPSHOT / name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    manifest[name] = hashlib.sha256(source.read_bytes()).hexdigest()
(HERE / 'verification-inputs.json').write_text(json.dumps(manifest, indent=2) + '\n')
with zipfile.ZipFile(HERE / 'runtime-inputs.zip', 'w', compression=zipfile.ZIP_DEFLATED) as archive:
    for name in manifest:
        archive.write(SNAPSHOT / name, name)
print('Frozen', len(manifest), 'input files.')
