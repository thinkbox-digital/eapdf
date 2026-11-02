"""Optional MessageID absence, rejected decoding and known contradictions differ."""
import unittest
import pikepdf
import test_dpm_schema as fixtures
from test_preservation_safety import validator as v
CODE='DPM_MAIL_MESSAGEID_MATCH'

class DpmMessageIdDecodingTests(unittest.TestCase):
    document=fixtures.DpmSchemaTests.document
    check=fixtures.DpmSchemaTests.check

    def assess(self,value,expected):
        pdf,message,_=self.document()
        with pdf:
            message.DPM.Mail_MessageID=value
            report=self.check(pdf)
            self.assertEqual({expected},{i['level'] for i in report.items if i['code']==CODE})
            self.assertIn('CONTENT_SET_COUNT',report.seen_codes)
            return report

    def test_rejected_utf8_is_unavailable(self):
        report=self.assess(pikepdf.String(b'\xef\xbb\xbf\xff'),'WARN')
        self.assertTrue(any(i['code']=='VALIDATION_INCOMPLETE' and CODE in i.get('details',{}).get('rules',[]) for i in report.items))

    def test_rejected_utf8_surrogate_is_unavailable(self):
        self.assess(pikepdf.String(b'\xef\xbb\xbf\xed\xa0\x80'),'WARN')

    def test_valid_utf8_messageid_still_matches(self):
        self.assess(pikepdf.String(b'\xef\xbb\xbf17'),'PASS')

    def test_valid_utf16_messageid_still_matches(self):
        self.assess(pikepdf.String(b'\xfe\xff\x001\x007'),'PASS')

    def test_known_empty_messageid_fails(self):
        self.assess(pikepdf.String(''),'FAIL')

    def test_readable_mismatch_fails(self):
        self.assess(pikepdf.String('other'),'FAIL')

    def test_name_is_not_a_text_string(self):
        self.assess(pikepdf.Name('/17'),'FAIL')

    def test_unreadable_name_retains_reportable_wrong_class_failure(self):
        report=self.assess(pikepdf.Object.parse(b'/Vendor#FF'),'FAIL')
        import json
        json.dumps(report.items,ensure_ascii=False).encode('utf8')

    def test_optional_absence_does_not_create_a_comparison(self):
        pdf,message,_=self.document()
        with pdf:
            del message.DPM.Mail_MessageID
            self.assertNotIn(CODE,self.check(pdf).seen_codes)

    def test_existing_readable_whitespace_policy_is_preserved(self):
        self.assess(pikepdf.String(' 17 '),'PASS')

    def test_independent_guid_failure_remains(self):
        pdf,message,_=self.document()
        with pdf:
            message.DPM.Mail_MessageID=pikepdf.String(b'\xef\xbb\xbf\xff')
            message.DPM.Mail_GUID=42
            report=self.check(pdf)
            self.assertIn('DPM_MAIL_GUID_PRESENT',{i['code'] for i in report.items if i['level']=='FAIL'})
            self.assertEqual({'WARN'},{i['level'] for i in report.items if i['code']==CODE})

    def test_later_known_messageid_mismatch_is_not_lost(self):
        pdf,message,_=self.document()
        with pdf:
            message.DPM.Mail_MessageID=pikepdf.String(b'\xef\xbb\xbf\xff')
            top=pdf.Root.DPartRoot.DPartRootNode
            second=pdf.make_indirect(pikepdf.Dictionary(Parent=top,
                DPM=pikepdf.Dictionary(Mail_GUID='42',Mail_MessageID='other')))
            page=pdf.add_blank_page()
            leaf=pdf.make_indirect(pikepdf.Dictionary(Parent=second,Start=page.obj,
                DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.Other,Mail_Desc='Other')))
            second.DParts=pikepdf.Array([pikepdf.Array([leaf])]);page.obj.DPart=leaf
            top.DParts[0].append(second)
            report=self.check(pdf)
            self.assertEqual({'WARN','FAIL'},{i['level'] for i in report.items if i['code']==CODE})

    def test_readable_guid_keeps_precedence_for_body_source_identity(self):
        item={'dpm':{'Mail_GUID':pikepdf.String('42'),'Mail_MessageID':pikepdf.String(b'\xef\xbb\xbf\xff')}}
        self.assertEqual({'Mail_GUID':'42','Message-ID':''},v.dpart_message_identity(item,{}))

    def test_unreadable_local_messageid_does_not_select_an_ancestor_email(self):
        item={'dpm':{'Mail_MessageID':pikepdf.String(b'\xef\xbb\xbf\xff')},'parent_objgen':(1,0)}
        self.assertEqual({'Mail_GUID':'','Message-ID':''},v.dpart_message_identity(item,{(1,0):{'dpm':{'Mail_GUID':'other'}}}))

    def test_independent_body_media_mismatch_survives_unreadable_messageid(self):
        path=fixtures.ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
        with pikepdf.open(path) as pdf:
            _,_,nodes=v.get_dpart_nodes(pdf)
            next(n for n in nodes if n['dpm'].get('Mail_GUID') is not None)['node'].DPM.Mail_MessageID=pikepdf.String(b'\xef\xbb\xbf\xff')
            next(n for n in nodes if n['dpm'].get('Mail_ContentSetType')==pikepdf.Name.BodyRendering)['node'].DPM.Mail_Subtype=pikepdf.Name('/application/pdf')
            report=v.Report('body-source.pdf')
            root,_,_=v.parse_xmp(v.read_metadata_stream(pdf),report)
            v.check_body_media_types(pdf,root,v.get_af_specs(pdf),report)
            media=[i for i in report.items if i['code']=='DPM_BODY_MEDIA_TYPE_MATCH_SOURCE']
            self.assertEqual({'FAIL'},{i['level'] for i in media if i.get('details',{}).get('check')=='body_source_media_type'})
            self.assertEqual({'N/A'},{i['level'] for i in report.items if i['code']=='DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE'})

if __name__=='__main__':unittest.main()
