"""Required first values and owned attachment evidence are distinct from provenance."""
from unittest.mock import patch
from xml.etree import ElementTree as ET
import os
import unittest

from test_preservation_safety import email_metadata, validator as v


def creator_packet(first, second=None):
    root = ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:dc="{v.NS_DC}" xmlns:pdf="{v.NS_PDF}">'
        '<rdf:Description><dc:creator><rdf:Seq/></dc:creator><pdf:Producer>mailtool@example.org</pdf:Producer>'
        '</rdf:Description></rdf:RDF>')
    sequence = root.find('.//' + v.qn(v.NS_RDF, 'Seq'))
    ET.SubElement(sequence, v.qn(v.NS_RDF, 'li')).text = first
    if second is not None:
        ET.SubElement(sequence, v.qn(v.NS_RDF, 'li')).text = second
    return root


class PresenceProvenanceTests(unittest.TestCase):
    def test_later_creator_cannot_populate_the_required_first_member(self):
        for first in ('', '   '):
            report = v.Report('metadata.pdf')
            v.check_eapdf_xmp(creator_packet(first, 'Recorded later'), {}, report)
            row = next(r for r in report.items if r['code'] == 'XMP_CREATOR')
            self.assertEqual('FAIL', row['level'])

    def test_nonempty_first_creator_is_preserved(self):
        report = v.Report('metadata.pdf')
        v.check_eapdf_xmp(creator_packet('alice@example.org', 'Another creator'), {}, report)
        self.assertEqual('PASS', next(r for r in report.items if r['code'] == 'XMP_CREATOR')['level'])

    def test_mailbox_spelling_and_source_sender_equality_do_not_prove_wrong_attribution(self):
        for source in (None, {'raw_from': 'alice@example.org'}):
            with self.subTest(source=source), patch.object(v, 'summarize_source_email', return_value=source), \
                    patch.object(v, 'value_matches_source_sender', return_value=source is not None):
                report = v.Report('metadata.pdf')
                v.check_single_source_xmp_semantics(creator_packet('alice@example.org'), [], report)
                for code in ('XMP_CREATOR_SEMANTICS', 'XMP_PRODUCER_SEMANTICS'):
                    row = next(r for r in report.items if r['code'] == code)
                    self.assertEqual('WARN', row['level'])
                    self.assertEqual('unavailable', row['details']['assessment'])

    def count_report(self, owner='email-A', count=None):
        record = dict(Mail_GUID='email-A', Sent='2026-10-19T12:30:00Z')
        if count is not None:
            record['NumberAttachments'] = count
        root = email_metadata([record])
        description = root[0]
        attachment = ET.SubElement(ET.SubElement(ET.SubElement(description, v.qn(v.NS_PDFMAILMETA, 'attachments')),
                    v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li'), {v.qn(v.NS_RDF, 'parseType'): 'Resource'})
        for key, value in (('Mail_GUID', owner), ('Filename', 'file.bin'), ('SizeInBytes', '0'), ('Content-Type', 'application/octet-stream')):
            ET.SubElement(attachment, v.qn(v.NS_PDFMAILMETA, key)).text = value
        report = v.Report('isolated.pdf')
        v.check_required_email_metadata(root, 'si', report)
        return [r for r in report.items if r['code'] == 'MESSAGE_ATTACHMENT_COUNT_XMP' and r['level'] == 'FAIL']

    def test_optional_owned_attachment_inventory_does_not_prove_a_source_count(self):
        self.assertFalse(self.count_report())

    def test_other_or_differently_spelled_owner_cannot_make_this_email_count_required(self):
        for owner in ('email-B', ' email-A', 'EMAIL-A'):
            self.assertFalse(self.count_report(owner=owner))

    def test_supplied_count_is_checked_as_an_integer_without_original_evidence(self):
        for count in ('0','1'):
            self.assertFalse(self.count_report(count=count))
        for count in ('invalid','-1'):
            self.assertTrue(self.count_report(count=count))

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for independent presence/provenance PDF checks')
    def test_frozen_presence_and_provenance_pdf_corpus(self):
        import hashlib
        import json
        from test_preservation_safety import ROOT
        root = ROOT / 'tests/fixtures/eapdf/validator-presence'
        for case in json.loads((root / 'expectations.json').read_text())['cases']:
            with self.subTest(file=case['file']):
                path = root / case['file']
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(sorted(case['expected_failures']), sorted({r['code'] for r in result['items'] if r['level'] == 'FAIL'}))
                self.assertEqual('FAIL' if case['expected_failures'] else 'PASS', result['summary']['status'])


if __name__ == '__main__':
    unittest.main()
