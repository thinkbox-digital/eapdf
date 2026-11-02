"""Every individual property in the active P array retains its constraints."""
import unittest

import pikepdf
import test_message_guid_content_owners as messages
import test_content_group_owner_types as groups


GUID = 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP'
GUID_PRESENT = 'MAIL_MESSAGE_GUID_ATTRIBUTE'
GROUP = 'MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT'
SUBTYPE = 'MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT'


def levels(report):
    return {item['code']: item['level'] for item in report.items}


class ActiveUserPropertyOccurrenceTests(unittest.TestCase):
    def test_uniform_guid_occurrences_remain_permitted(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_GUID', V='one'))
            self.assertEqual('PASS', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_wrong_guid_before_matching_occurrence_is_still_checked(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_GUID', V='two'))
            self.assertEqual('FAIL', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_wrong_guid_after_matching_occurrence_has_the_same_constraint(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.append(pikepdf.Dictionary(N='Mail_GUID', V='two'))
            self.assertEqual('FAIL', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_globally_absent_guid_cannot_hide_before_a_matching_one(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_GUID', V='not-in-metadata'))
            self.assertEqual('FAIL', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_supplied_empty_guid_is_compared_with_known_metadata(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_GUID', V=''))
            self.assertEqual('FAIL', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_a_later_empty_value_does_not_erase_an_existing_attribute(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.append(pikepdf.Dictionary(N='Mail_GUID', V=''))
            result = levels(messages.MessageGuidContentOwnerTests().check(pdf)[1])
            self.assertEqual('PASS', result[GUID_PRESENT])
            self.assertEqual('FAIL', result[GUID])

    def test_wrong_value_type_remains_unverified_beside_matching_occurrence(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_GUID', V=pikepdf.Name('/one')))
            self.assertEqual('WARN', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_later_attribute_p_array_replaces_the_entire_earlier_array(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            earlier = pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                P=pikepdf.Array([pikepdf.Dictionary(N='Mail_GUID', V='not-in-metadata')]))
            tags[0].A = pikepdf.Array([earlier, tags[0].A])
            self.assertEqual('PASS', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_text_owner_cannot_supply_required_user_property(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            tags[0].A.O = pikepdf.String('UserProperties')
            self.assertEqual('FAIL', levels(messages.MessageGuidContentOwnerTests().check(pdf)[1])[GUID_PRESENT])

    def test_wrong_owner_type_cannot_replace_an_earlier_valid_array(self):
        pdf, tags, parts, paragraphs = messages.MessageGuidContentOwnerTests().document()
        with pdf:
            invalid = pikepdf.Dictionary(O=pikepdf.String('UserProperties'),
                P=pikepdf.Array([pikepdf.Dictionary(N='Mail_GUID', V='two')]))
            tags[0].A = pikepdf.Array([tags[0].A, invalid])
            self.assertEqual('PASS', messages.MessageGuidContentOwnerTests().check(pdf)[0]['level'])

    def test_wrong_group_type_before_correct_occurrence_is_checked(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_ContentSetType', V='Other'))
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][GROUP]['level'])

    def test_ineligible_owner_cannot_hide_a_known_wrong_group_property(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].A.P[0].V = 'Other'
            invalid = pikepdf.Dictionary(O=pikepdf.String('UserProperties'),
                P=pikepdf.Array([pikepdf.Dictionary(N='Mail_ContentSetType', V='BodyRendering')]))
            tags[0].A = pikepdf.Array([tags[0].A, invalid])
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][GROUP]['level'])

    def test_empty_group_type_is_not_erased_by_a_later_value(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_ContentSetType', V=''))
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][GROUP]['level'])

    def test_uniform_group_type_occurrences_remain_permitted(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].A.P.insert(0, pikepdf.Dictionary(N='Mail_ContentSetType', V='BodyRendering'))
            self.assertEqual('PASS', groups.ContentGroupOwnerTypeTests().check(pdf)[0][GROUP]['level'])

    def test_wrong_subtype_before_matching_occurrence_is_checked(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].K.A.P.insert(0, pikepdf.Dictionary(N='Mail_Subtype', V='application/x-other'))
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][SUBTYPE]['level'])

    def test_empty_subtype_is_not_erased_by_a_later_value(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].K.A.P.insert(0, pikepdf.Dictionary(N='Mail_Subtype', V=''))
            self.assertEqual('FAIL', groups.ContentGroupOwnerTypeTests().check(pdf)[0][SUBTYPE]['level'])

    def test_uniform_subtype_occurrences_remain_permitted(self):
        pdf, tags, parts = groups.ContentGroupOwnerTypeTests().document()
        with pdf:
            tags[0].K.A.P.insert(0, pikepdf.Dictionary(N='Mail_Subtype', V='text/plain'))
            self.assertEqual('PASS', groups.ContentGroupOwnerTypeTests().check(pdf)[0][SUBTYPE]['level'])


if __name__ == '__main__':
    unittest.main()
