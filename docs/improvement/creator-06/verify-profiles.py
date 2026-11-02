"""Recheck six normal/accessibility profiles after the C06 MSG address-preservation repair."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator
import validator_web as web
import pikepdf

os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
JAR = Path('/tmp/eapdf-creator-06-build/emailtopdfa-1.4.0.jar')
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
baseline = json.loads((HERE / 'before-inputs.json').read_text())
for name in ('validate_eapdf.py', 'validator_rule_text.json', 'validator_web.py'):
    assert sha(ROOT / name) == baseline[name], name
originals = {
    'normal': ROOT / 'docs/improvement/creator-01/inputs/normal',
    'accessibility': ROOT / 'docs/improvement/creator-01/inputs/accessibility',
}
results = []
for mode, original in originals.items():
    inputs = HERE / 'inputs' / mode
    if not inputs.exists():
        shutil.copytree(original, inputs)
    config = json.loads((ROOT / 'config/eapdf_config.json').read_text())
    if mode == 'accessibility':
        config['accessibility']['addPdfUaFeatures'] = True
    config_path = HERE / (mode + '-config.json')
    config_path.write_text(json.dumps(config, indent=2) + '\n')
    alternate_path = HERE / 'normal-isolated-config.json'
    if mode == 'normal':
        alternate = json.loads(json.dumps(config))
        alternate['branding']['enabled'] = False
        alternate['premis']['enabled'] = True
        alternate_path.write_text(json.dumps(alternate, indent=2) + '\n')
    for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
        out = HERE / 'outputs' / mode / profile
        out.mkdir(parents=True, exist_ok=True)
        source = inputs / ('Inbox/first.eml' if mode == 'normal' else 'first.eml') if profile in ('1s', '1si') else inputs
        environment = dict(os.environ, EAPDF_CONFIG=str(
            alternate_path if mode == 'normal' and profile.endswith('i') else config_path))
        command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', str(JAR),
                   'eapdf.EmailToPDFA', '--out', str(out), '--debug-dir', str(HERE / 'scratch')]
        command += web.generation_profile_args(profile, str(out / (profile + '.pdf')))
        command.append(str(source))
        generated = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, text=True, timeout=240)
        (out / 'generation.log').write_text(generated.stdout + generated.stderr)
        assert generated.returncode == 0, (mode, profile, generated.returncode)
        paths = [p for p in out.rglob('*.pdf') if not p.name.endswith('_finding_aid.pdf') and p.name != 'Finding_Aid.pdf']
        assert len(paths) == 1, paths
        pdf = paths[0]
        report = validator.validate_one(str(pdf), html_path=str(out / 'validation.html'))
        (out / 'validation.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
        iso = next(i for i in report['items'] if i['code'] == 'ISO32005_UPSTREAM')
        failures = sorted({i['code'] for i in report['items'] if i['level'] == 'FAIL'})
        row = dict(mode=mode, profile=profile, file=str(pdf.relative_to(ROOT)), sha256=sha(pdf),
                   status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
                   profile_verified=(iso.get('details') or {}).get('profile_verified'), failures=failures)
        results.append(row)
        (HERE / 'profile-results.json').write_text(json.dumps(results, indent=2) + '\n')
        print(json.dumps(row), flush=True)
        assert row['status'] == row['pdfa'] == row['iso'] == 'PASS' and row['profile_verified'] and not failures, row
        with pikepdf.Pdf.open(pdf) as document:
            if profile in ('1m', '1mi'):
                assert any(str(s.get('/UF', s.get('/F', ''))).endswith('_index.xlsx') for s in document.Root.AF)
        if profile in ('1c', '1ci'):
            sidecar = pdf.with_name(pdf.stem + '_finding_aid.pdf')
            index = pdf.with_name(pdf.stem + '_index.xlsx')
            assert sidecar.is_file() and index.is_file()
            with ZipFile(index) as workbook:
                assert 'xl/workbook.xml' in workbook.namelist()
            with pikepdf.Pdf.open(sidecar) as document:
                assert not validator.get_embedded_files(document) and not validator.get_af_specs(document)
            pdfa = validator.run_verapdf(sidecar)
            (out / 'finding-aid-pdfa.json').write_text(json.dumps(pdfa, indent=2, default=str) + '\n')
            assert pdfa['status'] == 'PASS', (mode, profile, pdfa)
assert len(results) == 12
(HERE / 'profile-inputs.json').write_text(json.dumps(dict(
    jar_sha256=sha(JAR), validator_sha256=sha(ROOT / 'validate_eapdf.py'),
    files={str(p.relative_to(ROOT)): sha(p) for p in sorted((HERE / 'inputs').rglob('*')) if p.is_file()}
), indent=2) + '\n')
print('All twelve generated profiles and four collection finding-aid PDF/XLSX sidecars passed.', flush=True)
