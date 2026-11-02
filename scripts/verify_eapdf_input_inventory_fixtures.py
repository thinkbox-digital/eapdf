"""Reconcile authored input expectations independently of Java and PDF-only validation."""
from collections import Counter
from email import policy
from email.parser import BytesParser
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path
import sys
from xml.etree import ElementTree as ET

import pikepdf

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import validate_eapdf as validator

MAIL = 'http://www.pdfa.org/eapdf/ns/meta/'
RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'


def fingerprint(data):
    return len(data), hashlib.sha256(data).hexdigest()


def declared_fingerprint(value):
    return value['size'], value['sha256']


def metadata_rows(xml, name):
    return xml.findall('.//{' + MAIL + '}' + name + '/{' + RDF + '}Bag/{' + RDF + '}li')


def field(row, name):
    return row.findtext('{' + MAIL + '}' + name, '')


def authored_inputs(snapshot, case):
    """Check captured hashes against authored source bytes, not Java's hashing code."""
    original = (ROOT / 'tests/fixtures/eapdf/batch11/source.eml').read_bytes().decode()
    inputs = {}
    for letter in 'ABC':
        raw = original.replace('Child acceptance', 'Inventory ' + letter).replace(
            'Original native attachment bytes', 'Original payload ' + letter).encode()
        message = BytesParser(policy=policy.default).parsebytes(raw)
        native = next(part.get_payload(decode=True) for part in message.walk()
                      if part.get_filename() == 'native.txt')
        inputs[fingerprint(native)] = fingerprint(raw)
    assert len(snapshot['messages']) == (1 if snapshot['profile'].startswith('s') else 3), case['id']
    assert snapshot['isolated'] == snapshot['profile'].endswith('i'), case['id']
    all_natives = Counter(declared_fingerprint(item['original']) for message in snapshot['messages'].values()
                          for item in message['attachments'])
    expected_natives = Counter(list(inputs)[:1] if snapshot['profile'].startswith('s') else inputs.keys())
    if case['id'] == 'reused-identical-occurrence':
        expected_natives.update([next(iter(inputs))])
    assert all_natives == expected_natives, case['id']
    for guid, message in snapshot['messages'].items():
        assert guid == message['guid'] and message['attachments'], case['id']
        assert len(message['attachments']) == (2 if case['id'] == 'reused-identical-occurrence' else 1), case['id']
        natives = [declared_fingerprint(item['original']) for item in message['attachments']]
        assert all(native in inputs for native in natives), case['id']
        assert all(item['filename'] == 'native.txt' for item in message['attachments']), case['id']
        assert message['assets'] == [] if snapshot['isolated'] else (
            len(message['assets']) == 1 and message['assets'][0]['relationship'] == 'Source'
            and declared_fingerprint(message['assets'][0]['original']) == inputs[natives[0]]), case['id']


def named_files(node):
    names = node.get('/Names', [])
    yield from (names[index] for index in range(1, len(names), 2))
    for child in node.get('/Kids', []):
        yield from named_files(child)


def stream_bytes(file):
    data = [file.EF[key].read_bytes() for key in ('/F', '/UF') if key in file.EF]
    assert data and all(value == data[0] for value in data), 'Unequal alternative embedded streams'
    return data[0]


def independent_reconciliation(path, snapshot):
    expected = snapshot['messages']
    leaf_messages = Counter()
    differences = []

    def scan(pdf, root=False, depth=0):
        assert depth <= 8
        xml = ET.fromstring(pdf.Root.Metadata.read_bytes())
        present = [field(row, 'Mail_GUID') for row in metadata_rows(xml, 'email')]
        if root and Counter(present) != Counter({guid: 1 for guid in expected}):
            differences.append('root-message-occurrences')
        known = [guid for guid in present if guid in expected]
        expected_sources = Counter(declared_fingerprint(asset['original'])
                                   for guid in known for asset in expected[guid]['assets'])
        actual_sources = Counter(fingerprint(stream_bytes(file)) for file in pdf.Root.get('/AF', [])
                                 if str(file.get('/AFRelationship')) == '/Source')
        if actual_sources != expected_sources:
            differences.append('source-bytes')
        actual_profile = xml.findtext('.//{http://www.pdfa.org/eapdf/ns/id/}conformance')
        if actual_profile in ('c', 'ci'):
            for file in named_files(pdf.Root.Names.EmbeddedFiles):
                if str(file.get('/AFRelationship')) == '/Data':
                    with pikepdf.Pdf.open(BytesIO(stream_bytes(file))) as child:
                        scan(child, depth=depth + 1)
            return
        leaf_messages.update(present)
        actual_attachments = {guid: Counter() for guid in known}
        for page in pdf.pages:
            part = page.get('/DPart')
            seen = set()
            guid = ''
            while part is not None and part.objgen not in seen:
                seen.add(part.objgen)
                guid = str(part.get('/DPM', {}).get('/Mail_GUID', ''))
                if guid:
                    break
                part = part.get('/Parent')
            for annotation in page.get('/Annots', []):
                if str(annotation.get('/Subtype')) != '/FileAttachment' or str(annotation.FS.get('/AFRelationship')) != '/Data':
                    continue
                key = (str(annotation.FS.get('/UF', annotation.FS.get('/F'))), fingerprint(stream_bytes(annotation.FS)))
                actual_attachments.setdefault(guid, Counter())[key] += 1
        for guid, observed in actual_attachments.items():
            intended = Counter((item['filename'], fingerprint(b'') if snapshot['isolated'] else declared_fingerprint(item['original']))
                               for item in expected.get(guid, {}).get('attachments', []))
            if observed != intended:
                differences.append('native-bytes-owner-or-occurrences')

    with pikepdf.Pdf.open(path) as pdf:
        scan(pdf, root=True)
    if leaf_messages != Counter({guid: 1 for guid in expected}):
        differences.append('rendered-message-occurrences')
    return sorted(set(differences))


def main():
    output = Path(os.environ.get('EAPDF_INPUT_INVENTORY_FIXTURE_DIR', ROOT / 'docs/improvement/batch-12-corpus')).resolve()
    cases = json.loads((ROOT / 'tests/fixtures/eapdf/batch12/cases.json').read_text())
    results = []
    for case in cases:
        path = output / (case['id'] + '.pdf')
        snapshot = json.loads(path.with_suffix('.expected-inputs.json').read_text())
        creator = json.loads(path.with_suffix('.creator.json').read_text())
        authored_inputs(snapshot, case)
        differences = independent_reconciliation(path, snapshot)
        assert creator['status'] == case['creator'] == ('REJECT' if differences else 'PASS'), (case['id'], creator, differences)
        if differences:
            assert creator['existing_destination_check'] == 'unchanged bytes asserted', case['id']
        report = validator.validate_one(str(path), html_path=str(path.with_suffix('.html')))
        path.with_suffix('.json').write_text(json.dumps(report, indent=2, default=str) + '\n')
        failures = sorted({row['code'] for row in report['items'] if row['level'] == 'FAIL'})
        assert report['pdfa']['status'] == 'PASS', (case['id'], report['pdfa'])
        assert report['summary']['status'] == case['eapdf'] and failures == case['failures'], (case['id'], failures, report['summary'])
        children = [row['details'] for row in report['items'] if row['code'] == 'COLLECTION_CHILD_VALIDATION'
                    and 'child' in row.get('details', {})]
        assert len(children) == case['child_count'] and all(child['pdfa_status'] == 'PASS' for child in children), case['id']
        child_failures = sorted({row['code'] for child in children for row in child['findings'] if row['level'] == 'FAIL'})
        assert child_failures == case['child_failures'], (case['id'], child_failures)
        result = dict(case=case['id'], sha256=hashlib.sha256(path.read_bytes()).hexdigest(), creator=creator['status'],
                      independent_input_differences=differences, pdfa='PASS', pdf_only_eapdf=report['summary']['status'],
                      pdf_only_failures=failures, child_count=len(children), child_pdfa='PASS' if children else 'N/A',
                      child_failures=child_failures, expected_result_observed=True)
        results.append(result)
        print(case['id'], creator['status'], differences, 'PDF/A PASS', report['summary']['status'], failures, flush=True)
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
