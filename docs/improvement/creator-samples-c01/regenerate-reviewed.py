"""Stage the requested C01 samples and retain unchanged V85 validation results.

This recipe does not publish files or relax the standard regeneration script.
Review INCOMPLETE results explicitly before copying any candidates to examples.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent / 'reviewed'
sys.path.insert(0, str(ROOT))
import pikepdf
import validate_eapdf as validator

JAR = Path(os.environ['EAPDF_GENERATOR_CLASSPATH'])
sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
HERE.mkdir(parents=True, exist_ok=True)
inputs = json.loads((HERE.parent / 'resumed/inputs.json').read_text())
assert sha(JAR) == inputs['creator_jar_sha256']
for name, expected in inputs['inputs'].items():
    assert sha(ROOT / name) == expected, name
rows = []
for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
    destination = HERE / 'candidates' / profile
    destination.mkdir(parents=True, exist_ok=True)
    pdf = destination / f'sample_{profile}.pdf'
    command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', str(JAR),
               'eapdf.EmailToPDFA', '--out', str(destination)]
    if profile in ('1m', '1mi', '1c', '1ci'):
        command += ['--archive-profile', profile[1:], '--archive-output', str(pdf)]
    elif profile == '1si':
        command += ['--omit-source']
    command += [str(ROOT / 'in/email' / ('test.pst' if profile in ('1m', '1mi') else 'test3.eml'))]
    generated = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=600)
    (HERE / f'{profile}-generation.log').write_text(generated.stdout + generated.stderr)
    assert generated.returncode == 0, profile
    if not pdf.exists():
        paths = list(destination.glob('*.pdf'))
        assert len(paths) == 1, paths
        pdf = paths[0]
    report = validator.validate_one(str(pdf), html_path=str(HERE / f'{profile}-validation.html'))
    (HERE / f'{profile}-validation.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
    iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
    row = dict(profile=profile, candidate=str(pdf.relative_to(ROOT)), sha256=sha(pdf),
               status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
               iso_profile_verified=(iso.get('details') or {}).get('profile_verified'),
               failures=[item for item in report['items'] if item['level'] == 'FAIL'],
               incomplete=[item for item in report['items'] if item['code'] == 'VALIDATION_INCOMPLETE' and item['level'] == 'WARN'],
               sidecars=[])
    for suffix in ('_finding_aid.pdf', '_index.xlsx', '_premis.xml'):
        sidecar = pdf.with_name(pdf.stem + suffix)
        if not sidecar.is_file():
            continue
        item = dict(candidate=str(sidecar.relative_to(ROOT)), sha256=sha(sidecar), suffix=suffix)
        if suffix == '_finding_aid.pdf':
            with pikepdf.Pdf.open(sidecar) as document:
                assert not validator.get_embedded_files(document) and not validator.get_af_specs(document)
            result = validator.run_verapdf(sidecar)
            (HERE / f'{profile}-finding-aid-pdfa.json').write_text(json.dumps(result, indent=2, default=str) + '\n')
            item['pdfa'] = result['status']
            assert item['pdfa'] == 'PASS'
        elif suffix == '_index.xlsx':
            with ZipFile(sidecar) as workbook:
                assert 'xl/workbook.xml' in workbook.namelist()
            item['workbook'] = True
        row['sidecars'].append(item)
    rows.append(row)
    (HERE / 'results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps({key: row[key] for key in ('profile', 'status', 'pdfa', 'iso')}), flush=True)
    assert not row['failures'] and row['pdfa'] == row['iso'] == 'PASS' and row['iso_profile_verified'], profile
print('Six candidates retained for review; no samples published.', flush=True)
