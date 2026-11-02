"""Table 16: ambiguity cannot hide a contradiction shared by every source."""
from itertools import permutations
import hashlib
import json
import unittest
from unittest.mock import patch

from test_asset_ownership import source, record, report_for
from test_preservation_safety import ROOT, validator as v, failed_codes


class AmbiguousSourceAssetTests(unittest.TestCase):
    def sources(self):
        return [source('one.eml', b'same'), source('two.txt', b'same', 'text/plain')]

    def check(self, changes, files=None):
        files = self.sources() if files is None else files
        second = dict(record(files[1], 'original-b.eml'),
                      SizeInBytes=str(len(files[1]['bytes'])),
                      ContentType=files[1].get('reconstructed_media_type') or files[1]['subtype'])
        records = [dict(record(files[0], 'original-a.eml'), **changes), second]
        return report_for(records, files)

    def test_size_contradicted_by_every_source_fails_without_assigning_an_owner(self):
        files = self.sources()
        records = [dict(record(files[0], 'first-original.eml'), SizeInBytes='9' * 5000),
                   record(files[1], 'second-original.eml')]
        for sources in permutations(files):
            for assets in permutations(records):
                with self.subTest(sources=[f['filename'] for f in sources], assets=[a['Filename'] for a in assets]):
                    result = report_for(assets, sources)
                    self.assertEqual({'ASSET_SIZE_MATCH'}, failed_codes(result))
                    self.assertIn('VALIDATION_INCOMPLETE', result.seen_codes)
                    self.assertEqual({'WARN'}, {i['level'] for i in result.items if i['code'] == 'ASSET_MATCH'})

    def test_media_contradicted_by_every_source_fails(self):
        result = self.check({'ContentType': 'application/pdf'})
        self.assertEqual({'ASSET_CONTENTTYPE_MATCH'}, failed_codes(result))

    def test_both_independent_contradictions_are_reported(self):
        result = self.check({'SizeInBytes': '999', 'ContentType': 'application/pdf'})
        self.assertEqual({'ASSET_SIZE_MATCH', 'ASSET_CONTENTTYPE_MATCH'}, failed_codes(result))

    def test_any_compatible_media_candidate_prevents_that_failure(self):
        for media in ('message/rfc822', 'text/plain'):
            result = self.check({'ContentType': media})
            self.assertFalse(failed_codes(result))
            self.assertIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_unknown_size_blocks_only_the_size_failure(self):
        files = self.sources()
        files[1]['size'] = None
        result = self.check({'SizeInBytes': '999', 'ContentType': 'application/pdf'}, files)
        self.assertEqual({'ASSET_CONTENTTYPE_MATCH'}, failed_codes(result))

    def test_unknown_reconstruction_blocks_only_the_media_failure(self):
        files = self.sources()
        files[1]['reconstructed_media_type'] = None
        result = self.check({'SizeInBytes': '999', 'ContentType': 'application/pdf'}, files)
        self.assertEqual({'ASSET_SIZE_MATCH'}, failed_codes(result))

    def test_equivalent_integer_lexical_forms_do_not_fail(self):
        for value in ('4', '+0004', '0' * 5000 + '4'):
            self.assertFalse(failed_codes(self.check({'SizeInBytes': value})))

    def test_schema_invalid_semantic_values_do_not_invent_comparison_failures(self):
        files = self.sources()
        for change in ({'SizeInBytes': v.XmpUri('999')}, {'SizeInBytes': ['999']},
                       {'SizeInBytes': '-1'}, {'ContentType': v.XmpUri('application/pdf')},
                       {'ContentType': ['application/pdf']}):
            records = [dict(record(files[0], 'original-a.eml'), **change), record(files[1], 'original-b.eml')]
            with self.subTest(change=change), patch.object(v, 'flatten_assets', return_value=records):
                result = report_for([], files)
                self.assertFalse({'ASSET_SIZE_MATCH', 'ASSET_CONTENTTYPE_MATCH'} & failed_codes(result))

    def test_no_sources_cannot_prove_size_or_media_contradictions(self):
        file = self.sources()[0]
        result = report_for([dict(record(file), SizeInBytes='999', ContentType='application/pdf')], [])
        self.assertFalse({'ASSET_SIZE_MATCH', 'ASSET_CONTENTTYPE_MATCH'} & failed_codes(result))

    def test_permitted_media_parameter_forms_compare_equivalently(self):
        files = self.sources()
        files[0]['reconstructed_media_type'] = 'text/plain; name="résumé.txt"'
        files[1]['reconstructed_media_type'] = 'text/html; name="different.txt"'
        result = self.check({'ContentType': "TEXT/PLAIN; name*=utf-8''r%C3%A9sum%C3%A9.txt"}, files)
        self.assertFalse(failed_codes(result))

    def test_malformed_parameter_contradiction_is_not_dropped(self):
        files = self.sources()
        files[0]['reconstructed_media_type'] = 'text/plain; x=y; =first'
        files[1]['reconstructed_media_type'] = 'text/plain; x=y; =second'
        result = self.check({'ContentType': 'text/plain; x=y; =different'}, files)
        self.assertEqual({'ASSET_CONTENTTYPE_MATCH'}, failed_codes(result))

    def test_actual_source_asset_pdf_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-ambiguous-sources'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate(str(path))
                self.assertEqual(case['expected_status'], result.summary()['status'])
                self.assertEqual(set(case['expected_failures']), failed_codes(result))
                if case['file'] != 'unique-name.pdf':
                    self.assertIn('VALIDATION_INCOMPLETE', result.seen_codes)
                    self.assertEqual({'WARN'}, {i['level'] for i in result.items if i['code'] == 'ASSET_MATCH'})


if __name__ == '__main__':
    unittest.main()
