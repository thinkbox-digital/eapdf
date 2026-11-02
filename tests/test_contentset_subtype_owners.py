"""Subtype matching uses corresponding content data and independent uncertainty."""
import unittest

import pikepdf
import test_content_group_owner_types as groups

CODE = 'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'
RECOMMEND = 'MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED'


class ContentSetSubtypeOwnerTests(unittest.TestCase):
    def document(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        parts[1].DParts[0][0].DPM.Mail_Subtype = pikepdf.Name('/application/pdf')
        next(prop for prop in tags[1].K.A.P if str(prop.N) == 'Mail_Subtype').V = 'application/pdf'
        return pdf, tags, parts

    def assert_unavailable(self, pdf):
        findings, report = groups.ContentGroupOwnerTypeTests().check(pdf)
        self.assertEqual('WARN', findings[CODE]['level'])
        self.assertTrue(any(item['code'] == 'VALIDATION_INCOMPLETE' and item['level'] == 'WARN'
                            for item in report.items))

    def test_distinct_subtypes_match_their_corresponding_content_sets(self):
        pdf, tags, parts = self.document()
        with pdf:
            self.assertEqual('PASS', groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level'])

    def test_subtypes_on_other_content_sets_cannot_be_borrowed(self):
        pdf, tags, parts = self.document()
        with pdf:
            next(prop for prop in tags[0].K.A.P if str(prop.N) == 'Mail_Subtype').V = 'application/pdf'
            next(prop for prop in tags[1].K.A.P if str(prop.N) == 'Mail_Subtype').V = 'text/plain'
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level'])

    def test_absent_required_dpm_subtype_does_not_compare_with_another_owner(self):
        pdf, tags, parts = self.document()
        with pdf:
            del parts[0].DParts[0][0].DPM.Mail_Subtype
            self.assert_unavailable(pdf)

    def test_absent_optional_counterpart_is_not_an_invented_presence_failure(self):
        pdf, tags, parts = self.document()
        with pdf:
            del parts[1].DParts[0][0].DPM.Mail_Subtype
            self.assert_unavailable(pdf)

    def test_supplied_empty_subtype_contradicts_known_corresponding_data(self):
        pdf, tags, parts = self.document()
        with pdf:
            next(prop for prop in tags[0].K.A.P if str(prop.N) == 'Mail_Subtype').V = ''
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level'])

    def test_optional_absence_keeps_recommendation_and_conditional_na(self):
        pdf, tags, parts = self.document()
        with pdf:
            for group in tags:
                group.K.A.P = pikepdf.Array([prop for prop in group.K.A.P if str(prop.N) != 'Mail_Subtype'])
            findings, _ = groups.ContentGroupOwnerTypeTests().check(pdf)
            self.assertEqual('N/A', findings[CODE]['level'])
            self.assertEqual('WARN', findings[RECOMMEND]['level'])

    def test_missing_email_guid_does_not_hide_available_subtype_correspondence(self):
        pdf, tags, parts = self.document()
        with pdf:
            del parts[0].DPM.Mail_GUID
            self.assertEqual('PASS', groups.ContentGroupOwnerTypeTests().check(pdf)[0][CODE]['level'])


if __name__ == '__main__':
    unittest.main()
