"""Retained-source occurrences are distinct from complete rendered coverage."""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_preservation_safety import ROOT, email_metadata, validator, web
import test_rendered_header_inventory as observed_tests


class HeaderOccurrenceTests(unittest.TestCase):
    def compare(self,value='one',extra='X-Project: one\r\nX-Project: two\r\n',missing_source=False,
                label='X-Project:',no_fields=False,raw_bytes=None):
        helper=observed_tests.RenderedHeaderInventoryTests()
        raw=helper.source(extra=extra) if raw_bytes is None else raw_bytes
        source=dict(afrelationship='Source',subtype='message/rfc822',bytes=raw,objgen=(100,0),filename='owner.eml')
        record=dict(Mail_GUID='uuid:first',messageid='&lt;duplicate@example.invalid&gt;',Subject='Source A',
                    SizeInBytes=str(len(raw)),Sent='2003-01-29T02:20:54Z')
        with helper.document(label=label,value=value) as pdf:
            if no_fields:del pdf.Root.StructTreeRoot.K.K.K
            elements,analysis=helper.evidence(pdf)
        sources=[]
        rows=validator.observed_header_source_inventory(elements,analysis,email_metadata([record]),
             [] if missing_source else [source],owner_ranges=[dict(identity={'Mail_GUID':'uuid:first'},pages=[1])],
             source_inventory=sources)
        return rows,sources

    def test_source_occurrences_preserve_order_case_folding_and_body_boundary(self):
        raw=(b'X-Project: one\r\nSubject: hello\r\nx-project: two\r\n\tfolded\r\n'
             b'X-Project: =?UTF-8?Q?caf=C3=A9?=\r\n\r\nX-Project: body only\r\n')
        source=dict(afrelationship='Source',subtype='message/rfc822',bytes=raw)
        occurrences=validator.summarize_source_emails([source])[0]['header_occurrences']
        self.assertEqual(['X-Project','Subject','x-project','X-Project'],[x['name'] for x in occurrences])
        self.assertEqual([1,2,3,4],[x['index'] for x in occurrences])
        self.assertEqual([1,1,2,3],[x['occurrence'] for x in occurrences])
        self.assertEqual('two\r\n\tfolded',occurrences[2]['raw'])
        self.assertEqual('=?UTF-8?Q?caf=C3=A9?=',occurrences[3]['raw'])

    def test_distinct_repeated_values_identify_one_source_occurrence(self):
        for value,number in (('one',1),('two',2)):
            with self.subTest(value=value):
                rows,sources=self.compare(value=value)
                self.assertEqual('equal',rows[0]['comparison'])
                self.assertEqual(number,rows[0]['source_occurrence'])
                occurrences=[x for x in sources[0]['occurrences'] if x['header']=='x-project']
                self.assertEqual([number],[x['occurrence'] for x in occurrences if x['matched_fields']])

    def test_identical_or_normalized_candidates_do_not_get_an_arbitrary_ordinal(self):
        for first,second,value in (('one','one','one'),('one  two','one two','one two'),
                                   ('=?UTF-8?Q?caf=C3=A9?=','=?UTF-8?B?Y2Fmw6k=?=','café')):
            with self.subTest(first=first):
                rows,sources=self.compare(value=value,extra=f'X-Project: {first}\r\nX-Project: {second}\r\n')
                self.assertEqual('unavailable',rows[0]['comparison'])
                self.assertEqual([1,2],rows[0]['candidate_occurrences'])
                self.assertNotIn('source_occurrence',rows[0])
                self.assertTrue(all(not x['matched_fields'] for x in sources[0]['occurrences']))

    def test_no_matching_repeated_value_does_not_guess_a_modified_occurrence(self):
        rows,_=self.compare(value='changed')
        self.assertEqual('unavailable',rows[0]['comparison'])
        self.assertEqual([],rows[0]['candidate_occurrences'])
        self.assertNotIn('source_occurrence',rows[0])

    def test_undecodable_candidate_prevents_unique_match_claim(self):
        rows,sources=self.compare(extra='X-Project: one\r\nX-Project: =?unknown-charset?Q?one?=\r\n')
        self.assertEqual('unavailable',rows[0]['comparison'])
        bad=[x for x in sources[0]['occurrences'] if x['header']=='x-project'][1]
        self.assertIsNone(bad['decoded']);self.assertIn('reason',bad)
        self.assertNotIn('source_occurrence',rows[0])

    def test_unobserved_source_headers_are_not_claimed_missing_or_unrendered(self):
        rows,sources=self.compare()
        self.assertEqual('available',sources[0]['assessment'])
        self.assertEqual(8,len(sources[0]['occurrences']))
        subject=next(x for x in sources[0]['occurrences'] if x['header']=='subject')
        self.assertEqual([],subject['matched_fields'])
        self.assertNotIn('rendered',subject);self.assertNotIn('missing',subject)

    def test_owner_without_custom_fields_still_has_a_source_inventory(self):
        rows,sources=self.compare(no_fields=True)
        self.assertEqual([],rows);self.assertEqual(1,len(sources))
        self.assertEqual(8,len(sources[0]['occurrences']))
        self.assertTrue(all(not x['matched_fields'] for x in sources[0]['occurrences']))

    def test_missing_source_retains_observed_text_and_page_evidence(self):
        rows,sources=self.compare(missing_source=True)
        self.assertEqual('unavailable',rows[0]['comparison'])
        self.assertEqual(('X-Project','one',[1],'within-owner-ranges'),
                         (rows[0]['name'],rows[0]['value'].strip(),rows[0]['pages'],rows[0]['ownership']))
        self.assertEqual('unavailable',sources[0]['assessment'])
        self.assertEqual([],sources[0]['occurrences'])

    def test_folded_source_raw_and_decoded_display_stay_distinct(self):
        rows,sources=self.compare(value='café continued',extra='X-Project: =?UTF-8?Q?caf=C3=A9?=\r\n continued\r\n')
        self.assertEqual('equal',rows[0]['comparison'])
        field=next(x for x in sources[0]['occurrences'] if x['header']=='x-project')
        self.assertIn('\r\n ',field['raw']);self.assertIn('=?UTF-8?',field['raw'])
        self.assertNotIn('=?UTF-8?',field['decoded'])

    def test_parser_recovery_cannot_prove_a_complete_source_header_inventory(self):
        rows,sources=self.compare(extra='X-Project: one\r\nbroken header\r\nX-Project: two\r\n')
        self.assertEqual('unavailable',rows[0]['comparison'])
        self.assertEqual('partial',sources[0]['assessment'])
        self.assertIn('MissingHeaderBodySeparatorDefect',sources[0]['parse_defects'])
        self.assertEqual(1,len([x for x in sources[0]['occurrences'] if x['header']=='x-project']))

    def test_valid_direct_utf8_occurrence_is_decoded_and_compared(self):
        rows,sources=self.compare(value='café',extra='X-Project: café\r\n')
        self.assertEqual('equal',rows[0]['comparison'])
        field=next(x for x in sources[0]['occurrences'] if x['header']=='x-project')
        self.assertEqual('café',field['decoded'])
        self.assertEqual('café',field['raw'])

    def test_undecodable_raw_octets_remain_explicitly_unavailable(self):
        raw=observed_tests.RenderedHeaderInventoryTests().source(extra='X-Project: café\r\n')
        raw=raw.replace(b'caf\xc3\xa9',b'caf\xff')
        rows,sources=self.compare(value='café',raw_bytes=raw)
        self.assertEqual('unavailable',rows[0]['comparison'])
        field=next(x for x in sources[0]['occurrences'] if x['header']=='x-project')
        self.assertIsNone(field['decoded']);self.assertIn('reason',field)
        report=validator.Report('fields.pdf')
        report.warn('HEADER_FIELDS_TAGGED_RECOMMENDED','Source headers',{'source_header_inventory':sources})
        validator.render_html(report).encode('utf-8')
        web.render_details({'source_header_inventory':sources}).encode('utf-8')
        self.assertIsNone(field['raw'])
        self.assertEqual(b'caf\xff',base64.b64decode(field['raw_bytes_base64']))

    def test_body_boundary_defect_does_not_hide_intact_header_occurrences(self):
        rows,sources=self.compare(extra='X-Project: one\r\nContent-Type: multipart/mixed; boundary=absent\r\n')
        self.assertEqual('equal',rows[0]['comparison'])
        self.assertEqual('available',sources[0]['assessment'])
        self.assertEqual([],sources[0]['parse_defects'])
        self.assertIn('StartBoundaryNotFoundDefect',sources[0]['other_parse_defects'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for occurrence fixtures')
    def test_creator_generated_occurrence_corpus(self):
        with tempfile.TemporaryDirectory(prefix='.eapdf-header-occurrences-',dir=ROOT) as directory:
            process=subprocess.run(['python3','-B','scripts/build_eapdf_header_occurrence_fixtures.py'],cwd=ROOT,
                env=dict(os.environ,EAPDF_HEADER_OCCURRENCE_FIXTURE_DIR=directory),capture_output=True,text=True,timeout=300)
            if os.environ.get('EAPDF_INTEGRATION_REPORT_DIR'):
                shutil.copytree(directory,Path(os.environ['EAPDF_INTEGRATION_REPORT_DIR'])/'header-occurrence-corpus',dirs_exist_ok=True)
            self.assertEqual(0,process.returncode,process.stdout+process.stderr)
            results=json.loads((Path(directory)/'results.json').read_text())
            self.assertEqual(6,len(results));self.assertTrue(all(x['expected_result_observed'] for x in results))
