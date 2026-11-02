"""Only Table 7 DPM keys identify an email; generic extensions retain their data."""
import unittest
import pikepdf
import test_dpm_schema as fixtures
from test_preservation_safety import validator as v

class DpmExtensionIdentityTests(unittest.TestCase):
    def parent(self):
        return {(1,0):{'dpm':{'Mail_GUID':'owner','Mail_MessageID':'actual'}}}

    def test_generic_aliases_do_not_stop_parent_identity_lookup(self):
        for name in ('Message-ID','MessageID','messageid'):
            with self.subTest(name=name):
                item={'dpm':{name:'unrelated extension'},'parent_objgen':(1,0)}
                self.assertEqual({'Mail_GUID':'owner','Message-ID':'actual'},v.dpart_message_identity(item,self.parent()))

    def test_generic_name_does_not_become_an_email_identity(self):
        item={'dpm':{'Message-ID':pikepdf.Name('/VendorData')},'parent_objgen':(1,0)}
        self.assertEqual('owner',v.dpart_message_identity(item,self.parent())['Mail_GUID'])

    def test_generic_array_does_not_become_an_email_identity(self):
        item={'dpm':{'Message-ID':pikepdf.Array(['vendor',42,True])},'parent_objgen':(1,0)}
        self.assertEqual('owner',v.dpart_message_identity(item,self.parent())['Mail_GUID'])

    def test_canonical_messageid_is_not_overridden_by_a_generic_alias(self):
        item={'dpm':{'Message-ID':'unrelated','Mail_MessageID':'actual'}}
        self.assertEqual({'Mail_GUID':'','Message-ID':'actual'},v.dpart_message_identity(item,{}))

    def test_readable_canonical_guid_keeps_its_actual_messageid(self):
        item={'dpm':{'Mail_GUID':'owner','Mail_MessageID':'actual','Message-ID':'unrelated'}}
        self.assertEqual({'Mail_GUID':'owner','Message-ID':'actual'},v.dpart_message_identity(item,{}))

    def test_unreadable_canonical_messageid_does_not_select_a_generic_alias(self):
        item={'dpm':{'Mail_MessageID':pikepdf.String(b'\xef\xbb\xbf\xff'),'Message-ID':'unrelated'},'parent_objgen':(1,0)}
        self.assertEqual({'Mail_GUID':'','Message-ID':''},v.dpart_message_identity(item,self.parent()))

    def test_unreadable_canonical_guid_does_not_select_a_generic_alias(self):
        item={'dpm':{'Mail_GUID':pikepdf.String(b'\xef\xbb\xbf\xff'),'Message-ID':'unrelated'},'parent_objgen':(1,0)}
        self.assertEqual({'Mail_GUID':'','Message-ID':''},v.dpart_message_identity(item,self.parent()))

    def test_shared_xmp_and_source_alias_handling_remains_available(self):
        for name in ('Message-ID','MessageID','messageid'):
            with self.subTest(name=name):self.assertEqual('actual',v.identity_fields({name:'actual'})['Message-ID'])

    def test_actual_generic_leaf_controls_retain_verified_source_and_annotation_checks(self):
        for name in ('leaf-message-dash-id','leaf-message-id','leaf-lower-messageid','leaf-generic-name','leaf-generic-array'):
            with self.subTest(name=name):
                path=fixtures.ROOT/'tests/fixtures/eapdf/validator-dpm-extension-identity'/(name+'.pdf')
                report=v.validate(str(path))
                self.assertEqual('PASS',report.summary()['status'])
                self.assertEqual({'PASS'},{i['level'] for i in report.items if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'})
                media=[i for i in report.items if i['code']=='DPM_BODY_MEDIA_TYPE_MATCH_SOURCE']
                self.assertEqual({'PASS'},{i['level'] for i in media if i.get('details',{}).get('check')=='body_source_media_type'})
                self.assertEqual({'N/A'},{i['level'] for i in report.items if i['code']=='DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE'})

    def test_canonical_guid_contradiction_is_not_hidden_by_extensions(self):
        path=fixtures.ROOT/'tests/fixtures/eapdf/validator-dpm-extension-identity/canonical-guid-mismatch.pdf'
        report=v.validate(str(path))
        for code in ('DPM_MAIL_GUID_MATCH','MAIL_MESSAGE_GUID_MATCH_DPM_XMP'):
            self.assertEqual({'FAIL'},{i['level'] for i in report.items if i['code']==code})

if __name__=='__main__':unittest.main()
