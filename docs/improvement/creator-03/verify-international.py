"""Check source bytes, XMP and rendered UTF-8 headers across all six profiles."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import pikepdf
import validate_eapdf as validator
import validator_web as web

os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
JAR = Path('/tmp/eapdf-creator-03-build/emailtopdfa-1.4.0.jar')
sha = lambda data: hashlib.sha256(data).hexdigest()
source = HERE / 'international.eml'
original = ('From: José <jose@example.org>\r\nTo: Zoë <zoe@example.org>\r\n'
            'Subject: Résumé – réunion\r\nDate: Thu, 22 Oct 2026 12:00:00 +0000\r\n'
            'Message-ID: <international@example.org>\r\nMIME-Version: 1.0\r\n'
            'Content-Type: text/plain; charset=UTF-8\r\n\r\nOriginal body\r\n').encode('utf-8')
source.write_bytes(original)
second = HERE / 'international-second.eml'
second_bytes = original.replace(b'<international@example.org>', b'<second@example.org>')
second.write_bytes(second_bytes)
archive_inputs = HERE / 'international-inputs'
archive_inputs.mkdir(exist_ok=True)
(archive_inputs / 'first.eml').write_bytes(original)
(archive_inputs / 'second.eml').write_bytes(second_bytes)
rows = []
for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
    directory = HERE / 'international' / profile
    directory.mkdir(parents=True, exist_ok=True)
    command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', str(JAR),
               'eapdf.EmailToPDFA', '--out', str(directory)]
    command += web.generation_profile_args(profile, str(directory / f'{profile}.pdf'))
    command += [str(archive_inputs if profile in ('1m', '1mi') else source)]
    generated = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=240)
    (directory / 'generation.log').write_text(generated.stdout + generated.stderr)
    assert generated.returncode == 0, profile
    paths = [path for path in directory.glob('*.pdf')
             if not path.name.endswith('_finding_aid.pdf') and path.name != 'inspected-child.pdf']
    assert len(paths) == 1, paths
    path = paths[0]
    report = validator.validate_one(str(path), html_path=str(directory / 'validation.html'))
    (directory / 'validation.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
    iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
    assert report['summary']['status'] == report['pdfa']['status'] == iso['level'] == 'PASS', profile
    assert iso['details']['profile_verified']
    with pikepdf.Pdf.open(path) as pdf:
        inspected = pdf
        if profile in ('1c', '1ci'):
            children = [item for item in validator.get_af_specs(pdf) if item['filename'].endswith('.pdf')]
            assert len(children) == 1
            import io
            inspected = pikepdf.Pdf.open(io.BytesIO(children[0]['bytes']))
            rendered_path = directory / 'inspected-child.pdf'
            rendered_path.write_bytes(children[0]['bytes'])
        else:
            rendered_path = path
        try:
            metadata = ET.fromstring(inspected.Root.Metadata.read_bytes())
            values = [node.text for node in metadata.iter() if node.tag.endswith('}Subject')]
            assert 'Résumé – réunion' in values
            xml = inspected.Root.Metadata.read_bytes().decode('utf-8')
            assert 'José' in xml and 'Zoë' in xml
            retained = [item for item in validator.get_af_specs(inspected) if item['afrelationship'] == 'Source']
            if profile.endswith('i'):
                assert not retained
            else:
                expected = {original, second_bytes} if profile == '1m' else {original}
                assert {item['bytes'] for item in retained} == expected
        finally:
            if inspected is not pdf:
                inspected.close()
    text = subprocess.run(['pdftotext', str(rendered_path), '-'], capture_output=True, text=True, check=True).stdout
    (directory / 'rendered-text.txt').write_text(text)
    assert all(value in text for value in ('Résumé', 'réunion', 'José', 'Zoë')), (profile, text)
    row = dict(profile=profile, file=str(path.relative_to(ROOT)), sha256=sha(path.read_bytes()),
               status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
               source_bytes_exact=not profile.endswith('i'), rendered_headers_correct=True)
    rows.append(row)
    (HERE / 'international-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps(row), flush=True)
print('All six international header profiles passed.', flush=True)
