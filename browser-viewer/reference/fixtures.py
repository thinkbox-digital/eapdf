"""Manifest builder/checker. Refresh is explicit; tests never accept new outputs silently."""
import argparse
import hashlib
import importlib.metadata
import json
import sys
from pathlib import Path

import pikepdf
from jsonschema import Draft202012Validator
from inspect_pdf import inspect_path
from make_controls import CONTROLS

VIEWER = Path(__file__).resolve().parents[1]
ROOT = VIEWER.parent
FIXTURES = VIEWER / 'tests/inspection'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cases():
    rows = []
    for profile, pages, messages, files, source_records, annotations, ranges in [
        ('s', 2, 1, 2, 1, 1, [[0, 1]]), ('si', 2, 1, 1, 0, 1, [[0, 1]]),
        ('m', 5, 2, 5, 2, 2, [[1, 2], [3, 4]]), ('mi', 5, 2, 3, 0, 2, [[1, 2], [3, 4]]),
        ('c', 1, 2, 4, 2, 0, []), ('ci', 1, 2, 2, 0, 0, []),
    ]:
        rows.append(dict(id='example-1' + profile, sourcePath=f'browser-viewer/tests/inspection/source-examples/eml/eml_sample_1{profile}.pdf',
            category='repository-example', intendedProfile=profile,
            provenance='Existing example manifest plus direct XMP, page-object and embedded-stream inspection; validation PASS was not used as an extraction oracle.',
            requiredFacts=dict(profile=profile, pages=pages, messages=messages, files=files,
                sourceAssetRecords=source_records, annotationFiles=annotations, bodyRanges=ranges,
                childProfiles=['s', 's'] if profile == 'c' else ['si', 'si'] if profile == 'ci' else []),
            requiredDiagnostics=[]))
    expected_codes = {
        'si-name-guid': ['INVALID_MESSAGE_IDENTITY'], 'm-duplicate-guid': ['AMBIGUOUS_MESSAGE_IDENTITY'],
        'mi-reversed-range': ['INVALID_PAGE_RANGE'], 'c-folder-cycle': ['COLLECTION_FOLDER_TRAVERSAL_STOPPED'],
        'ci-corrupt-child': ['COLLECTION_CHILD_UNAVAILABLE'], 's-unknown-profile': ['UNKNOWN_PROFILE'],
        's-conflicting-profile': ['XMP_VALUE_AMBIGUOUS'], 's-uri-guid': ['XMP_VALUE_NOT_TEXT'],
        's-duplicate-files': ['AMBIGUOUS_FILE_BINDING'], 's-bad-checksum': ['CHECKSUM_MISMATCH'],
        's-malformed-xmp': ['METADATA_UNREADABLE'], 's-dpart-cycle': ['DPART_TRAVERSAL_STOPPED'],
        's-invalid-page-ref': ['INVALID_PAGE_RANGE'],
    }
    for name, (profile, mutation) in sorted(CONTROLS.items()):
        rows.append(dict(id=name, sourcePath=f'browser-viewer/tests/inspection/controls/{name}.pdf',
            category='synthetic-structural-control', intendedProfile=profile,
            provenance=f'Original Apache-2.0 control from reference/make_controls.py; mutation={mutation}. Not a conforming EA-PDF or validation oracle.',
            requiredFacts={}, requiredDiagnostics=expected_codes.get(name, [])))
    for name, path, code in [
        ('existing-reversed-order', 'validator-dpart-object-ranges/multi-reversed.pdf', None),
        ('existing-uri-checksum', 'validator-xmp-records/attachment-uri-checksum.pdf', 'XMP_VALUE_NOT_TEXT'),
    ]:
        rows.append(dict(id=name, sourcePath='tests/fixtures/eapdf/' + path, category='repository-negative', intendedProfile=None,
                         provenance='Existing intentionally malformed regression PDF; compare the neighboring expectations.json for its original purpose, not as an extraction verdict.',
                         requiredFacts=dict(bodyRanges=[[4, 4], [3, 3], [1, 2]]) if code is None else {},
                         requiredDiagnostics=[code] if code else []))
    rows.append(dict(id='ordinary', sourcePath='browser-viewer/tests/fixtures/ordinary.pdf', category='ordinary-control', intendedProfile=None,
                     provenance='M0 original Apache-2.0 ordinary PDF; it must not acquire an EA-PDF declaration.',
                     requiredFacts=dict(pages=2, messages=0, files=0, sourceAssetRecords=0, annotationFiles=0, bodyRanges=[], childProfiles=[]), requiredDiagnostics=[]))
    return rows


def requirements(result):
    """Reviewable exact relationships and byte facts, not conformance claims."""
    rows = []
    def walk(doc):
        for part in doc['parts']:
            if part['pageRange']['state'] == 'present':
                rows.append(dict(kind='part-pages', documentId=doc['documentId'], partId=part['id'],
                                 pageRange=part['pageRange']['value'], messageIds=part['messageIds']))
        for file in doc['files']:
            for stream in file['streams']:
                rows.append(dict(kind='embedded-bytes', documentId=doc['documentId'], fileId=file['id'], locator=stream['locator'],
                    efKeys=stream['efKeys'], decodedSize=stream['decodedSize'], sha256=stream['sha256'], owners=file['owners']))
        node = doc['collection']['value']
        if node:
            for child in node['children']:
                rows.append(dict(kind='collection-child', documentId=doc['documentId'], fileId=child['fileId'],
                                 childDocumentId=child['documentId'], state=child['state']))
                if child['inspection']:
                    walk(child['inspection'])
    walk(result)
    return rows


def generate():
    schema = json.loads((VIEWER / 'schema/inspection-v1.schema.json').read_text())
    validator = Draft202012Validator(schema)
    Draft202012Validator.check_schema(schema)
    inputs = ['docs/reference/EA-PDF-v1.pdf', 'validate_eapdf.py', 'browser-viewer/tests/inspection/source-examples/eml/manifest.json',
              'docs/improvement/specification-audit/specification-reexamination-2026-10-27.txt',
              'tests/fixtures/eapdf/validator-dpart-object-ranges/expectations.json',
              'tests/fixtures/eapdf/validator-xmp-records/expectations.json']
    manifest = dict(schemaVersion=1, scope='M1 extraction expectations, not conformance judgments',
        reference=dict(engine='qpdf via pikepdf; independent of PDF.js and validator verdicts',
            python=sys.version.split()[0], pikepdf=importlib.metadata.version('pikepdf'),
            jsonschema=importlib.metadata.version('jsonschema'), qpdf=pikepdf.__libqpdf_version__,
            dependencies={line.split('==')[0]: importlib.metadata.version(line.split('==')[0])
                for line in (VIEWER/'reference/requirements.txt').read_text().splitlines() if '==' in line and not line.startswith('#')},
            sources={str(p.relative_to(ROOT)): sha(p) for p in [Path(__file__), VIEWER/'reference/inspect_pdf.py',
                VIEWER/'reference/make_controls.py', VIEWER/'reference/requirements.txt',
                VIEWER/'scripts/inspection-contract.mjs', VIEWER/'schema/inspection-v1.schema.json']}),
        evidenceHashes={name: sha(ROOT/name) for name in inputs}, fixtures=[])
    outputs = {}
    for case in cases():
        path = ROOT / case['sourcePath']
        result = inspect_path(path)
        validator.validate(result)
        data = json.dumps(result, indent=2, ensure_ascii=False) + '\n'
        expected = 'expected/' + case['id'] + '.json'
        outputs[expected] = data
        manifest['fixtures'].append(dict(case, sha256=sha(path), expected=expected,
            expectedSha256=hashlib.sha256(data.encode()).hexdigest(),
            expectedDiagnosticCodes=sorted({d['code'] for d in result['diagnostics']}),
            requiredRelationships=requirements(result)))
    return manifest, outputs


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true', help='Explicitly refresh reviewed expected files and hashes')
    args = parser.parse_args()
    manifest, outputs = generate()
    outputs['manifest.json'] = json.dumps(manifest, indent=2, ensure_ascii=False) + '\n'
    if args.write:
        for name, data in outputs.items():
            (FIXTURES/name).write_text(data)
        print(f'Wrote {len(manifest["fixtures"])} expectation records and their provenance manifest. Review before accepting.')
    else:
        for name, data in outputs.items():
            if (FIXTURES/name).read_text() != data:
                raise SystemExit(f'Inspection evidence changed: {name}. Review the cause; tests do not refresh snapshots.')
        print(f'All {len(manifest["fixtures"])} reference records and provenance hashes match.')
