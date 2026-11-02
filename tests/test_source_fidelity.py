import unittest
from xml.etree import ElementTree as ET
from test_preservation_safety import validator as v
from test_foaf_mailbox_uris import metadata


class SourceFidelityTests(unittest.TestCase):
    def summary(self, value):
        raw = ('From: author@example.org\r\nTo: reader@example.org\r\nCc: ' + value
               + '\r\nSubject: Review\r\n\r\nBody').encode('ascii')
        return v.summarize_source_email([{'afrelationship': 'Source', 'filename': 'source.eml',
                                         'subtype': 'message/rfc822', 'bytes': raw}])

    def test_quoted_display_name_does_not_invent_a_mailbox(self):
        supplied = '"reader@example.org"'
        summary = self.summary(supplied)
        self.assertFalse(v.valid_smtp_mailbox(summary['cc_agents'][0]['mbox']))
        root = metadata([], field='Cc')
        ET.SubElement(root[0], v.qn(v.NS_PDFMAILMETA, 'Raw-Cc')).text = supplied
        report = v.Report('quoted display name')
        v.compare_agent_property(root, report, 'Cc', summary['cc_agents'], 'MESSAGE_CC_XMP', 'MESSAGE_CC_FOAF', summary['raw_cc'])
        self.assertFalse(any(i['level'] == 'FAIL' for i in report.items), report.items)
        self.assertTrue(any(i['code'] == 'MESSAGE_CC_XMP' and i['level'] == 'PASS' for i in report.items))

    def test_a_quoted_local_part_with_its_domain_remains_a_mailbox(self):
        summary = self.summary('"two  spaces"@example.org')
        self.assertEqual(['mailto:"two  spaces"@example.org'], [a['mbox'] for a in summary['cc_agents']])
        self.assertTrue(v.valid_smtp_mailbox(summary['cc_agents'][0]['mbox']))
