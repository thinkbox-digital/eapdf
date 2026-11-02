"""Expected source encoding failures cannot abort unrelated assessments."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from test_preservation_safety import ROOT, validator, web
import test_header_occurrences as occurrence_tests
import test_userproperty_sources as property_tests


class EncodedHeaderSafetyTests(unittest.TestCase):
    def test_supported_valid_encoded_words_keep_their_display_values(self):
        for raw,expected in [('=?UTF-8?Q?caf=C3=A9?=','café'),('=?ISO-8859-1?b?Y2Fm6Q==?=','café'),
                             ('=?utf-8?q?hello_?=\r\n =?utf-8?B?d29ybGQ=?=','hello world'),
                             ('cost=?','cost=?'),('2 + 2 =? 4','2 + 2 =? 4')]:
            with self.subTest(raw=raw):self.assertEqual(expected,validator.decoded_source_header(raw))

    def test_malformed_encoded_payloads_are_explicitly_unavailable(self):
        for raw in ('=?utf-8?B?a?=','=?utf-8?B?@@?=','=?utf-8?B?YQ?=',
                    '=?utf-8?Q?bad=ZZ?=','=?utf-8?Q?bad=?=','=?utf-8?Q?bad text?=',
                    '=?utf-8?X?one?=','=?utf-8?Q??=','=?utf-8?B?/w==?='):
            with self.subTest(raw=raw),self.assertRaises(ValueError):validator.decoded_source_header(raw)

    def test_bad_unobserved_header_does_not_truncate_later_occurrences(self):
        rows,sources=occurrence_tests.HeaderOccurrenceTests().compare(label='Subject:',value='Source A',
            extra='X-Project: =?utf-8?B?a?=\r\nX-Later: preserved\r\n')
        self.assertEqual('equal',rows[0]['comparison'])
        self.assertEqual('available',sources[0]['assessment'])
        bad=next(item for item in sources[0]['occurrences'] if item['header']=='x-project')
        self.assertIsNone(bad['decoded']);self.assertIn('reason',bad)
        self.assertEqual('preserved',next(item for item in sources[0]['occurrences'] if item['header']=='x-later')['decoded'])
        report=validator.Report('fields.pdf')
        report.warn('HEADER_FIELDS_TAGGED_RECOMMENDED','Headers',{'source_header_inventory':sources})
        validator.render_html(report).encode('utf-8')
        web.render_details({'source_header_inventory':sources}).encode('utf-8')

    def test_bad_repeated_candidate_cannot_select_a_supported_match(self):
        rows,sources=occurrence_tests.HeaderOccurrenceTests().compare(extra='X-Project: one\r\nX-Project: =?utf-8?B?a?=\r\n')
        self.assertEqual('unavailable',rows[0]['comparison'])
        self.assertEqual([1],rows[0]['candidate_occurrences'])
        self.assertEqual([2],rows[0]['unresolved_occurrences'])
        self.assertNotIn('source_occurrence',rows[0])
        self.assertTrue(all(not item['matched_fields'] for item in sources[0]['occurrences']))

    def test_silent_repairs_cannot_prove_attribute_fidelity(self):
        for raw,value in [('=?utf-8?B?@@?=',''),('=?utf-8?B?YQ?=','a'),('=?utf-8?Q?bad=ZZ?=','bad=ZZ'),
                          ('=?utf-8?B?a?=','a')]:
            with self.subTest(raw=raw):
                findings,_=property_tests.UserPropertySourceTests().check([('Mail_X-Project',value)],extra=f'X-Project: {raw}\r\n')
                findings=[item for item in findings if item['details'].get('name')=='Mail_X-Project']
                self.assertTrue(findings)
                self.assertEqual({'WARN'},{item['level'] for item in findings})
                self.assertTrue(all(item['details']['comparison']=='unavailable' for item in findings))

    def test_raw_attribute_comparison_preserves_malformed_encoded_text(self):
        helper=property_tests.UserPropertySourceTests();raw='=?utf-8?B?a?='
        source=helper.source().replace(b'=?UTF-8?Q?Fixture_Author?= <author@example.invalid>',raw.encode())
        findings,_=helper.check([('Mail_Raw_From',raw)],source=source)
        self.assertEqual({'PASS'},{item['level'] for item in findings})
        self.assertEqual(raw,validator.decoded_header_text(raw))

    def test_unexpected_decoder_engine_errors_are_not_reclassified_as_bad_source(self):
        with patch.object(validator,'decode_header',side_effect=RuntimeError('decoder engine fault')):
            with self.assertRaisesRegex(RuntimeError,'decoder engine fault'):
                validator.decoded_source_header('ordinary text')
            with self.assertRaisesRegex(RuntimeError,'decoder engine fault'):
                validator.decoded_header_text('ordinary text')

    def test_expected_parser_exception_uses_the_source_unavailable_boundary(self):
        with patch.object(validator,'decode_header',side_effect=validator.HeaderParseError('Base64 decoding error')):
            with self.assertRaises(ValueError):validator.decoded_source_header('ordinary text')
            self.assertEqual('ordinary text',validator.decoded_header_text('ordinary text'))

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for encoded-header fixtures')
    def test_creator_generated_encoding_corpus(self):
        with tempfile.TemporaryDirectory(prefix='.eapdf-encoded-headers-',dir=ROOT) as directory:
            process=subprocess.run(['python3','-B','scripts/build_eapdf_encoded_header_fixtures.py'],cwd=ROOT,
                env=dict(os.environ,EAPDF_ENCODED_HEADER_FIXTURE_DIR=directory),capture_output=True,text=True,timeout=300)
            if os.environ.get('EAPDF_INTEGRATION_REPORT_DIR'):
                shutil.copytree(directory,Path(os.environ['EAPDF_INTEGRATION_REPORT_DIR'])/'encoded-header-corpus',dirs_exist_ok=True)
            self.assertEqual(0,process.returncode,process.stdout+process.stderr)
            results=json.loads((Path(directory)/'results.json').read_text())
            self.assertEqual(6,len(results));self.assertTrue(all(row['expected_result_observed'] for row in results))
