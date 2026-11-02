"""Declared source parameters cannot inherit PASS from other comparisons."""
from copy import deepcopy
import hashlib
import json
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, validator as v

CODE = 'EMBEDDED_MEDIA_TYPE_MATCH_XMP'


class SourceParameterEvidenceTests(unittest.TestCase):
    def check(self, assets=None, sources=True, attachments=True):
        path = ROOT / 'tests/fixtures/eapdf/validator-ambiguous-sources/compatible-parameters.pdf'
        with pikepdf.Pdf.open(path) as pdf:
            report = v.Report('source-parameters.pdf')
            metadata = v.read_metadata_stream(pdf)
            root, _, _ = v.parse_xmp(metadata, report)
            records = deepcopy(v.flatten_assets(root)) if assets is None else assets
            files = v.embedded_file_candidates(pdf, v.get_embedded_files(pdf), v.get_af_specs(pdf))
            if not sources:
                files = [f for f in files if f['afrelationship'] != 'Source']
            if not attachments:
                files = [f for f in files if f['afrelationship'] == 'Source']
            with patch.object(v, 'flatten_assets', return_value=records), \
                    patch.object(v, 'embedded_file_candidates', return_value=files):
                v.check_additional_rule_coverage(pdf, root, metadata, files, files, 's', 4, report)
            return next(i for i in report.items if i['code'] == CODE), report

    def assets(self):
        path = ROOT / 'tests/fixtures/eapdf/validator-ambiguous-sources/compatible-parameters.pdf'
        with pikepdf.Pdf.open(path) as pdf:
            root, _, _ = v.parse_xmp(v.read_metadata_stream(pdf), v.Report('parse'))
            return v.flatten_assets(root)

    def test_verified_attachments_do_not_hide_ambiguous_source_parameters(self):
        finding, _ = self.check()
        self.assertEqual('WARN', finding['level'])
        self.assertEqual('unavailable', finding['details']['assessment'])

    def test_unique_source_names_disambiguate_shared_checksums_without_using_media(self):
        assets = self.assets()
        assets[0]['Filename'] = 'test3.eml'
        assets[1]['Filename'] = 'copy.txt'
        finding, _ = self.check(assets)
        self.assertEqual('PASS', finding['level'])
        self.assertIn('test3.eml', {c['filename'] for c in finding['details']['comparisons']})

    def test_unique_checksum_disambiguates_colliding_original_basenames(self):
        path = ROOT / 'tests/fixtures/eapdf/validator-asset-ownership/collision-forward.pdf'
        result = v.validate(str(path))
        self.assertEqual({'PASS'}, {i['level'] for i in result.items if i['code'] == 'EMBEDDED_MEDIA_TYPE_MATCH_XMP'})

    def test_ambiguous_declared_parameters_do_not_become_not_applicable(self):
        finding, _ = self.check(attachments=False)
        self.assertEqual('WARN', finding['level'])

    def test_no_source_association_keeps_declared_parameters_unavailable(self):
        assets = self.assets()
        for asset in assets:
            asset['Checksum'] = '0' * 32
            asset['Filename'] = 'unknown-original.eml'
        finding, _ = self.check(assets, attachments=False)
        self.assertEqual('WARN', finding['level'])

    def test_no_sources_does_not_impose_a_new_source_copy_requirement(self):
        finding, _ = self.check(sources=False, attachments=False)
        self.assertEqual('N/A', finding['level'])

    def test_bare_types_still_need_an_independent_source_to_xmp_association(self):
        assets = self.assets()
        for asset in assets:
            asset['ContentType'] = 'message/rfc822'
        finding, _ = self.check(assets, attachments=False)
        self.assertEqual('WARN', finding['level'])


    def test_invalid_media_shapes_do_not_certify_complete_media_consistency(self):
        for value in (v.XmpUri('text/plain; name=x'), ['text/plain; name=x'], None, ''):
            assets = self.assets()
            for asset in assets:
                asset['ContentType'] = value
            with self.subTest(value=value):
                finding, _ = self.check(assets, attachments=False)
                self.assertEqual('WARN', finding['level'])


    def test_definite_attachment_parameter_failure_wins_over_unavailable_sources(self):
        path = ROOT / 'tests/fixtures/eapdf/validator-ambiguous-sources/compatible-parameters.pdf'
        with pikepdf.Pdf.open(path) as pdf:
            report = v.Report('definite-and-unavailable.pdf')
            metadata = v.read_metadata_stream(pdf)
            root, _, _ = v.parse_xmp(metadata, report)
            records = v.flatten_attachments(root)
            for record in records:
                record['Content-Type'] = record['Content-Type'].split(';', 1)[0] + '; injected=wrong'
            with patch.object(v, 'flatten_attachments', return_value=records):
                v.check_additional_rule_coverage(pdf, root, metadata, v.get_embedded_files(pdf), v.get_af_specs(pdf), 's', 4, report)
            finding = next(i for i in report.items if i['code'] == CODE)
            self.assertEqual('FAIL', finding['level'])

    def test_actual_pdf_parameter_corpus(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-source-parameters'
        for case in json.loads((directory / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = directory / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate(str(path))
                self.assertEqual(case['expected_status'], result.summary()['status'])
                self.assertEqual(set(case['expected_failures']), {i['code'] for i in result.items if i['level'] == 'FAIL'})
                parameters=[i for i in result.items if i['code']==CODE]
                self.assertEqual({case['expected_media_consistency_level']}, {i['level'] for i in parameters})
                if case['file'] in ('ambiguous-verified.pdf','unknown-verified.pdf'):
                    # An independently verified attachment must not turn the
                    # unresolved Source association into an aggregate PASS.
                    self.assertEqual({'PASS'}, {i['level'] for i in result.items
                                     if i['code']=='SOURCE_MEDIATYPE_PARAMETERS_MATCH'
                                     and i.get('details',{}).get('evidence')=='original attachment bytes'})


if __name__ == '__main__':
    unittest.main()
