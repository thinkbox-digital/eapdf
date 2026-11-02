"""A typed unique parent MessageID can bind a GUID-owned attachment to its body."""
import copy,unittest
from xml.etree import ElementTree as ET
import pikepdf
import test_dpm_schema as fixtures
from test_preservation_safety import validator as v
CODE='ATTACHMENT_ANNOTATIONS_PRESENT'

class AttachmentOwnerMessageIdTests(unittest.TestCase):
    def path(self,name):
        return fixtures.ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid'/(name+'.pdf')

    def annotation(self,report):
        items=[i for i in report.items if i['code']==CODE]
        self.assertEqual(1,len(items))
        return items[0]

    def test_known_messageid_binds_all_four_invalid_guid_class_controls(self):
        for name in ('empty-guid','name-guid','decoded-empty-guid','integer-guid'):
            with self.subTest(name=name):
                report=v.validate(str(self.path(name)))
                self.assertEqual('PASS',self.annotation(report)['level'])
                expected={'DPM_MAIL_GUID_PRESENT'}
                # A known empty ancestor cannot own an email Content Set, even
                # though the separate MessageID evidence still binds its icon.
                if name in ('empty-guid','decoded-empty-guid'):
                    expected.add('DPM_EMAIL_CONTENT_SET_OWNERSHIP')
                    self.assertTrue(any(i['level']=='FAIL' and i['code']=='DPM_EMAIL_CONTENT_SET_OWNERSHIP'
                                        for i in report.items))
                self.assertEqual(expected,{i['code'] for i in report.items if i['level']=='FAIL'})

    def test_readable_guid_keeps_known_annotation_binding(self):
        self.assertEqual('PASS',self.annotation(v.validate(str(self.path('readable-guid'))))['level'])

    def test_unreadable_guid_does_not_fall_back_to_parent_messageid(self):
        report=v.validate(str(self.path('unreadable-guid')))
        self.assertEqual('WARN',self.annotation(report)['level'])
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_conflicting_readable_guid_keeps_precedence_over_matching_messageid(self):
        report=v.validate(str(self.path('conflicting-guid')))
        self.assertNotEqual('PASS',self.annotation(report)['level'])
        self.assertIn('DPM_MAIL_GUID_MATCH',{i['code'] for i in report.items if i['level']=='FAIL'})

    def test_known_owner_with_outside_annotations_still_fails_actual_geometry(self):
        report=v.validate(str(self.path('outside-annotations')))
        self.assertEqual('PASS',self.annotation(report)['level'])
        item=next(i for i in report.items if i['code']=='ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX')
        self.assertEqual('FAIL',item['level'])
        self.assertTrue(all('content_set_pages' in x for x in item['details']['actual']))

    def altered_metadata(self,name,change):
        path=self.path(name)
        with pikepdf.open(path) as pdf:
            report=v.Report(str(path))
            root,_,_=v.parse_xmp(v.read_metadata_stream(pdf),report)
            change(root)
            v.check_additional_rule_coverage(pdf,root,ET.tostring(root),v.get_embedded_files(pdf),v.get_af_specs(pdf),'s',4,report)
            return self.annotation(report)

    def messageid(self,root):
        item=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'messageid'))
        self.assertIsNotNone(item)
        return item

    def duplicate_record(self,root):
        email=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'email'))
        self.assertIsNotNone(email)
        array=next(iter(email));cloned=copy.deepcopy(array[0])
        guid=cloned.find(v.qn(v.NS_PDFMAILMETA,'Mail_GUID'));self.assertIsNotNone(guid);guid.text='other-owner'
        array.append(cloned)

    def test_uri_parent_messageid_does_not_supply_a_text_match(self):
        def change(root):
            mid=self.messageid(root);value=mid.text;mid.text=None;mid.set(v.qn(v.NS_RDF,'resource'),value)
        self.assertNotEqual('PASS',self.altered_metadata('empty-guid',change)['level'])

    def test_missing_parent_messageid_does_not_supply_an_invented_match(self):
        def change(root):
            mid=self.messageid(root)
            next(p for p in root.iter() if mid in list(p)).remove(mid)
        self.assertNotEqual('PASS',self.altered_metadata('empty-guid',change)['level'])

    def test_duplicate_parent_messageids_do_not_justify_a_new_match(self):
        self.assertNotEqual('PASS',self.altered_metadata('empty-guid',self.duplicate_record)['level'])

    def test_duplicate_messageids_do_not_erase_a_definite_guid_binding(self):
        self.assertEqual('PASS',self.altered_metadata('readable-guid',self.duplicate_record)['level'])

if __name__=='__main__':unittest.main()
