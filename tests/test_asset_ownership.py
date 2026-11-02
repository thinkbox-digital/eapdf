"""Source-asset association is independent of unordered XMP Bag order."""
import hashlib
import itertools
import json
import os
import unittest
from xml.etree import ElementTree as ET

from test_preservation_safety import ROOT, validator as v, failed_codes


def source(name, data, subtype='message/rfc822'):
    return dict(filename=name, ufilename=name, tree_name=name, afrelationship='Source',
                md5=hashlib.md5(data).hexdigest(), bytes=data, size=len(data), subtype=subtype)


def record(file, name=None):
    return dict(Filename=name or file['filename'], SizeInBytes=str(file['size']),
                Checksum=file['md5'], ContentType=file['subtype'])


def report_for(records, files):
    root = ET.Element(v.qn(v.NS_RDF, 'RDF'))
    description = ET.SubElement(root, v.qn(v.NS_RDF, 'Description'))
    bag = ET.SubElement(ET.SubElement(description, v.qn(v.NS_PDFMAILMETA, 'assets')), v.qn(v.NS_RDF, 'Bag'))
    for values in records:
        node = ET.SubElement(bag, v.qn(v.NS_RDF, 'li'), {v.qn(v.NS_RDF, 'parseType'): 'Resource'})
        for field, value in values.items():
            ET.SubElement(node, v.qn(v.NS_PDFMAILMETA, field)).text = value
    report = v.Report('assets.pdf')
    v.check_asset_metadata(root, [], files, 'm', report)
    return report


class AssetOwnershipTests(unittest.TestCase):
    def test_unique_checksum_survives_path_collision_and_every_order(self):
        files = [source('message.eml', b'first'), source('message_2.eml', b'second')]
        records = [record(files[0], 'folder-1/message.eml'), record(files[1], 'folder-2/message.eml')]
        for asset_order, file_order in itertools.product(itertools.permutations(records), itertools.permutations(files)):
            with self.subTest(assets=asset_order, files=file_order):
                self.assertEqual(set(), failed_codes(report_for(asset_order, file_order)))

    def test_checksum_can_identify_renamed_file_despite_another_matching_name(self):
        files = [source('one.eml', b'one'), source('two.eml', b'two')]
        records = [dict(record(files[0], 'two.eml'), Checksum=files[0]['md5'].upper()),
                   record(files[1], 'one.eml')]
        self.assertEqual(set(), failed_codes(report_for(records, files)))

    def test_unique_filename_still_exposes_a_wrong_checksum(self):
        file = source('message.eml', b'first')
        result = report_for([dict(record(file), Checksum='0' * 32)], [file])
        self.assertEqual({'ASSET_MD5_MATCH'}, failed_codes(result))

    def test_ambiguous_filename_keeps_provable_checksum_and_coverage_failures(self):
        files = [source('message.eml', b'first'), source('message.eml', b'second')]
        result = report_for([dict(record(files[0]), Checksum='0' * 32)], files)
        self.assertEqual({'ASSET_MD5_MATCH', 'SOURCE_WITHOUT_XMP'}, failed_codes(result))
        self.assertIn('VALIDATION_INCOMPLETE', result.seen_codes)
        self.assertEqual({'WARN'}, {item['level'] for item in result.items if item['code'] == 'ASSET_MATCH'})
        self.assertFalse(any(item['code'] == 'SOURCE_WITHOUT_XMP' and item['level'] == 'PASS' for item in result.items))

    def test_unrelated_uncovered_source_is_still_a_failure(self):
        files = [source('message.eml', b'first'), source('message.eml', b'second'), source('extra.eml', b'extra')]
        result = report_for([dict(record(files[0]), Checksum='0' * 32)], files)
        self.assertIn('SOURCE_WITHOUT_XMP', failed_codes(result))

    def test_identical_payload_and_media_candidates_are_interchangeable_for_comparison(self):
        files = [source('one.eml', b'same'), source('two.eml', b'same')]
        records = [record(files[0], 'folder-a/original.eml'), record(files[1], 'folder-b/original.eml')]
        result = report_for(records, files)
        self.assertEqual(set(), failed_codes(result))
        self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_same_bytes_with_different_media_remain_unavailable_without_unique_name(self):
        files = [source('one.eml', b'same'), source('two.txt', b'same', 'text/plain')]
        result = report_for([record(files[0], 'original-a.eml'), record(files[1], 'original-b.eml')], files)
        self.assertEqual(set(), failed_codes(result))
        self.assertIn('VALIDATION_INCOMPLETE', result.seen_codes)

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'), 'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_source_asset_ownership_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-asset-ownership'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(case['expected_status'], result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),
                                 {item['code'] for item in result['items'] if item['level'] == 'FAIL'})
                for code in case['required_unavailable_codes']:
                    self.assertTrue(any(item['code'] == code and item['level'] == 'WARN' for item in result['items']))


if __name__ == '__main__':
    unittest.main()
