"""Check international versus ASCII original media labels and unchanged source bytes."""
from collections import Counter
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
JAR = Path('/tmp/eapdf-creator-09-build/emailtopdfa-1.4.0.jar')
BUILD_TESTS = Path('/tmp/eapdf-creator-09-build/test-classes')
subprocess.run(['java', '-cp', str(BUILD_TESTS) + ':' + str(JAR), 'eapdf.InternationalSourceTypeTests',
                str(HERE / 'global-inputs')], cwd=ROOT, check=True, capture_output=True, text=True)
first_source = HERE / 'global-inputs' / 'original.eml'
(HERE / 'global-inputs' / 'second.eml').write_bytes(first_source.read_bytes().replace(b'<global-source@example.org>', b'<ascii-source@example.org>').replace('Résumé'.encode(), b'=?UTF-8?Q?R=C3=A9sum=C3=A9?='))
assert (HERE / 'global-inputs' / 'source-type.txt').read_text() == 'message/global'
(HERE / 'global-inputs' / 'source-type.txt').unlink()
expected_sources = {'original.eml': 'message/global', 'second.eml': 'message/rfc822'}
source_bytes = {path.name: path.read_bytes() for path in (HERE / 'global-inputs').glob('*.eml')}
expected_type = '/text/plain; charset=UTF-8'
sha = lambda data: hashlib.sha256(data).hexdigest()
rows = []
for profile in ('1s', '1si', '1m', '1mi', '1c', '1ci'):
    directory = HERE / 'international' / profile
    directory.mkdir(parents=True, exist_ok=True)
    command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', str(JAR),
               'eapdf.EmailToPDFA', '--out', str(directory)]
    command += web.generation_profile_args(profile, str(directory / f'{profile}.pdf'))
    command += [str(HERE / 'global-inputs' / 'original.eml' if profile in ('1s', '1si') else HERE / 'global-inputs')]
    generated = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=240)
    (directory / 'generation.log').write_text(generated.stdout + generated.stderr)
    assert generated.returncode == 0, profile
    paths = [path for path in directory.glob('*.pdf') if not path.name.endswith('_finding_aid.pdf')
             and not path.name.startswith('inspected-child-')]
    assert len(paths) == 1, paths
    path = paths[0]
    report = validator.validate_one(str(path), html_path=str(directory / 'validation.html'))
    (directory / 'validation.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
    iso = next(item for item in report['items'] if item['code'] == 'ISO32005_UPSTREAM')
    failures = [item for item in report['items'] if item['level'] == 'FAIL']
    assert not failures and report['pdfa']['status'] == iso['level'] == 'PASS' and iso['details']['profile_verified'], profile
    assert report['summary']['status'] == 'PASS', (profile, report['summary'])
    all_types = []
    retained_sources = []
    with pikepdf.Pdf.open(path) as pdf:
        documents = [(pdf, path)]
        children = []
        if profile in ('1c', '1ci'):
            documents = []
            children = [item for item in validator.get_af_specs(pdf) if item['filename'].endswith('.pdf')]
            assert len(children) == 2
            for index, child in enumerate(children):
                child_path = directory / f'inspected-child-{index}.pdf'
                child_path.write_bytes(child['bytes'])
                documents.append((pikepdf.Pdf.open(io.BytesIO(child['bytes'])), child_path))
        try:
            for document, rendered in documents:
                types = [str(item['dpm']['Mail_Subtype']) for item in validator.get_dpart_nodes(document)[2]
                         if str(item['dpm'].get('Mail_ContentSetType', '')) == '/BodyRendering']
                text = subprocess.run(['pdftotext', '-layout', str(rendered), '-'], capture_output=True, text=True, check=True).stdout
                rendered.with_suffix('.txt').write_text(text)
                assert all(item == expected_type for item in types), types
                assert 'International original body' in text and 'Résumé' in text, text
                all_types.extend(types)
                for original in validator.get_af_specs(document):
                    if original['afrelationship'] != 'Source':
                        continue
                    assert original['bytes'] == source_bytes[original['filename']], profile
                    assert original['subtype'].lstrip('/') == expected_sources[original['filename']], original
                    retained_sources.append(original['filename'])
        finally:
            for document, _ in documents:
                if document is not pdf:
                    document.close()
    assert Counter(all_types) == Counter([expected_type] * (1 if profile in ('1s', '1si') else 2))
    expected_count = 0 if profile.endswith('i') else 1 if profile == '1s' else 2
    assert len(retained_sources) == expected_count
    row = dict(profile=profile, file=str(path.relative_to(ROOT)), sha256=sha(path.read_bytes()),
               status=report['summary']['status'], pdfa=report['pdfa']['status'], iso=iso['level'],
               failures=failures, body_subtypes=all_types, visible_body_correct=True,
               retained_sources=retained_sources, original_eml_bytes_exact=True, mime_parameters_retained=True, source_media_types_correct=True,
               incomplete_reasons=dict(Counter(item['message'] for item in report['items']
                                              if item['code'] == 'VALIDATION_INCOMPLETE' and item['level'] == 'WARN')))
    rows.append(row)
    (HERE / 'international-results.json').write_text(json.dumps(rows, indent=2) + '\n')
    print(json.dumps(row), flush=True)
print('All six international/ASCII original media-type and source-byte profile checks passed.', flush=True)
