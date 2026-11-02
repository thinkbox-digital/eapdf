"""Media field contradictions must not depend on selecting an occurrence."""
from copy import deepcopy
import hashlib
import itertools
import json
import os
import unittest

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes

MEDIA='EMBEDDED_MEDIA_TYPE_MATCH_XMP'


class AmbiguousAttachmentMediaTests(unittest.TestCase):
    def record(self, ctype='text/plain', **changes):
        return dict({'Filename':'same.txt','SizeInBytes':'7','Content-Type':ctype,'Mail_GUID':'owner'},**changes)

    def file(self, ctype='text/plain;', **changes):
        return dict({'ufilename':'same.txt','size':7,'md5':v.md5hex(b'payload'),
                     'reconstructed_media_type':ctype,'afrelationship':'Data'},**changes)

    def check(self, records, files):
        report=v.Report('media.pdf');ambiguities=[]
        associations=v.check_attachment_stream_consistency(records,files,report,ambiguities=ambiguities)
        return report,associations,ambiguities

    def test_type_contradiction_in_every_record_and_file_order(self):
        records=[self.record(),self.record('application/pdf')]
        files=[self.file(objgen=(1,0)),self.file(objgen=(2,0))]
        for rows,payloads in itertools.product(itertools.permutations(records),itertools.permutations(files)):
            report,associations,_=self.check(rows,payloads)
            self.assertEqual({MEDIA},failed_codes(report));self.assertFalse(associations)
            self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)
            actual=next(r for r in report.items if r['code']==MEDIA)['details']['actual']
            self.assertEqual(1,len(actual));self.assertFalse(actual[0]['unique_occurrence_identity'])
            self.assertEqual(2,len(actual[0]['native_candidates']))

    def test_parameter_contradictions_are_independent_of_bare_type(self):
        for expected in ('text/plain; name=A','application/pdf; name=A'):
            report,_,_=self.check([self.record(expected)],
                [self.file('text/plain; name=B'),self.file('text/csv; name=C')])
            self.assertEqual({MEDIA},failed_codes(report))
        report,_,ambiguities=self.check([self.record('application/pdf; charset=utf-8')],
            [self.file('text/plain; charset=UTF-8'),self.file('text/csv; charset=utf-8')])
        self.assertEqual({MEDIA},failed_codes(report))
        self.assertTrue(any(i.get('parameter_comparison_unavailable') for i in ambiguities))

    def test_a_compatible_candidate_prevents_universal_failure(self):
        records=[self.record('text/plain; charset=utf-8'),self.record('text/plain; charset=iso-8859-1')]
        files=[self.file('text/plain; charset=utf-8'),self.file('text/plain; charset=iso-8859-1')]
        for rows,payloads in itertools.product(itertools.permutations(records),itertools.permutations(files)):
            report,associations,ambiguities=self.check(rows,payloads)
            self.assertFalse(failed_codes(report));self.assertFalse(associations)
            self.assertTrue(any(i.get('parameter_comparison_unavailable') for i in ambiguities))

    def test_unreadable_media_blocks_failures_without_parser_defaults(self):
        for unknown in (None,''):
            report,associations,ambiguities=self.check([self.record('application/pdf; name=A')],
                [self.file(),self.file(unknown)])
            self.assertFalse(failed_codes(report));self.assertFalse(associations)
            self.assertTrue(any(i.get('parameter_comparison_unavailable') for i in ambiguities))

    def test_schema_invalid_declarations_are_not_media_mismatches(self):
        for value in (v.XmpUri('application/pdf; name=A'),['application/pdf; name=A'],None,''):
            report,_,_=self.check([self.record(value),self.record()],[self.file()])
            self.assertFalse(failed_codes(report))

    def test_legal_equivalence_retains_quoted_space_and_rfc2231_values(self):
        records=[self.record('TEXT/PLAIN; charset=UTF-8; name="A  B"'),
                 self.record("text/plain; name*=utf-8''A%20%20B; charset=utf-8")]
        files=[self.file('text/plain; name="A  B"; charset=utf-8')]
        report,associations,_=self.check(records,files)
        self.assertFalse(failed_codes(report));self.assertEqual(1,len(associations))
        self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)
        changed=[self.record('text/plain; name="A  B"'),self.record('text/plain; name="A   B"')]
        report,_,_=self.check(changed,[self.file('text/plain; name="A B"')])
        self.assertEqual({MEDIA},failed_codes(report))

    def test_malformed_text_requires_actual_equality(self):
        value='text/plain; note=x; =ignored'
        report,_,_=self.check([self.record(value)],
            [self.file('text/plain; note=x'),self.file('text/plain; note=x; =other')])
        self.assertEqual({MEDIA},failed_codes(report))
        report,_,_=self.check([self.record(value)],
            [self.file(value),self.file('text/plain; note=x')])
        self.assertFalse(failed_codes(report))

    def test_missing_candidates_sources_alternatives_and_aliases_do_not_invent_media_failures(self):
        for files in ([],[self.file(afrelationship='Source')],[self.file(afrelationship='Alternative')],
                      [self.file(ufilename='unknown.txt')]):
            report,_,_=self.check([self.record('application/pdf; name=A')],files)
            self.assertFalse(failed_codes(report))

    def test_unavailable_parameters_cannot_hide_a_separate_unique_mismatch(self):
        path=ROOT/'tests/fixtures/eapdf/validator-ambiguous-media/parameters-compatible.pdf'
        with pikepdf.open(path) as pdf:
            root,_,_=v.parse_xmp(v.read_metadata_stream(pdf),v.Report('parse'))
            stream=pdf.make_stream(b'payload');stream.Subtype=pikepdf.Name('/text/plain')
            stream.Mail_MediaTypeParameters=pikepdf.String('name=native')
            spec=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,F='unique.txt',UF='unique.txt',
                AFRelationship=pikepdf.Name.Data,EF=pikepdf.Dictionary(F=stream)))
            pdf.Root.AF.append(spec)
            pikepdf.NameTree(pdf.Root.Names.EmbeddedFiles)['unique.txt']=spec
            bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            row=deepcopy(bag[0]);row.find(v.qn(v.NS_PDFMAILMETA,'Filename')).text='unique.txt'
            row.find(v.qn(v.NS_PDFMAILMETA,'Content-Type')).text='text/plain; name=xmp';bag.append(row)
            report=v.Report('mixed.pdf')
            v.check_additional_rule_coverage(pdf,root,b'',v.get_embedded_files(pdf),v.get_af_specs(pdf),'mi',4,report)
            self.assertTrue(any(i['code']==MEDIA and i['level']=='FAIL' for i in report.items))
            self.assertTrue(any(i['code']=='ATTACHMENT_XMP_SIZE_MATCH' and i['level']=='WARN' for i in report.items))

    def test_actual_pdf_corpus_and_unavailable_parameter_reporting(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-ambiguous-media'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=directory/case['file'];self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                report=v.validate(str(path))
                self.assertEqual(case['expected_status'],report.summary()['status'])
                self.assertEqual(set(case['expected_failures']),failed_codes(report))
                self.assertEqual({case['expected_media_consistency_level']},
                                 {i['level'] for i in report.items if i['code']==MEDIA})

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_pdf_corpus(self):
        directory=ROOT/'tests/fixtures/eapdf/validator-ambiguous-media'
        for case in json.loads((directory/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                result=v.validate_one(str(directory/case['file']),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{r['code'] for r in result['items'] if r['level']=='FAIL'})


if __name__=='__main__':unittest.main()
