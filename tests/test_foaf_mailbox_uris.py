"""Mailbox URI syntax and comparison follow RFC6068 and RFC5321."""
import unittest
from xml.etree import ElementTree as ET
from test_preservation_safety import validator as v


def metadata(values, field='To', resource=True):
    root=ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:m="{v.NS_PDFMAILMETA}" xmlns:f="{v.NS_FOAF}"><rdf:Description/></rdf:RDF>')
    bag=ET.SubElement(ET.SubElement(root[0],v.qn(v.NS_PDFMAILMETA,field)),v.qn(v.NS_RDF,'Seq'))
    for value in values:
        li=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
        node=ET.SubElement(li,v.qn(v.NS_FOAF,'mbox'))
        if resource:node.set(v.qn(v.NS_RDF,'resource'),value)
        else:node.text=value
    ET.SubElement(ET.SubElement(root[0],v.qn(v.NS_PDFMAILMETA,'attachments')),v.qn(v.NS_RDF,'Bag'))
    return root


class FoafMailboxUriTests(unittest.TestCase):
    def compare(self, values, sources=None, field='To', resource=True):
        report=v.Report('mailboxes.pdf')
        agents=None if sources is None else [{'name':'','mbox':value} for value in sources]
        v.compare_agent_property(metadata(values,field,resource),report,field,agents,'MESSAGE_'+field.upper()+'_XMP','MESSAGE_'+field.upper()+'_FOAF')
        return report

    def assert_valid(self, report):
        self.assertFalse(any(i['level']=='FAIL' or i['code']=='VALIDATION_INCOMPLETE' for i in report.items),report.items)

    def test_percent_encoded_uri_text_and_resource_forms_compare_equally(self):
        for resource in (True,False):
            self.assert_valid(self.compare(['mailto:f%6Fo%2Bbar@example.org'],['mailto:foo+bar@example.org'],resource=resource))

    def test_percent_escapes_are_decoded_once_and_plus_is_literal(self):
        for encoded,source in [('mailto:foo%252Bbar@example.org','mailto:foo%2Bbar@example.org'),
                               ('mailto:foo+bar@example.org','mailto:foo+bar@example.org')]:
            self.assert_valid(self.compare([encoded],[source]))
        report=self.compare(['mailto:foo%252Bbar@example.org'],['mailto:foo+bar@example.org'])
        self.assertTrue(any(i['level']=='FAIL' for i in report.items))

    def test_legacy_bare_address_retains_literal_percent_text(self):
        self.assert_valid(self.compare(['foo%2Bbar@example.org'],['mailto:foo%2Bbar@example.org'],resource=False))

    def test_quoted_local_parts_preserve_data_and_equivalent_quoting(self):
        for uri,source in [('mailto:%22First%22@example.org','mailto:First@example.org'),
                           ('mailto:%22not..atom%22@example.org','mailto:"not..atom"@example.org'),
                           ('mailto:%22a%20%20b%22@example.org','mailto:"a  b"@example.org'),
                           ('mailto:%22a%5C%22b%22@example.org','mailto:"a\\"b"@example.org')]:
            self.assert_valid(self.compare([uri],[source]))
        self.assertTrue(any(i['level']=='FAIL' for i in self.compare(['mailto:%22a%20%20b%22@example.org'],['mailto:"a b"@example.org']).items))

    def test_local_case_differs_but_domain_case_is_equivalent(self):
        self.assert_valid(self.compare(['mailto:Case@EXAMPLE.ORG'],['mailto:Case@example.org']))
        self.assertTrue(any(i['level']=='FAIL' for i in self.compare(['mailto:Case@example.org'],['mailto:case@example.org']).items))

    def test_invalid_escapes_utf8_and_decoded_controls_fail_without_aborting(self):
        for suffix in ('%G0','%','%FF','%00','%09','%0A','%0D','%7F'):
            with self.subTest(suffix=suffix):
                report=self.compare(['mailto:foo'+suffix+'@example.org'])
                self.assertTrue(any(i['level']=='FAIL' for i in report.items),report.items)
                self.assertNotIn('VALIDATION_ERROR',{i['code'] for i in report.items})

    def test_all_four_address_fields_keep_ordered_agent_semantics(self):
        for field in ('To','From','Cc','Bcc'):
            self.assert_valid(self.compare(['mailto:f%69rst@example.org','mailto:second@example.org'],['mailto:first@example.org','mailto:second@example.org'],field=field))
            self.assertTrue(any(i['level']=='FAIL' for i in self.compare(['mailto:second@example.org','mailto:first@example.org'],['mailto:first@example.org','mailto:second@example.org'],field=field).items))

    def test_unverified_idna_equivalence_stays_unavailable_without_hiding_known_differences(self):
        values=['mailto:user@b%C3%BCcher.example'];sources=['mailto:user@xn--bcher-kva.example']
        report=self.compare(values,sources)
        self.assertFalse(any(i['level']=='FAIL' for i in report.items))
        self.assertIn('VALIDATION_INCOMPLETE',{i['code'] for i in report.items})
        report=self.compare(values+['mailto:Different@example.org'],sources+['mailto:different@example.org'])
        self.assertTrue(any(i['level']=='FAIL' for i in report.items))

    def test_legal_uri_without_a_source_receives_schema_checks_only(self):
        self.assert_valid(self.compare(['mailto:%22not..atom%22@example.org']))


if __name__=='__main__':unittest.main()
