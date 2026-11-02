"""Attached but uninterpreted originals cannot certify source comparisons."""
import unittest
from unittest.mock import patch
from test_preservation_safety import validator as v, email_metadata, failed_codes, failed_source_fields
from test_foaf_mailbox_uris import metadata


class UninspectableSourceTests(unittest.TestCase):
    def original(self, **changes):
        source = dict(afrelationship='Source', subtype='application/vnd.ms-outlook', bytes=b'opaque original',
                      filename='original.msg', objgen=(1, 0))
        source.update(changes)
        return source

    def root(self):
        return email_metadata([{'Mail_GUID':'uuid:one','Message-ID':'&lt;one@example.org&gt;', 'Subject':'Claimed'}])

    def check(self, files, profile='s', root=None):
        report = v.Report('original.pdf')
        v.check_message_metadata(self.root() if root is None else root, files, profile, report)
        return report

    def test_unsupported_originals_are_unavailable_in_all_mail_profiles(self):
        for profile in ('s', 'si', 'm', 'mi'):
            with self.subTest(profile=profile):
                report = self.check([self.original()], profile)
                self.assertEqual('INCOMPLETE', report.summary()['status'])
                self.assertFalse(failed_codes(report))
                self.assertTrue(any(i['code']=='MESSAGE_SOURCE_COVERAGE' and i['level']=='WARN' for i in report.items))

    def test_pst_and_unknown_media_are_not_invented_emails(self):
        for media, filename in [('application/vnd.ms-outlook-pst','original.pst'), ('application/octet-stream','original.data')]:
            with self.subTest(media=media):
                report = self.check([self.original(subtype=media, filename=filename)])
                self.assertEqual('INCOMPLETE', report.summary()['status'])
                self.assertFalse(failed_codes(report))

    def test_isolated_omission_remains_permitted(self):
        for profile in ('si', 'mi'):
            with self.subTest(profile=profile):
                self.assertEqual('PASS', self.check([], profile).summary()['status'])

    def test_unrelated_attachment_does_not_invent_source_requirement(self):
        self.assertEqual('PASS', self.check([self.original(afrelationship='Data')], 'si').summary()['status'])

    def test_container_sources_are_checked_in_email_children(self):
        for profile in ('c', 'ci'):
            self.assertEqual('PASS', self.check([self.original()], profile).summary()['status'])

    def test_parser_failure_is_unavailable_not_absent(self):
        original = self.original(subtype='message/rfc822', filename='original.eml')
        with patch.object(v.BytesParser, 'parsebytes', side_effect=ValueError('unavailable parser')):
            report = self.check([original])
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertFalse(failed_codes(report))

    def test_legacy_document_properties_also_report_unavailable_source(self):
        root = metadata(['mailto:known@example.org'], 'From')
        v.ET.SubElement(root[0], v.qn(v.NS_PDFMAILMETA, 'Mail_GUID')).text = 'uuid:one'
        report = self.check([self.original()], root=root)
        self.assertEqual('INCOMPLETE', report.summary()['status'])
        self.assertFalse(failed_codes(report))

    def test_independent_supplied_schema_failure_is_preserved(self):
        report = self.check([self.original()], root=metadata(['mailto:bad..address@example.org'], 'From'))
        self.assertEqual('FAIL', report.summary()['status'])
        self.assertIn('MESSAGE_FROM_FOAF', failed_codes(report))

    def test_matched_inspectable_email_is_not_invalidated_by_extra_original(self):
        raw = b'Message-ID: <one@example.org>\r\nSubject: Claimed\r\n\r\nBody'
        source = self.original(subtype='message/rfc822', filename='original.eml', bytes=raw)
        root = email_metadata([{'Mail_GUID':'uuid:one','Message-ID':'&lt;one@example.org&gt;', 'Subject':'Claimed', 'SizeInBytes':str(len(raw))}])
        report = self.check([source, self.original(objgen=(2,0))], root=root)
        self.assertEqual('PASS', report.summary()['status'])
        self.assertFalse(any(i['code']=='VALIDATION_INCOMPLETE' for i in report.items))

    def test_catalog_source_in_isolated_profile_is_not_automatically_forbidden(self):
        from pathlib import Path
        path = Path(__file__).resolve().parent / 'fixtures/eapdf/validator-uninspectable-source/msg-si.pdf'
        report = v.validate(str(path))
        self.assertNotIn('SOURCE_IN_ISOLATED', failed_codes(report))
        self.assertTrue(any(i['code']=='SOURCE_IN_ISOLATED' and i['level']=='WARN' for i in report.items))
        self.assertEqual('FAIL', report.summary()['status'])
        self.assertEqual({'MESSAGE_METADATA_MATCH_SOURCE'},failed_codes(report))
        self.assertEqual({'NumberAttachments', 'Subject', 'To', 'From', 'Sent', 'messageid', 'Raw-To', 'Raw-From'},
                         failed_source_fields(report))
        self.assertTrue(any(i['code']=='VALIDATION_INCOMPLETE' and i['level']=='WARN' for i in report.items))
