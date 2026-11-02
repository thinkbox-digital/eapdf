"""Resolve a Table 19 attachment through email XMP before selecting DPM body sets."""
import hashlib
import json
import os
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes


class AttachmentOwnerRoutingTests(unittest.TestCase):
    def emails(self):
        return [dict(Mail_GUID='A',messageid='<Owner@example.org>',SizeInBytes='100'),
                dict(Mail_GUID='B',messageid='<owner@example.org>',SizeInBytes='100')]

    def test_guid_has_priority_over_a_conflicting_optional_message_id(self):
        owners=self.emails()
        self.assertEqual([owners[0]],v.attachment_email_owners({'Mail_GUID':'A','Message-ID':'<owner@example.org>'},owners))
        self.assertEqual([],v.attachment_email_owners({'Mail_GUID':'unknown','Message-ID':'<Owner@example.org>'},owners))

    def test_message_id_owner_lookup_preserves_case_and_punctuation(self):
        owners=self.emails()+[dict(Mail_GUID='C',messageid="<own'er@example.org>"),dict(Mail_GUID='D',messageid='<owner2@example.org>')]
        for record in owners:
            with self.subTest(identity=record['messageid']):
                self.assertEqual([record],v.attachment_email_owners({'Message-ID':record['messageid']},owners))
        self.assertEqual([],v.attachment_email_owners({'Message-ID':'<OWNER@example.org>'},owners))
        self.assertEqual([],v.attachment_email_owners({'Message-ID':v.XmpUri('<Owner@example.org>')},owners))

    def test_duplicate_message_ids_are_not_arbitrarily_assigned(self):
        owners=[dict(Mail_GUID='A',messageid='duplicate'),dict(Mail_GUID='B',messageid='duplicate')]
        self.assertEqual(owners,v.attachment_email_owners({'Message-ID':'duplicate'},owners))
        self.assertEqual([owners[1]],v.attachment_email_owners({'Mail_GUID':'B','Message-ID':'duplicate'},owners))

    def test_source_hint_uses_the_unique_email_xmp_owner(self):
        owners=self.emails()
        sources=[dict(message_id=record['messageid'],size_in_bytes=100,subject='',sent_norm='',raw_from='',
                      source_sha256=record['Mail_GUID'],mime_attachments=[dict(filename='note.txt',size_in_bytes=7,
                      checksum=v.md5hex(data),content_type='text/plain')]) for record,data in zip(owners,(b'payload',b'another'))]
        att={'Filename':'note.txt','SizeInBytes':'7','Content-Type':'text/plain','Message-ID':'<Owner@example.org>'}
        self.assertEqual(v.md5hex(b'payload'),v.attachment_source_checksum(att,{'identity':v.identity_fields(att)},owners,sources))

    def test_ambiguous_or_unknown_xmp_owner_is_incomplete_not_an_annotation_failure(self):
        for message_ids in (('duplicate','duplicate'),('first','second')):
            root=ET.Element(v.qn(v.NS_RDF,'RDF'));description=ET.SubElement(root,v.qn(v.NS_RDF,'Description'))
            bag=ET.SubElement(ET.SubElement(description,v.qn(v.NS_PDFMAILMETA,'email')),v.qn(v.NS_RDF,'Bag'))
            for guid,message_id in zip(('A','B'),message_ids):
                row=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
                ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,'Mail_GUID')).text=guid
                ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,'messageid')).text=message_id
            bag=ET.SubElement(ET.SubElement(description,v.qn(v.NS_PDFMAILMETA,'attachments')),v.qn(v.NS_RDF,'Bag'))
            row=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
            for key,value in {'Filename':'note.txt','SizeInBytes':'7','Content-Type':'text/plain','Message-ID':'duplicate'}.items():
                ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,key)).text=value
            bodies=[dict(identity={'Mail_GUID':guid,'Message-ID':message_id},pages=[index],start_page=index,end_page=index)
                    for index,(guid,message_id) in enumerate(zip(('A','B'),message_ids),start=1)]
            with self.subTest(message_ids=message_ids),pikepdf.Pdf.new() as pdf:
                pdf.add_blank_page();pdf.add_blank_page();report=v.Report('ambiguous.pdf')
                with patch.object(v,'get_dpart_nodes',return_value=(None,None,[])),patch.object(v,'collect_body_content_sets',return_value=bodies),patch.object(v,'collect_page_text_analysis',return_value=None):
                    v.check_additional_rule_coverage(pdf,root,b'',[],[],'mi',4,report)
                self.assertEqual(['WARN'],[item['level'] for item in report.items if item['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'])
                self.assertTrue(any(item['code']=='VALIDATION_INCOMPLETE' and item['level']=='WARN' for item in report.items))

    def test_optional_inventory_does_not_prove_source_count_for_either_owner_identity(self):
        for identity in ({'Message-ID':'<Owner@example.org>'},{'Mail_GUID':'A'}):
            for count in (None,'0','+000','-0','1','2','not-an-integer','-1'):
                root=ET.Element(v.qn(v.NS_RDF,'RDF'));desc=ET.SubElement(root,v.qn(v.NS_RDF,'Description'))
                bag=ET.SubElement(ET.SubElement(desc,v.qn(v.NS_PDFMAILMETA,'email')),v.qn(v.NS_RDF,'Bag'))
                for record in self.emails():
                    row=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
                    for field,value in dict(record,Sent='2026-10-20T12:00:00Z').items():
                        ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,field)).text=value
                    if record['Mail_GUID']=='A' and count is not None:
                        ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,'NumberAttachments')).text=count
                bag=ET.SubElement(ET.SubElement(desc,v.qn(v.NS_PDFMAILMETA,'attachments')),v.qn(v.NS_RDF,'Bag'))
                row=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
                for field,value in {'Filename':'note.txt','SizeInBytes':'7','Content-Type':'text/plain',**identity}.items():
                    ET.SubElement(row,v.qn(v.NS_PDFMAILMETA,field)).text=value
                with self.subTest(identity=identity,count=count):
                    report=v.Report('counts.pdf');v.check_required_email_metadata(root,'mi',report)
                    self.assertEqual(count in ('not-an-integer','-1'),'MESSAGE_ATTACHMENT_COUNT_XMP' in failed_codes(report))
                    self.assertNotIn('MESSAGE_CORE_FIELDS_SOURCE_REQUIRED',failed_codes(report))
                    self.assertNotIn('MESSAGE_METADATA_MATCH_SOURCE',failed_codes(report))

    def test_actual_pdf_owner_cases(self):
        base=ROOT/'tests/fixtures/eapdf/validator-attachment-owners'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                report=v.validate(str(base/case['file']))
                self.assertEqual(set(case['expected_failures']),failed_codes(report))
                self.assertEqual(case['expected_status'],report.summary()['status'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for complete PDF validation')
    def test_frozen_owner_corpus(self):
        base=ROOT/'tests/fixtures/eapdf/validator-attachment-owners'
        for case in json.loads((base/'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path=base/case['file'];self.assertEqual(case['sha256'],hashlib.sha256(path.read_bytes()).hexdigest())
                result=v.validate_one(str(path),write_html=False)
                self.assertEqual('PASS',result['pdfa']['status'])
                self.assertEqual(case['expected_status'],result['summary']['status'])
                self.assertEqual(set(case['expected_failures']),{item['code'] for item in result['items'] if item['level']=='FAIL'})


if __name__=='__main__':unittest.main()
