"""Check encoded international attached-message preservation in all profiles."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
import pikepdf
import validate_eapdf as validator
import validator_web as web

os.environ.update(VERAPDF_MOUNT_ROOT=str(ROOT), GOMAXPROCS='2')
JAR = Path('/tmp/eapdf-creator-04-build/emailtopdfa-1.4.0.jar')
sha = lambda data: hashlib.sha256(data).hexdigest()
child = ('From: José <jose@example.org>\r\nTo: Zoë <zoe@example.org>\r\n'
         'Subject: Résumé\r\nDate: Thu, 22 Oct 2026 12:00:00 +0000\r\n'
         'Message-ID: <child@example.org>\r\nContent-Type: text/plain; charset=UTF-8\r\n'
         '\r\nAttached body\r\n').encode('utf-8')
encoded = base64.encodebytes(child).replace(b'\n', b'\r\n')
parent = (b'From: parent@example.org\r\nTo: reader@example.org\r\nSubject: Forwarded parent\r\n'
          b'Date: Thu, 22 Oct 2026 12:00:00 +0000\r\nMessage-ID: <parent@example.org>\r\n'
          b'MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=forwarded\r\n\r\n'
          b'--forwarded\r\nContent-Type: text/plain\r\n\r\nParent body\r\n'
          b'--forwarded\r\nContent-Type: message/global\r\nContent-Transfer-Encoding: base64\r\n\r\n'
          + encoded + b'\r\n--forwarded--\r\n')
inputs = HERE / 'attachment-inputs'
inputs.mkdir(exist_ok=True)
(inputs / 'first.eml').write_bytes(parent)
(inputs / 'second.eml').write_bytes(parent.replace(b'<parent@example.org>', b'<second@example.org>'))
(HERE / 'attached-global.eml').write_bytes(child)
rows = []
for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
    directory = HERE / 'attachments' / profile
    directory.mkdir(parents=True, exist_ok=True)
    command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', str(JAR),
               'eapdf.EmailToPDFA', '--out', str(directory)]
    command += web.generation_profile_args(profile, str(directory / f'{profile}.pdf'))
    command += [str(inputs if profile in ('1m', '1mi') else inputs / 'first.eml')]
    generated = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=240)
    (directory / 'generation.log').write_text(generated.stdout + generated.stderr)
    assert generated.returncode == 0, profile
    paths = [path for path in directory.glob('*.pdf') if not path.name.endswith('_finding_aid.pdf')
             and path.name != 'inspected-child.pdf']
    assert len(paths) == 1, paths
    path = paths[0]
    report = validator.validate_one(str(path), html_path=str(directory / 'validation.html'))
    (directory / 'validation.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
    iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
    assert report['summary']['status'] == report['pdfa']['status'] == iso['level'] == 'PASS', profile
    assert iso['details']['profile_verified']
    with pikepdf.Pdf.open(path) as pdf:
        inspected = pdf
        rendered = path
        if profile in ('1c', '1ci'):
            children = [item for item in validator.get_af_specs(pdf) if item['filename'].endswith('.pdf')]
            assert len(children) == 1
            inspected = pikepdf.Pdf.open(io.BytesIO(children[0]['bytes']))
            rendered = directory / 'inspected-child.pdf'
            rendered.write_bytes(children[0]['bytes'])
        try:
            native = []
            for page in inspected.pages:
                for annotation in page.obj.get('/Annots', []):
                    if annotation.get('/Subtype') != pikepdf.Name('/FileAttachment'):
                        continue
                    filespec = annotation.FS
                    stream = filespec.EF.get('/UF', filespec.EF.get('/F'))
                    if str(stream.get('/Subtype')) == '/message/global':
                        native.append(stream.read_bytes())
            assert len(native) == (2 if profile in ('1m', '1mi') else 1), (profile, len(native))
            expected = b'' if profile.endswith('i') else child
            assert all(payload == expected for payload in native), profile
        finally:
            if inspected is not pdf:
                inspected.close()
    text = subprocess.run(['pdftotext', str(rendered), '-'], capture_output=True, text=True, check=True).stdout
    (directory / 'rendered-text.txt').write_text(text)
    assert 'Parent body' in text and 'Unsupported email body' not in text and 'Attached body' not in text
    row = dict(profile=profile, file=str(path.relative_to(ROOT)), sha256=sha(path.read_bytes()),
               status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
               native_attachments=len(native), native_bytes_exact=not profile.endswith('i'),
               isolated_placeholders=profile.endswith('i'), parent_body_correct=True)
    rows.append(row)
    (HERE / 'attachment-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps(row), flush=True)
print('All six encoded attached-message profiles passed.', flush=True)
