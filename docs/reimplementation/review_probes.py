import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zlib

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import pikepdf
import validate_eapdf as validator


def image_bytes(red, green, blue):
    def chunk(kind, data):
        checksum = zlib.crc32(kind + data)
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', checksum)

    pixels = (b'\0' + bytes([red, green, blue]) * 20) * 20
    return (b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', 20, 20, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b''))


def source_manifest():
    paths = [ROOT / 'pom.xml', ROOT / 'VERSION', ROOT / 'validate_eapdf.py',
             ROOT / 'validator_web.py', ROOT / 'validator_rule_text.json',
             ROOT / 'config/eapdf_config.json', ROOT / 'requirements-web.txt']
    paths += list((ROOT / 'src/main/java/eapdf').glob('*.java'))
    paths += list((ROOT / 'src/test/java/eapdf').glob('*.java'))
    paths += list((ROOT / 'tests').glob('*.py'))
    paths += list((ROOT / 'scripts').glob('*.py'))
    files = [{'path': path.relative_to(ROOT).as_posix(),
              'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
             for path in sorted(paths)]
    fingerprint = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {'fingerprint': fingerprint, 'files': files}


def validate_output(path, directory, include_pdfa):
    if include_pdfa:
        with tempfile.TemporaryDirectory(prefix='.eapdf-review-', dir=ROOT) as temporary:
            staged = Path(temporary) / path.name
            staged.write_bytes(path.read_bytes())
            result = validator.validate_one(str(staged), html_path=str(directory / 'validation.html'))
    else:
        report = validator.validate(str(path))
        result = {'summary': report.summary(), 'items': report.items,
                  'pdfa': {'status': 'NOT_RUN'}}
    (directory / 'validation.json').write_text(json.dumps(result, default=str, indent=2))
    return {
        'status': result['summary']['status'],
        'pdfaStatus': result['pdfa']['status'],
        'failures': [item['code'] for item in result['items'] if item['level'] == 'FAIL'],
        'warnings': [item['code'] for item in result['items'] if item['level'] == 'WARN']
    }


def inspect_pdf(path):
    with pikepdf.open(path) as document:
        return {
            'imageObjects': sum(1 for item in document.objects
                                if isinstance(item, pikepdf.Stream)
                                and item.get('/Subtype') == pikepdf.Name('/Image')),
            'bodySubtypes': [validator.safe_name(item['dpm']['Mail_Subtype'])
                             for item in validator.get_dpart_nodes(document)[2]
                             if 'Mail_Subtype' in item['dpm']],
            'figureAlternatives': [str(item.get('/Alt', '')) for item in document.objects
                                   if isinstance(item, pikepdf.Dictionary)
                                   and item.get('/S') == pikepdf.Name('/Figure')]
        }


def generate_case(options, name, body, content_type='text/html; charset=UTF-8',
                  accessibility=False, premis=False, profile=None):
    directory = options.output / name
    directory.mkdir()
    config = directory / 'config.json'
    config.write_text(json.dumps({
        'branding': {'enabled': False}, 'findingAid': {'pdfEnabled': False},
        'accessibility': {'addPdfUaFeatures': accessibility},
        'premis': {'enabled': premis}
    }))
    source = directory / (name + '.eml')
    source.write_bytes((
        'From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n'
        'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: ' + name + '\r\n'
        'Message-ID: <' + name + '@example.org>\r\nIn-Reply-To: <parent@example.org>\r\n'
        'MIME-Version: 1.0\r\nContent-Type: ' + content_type + '\r\n\r\n'
        + body + '\r\n').encode('utf-8'))
    command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-jar', str(options.jar),
               '--out', str(directory)]
    if profile:
        command += ['--archive-profile', profile]
    command.append(str(source))
    process = subprocess.run(command, cwd=ROOT, env=dict(os.environ, EAPDF_CONFIG=str(config)),
                             capture_output=True, text=True, timeout=90)
    (directory / 'generator.log').write_text(process.stdout + process.stderr)
    result = {'case': name, 'generatorExit': process.returncode,
              'conversionWarnings': [line for line in (process.stdout + process.stderr).splitlines()
                                     if 'warning:' in line]}
    if process.returncode:
        return result, None
    outputs = list(directory.glob('*.pdf'))
    if len(outputs) != 1:
        raise RuntimeError('Expected exactly one generated PDF for ' + name)
    output = outputs[0]
    result.update(inspect_pdf(output))
    result['validation'] = validate_output(output, directory, options.pdfa)
    if premis and options.premis_schema:
        from lxml import etree
        schema = etree.XMLSchema(etree.parse(str(options.premis_schema)))
        sidecar = next(directory.glob('*_premis.xml'))
        result['premisSchemaValid'] = schema.validate(etree.parse(str(sidecar)))
        result['premisSchemaErrors'] = [error.message for error in schema.error_log]
    return result, output


def mutate_case(options, source, name):
    directory = options.output / name
    directory.mkdir()
    output = directory / (name + '.pdf')
    with pikepdf.open(source) as document:
        if name == 'nfsm':
            document.Root.ViewerPreferences.NonFullScreenPageMode = pikepdf.Name('/UseNone')
        elif name == 'missing-subject':
            data, count = re.subn(br'<pdfmailmeta:Subject>[^<]*</pdfmailmeta:Subject>',
                                 b'', document.Root.Metadata.read_bytes(), count=1)
            if count != 1:
                raise RuntimeError('Subject mutation did not find its intended field')
            document.Root.Metadata.write(data)
        else:
            raise ValueError(name)
        document.save(output)
    return {'case': name, 'validation': validate_output(output, directory, options.pdfa)}


def main():
    parser = argparse.ArgumentParser(description='Run synthetic readiness probes; results are observations, not acceptance.')
    parser.add_argument('--jar', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--pdfa', action='store_true')
    parser.add_argument('--premis-schema', type=Path)
    options = parser.parse_args()
    options.jar = options.jar.resolve(strict=True)
    options.output = options.output.resolve()
    options.output.mkdir(parents=True, exist_ok=False)
    red_image = base64.b64encode(image_bytes(255, 0, 0)).decode('ascii')
    blue_image = base64.b64encode(image_bytes(0, 0, 255)).decode('ascii')
    results = []
    result, unused_output = generate_case(options, 'image_only',
        '<img alt="red" src="data:image/png;base64,' + red_image + '"/>'
        '<img alt="blue" src="data:image/png;base64,' + blue_image + '"/>')
    results.append(result)
    result, unused_output = generate_case(options, 'image_with_text',
        '<p>Visible text</p><img src="data:image/png;base64,' + red_image + '"/>', accessibility=True)
    results.append(result)
    result, plain_output = generate_case(options, 'body_parameters', 'A plain text body.',
                                         content_type='text/plain; charset=UTF-8; format=flowed')
    results.append(result)
    result, unused_output = generate_case(options, 'premis', '<p>PREMIS schema probe.</p>', premis=True)
    results.append(result)
    if os.name == 'posix':
        result, unused_output = generate_case(options, 'colon:source', '<p>Collection filename probe.</p>', profile='c')
        results.append(result)
    else:
        results.append({'case': 'colon:source', 'skipped': 'Fixture filename requires POSIX'})
    if plain_output:
        results += [mutate_case(options, plain_output, name) for name in ('nfsm', 'missing-subject')]
    evidence = {'scope': 'Observed current behavior; not production acceptance',
                'sourceManifest': source_manifest(), 'results': results}
    (options.output / 'results.json').write_text(json.dumps(evidence, indent=2))
    for result in results:
        print(json.dumps(result))
    if any(result.get('generatorExit', 0) != 0 for result in results):
        raise SystemExit('A generator probe failed to execute; inspect generator.log files')


if __name__ == '__main__':
    main()
