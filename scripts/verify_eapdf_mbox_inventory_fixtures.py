"""Compare packaged MBOX extraction and PDF bytes with authored byte-span oracles."""
from collections import Counter
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator

FIXTURES = ROOT / 'tests/fixtures/eapdf/batch13'
MAIL = 'http://www.pdfa.org/eapdf/ns/meta/'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def named_files(node):
    names = node.get('/Names', [])
    yield from (names[index] for index in range(1, len(names), 2))
    for child in node.get('/Kids', []):
        yield from named_files(child)


def embedded_bytes(file):
    values = [file.EF[key].read_bytes() for key in ('/F', '/UF') if key in file.EF]
    assert values and all(value == values[0] for value in values)
    return values[0]


def check_oracles(inputs, reports):
    for source in inputs.values():
        raw = (FIXTURES / source['input']).read_bytes()
        assert len(raw) == source['containerSize'] and sha(raw) == source['containerSha256']
        report = json.loads((reports / (source['id'] + '.json')).read_text())
        assert report['requestedFormat'] == source['format']
        assert report['containerSize'] == len(raw) and report['containerSha256'] == sha(raw)
        assert len(report['entries']) == len(source['entries'])
        end = 0
        for entry, actual in zip(source['entries'], report['entries']):
            assert all(actual[key] == entry[key] for key in actual), (source['id'], entry, actual)
            assert entry['envelopeOffset'] == end
            envelope = raw[end:entry['messageOffset']]
            assert envelope.startswith(b'From sender@example.invalid ') and envelope.endswith((b'\n', b'\r'))
            start = entry['messageOffset']
            body = raw[start:start + entry['rawSize']]
            assert sha(body) == entry['rawSha256']
            export = (FIXTURES / entry['exportFile']).read_bytes()
            assert len(export) == entry['exportedSize'] and sha(export) == entry['exportedSha256']
            end = start + entry['rawSize'] + entry['paddingSize']
            assert not raw[start + entry['rawSize']:end].strip(b'\r\n')
        assert end == len(raw)


def provenance(source, entry):
    return ('MBOX inventory: container SHA-256=' + source['containerSha256']
            + '; bytes=' + str(source['containerSize']) + '; requested format=' + source['format']
            + '; entry=' + str(entry['ordinal']) + '/' + str(len(source['entries']))
            + '; envelope byte=' + str(entry['envelopeOffset']) + '; raw message byte=' + str(entry['messageOffset'])
            + '; raw size=' + str(entry['rawSize']) + '; raw SHA-256=' + entry['rawSha256']
            + '; exported size=' + str(entry['exportedSize']) + '; exported SHA-256=' + entry['exportedSha256']
            + '; padding bytes=' + str(entry['paddingSize']) + '; framing=' + entry['framing']
            + '; quoting=' + entry['quoting'] + '.')


def inspect_pdf(pdf, source, isolated):
    xml = ET.fromstring(pdf.Root.Metadata.read_bytes())
    text = ''.join(xml.itertext())
    rows = xml.findall('.//{' + MAIL + '}email/{' + RDF + '}Bag/{' + RDF + '}li')
    subjects = Counter(row.findtext('{' + MAIL + '}Subject', '') for row in rows)
    selected = [entry for entry in source['entries'] if entry['subject'] in subjects]
    assert subjects == Counter(entry['subject'] for entry in selected), subjects
    assert selected
    for entry in selected:
        assert provenance(source, entry) in text, entry
    observed = Counter(sha(embedded_bytes(file)) for file in pdf.Root.get('/AF', [])
                       if str(file.get('/AFRelationship')) == '/Source')
    expected = Counter() if isolated else Counter(entry['exportedSha256'] for entry in selected)
    assert observed == expected, (observed, expected)
    profile = xml.findtext('.//{http://www.pdfa.org/eapdf/ns/id/}conformance')
    if profile in ('c', 'ci'):
        leaves = Counter()
        count = 0
        for file in named_files(pdf.Root.Names.EmbeddedFiles):
            if str(file.get('/AFRelationship')) == '/Data':
                with pikepdf.Pdf.open(BytesIO(embedded_bytes(file))) as child:
                    leaves.update(inspect_pdf(child, source, isolated))
                count += 1
        assert count == len(selected) and leaves == subjects
    return subjects


def main():
    output = Path(os.environ.get('EAPDF_MBOX_CORPUS_DIR', ROOT / 'docs/improvement/batch-13-corpus')).resolve()
    output.mkdir(parents=True, exist_ok=True)
    reports = ROOT / 'docs/improvement/batch-13-reader-reports'
    inputs = {source['id']: source for source in json.loads((FIXTURES / 'inputs.json').read_text())}
    cases = json.loads((FIXTURES / 'cases.json').read_text())
    check_oracles(inputs, reports)
    config = FIXTURES / 'config.json'
    classpath = os.environ['EAPDF_GENERATOR_CLASSPATH']
    results = []
    for case in cases:
        destination = output / case['id']
        destination.mkdir(exist_ok=True)
        archive = destination / 'archive.pdf'
        source = inputs.get(case['input'])
        input_path = FIXTURES / (source['input'] if source else case['input'] + '.mbox')
        command = ['java', '-Djava.awt.headless=true', '-Xmx512m', '-cp', classpath,
                   'eapdf.EmailToPDFA', '--out', str(destination)]
        profile = case['profile']
        if profile in ('s', 'si'):
            if profile == 'si':
                command.append('--omit-source')
        else:
            command.extend(['--archive-profile', profile, '--archive-output', str(archive)])
        command.append(str(input_path))
        sentinel = b'B13 existing destination must survive rejected mailbox\n'
        if case['result'] == 'REJECT':
            archive.write_bytes(sentinel)
        environment = dict(os.environ, EAPDF_CONFIG=str(config), EAPDF_MBOX_FORMAT=source['format'] if source else 'mboxrd')
        process = subprocess.run(command, cwd=ROOT, env=environment, text=True, capture_output=True, timeout=180)
        log = process.stdout + process.stderr
        (destination / 'generation.log').write_text(log)
        result = dict(case=case['id'], command=command, input_sha256=sha(input_path.read_bytes()), exit_code=process.returncode)
        if case['result'] == 'REJECT':
            assert process.returncode != 0 and archive.read_bytes() == sentinel, log
            assert 'completed: 0' in log and 'failed: 1' in log, log
            assert list(destination.glob('*.pdf')) == [archive]
            assert not list(destination.glob('*_index.xlsx')) and not list(destination.glob('*_premis.xml'))
            result.update(status='REJECT', destination_unchanged_sha256=sha(archive.read_bytes()))
        else:
            assert process.returncode == 0, log
            paths = sorted(destination.glob('*.pdf'))
            assert len(paths) == case['pdf_count'], paths
            all_subjects = Counter()
            observations = []
            for path in paths:
                with pikepdf.Pdf.open(path) as pdf:
                    all_subjects.update(inspect_pdf(pdf, source, profile.endswith('i')))
                report = validator.validate_one(str(path), html_path=str(path.with_suffix('.html')))
                path.with_suffix('.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
                assert report['pdfa']['status'] == report['summary']['status'] == 'PASS', (path, report['summary'])
                children = [row['details'] for row in report['items'] if row['code'] == 'COLLECTION_CHILD_VALIDATION'
                            and 'child' in row.get('details', {})]
                assert len(children) == case['child_count']
                assert all(child['pdfa_status'] == child['status'] == 'PASS' for child in children)
                observations.append(dict(pdf=str(path.relative_to(ROOT)), sha256=sha(path.read_bytes()),
                                         pdfa='PASS', eapdf='PASS', child_count=len(children)))
            assert all_subjects == Counter(entry['subject'] for entry in source['entries'])
            result.update(status='PASS', documents=observations)
        result['expected_result_observed'] = True
        results.append(result)
        print(case['id'], result['status'], flush=True)
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
