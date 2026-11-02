"""Source filename identity preserves decoded text, paths and schema types."""
from itertools import permutations
import hashlib
import json
import unittest
from unittest.mock import patch

import pikepdf

from test_asset_ownership import source, record, report_for
from test_preservation_safety import ROOT, validator as v, failed_codes


class SourceFilenameIdentityTests(unittest.TestCase):
    def test_distinct_space_names_keep_their_independent_media_owners(self):
        files = [source('a  b.eml', b'same'), source('a b.eml', b'same', 'text/plain')]
        records = [record(f) for f in files]
        for file_order in permutations(files):
            for asset_order in permutations(records):
                result = report_for(asset_order, file_order)
                self.assertFalse(failed_codes(result))
                self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_whitespace_alias_cannot_select_the_wrong_source(self):
        files = [source('a  b.eml', b'same'), source('other.eml', b'same', 'text/plain')]
        records = [record(files[1], 'a b.eml'), record(files[0], 'unknown.eml')]
        result = report_for(records, files)
        self.assertFalse(failed_codes(result))
        self.assertIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_raw_pdf_names_take_precedence_over_display_cleanup(self):
        files = [source('a b.eml', b'same'), source('a b.eml', b'same', 'text/plain')]
        files[0].update(raw_ufilename='a  b.eml', raw_filename='a  b.eml')
        files[1].update(raw_ufilename='a b.eml', raw_filename='a b.eml')
        records = [record(files[0], 'a  b.eml'), record(files[1], 'a b.eml')]
        result = report_for(records, files)
        self.assertFalse(failed_codes(result))
        self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_leading_trailing_tabs_and_unicode_spaces_remain_distinct(self):
        for first, second in [(' report.eml', 'report.eml '), ('a\tb.eml', 'a b.eml'),
                              ('a\u00a0b.eml', 'a b.eml'), ('a\nb.eml', 'a b.eml')]:
            files = [source(first, b'same'), source(second, b'same', 'text/plain')]
            with self.subTest(first=first, second=second):
                result = report_for([record(f) for f in files], files)
                self.assertFalse(failed_codes(result))
                self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_case_and_unicode_normalization_are_not_filename_aliases(self):
        for first, second in [('Case.eml', 'case.eml'), ('résumé.eml', 're\u0301sume\u0301.eml')]:
            files = [source(first, b'same'), source(second, b'same', 'text/plain')]
            result = report_for([record(f) for f in files], files)
            self.assertFalse(failed_codes(result))
            self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_permitted_source_paths_keep_exact_basename_text(self):
        files = [source('a  b.eml', b'same'), source('a b.eml', b'same', 'text/plain')]
        for first in ('folder/a  b.eml', 'C:\\folder\\a  b.eml'):
            result = report_for([record(files[0], first), record(files[1], 'another/a b.eml')], files)
            self.assertFalse(failed_codes(result))
            self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_unique_checksum_remains_primary_when_original_names_differ(self):
        files = [source('a  b.eml', b'first'), source('a b.eml', b'second', 'text/plain')]
        result = report_for([record(files[0], 'different.eml'), record(files[1], 'a  b.eml')], files)
        self.assertFalse(failed_codes(result))
        self.assertNotIn('VALIDATION_INCOMPLETE', result.seen_codes)

    def test_universally_contradicted_media_remains_a_failure(self):
        files = [source('a  b.eml', b'same'), source('other.eml', b'same', 'text/plain')]
        records = [dict(record(files[1], 'a b.eml'), ContentType='application/pdf'), record(files[0], 'unknown.eml')]
        self.assertEqual({'ASSET_CONTENTTYPE_MATCH'}, failed_codes(report_for(records, files)))

    def test_uri_filename_cannot_create_normal_text_name_evidence(self):
        files = [source('a  b.eml', b'same'), source('other.eml', b'same', 'text/plain')]
        records = [record(files[1], 'a  b.eml'), record(files[0], 'unknown.eml')]
        records[0]['Filename'] = v.XmpUri('a  b.eml')
        with patch.object(v, 'flatten_assets', return_value=records):
            result = report_for([], files)
        self.assertEqual({'ASSET_XMP_FIELDS'}, failed_codes(result))

    def test_actual_decoded_pdf_filename_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-source-filenames'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                with pikepdf.Pdf.open(path) as pdf:
                    native = [str(spec.UF) for spec in pdf.Root.AF if spec.AFRelationship == pikepdf.Name.Source]
                    self.assertEqual(case['native_filenames'], native)
                result = v.validate(str(path))
                self.assertEqual(case['expected_status'], result.summary()['status'])
                self.assertEqual(set(case['expected_failures']), failed_codes(result))


if __name__ == '__main__':
    unittest.main()
