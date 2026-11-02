"""Unreadable DPM descriptions remain assessed without aborting later checks."""
import unittest
import pikepdf
import test_dpm_schema as fixtures
from test_preservation_safety import validator as v
CODE='DPM_DESC_REQUIRED'

class DpmDescriptionDecodingTests(unittest.TestCase):
    document=fixtures.DpmSchemaTests.document
    check=fixtures.DpmSchemaTests.check

    def assess(self,value,expected):
        pdf,_,leaf=self.document()
        with pdf:
            leaf.DPM.Mail_Desc=value
            report=self.check(pdf)
            self.assertEqual({expected},{i['level'] for i in report.items if i['code']==CODE})
            self.assertIn('CONTENT_SET_COUNT',report.seen_codes)
            return report

    def test_rejected_utf8_description_is_unavailable(self):
        report=self.assess(pikepdf.String(b'\xef\xbb\xbf\xff'),'WARN')
        self.assertTrue(any(i['code']=='VALIDATION_INCOMPLETE' and CODE in i.get('details',{}).get('rules',[]) for i in report.items))

    def test_rejected_utf8_surrogate_is_unavailable(self):
        self.assess(pikepdf.String(b'\xef\xbb\xbf\xed\xa0\x80'),'WARN')

    def test_valid_utf8_human_readable_text_remains_valid(self):
        self.assess(pikepdf.String(b'\xef\xbb\xbf'+ 'Résumé'.encode()),'PASS')

    def test_valid_utf16_human_readable_text_remains_valid(self):
        self.assess(pikepdf.String(b'\xfe\xff'+ 'Résumé'.encode('utf-16be')),'PASS')

    def test_known_empty_description_still_fails(self):
        self.assess(pikepdf.String(''),'FAIL')

    def test_utf16_decoded_empty_is_distinct_from_rejected_decoding(self):
        self.assess(pikepdf.String(b'\xfe\xff\xd8\x00'),'FAIL')

    def test_known_whitespace_description_still_fails(self):
        self.assess(pikepdf.String(b'\xef\xbb\xbf \t\n'),'FAIL')

    def test_name_cannot_satisfy_text_description(self):
        self.assess(pikepdf.Name('/Body'),'FAIL')

    def test_wrong_class_with_unreadable_name_has_reportable_failure(self):
        report=self.assess(pikepdf.Object.parse(b'/Vendor#FF'),'FAIL')
        import json
        json.dumps(report.items,ensure_ascii=False).encode('utf8')

    def test_other_known_failure_remains_when_description_is_unreadable(self):
        pdf,message,leaf=self.document()
        with pdf:
            message.DPM.Mail_GUID=42
            leaf.DPM.Mail_Desc=pikepdf.String(b'\xef\xbb\xbf\xff')
            report=self.check(pdf)
            self.assertIn('DPM_MAIL_GUID_PRESENT',{i['code'] for i in report.items if i['level']=='FAIL'})
            self.assertEqual({'WARN'},{i['level'] for i in report.items if i['code']==CODE})

if __name__=='__main__':unittest.main()
