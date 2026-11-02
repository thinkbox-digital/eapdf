"""Reviewed EA-PDF GUID identity and permitted XMP representation contracts."""
import unittest
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import validator, failed_codes


def metadata(body):
    return ET.fromstring(f'<rdf:RDF xmlns:rdf="{validator.NS_RDF}" '
                         f'xmlns:m="{validator.NS_PDFMAILMETA}" xmlns:xmp="{validator.NS_XMP}" '
                         f'xmlns:foaf="{validator.NS_FOAF}"><rdf:Description>{body}'
                         '</rdf:Description></rdf:RDF>')


def email_records(members, kind='Bag'):
    return metadata(f'<m:email><rdf:{kind}>{members}</rdf:{kind}></m:email>')


class XmpRepresentationTests(unittest.TestCase):
    def test_pdfa_identification_keeps_existing_numeric_whitespace_handling(self):
        root = metadata('')
        desc = root.find(validator.qn(validator.NS_RDF, 'Description'))
        ET.SubElement(desc, validator.qn(validator.NS_PDFAID, 'part')).text = ' 3 '
        ET.SubElement(desc, validator.qn(validator.NS_PDFAID, 'conformance')).text = 'U'
        report = validator.Report('identification.pdf')
        self.assertEqual((3, 'U'), validator.check_pdfa_xmp(root, report))
        self.assertEqual(set(), failed_codes(report))

    def test_equivalent_structures_and_qualified_guid_values(self):
        fields = '<m:Mail_GUID> id  A </m:Mail_GUID><m:Sent>2026-10-19T12:00:00Z</m:Sent>'
        forms = [
            '<rdf:li rdf:parseType="Resource">' + fields + '</rdf:li>',
            '<rdf:li><rdf:Description>' + fields + '</rdf:Description></rdf:li>',
            '<rdf:li m:Mail_GUID=" id  A " m:Sent="2026-10-19T12:00:00Z"/>',
            '<rdf:li><rdf:Description m:Mail_GUID=" id  A " m:Sent="2026-10-19T12:00:00Z"/></rdf:li>',
            '<rdf:li rdf:parseType="Resource"><m:Mail_GUID rdf:parseType="Resource">'
            '<rdf:value> id  A </rdf:value><xmp:Label>qualifier</xmp:Label></m:Mail_GUID>'
            '<m:Sent>2026-10-19T12:00:00Z</m:Sent></rdf:li>',
        ]
        for member in forms:
            with self.subTest(member=member):
                root = email_records(member)
                self.assertEqual(' id  A ', validator.flatten_email_records(root)[0]['Mail_GUID'])
                report = validator.Report('equivalent.pdf')
                validator.check_required_email_metadata(root, 's', report)
                self.assertEqual(set(), failed_codes(report))

    def test_top_level_simple_attribute_and_qualified_forms(self):
        for root in (
            metadata('<m:Mail_GUID> id  A </m:Mail_GUID>'),
            ET.fromstring(f'<rdf:RDF xmlns:rdf="{validator.NS_RDF}" xmlns:m="{validator.NS_PDFMAILMETA}">'
                          '<rdf:Description m:Mail_GUID=" id  A "/></rdf:RDF>'),
            metadata('<m:Mail_GUID><rdf:Description><rdf:value> id  A </rdf:value>'
                     '<xmp:Label>Q</xmp:Label></rdf:Description></m:Mail_GUID>'),
            metadata('<m:Mail_GUID><rdf:Description rdf:value=" id  A " xmp:Label="Q"/></m:Mail_GUID>'),
        ):
            self.assertEqual(' id  A ', validator.first_xmp_value(root, validator.NS_PDFMAILMETA, 'Mail_GUID'))

    def test_uri_looking_text_is_valid_but_rdf_resource_is_not_a_normal_guid(self):
        for field, valid in (
            ('<m:Mail_GUID>https://example.org/identity</m:Mail_GUID>', True),
            ('<m:Mail_GUID rdf:resource="https://example.org/identity"/>', False),
            ('<m:Mail_GUID rdf:parseType="Resource"><rdf:value rdf:resource="urn:id:A"/>'
             '<xmp:Label>Q</xmp:Label></m:Mail_GUID>', False),
            ('<m:Mail_GUID><rdf:Bag><rdf:li>id</rdf:li></rdf:Bag></m:Mail_GUID>', False),
        ):
            with self.subTest(field=field):
                root = email_records('<rdf:li rdf:parseType="Resource">' + field
                                     + '<m:Sent>2026-10-19T12:00:00Z</m:Sent></rdf:li>')
                report = validator.Report('type.pdf')
                validator.check_required_email_metadata(root, 's', report)
                self.assertEqual(not valid, 'MAIL_GUID_XMP' in failed_codes(report))

    def test_foreign_namespace_guid_cannot_satisfy_required_field(self):
        for tag in ('xmp:Mail_GUID', 'm:GUID', 'm:mail_guid'):
            with self.subTest(tag=tag):
                root = email_records('<rdf:li rdf:parseType="Resource"><' + tag + '>id</' + tag + '>'
                                     '<m:Sent>2026-10-19T12:00:00Z</m:Sent></rdf:li>')
                report = validator.Report('namespace.pdf')
                validator.check_required_email_metadata(root, 's', report)
                self.assertIn('MAIL_GUID_XMP', failed_codes(report))

    def test_email_array_kind_and_record_shape_are_checked(self):
        for kind, member, valid in (
            ('Bag', '<rdf:li m:Mail_GUID="id" m:Sent="2026-10-19T12:00:00Z"/>', True),
            ('Seq', '<rdf:li m:Mail_GUID="id" m:Sent="2026-10-19T12:00:00Z"/>', False),
            ('Bag', '<rdf:li>not a structured email</rdf:li>', False),
            ('Bag', '<rdf:li rdf:resource="urn:email"/>', False),
        ):
            with self.subTest(kind=kind, member=member):
                report = validator.Report('array.pdf')
                validator.check_required_email_metadata(email_records(member, kind), 's', report)
                self.assertEqual(not valid, 'EMAIL_XMP_CONTAINER' in failed_codes(report))

    def test_empty_email_array_cannot_borrow_top_level_required_fields(self):
        root = metadata('<m:email><rdf:Bag/></m:email><m:Mail_GUID>id</m:Mail_GUID>'
                        '<m:Sent>2026-10-19T12:00:00Z</m:Sent>')
        for profile in ('s', 'si', 'm', 'mi'):
            with self.subTest(profile=profile):
                report = validator.Report('empty-emails.pdf')
                validator.check_required_email_metadata(root, profile, report)
                self.assertIn('EMAIL_XMP_CARDINALITY', failed_codes(report))

    def test_address_array_order_and_kind_are_preserved(self):
        for kind, valid in (('Seq', True), ('Bag', False), ('Alt', False)):
            root = metadata(f'<m:To><rdf:{kind}><rdf:li rdf:parseType="Resource">'
                            '<foaf:mbox>mailto:first@example.org</foaf:mbox></rdf:li>'
                            '<rdf:li rdf:parseType="Resource"><foaf:mbox>mailto:second@example.org</foaf:mbox>'
                            f'</rdf:li></rdf:{kind}></m:To>')
            agents = validator.extract_message_foaf_agents(root, 'To')
            self.assertEqual(['mailto:first@example.org', 'mailto:second@example.org'], [a['mbox'] for a in agents])
            report = validator.Report('addresses.pdf')
            validator.compare_agent_property(root, report, 'To', None, 'MESSAGE_TO_XMP', 'MESSAGE_TO_FOAF')
            self.assertEqual(not valid, 'MESSAGE_TO_FOAF' in failed_codes(report))

    def test_scalar_and_foreign_namespace_mailboxes_are_rejected(self):
        for member in ('<rdf:li>first@example.org</rdf:li>',
                       '<rdf:li rdf:parseType="Resource"><m:mbox>mailto:first@example.org</m:mbox></rdf:li>'):
            report = validator.Report('foreign-agent.pdf')
            root = metadata('<m:To><rdf:Seq>' + member + '</rdf:Seq></m:To>')
            validator.compare_agent_property(root, report, 'To', None, 'MESSAGE_TO_XMP', 'MESSAGE_TO_FOAF')
            self.assertIn('MESSAGE_TO_FOAF', failed_codes(report))

    def test_typed_inner_foaf_agent_keeps_its_mailbox(self):
        root = metadata('<m:To><rdf:Seq><rdf:li><foaf:Agent><foaf:mbox rdf:resource="mailto:first@example.org"/>'
                        '<foaf:name>First</foaf:name></foaf:Agent></rdf:li></rdf:Seq></m:To>')
        report = validator.Report('typed-agent.pdf')
        validator.compare_agent_property(root, report, 'To', None, 'MESSAGE_TO_XMP', 'MESSAGE_TO_FOAF')
        self.assertEqual(set(), failed_codes(report))
        self.assertEqual('mailto:first@example.org', validator.extract_message_foaf_agents(root, 'To')[0]['mbox'])

    def test_supplied_asset_and_attachment_arrays_require_unordered_records(self):
        for prop, code in (('assets', 'ASSET_XMP'), ('attachments', 'ATTACHMENT_XMP_CONTAINER')):
            for kind, member, valid in (('Bag', '<rdf:li rdf:parseType="Resource"/>', True),
                                         ('Seq', '<rdf:li rdf:parseType="Resource"/>', False),
                                         ('Bag', '<rdf:li>scalar</rdf:li>', False)):
                with self.subTest(prop=prop, kind=kind, member=member):
                    report = validator.Report('records.pdf')
                    root = metadata(f'<m:{prop}><rdf:{kind}>{member}</rdf:{kind}></m:{prop}>')
                    validator.check_xmp_record_array(root, prop, report, code)
                    self.assertEqual(not valid, code in failed_codes(report))

    def test_qualified_email_array_keeps_original_member_scoping(self):
        root = metadata('<m:email rdf:parseType="Resource"><rdf:value><rdf:Bag>'
                        '<rdf:li m:Mail_GUID="one" m:Sent="2026-10-19T12:00:00Z"/>'
                        '<rdf:li m:Mail_GUID="two" m:Sent="2026-10-19T12:00:00Z"/>'
                        '</rdf:Bag></rdf:value><xmp:Label>Q</xmp:Label></m:email>')
        self.assertEqual(['one', 'two'], [r['Mail_GUID'] for r in validator.flatten_email_records(root)])
        self.assertEqual(2, len(validator.xmp_array_members(root, 'email')))
        report = validator.Report('qualified-array.pdf')
        validator.check_required_email_metadata(root, 'm', report)
        self.assertEqual(set(), failed_codes(report))


class ExactGuidIdentityTests(unittest.TestCase):
    def test_guid_matching_preserves_case_whitespace_and_unicode(self):
        for a, b, expected in (('id', 'id', True), ('id', 'ID', False), (' id ', 'id', False),
                               ('a  b', 'a b', False), ('é', 'e\u0301', False),
                               ('urn:id:A', 'urn:id:A', True)):
            with self.subTest(a=a, b=b):
                self.assertEqual(a, validator.identity_fields({'Mail_GUID': a})['Mail_GUID'])
                self.assertEqual(expected, validator.identities_match({'Mail_GUID': a}, {'Mail_GUID': b}))

    def test_different_guids_do_not_match_through_shared_message_id(self):
        self.assertFalse(validator.identities_match(
            {'Mail_GUID': 'id', 'Message-ID': 'same'}, {'Mail_GUID': 'ID', 'Message-ID': 'same'}))

    def test_distinct_whitespace_guids_are_not_duplicate_emails(self):
        for guids, duplicate in ((['id', ' id '], False), (['a b', 'a  b'], False), (['id', 'id'], True)):
            root = email_records(''.join('<rdf:li rdf:parseType="Resource"><m:Mail_GUID>' + guid
                                        + '</m:Mail_GUID><m:Sent>2026-10-19T12:00:00Z</m:Sent></rdf:li>' for guid in guids))
            report = validator.Report('unique.pdf')
            validator.check_required_email_metadata(root, 'm', report)
            self.assertEqual(duplicate, 'MAIL_GUID_UNIQUE' in failed_codes(report))

    def test_xmp_guid_sets_exclude_attachment_owned_and_uri_values(self):
        root = email_records('<rdf:li m:Mail_GUID=" id " m:Sent="2026-10-19T12:00:00Z"/>')
        desc = root.find(validator.qn(validator.NS_RDF, 'Description'))
        desc.append(ET.fromstring(f'<m:attachments xmlns:m="{validator.NS_PDFMAILMETA}" xmlns:rdf="{validator.NS_RDF}">'
                                 '<rdf:Bag><rdf:li><rdf:Description m:Mail_GUID="attachment-id"/></rdf:li></rdf:Bag></m:attachments>'))
        self.assertEqual({' id '}, validator.xmp_value_set(root, 'Mail_GUID', 'GUID'))

    def test_dpart_guid_matching_is_exact(self):
        source = validator.ROOT / 'tests/fixtures/eapdf/validator-table11/valid.pdf'
        with pikepdf.Pdf.open(source) as pdf:
            root, _, _ = validator.parse_xmp(validator.read_metadata_stream(pdf), validator.Report('parse'))
            _, _, nodes = validator.get_dpart_nodes(pdf)
            node = next(item for item in nodes if 'Mail_GUID' in item['dpm'])
            original = str(node['dpm']['Mail_GUID'])
            node['node'].DPM.Mail_GUID = ' ' + original + ' '
            report = validator.Report('dpm.pdf')
            validator.check_dpart_and_content_sets(pdf, root, 's', report, [])
            self.assertIn('DPM_MAIL_GUID_MATCH', failed_codes(report))

    def test_attachment_source_owner_guid_is_not_trimmed(self):
        attachment = {'Mail_GUID': ' owner ', 'Filename': 'a.txt', 'SizeInBytes': '4', 'Content-Type': 'text/plain'}
        source = {'mail_guid': ' owner ', 'mime_attachments': [dict(filename='a.txt', size_in_bytes=4, content_type='text/plain')]}
        report = validator.Report('attachment.pdf')
        validator.validate_attachment_records([attachment], source, report)
        self.assertEqual({'PASS'}, {r['level'] for r in report.items
                                  if r['code']=='ATTACHMENT_XMP_FILENAME_MATCH_SOURCE'})
        attachment['Mail_GUID'] = 'owner'
        mismatch = validator.Report('mismatched-attachment.pdf')
        validator.validate_attachment_records([attachment], source, mismatch)
        findings=[r for r in mismatch.items if r['code']=='ATTACHMENT_XMP_FILENAME_MATCH_SOURCE']
        self.assertEqual({'WARN'}, {r['level'] for r in findings})
        self.assertTrue(all(r['details']['assessment']=='unavailable' for r in findings))


if __name__ == '__main__':
    unittest.main()
