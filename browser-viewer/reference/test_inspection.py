"""Focused M1 evidence checks, independent assertions as well as frozen snapshots."""
import copy
import hashlib
import io
import importlib.metadata
import json
import unittest
import xml.etree.ElementTree as ET

import pikepdf
from jsonschema import Draft202012Validator, ValidationError
from fixtures import FIXTURES, ROOT, VIEWER, generate
from inspect_pdf import IDENT, META, RDF, Inspector, inspect_path, read_embedded_bytes, ResourceLimitError
from make_controls import CONTROLS, make


class InspectionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads((FIXTURES/'manifest.json').read_text())
        cls.cases = {c['id']: c for c in cls.manifest['fixtures']}
        cls.results = {key: json.loads((FIXTURES/c['expected']).read_text()) for key, c in cls.cases.items()}
        cls.schema = json.loads((VIEWER/'schema/inspection-v1.schema.json').read_text())
        cls.validator = Draft202012Validator(cls.schema)

    def test_schema_and_frozen_provenance(self):
        for line in (VIEWER/'reference/requirements.txt').read_text().splitlines():
            if '==' in line and not line.startswith('#'):
                package, version = line.split('==')
                self.assertEqual(importlib.metadata.version(package), version, package)
        Draft202012Validator.check_schema(self.schema)
        manifest, outputs = generate()
        self.assertEqual(manifest, self.manifest)
        for name, expected in outputs.items():
            with self.subTest(name=name):
                self.assertEqual((FIXTURES/name).read_text(), expected)
                self.validator.validate(json.loads(expected))

    def test_hand_reviewed_six_profile_facts(self):
        profiles = set()
        for key, case in self.cases.items():
            doc = self.results[key]
            if case['category'] == 'repository-example':
                profiles.add(doc['identification']['declaredProfile']['value'])
            facts = dict(profile=doc['identification']['declaredProfile']['value'], pages=doc['pageCount'],
                messages=len(doc['messages']), files=len(doc['files']),
                sourceAssetRecords=sum(r['kind'] == 'source-asset' for r in doc['metadataRecords']),
                annotationFiles=sum(any(o['kind'] == 'annotation' for o in f['origins']) for f in doc['files']),
                bodyRanges=[[p['pageRange']['value']['start'], p['pageRange']['value']['end']] for p in doc['parts']
                    if p['dpm'].get('Mail_ContentSetType', {}).get('value') == 'BodyRendering' and p['pageRange']['state'] == 'present'],
                childProfiles=[c['inspection']['identification']['declaredProfile']['value']
                    for c in (doc['collection']['value'] or {}).get('children', []) if c['inspection']])
            with self.subTest(case=key):
                for field, expected in case['requiredFacts'].items():
                    self.assertEqual(facts[field], expected, field)
                codes = {d['code'] for d in doc['diagnostics']}
                self.assertTrue(set(case['requiredDiagnostics']) <= codes)
                self.assertEqual(sorted(codes), case['expectedDiagnosticCodes'])
        self.assertEqual(profiles, {'s', 'si', 'm', 'mi', 'c', 'ci'})

    def verify_direct(self, doc, data):
        """Read raw qpdf objects independently of Inspector's normalization/binding code."""
        self.assertEqual(doc['documentId'], 'sha256:' + hashlib.sha256(data).hexdigest())
        self.assertEqual(doc['source']['byteLength'], len(data))
        with pikepdf.open(io.BytesIO(data), attempt_recovery=False) as pdf:
            self.assertEqual(doc['pageCount'], len(pdf.pages))
            page_numbers = {p.obj.objgen: i for i, p in enumerate(pdf.pages)}
            by_id = {p['id']: p for p in doc['parts']}
            messages = {m['id']: m for m in doc['messages']}
            records = {r['id']: r for r in doc['metadataRecords']}
            files = {f['id']: f for f in doc['files']}
            for part in doc['parts']:
                loc = part['locator']
                node = pdf.get_object((loc['objectNumber'], loc['generation']))
                if part['parentId']:
                    self.assertIn(part['id'], by_id[part['parentId']]['childIds'])
                for child in part['childIds']:
                    self.assertEqual(by_id[child]['parentId'], part['id'])
                for mid in part['messageIds']['value'] or []:
                    self.assertIn(mid, messages)
                if part['pageRange']['state'] == 'present':
                    start = page_numbers[node.Start.objgen]
                    end = page_numbers[node.get('/End', node.Start).objgen]
                    self.assertEqual(part['pageRange']['value'], dict(indexBase=0, endInclusive=True, start=start, end=end))
                    self.assertLessEqual(start, end)
                    self.assertLess(end, len(pdf.pages))
                for name, obs in part['dpm'].items():
                    if obs['raw'] and obs['raw'][0]['bytesHex'] is not None:
                        self.assertEqual(obs['raw'][0]['bytesHex'], bytes(node.DPM['/' + name]).hex())
            for message in messages.values():
                self.assertIn(message['metadataRecordId'], records)
                self.assertEqual(message['documentId'], doc['documentId'])
                for pid in message['partIds']['value'] or []:
                    self.assertIn(message['id'], by_id[pid]['messageIds']['value'])
            for file in files.values():
                loc = file['locator']
                spec = pdf.get_object((loc['objectNumber'], loc['generation']))
                for field, name in [('filename', '/F'), ('unicodeFilename', '/UF')]:
                    if file[field]['state'] == 'present':
                        self.assertEqual(file[field]['raw'][0]['bytesHex'], bytes(spec[name]).hex())
                for mid in file['owners']['value'] or []:
                    self.assertIn(mid, messages)
                for rid in file['metadataRecordIds']:
                    self.assertIn(rid, records)
                for stream in file['streams']:
                    sloc = stream['locator']
                    obj = pdf.get_object((sloc['objectNumber'], sloc['generation']))
                    keys = sorted(k for k, v in spec.EF.items() if v.objgen == obj.objgen)
                    self.assertEqual(keys, stream['efKeys'])
                    payload = obj.read_bytes()
                    self.assertEqual(stream['decodedSize']['value'], len(payload))
                    self.assertEqual(stream['sha256']['value'], hashlib.sha256(payload).hexdigest())
                    self.assertEqual(stream['computedMd5']['value'], hashlib.md5(payload, usedforsecurity=False).hexdigest())
            if doc['identification']['declaredProfile']['state'] == 'present':
                xml = ET.fromstring(pdf.Root.Metadata.read_bytes())
                declarations = xml.findall(f'.//{{{RDF}}}RDF/{{{RDF}}}Description/{{{IDENT}}}conformance')
                self.assertIn(doc['identification']['declaredProfile']['value'], [n.text for n in declarations])
            collection = doc['collection']['value']
            if collection:
                folder_ids = {f['id'] for f in collection['folders']}
                for entry in collection['entries']:
                    self.assertIn(entry['fileId'], files)
                    self.assertTrue(set(entry['folderIds']['value'] or []) <= folder_ids)
                for child in collection['children']:
                    self.assertIn(child['fileId'], files)
                    if child['inspection']:
                        self.assertEqual(child['documentId'], child['inspection']['documentId'])
                        sloc = child['stream']
                        payload = pdf.get_object((sloc['objectNumber'], sloc['generation'])).read_bytes()
                        self.verify_direct(child['inspection'], payload)

    def test_direct_page_objects_stream_bytes_and_relationships(self):
        for key, doc in self.results.items():
            with self.subTest(case=key):
                self.verify_direct(doc, (ROOT/self.cases[key]['sourcePath']).read_bytes())

    def test_controls_are_reproducible(self):
        for name, args in CONTROLS.items():
            with self.subTest(case=name):
                self.assertEqual(make(*args), make(*args))
                self.assertEqual(make(*args), (FIXTURES/'controls'/f'{name}.pdf').read_bytes())
        with pikepdf.open(FIXTURES/'controls/s-malformed-xmp.pdf') as pdf:
            self.assertEqual(pdf.Root.Metadata.read_bytes(), b'<not-closed')

    def test_guid_is_not_message_id_and_ambiguity_is_retained(self):
        missing = self.results['s-missing-guid']
        self.assertEqual(missing['messages'][0]['mailGuid']['state'], 'missing')
        self.assertEqual(missing['messages'][0]['internetMessageId']['state'], 'present')
        self.assertTrue(all(p['messageIds']['value'] is None for p in missing['parts']))
        duplicate = self.results['m-duplicate-guid']
        self.assertEqual(len({m['id'] for m in duplicate['messages']}), 2)
        self.assertEqual(len({m['mailGuid']['value'] for m in duplicate['messages']}), 1)
        ambiguous = [p['messageIds'] for p in duplicate['parts'] if p['messageIds']['state'] == 'ambiguous']
        self.assertTrue(ambiguous)
        self.assertTrue(all(a['value'] is None and len(a['candidates']) == 2 for a in ambiguous))
        invalid = self.results['si-name-guid']
        self.assertTrue(any(p['dpm'].get('Mail_GUID', {}).get('state') == 'invalid' for p in invalid['parts']))
        self.assertTrue(all(p['messageIds']['value'] is None for p in invalid['parts']))

    def test_unavailable_sources_and_candidate_file_matches(self):
        for key in ['example-1s', 'example-1m', 'example-1c', 's-object-af-only']:
            for record in self.results[key]['metadataRecords']:
                if record['kind'] == 'source-asset':
                    self.assertEqual(record['owners']['state'], 'unavailable')
        objfile = self.results['s-object-af-only']['files'][0]
        self.assertEqual([o['kind'] for o in objfile['origins']], ['object-af'])
        self.assertEqual(objfile['roles']['value'], ['source-asset'])
        self.assertEqual(objfile['owners']['state'], 'unavailable')
        duplicate = next(r for r in self.results['s-duplicate-files']['metadataRecords'] if r['kind'] == 'attachment')
        self.assertEqual(duplicate['files']['state'], 'ambiguous')
        self.assertEqual(len(duplicate['files']['candidates']), 2)
        for key in ['example-1si', 'example-1mi']:
            for record in self.results[key]['metadataRecords']:
                if record['kind'] == 'attachment':
                    self.assertEqual(record['files']['state'], 'unavailable')

    def test_real_message_bindings_match_raw_xmp_and_ancestor_guids(self):
        for profile in ['s', 'si', 'm', 'mi']:
            key = 'example-1' + profile
            doc = self.results[key]
            with pikepdf.open(ROOT/self.cases[key]['sourcePath']) as pdf:
                xml = ET.fromstring(pdf.Root.Metadata.read_bytes())
                records = xml.findall(f'.//{{{META}}}email/{{{RDF}}}Bag/{{{RDF}}}li')
                guids = [r.find(f'{{{META}}}Mail_GUID').text for r in records]
                self.assertEqual(guids, [m['mailGuid']['value'] for m in doc['messages']])
                for part in doc['parts']:
                    loc = part['locator']
                    node = pdf.get_object((loc['objectNumber'], loc['generation']))
                    guid = None
                    while node is not None:
                        if '/DPM' in node and '/Mail_GUID' in node.DPM:
                            guid = str(node.DPM.Mail_GUID)
                            break
                        node = node.get('/Parent')
                    expected = [m['id'] for m in doc['messages'] if m['mailGuid']['value'] == guid] if guid else []
                    self.assertEqual(part['messageIds']['value'] or [], expected)
                for file in doc['files']:
                    pages = [o['pageIndex'] for o in file['origins'] if o['kind'] == 'annotation']
                    if pages:
                        expected = {mid for part in doc['parts'] if part['pageRange']['state'] == 'present'
                            and any(part['pageRange']['value']['start'] <= page <= part['pageRange']['value']['end'] for page in pages)
                            for mid in part['messageIds']['value'] or []}
                        self.assertEqual(set(file['owners']['value']), expected)

    def test_identity_matching_is_exact_and_guid_takes_precedence(self):
        with pikepdf.open(io.BytesIO(make('s'))) as pdf:
            part = pdf.Root.DPartRoot.DPartRootNode.DParts[0][0]
            part.DPM.Mail_GUID = pikepdf.String('GUID-0')
            output = io.BytesIO()
            pdf.save(output, fix_metadata_version=False)
        doc = Inspector(output.getvalue()).inspect()
        self.assertEqual(doc['messages'][0]['mailGuid']['value'], 'guid-0')
        self.assertTrue(all(p['messageIds']['value'] is None for p in doc['parts']))
        self.assertTrue(any(p['dpm'].get('Mail_GUID', {}).get('value') == 'GUID-0' for p in doc['parts']))

    def test_invalid_pdf_types_do_not_become_present_values(self):
        with pikepdf.open(io.BytesIO(make('s'))) as pdf:
            pdf.Root.Version = pikepdf.String('2.0')  # A name is required.
            attachment = next(a for a in pdf.pages[0].obj.Annots if a.get('/Subtype') == pikepdf.Name.FileAttachment)
            attachment.FS.EF.F = pikepdf.String('not a stream')
            output = io.BytesIO()
            pdf.save(output, fix_metadata_version=False)
        doc = Inspector(output.getvalue()).inspect()
        self.validator.validate(doc)
        self.assertEqual(doc['identification']['effectivePdfVersion']['state'], 'invalid')
        self.assertEqual(doc['files'][0]['embedding'], 'invalid')

    def test_empty_missing_and_multiple_streams_are_different(self):
        empty = self.results['example-1si']['files'][0]
        self.assertEqual(empty['embedding'], 'present')
        self.assertEqual(empty['streams'][0]['decodedSize']['value'], 0)
        self.assertEqual(empty['streams'][0]['extraction'], 'present')
        missing = self.results['s-missing-stream']['files'][0]
        self.assertEqual(missing['embedding'], 'missing')
        self.assertEqual(missing['streams'], [])
        streams = self.results['s-two-streams']['files'][0]['streams']
        self.assertEqual(len(streams), 2)
        self.assertNotEqual(streams[0]['sha256']['value'], streams[1]['sha256']['value'])

    def test_profile_and_metadata_states(self):
        for key, state in [('s-unknown-profile', 'unsupported'), ('s-conflicting-profile', 'ambiguous'),
                           ('s-malformed-xmp', 'unavailable'), ('ordinary', 'missing')]:
            self.assertEqual(self.results[key]['identification']['declaredProfile']['state'], state)
        self.assertEqual(self.results['s-malformed-xmp']['coverage']['metadata'], 'invalid')
        self.assertEqual(self.results['s-uri-guid']['messages'][0]['mailGuid']['state'], 'invalid')
        uri_case = self.results['existing-uri-checksum']
        attachment = next(r for r in uri_case['metadataRecords'] if r['kind'] == 'attachment')
        self.assertEqual(attachment['fields']['CheckSum']['state'], 'invalid')
        with pikepdf.open(ROOT/self.cases['existing-uri-checksum']['sourcePath']) as pdf:
            xml = ET.fromstring(pdf.Root.Metadata.read_bytes())
            checksum = xml.find(f'.//{{{META}}}attachments/{{{RDF}}}Bag/{{{RDF}}}li/{{{META}}}CheckSum')
            self.assertIsNotNone(checksum)
            self.assertIsNone(checksum.text)
            self.assertEqual(checksum.get(f'{{{RDF}}}resource'), 'urn:checksum:synthetic')
        # Unknown conflicting values must remain representable as candidates.
        doc = Inspector(make('future-profile', 'conflicting-profile')).inspect()
        self.validator.validate(doc)
        self.assertEqual(doc['identification']['declaredProfile']['state'], 'ambiguous')
        self.assertIn('future-profile', doc['identification']['declaredProfile']['candidates'])

    def test_nested_mixed_collections(self):
        children = self.results['c-nested-mixed']['collection']['value']['children']
        self.assertEqual([c['inspection']['identification']['declaredProfile']['value'] for c in children], ['m', 'ci'])
        grandchild = children[1]['inspection']['collection']['value']['children'][0]
        self.assertEqual(grandchild['inspection']['identification']['declaredProfile']['value'], 'si')
        self.assertEqual(self.results['ci-corrupt-child']['collection']['value']['children'][0]['state'], 'invalid')

    def test_resource_limits_are_unavailable_not_missing(self):
        path = ROOT/self.cases['example-1c']['sourcePath']
        doc = inspect_path(path, {'maxChildDepth': 0})
        self.assertTrue(all(c['state'] == 'unavailable' for c in doc['collection']['value']['children']))
        doc = inspect_path(path, {'maxMetadataBytes': 1})
        self.assertEqual(doc['coverage']['metadata'], 'unavailable')
        self.assertEqual(doc['identification']['declaredProfile']['state'], 'unavailable')
        doc = inspect_path(path, {'maxStreamBytes': 1})
        self.assertTrue(all(s['extraction'] == 'unavailable' for f in doc['files'] for s in f['streams']))
        doc = inspect_path(path, {'maxParts': 1})
        self.assertEqual(doc['coverage']['documentParts'], 'unavailable')
        with self.assertRaisesRegex(ValueError, 'Input file byte limit'):
            inspect_path(path, {'maxFileBytes': 1})

    def test_bounded_extraction_and_document_identity(self):
        for key in ['example-1s', 'example-1si', 's-two-streams']:
            doc = self.results[key]
            path = ROOT/self.cases[key]['sourcePath']
            for file in doc['files']:
                for stream in file['streams']:
                    request = dict(documentId=doc['documentId'], fileId=file['id'], stream=stream['locator'], maxBytes=stream['decodedSize']['value'])
                    data = read_embedded_bytes(path, request)
                    self.assertEqual(hashlib.sha256(data).hexdigest(), stream['sha256']['value'])
                    if data:
                        with self.assertRaises(ResourceLimitError):
                            read_embedded_bytes(path, dict(request, maxBytes=len(data)-1))
                    with self.assertRaisesRegex(ValueError, 'different document'):
                        read_embedded_bytes(path, dict(request, documentId='sha256:wrong'))
                    with self.assertRaisesRegex(ValueError, 'not an EF target'):
                        read_embedded_bytes(path, dict(request, stream=dict(stream['locator'], objectNumber=1, generation=0, path='object:1:0')))

    def test_schema_rejects_misleading_states_and_payloads(self):
        baseline = self.results['example-1s']
        mutations = [lambda d: d.update(schemaVersion=2), lambda d: d.update(binaryPayload='secret'),
            lambda d: d['identification'].update(validationStatus='PASS'),
            lambda d: d['identification']['declaredProfile'].update(value='future'),
            lambda d: d['identification']['declaredProfile'].update(basis='none'),
            lambda d: d['identification']['declaredProfile'].update(state='ambiguous'),
            lambda d: d['parts'][-1]['pageRange']['value'].update(start=-1)]
        for mutation in mutations:
            doc = copy.deepcopy(baseline)
            mutation(doc)
            with self.assertRaises(ValidationError):
                self.validator.validate(doc)


if __name__ == '__main__':
    unittest.main()
