"""Reviewed ownership must control execution and web visibility independently of PASS."""
import json
import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import ROOT, validator, web


DELEGATED = {
    'OUTPUT_INTENT', 'FONTS_EMBEDDED', 'FONTS_MISSING', 'FONTS_ALL_EMBEDDED',
    'EMBEDDED_SUBTYPE_PRESENT', 'EMBEDDED_SUBTYPE_NO_PARAMETERS',
    'XMP_PRESENT', 'XMP_MISSING', 'XMP_PARSE', 'XMP_UTF8',
    'PDFA3_EAPDF_XMP_EXTENSION_SCHEMA',
}


class ValidatorOwnershipTests(unittest.TestCase):
    def pdfa(self, status='PASS', profile='PDF/A-4F validation profile'):
        return dict(status=status, ran=status in ('PASS', 'FAIL'), compliant=status == 'PASS',
                    profile_allowed=True, profile_name=profile, message=status)

    def test_upstream_routing_guards_are_separate_from_the_eapdf_registry(self):
        self.assertEqual(DELEGATED, validator.PDFA_COVERED_RULES)
        self.assertFalse(DELEGATED & set(validator.RULE_TEXT))
        for code in ('PDFA_PART', 'PDF_EFFECTIVE_VERSION', 'DOCINFO_TITLE_MATCH',
                     'DOCINFO_KEYWORDS', 'CATALOG_PIECEINFO', 'EMBEDDED_MEDIA_TYPE_MATCH_XMP'):
            self.assertNotIn(code, validator.PDFA_COVERED_RULES)

    def test_runtime_never_reports_local_conformance_for_delegated_rules(self):
        path = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'
        for status, expected in (('PASS', 'PASS'), ('SKIP', 'INCOMPLETE'),
                                 ('FAIL', 'FAIL'), ('ERROR', 'INCOMPLETE')):
            with self.subTest(status=status), patch.object(validator, 'run_verapdf', return_value=self.pdfa(status)), \
                    patch.object(validator, 'run_iso32005', return_value={'status': 'PASS', 'profile_verified': True}):
                result = validator.validate_one(str(path), write_html=False)
                self.assertEqual(expected, result['summary']['status'])
                for code in DELEGATED:
                    rows = [item for item in result['items'] if item['code'] == code]
                    self.assertEqual([], rows, code)

    def test_no_duplicate_output_intent_utf8_or_extension_schema_test(self):
        path = ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf'
        with patch.object(validator, 'check_output_intents') as output_intents, \
                patch.object(validator, 'check_xmp_utf8') as utf8, \
                patch.object(validator, 'has_pdfa3_eapdf_extension_schema') as schema:
            report = validator.validate(str(path))
        output_intents.assert_not_called()
        utf8.assert_not_called()
        schema.assert_not_called()
        self.assertEqual('PASS', report.summary()['status'])

    def test_bad_custom_structure_still_fails_with_pdfa_pass(self):
        path = ROOT / 'tests/fixtures/eapdf/validator-table11/content-set-field-name.pdf'
        with patch.object(validator, 'run_verapdf', return_value=self.pdfa()), \
                patch.object(validator, 'run_iso32005', return_value={'status': 'PASS', 'profile_verified': True}):
            result = validator.validate_one(str(path), write_html=False)
        self.assertEqual('FAIL', result['summary']['status'])
        self.assertIn('MAIL_CONTENTSET_CHILDREN', {item['code'] for item in result['items'] if item['level'] == 'FAIL'})

    def test_eapdf_version_and_info_crosswalk_remain_custom_checks(self):
        report = validator.Report('custom.pdf')
        validator.record_verapdf_delegations(report, self.pdfa())
        xmp = ET.fromstring(f'<rdf:RDF xmlns:rdf="{validator.NS_RDF}"/>')
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page()
            pdf.docinfo['/Title'] = 'Title with no XMP counterpart'
            validator.check_pdf_version(pdf, report)
            validator.check_docinfo_consistency(pdf, xmp, 3, report)
        failures = {item['code'] for item in report.items if item['level'] == 'FAIL'}
        self.assertIn('PDF_EFFECTIVE_VERSION', failures)
        self.assertIn('DOCINFO_TITLE_MATCH', failures)

    def test_unreadable_metadata_still_blocks_custom_validation(self):
        for data in (None, b'<broken'):
            with self.subTest(data=data), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'metadata.pdf'
                with pikepdf.Pdf.new() as pdf:
                    pdf.add_blank_page()
                    if data:
                        pdf.Root.Metadata = pdf.make_stream(data)
                    pdf.save(path, min_version='1.7', fix_metadata_version=False)
                report = validator.validate(str(path))
                failures = {item['code'] for item in report.items if item['level'] == 'FAIL'}
                self.assertIn('EAPDF_XMP_UNCHECKED', failures)
                self.assertFalse(failures & DELEGATED)


class WebOwnershipTests(unittest.TestCase):
    def test_web_has_only_active_rules_in_specification_order(self):
        self.assertEqual(list(validator.RULE_TEXT), web.all_rule_codes())
        self.assertFalse(DELEGATED & set(web.all_rule_codes()))
        self.assertNotIn('ISO32005_UPSTREAM', web.all_rule_codes())
        self.assertEqual(list(range(1, len(validator.RULE_TEXT)+1)),
                         [r['rule_number'] for r in validator.RULE_TEXT.values()])

    def test_removed_rows_are_absent_and_upstream_failures_stay_visible(self):
        items = [dict(code='ISO32005_UPSTREAM',level='FAIL',message='Selected ISO profile failed',details=None)]
        result = dict(summary={'file':'fixture.pdf'},pdfa={'status':'FAIL','message':'PDF/A failed'},items=items)
        rendered = web.render_comparison_table([result])
        for code in DELEGATED | {'ISO32005_UPSTREAM'}:
            self.assertNotIn("data-rule-code='"+code+"'",rendered)
        upstream = web.render_pdfa_table([result])
        self.assertIn('PDF/A failed',upstream)
        self.assertIn('ISO/TS 32005',upstream)
        self.assertIn('Selected ISO profile failed',upstream)
        self.assertNotIn('Show rules delegated to veraPDF',rendered)

    def test_invalid_or_empty_rule_registry_cannot_silently_validate(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'rules.json'
            for text in ('{}','[]','broken',json.dumps({'BAD':{'rule_number':7}})):
                path.write_text(text)
                with patch.object(validator,'RULE_TEXT_PATH',path):
                    with self.assertRaises(RuntimeError):validator.load_rule_text()

    def test_rule_document_change_reloads_engine_and_authoritative_excerpts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / 'validate_eapdf.py'
            source.write_bytes((ROOT / 'validate_eapdf.py').read_bytes())
            config = root / 'config/verapdf'; config.mkdir(parents=True)
            (config/'ISO-32005.json').write_bytes((ROOT/'config/verapdf/ISO-32005.json').read_bytes())
            rules = root / 'validator_rule_text.json'
            document = json.loads((ROOT / 'validator_rule_text.json').read_text())
            rules.write_text(json.dumps(document))
            with patch.object(web, 'VALIDATOR_PATH', source), patch.object(web, 'RULE_TEXT_PATH', rules), \
                    patch.object(web, 'VALIDATOR', None), patch.object(web, 'VALIDATOR_MTIME_NS', None), \
                    patch.object(web, 'VALIDATOR_RULE_TEXT_MTIME_NS', None, create=True):
                first = web.get_validator()
                stamp = rules.stat().st_mtime_ns
                document['MAIL_CONTENTSET_CHILDREN']['spec_excerpt'] = 'Updated authored rule excerpt'
                rules.write_text(json.dumps(document))
                os.utime(rules, ns=(stamp + 1000000, stamp + 1000000))
                second = web.get_validator()
                self.assertIsNot(first, second)
                self.assertFalse(DELEGATED & set(second.ALL_RULE_CODES))
                self.assertEqual('Updated authored rule excerpt', second.spec_references_for('MAIL_CONTENTSET_CHILDREN')[0]['excerpt'])


if __name__ == '__main__':
    unittest.main()
