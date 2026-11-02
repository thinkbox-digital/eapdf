"""A proved GUID owner mismatch is distinct from unavailable body applicability."""
import unittest
from xml.etree import ElementTree as ET
from unittest.mock import patch
import pikepdf
from test_preservation_safety import validator as v, email_metadata

CODE='ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID'


def document(email_guid='owner', attachment_guid='unknown', message_id=None):
    root=email_metadata([{'Mail_GUID':email_guid,'Sent':'2026-10-21T12:00:00Z'}])
    desc=root.find(v.qn(v.NS_RDF,'Description'))
    bag=ET.SubElement(ET.SubElement(desc,v.qn(v.NS_PDFMAILMETA,'attachments')),v.qn(v.NS_RDF,'Bag'))
    att=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
    values={'Filename':'note.txt','SizeInBytes':'7','Content-Type':'text/plain','Mail_GUID':attachment_guid}
    if message_id is not None:values['Message-ID']=message_id
    for field,value in values.items():ET.SubElement(att,v.qn(v.NS_PDFMAILMETA,field)).text=value
    return root


class AttachmentGuidOwnerEvidenceTests(unittest.TestCase):
    def check(self, root):
        report=v.Report('owner.pdf');v.check_required_email_metadata(root,'si',report);return report

    def test_complete_guid_inventory_proves_unknown_reference(self):
        report=self.check(document())
        self.assertEqual(['FAIL'],[i['level'] for i in report.items if i['code']==CODE])

    def test_supplied_optional_guid_still_reflects_the_containing_email(self):
        report=self.check(document(message_id='<optional@example.org>'))
        self.assertIn(CODE,{i['code'] for i in report.items if i['level']=='FAIL'})

    def test_matching_guid_does_not_require_optional_message_id(self):
        report=self.check(document(attachment_guid='owner'))
        self.assertNotIn(CODE,{i['code'] for i in report.items if i['level']=='FAIL'})

    def test_invalid_email_guid_does_not_prove_absence_from_a_complete_inventory(self):
        root=document();node=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'Mail_GUID'))
        node.set(v.qn(v.NS_RDF,'resource'),'urn:unavailable');node.text=None
        report=self.check(root)
        self.assertEqual(['WARN'],[i['level'] for i in report.items if i['code']==CODE])
        self.assertIn('MAIL_GUID_XMP',{i['code'] for i in report.items if i['level']=='FAIL'})
        self.assertTrue(any(i['code']=='VALIDATION_INCOMPLETE' for i in report.items))

    def test_explicit_empty_optional_guid_does_not_disappear_as_absent(self):
        report=self.check(document(attachment_guid='',message_id='<optional@example.org>'))
        self.assertEqual(['FAIL'],[i['level'] for i in report.items if i['code']==CODE])

    def test_absent_guid_remains_optional_beside_message_id(self):
        root=document(message_id='<optional@example.org>')
        attachment=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag')+'/'+v.qn(v.NS_RDF,'li'))
        attachment.remove(attachment.find(v.qn(v.NS_PDFMAILMETA,'Mail_GUID')))
        self.assertNotIn(CODE,{i['code'] for i in self.check(root).items if i['level']=='FAIL'})

    def test_empty_reference_does_not_match_an_invalid_parent_guid_placeholder(self):
        root=document(attachment_guid='',message_id='<optional@example.org>')
        node=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'Mail_GUID'))
        node.set(v.qn(v.NS_RDF,'resource'),'urn:invalid-parent');node.text=None
        report=self.check(root)
        self.assertEqual(['WARN'],[i['level'] for i in report.items if i['code']==CODE])
        self.assertIn('MAIL_GUID_XMP',{i['code'] for i in report.items if i['level']=='FAIL'})

    def test_unbound_guid_cannot_prove_missing_body_annotation(self):
        root=document();body=dict(identity={'Mail_GUID':'owner','Message-ID':''},pages=[1],start_page=1,end_page=1)
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page();report=v.Report('unbound.pdf')
            with patch.object(v,'get_dpart_nodes',return_value=(None,None,[])),patch.object(v,'collect_body_content_sets',return_value=[body]),patch.object(v,'collect_page_text_analysis',return_value=None):
                v.check_additional_rule_coverage(pdf,root,b'',[],[],'si',4,report)
            self.assertEqual({'WARN'},{i['level'] for i in report.items if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'})

    def test_proved_body_with_no_annotation_retains_definite_failure(self):
        root=document(attachment_guid='owner');body=dict(identity={'Mail_GUID':'owner','Message-ID':''},pages=[1],start_page=1,end_page=1)
        with pikepdf.Pdf.new() as pdf:
            pdf.add_blank_page();report=v.Report('known-body.pdf')
            with patch.object(v,'get_dpart_nodes',return_value=(None,None,[])),patch.object(v,'collect_body_content_sets',return_value=[body]),patch.object(v,'collect_page_text_analysis',return_value=None):
                v.check_additional_rule_coverage(pdf,root,b'',[],[],'si',4,report)
            self.assertIn('ATTACHMENT_ANNOTATIONS_PRESENT',{i['code'] for i in report.items if i['level']=='FAIL'})


if __name__=='__main__':unittest.main()
