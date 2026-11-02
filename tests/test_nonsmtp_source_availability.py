from test_preservation_safety import failed_source_fields
"""Unknown non-SMTP normalization cannot become a fabricated SMTP mismatch."""
import copy
import unittest
from xml.etree import ElementTree as ET
from test_preservation_safety import validator as v, failed_codes
from test_foaf_mailbox_uris import metadata


class NonSmtpSourceAvailabilityTests(unittest.TestCase):
    raw='Sender </o=Exchange/cn=recipient>'
    def compare(self, mailboxes=(), raw=None, field='From', known=False):
        root=metadata(list(mailboxes),field)
        if raw is not None:ET.SubElement(root[0],v.qn(v.NS_PDFMAILMETA,'Raw-'+field)).text=raw
        source=[dict(name='Sender',mbox='mailto:/o=Exchange/cn=recipient')]
        if known:source.append(dict(name='Known',mbox='mailto:known@example.org'))
        original=copy.deepcopy(source);report=v.Report('nonsmtp.pdf')
        v.compare_agent_property(root,report,field,source,'MESSAGE_'+field.upper()+'_XMP','MESSAGE_'+field.upper()+'_FOAF',
                                 self.raw+', Known <known@example.org>' if known else self.raw)
        self.assertEqual(original,source)
        return report

    def test_all_four_unknown_source_fields_do_not_require_invented_smtp_presence(self):
        for field in ('From','To','Cc','Bcc'):
            with self.subTest(field=field):
                report=self.compare(field=field)
                self.assertFalse(failed_codes(report))
                self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_matching_raw_text_preserves_non_smtp_without_foaf_invention(self):
        report=self.compare(raw=self.raw)
        self.assertFalse(failed_codes(report))
        self.assertEqual('PASS',report.summary()['status'])

    def test_additional_smtp_normalization_with_matching_raw_is_unavailable(self):
        report=self.compare(['mailto:normalized@example.org'],raw=self.raw)
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])
        self.assertFalse(any('values match source email' in i['message'] for i in report.items))

    def test_normalization_without_raw_remains_unavailable(self):
        report=self.compare(['mailto:normalized@example.org'])
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_invalid_supplied_mailbox_still_fails(self):
        report=self.compare(['mailto:bad..address@example.org'],raw=self.raw)
        self.assertIn('MESSAGE_FROM_FOAF',failed_codes(report))

    def test_known_smtp_presence_is_required_even_with_unknown_tokens(self):
        report=self.compare(known=True)
        self.assertIn('From',failed_source_fields(report, 'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))

    def test_matching_known_smtp_and_exact_raw_can_pass(self):
        report=self.compare(['mailto:known@example.org'],known=True,raw=self.raw+', Known <known@example.org>')
        self.assertFalse(failed_codes(report))
        self.assertEqual('PASS',report.summary()['status'])

    def test_known_smtp_mismatch_is_still_definite_with_exact_raw(self):
        report=self.compare(['mailto:different@example.org'],known=True,raw=self.raw+', Known <known@example.org>')
        self.assertEqual({'From'},failed_source_fields(report))

    def test_additional_unknown_mapping_does_not_create_a_count_failure(self):
        report=self.compare(['mailto:known@example.org','mailto:normalized@example.org'],known=True,raw=self.raw+', Known <known@example.org>')
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_ordinary_smtp_extra_mailbox_still_fails(self):
        report=v.Report('smtp.pdf')
        v.compare_agent_property(metadata(['mailto:known@example.org','mailto:extra@example.org'],'From'),report,'From',
                                 [dict(name='Known',mbox='mailto:known@example.org')],'MESSAGE_FROM_XMP','MESSAGE_FROM_FOAF')
        self.assertEqual({'From'},failed_source_fields(report))

    def test_incorrect_supplied_raw_retains_independent_fidelity_failure(self):
        report=self.compare(raw='Different </o=Exchange/cn=other>')
        self.assertEqual({'Raw-From'},failed_source_fields(report))
        self.assertEqual('FAIL',report.summary()['status'])
        self.assertFalse(any(i['code']=='MESSAGE_METADATA_MATCH_SOURCE' and i.get('details',{}).get('field')=='From' and i['level']=='PASS' for i in report.items))

    def test_complete_validation_keeps_unavailable_presence_after_schema_failure(self):
        import io
        import tempfile
        from pathlib import Path
        import pikepdf
        seed=Path(__file__).resolve().parent/'fixtures/eapdf/validator-nonsmtp-source/omitted.pdf'
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'scalar.pdf'
            with pikepdf.open(seed) as pdf:
                packet=pdf.Root.Metadata.read_bytes()
                for _,(prefix,uri) in ET.iterparse(io.BytesIO(packet),events=['start-ns']):ET.register_namespace(prefix,uri)
                root=ET.fromstring(packet)
                email=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'email')+'/'+v.qn(v.NS_RDF,'Bag')+'/'+v.qn(v.NS_RDF,'li'))
                ET.SubElement(email,v.qn(v.NS_PDFMAILMETA,'From')).text='not a structured Agent array'
                data=ET.tostring(root,encoding='utf-8')
                for prefix,uri in [('pdfmail',v.NS_PDFMAIL),('pdfmailid',v.NS_PDFMAILID),('pdfmailmeta',v.NS_PDFMAILMETA)]:
                    if ('xmlns:'+prefix+'=').encode() not in data:root.set('xmlns:'+prefix,uri)
                pdf.Root.Metadata.write(ET.tostring(root,encoding='utf-8'));pdf.save(path,fix_metadata_version=False)
            report=v.validate(str(path))
        self.assertEqual({'MESSAGE_FROM_XMP','MESSAGE_FROM_FOAF'},failed_codes(report))
        self.assertTrue(any(i['code']=='MESSAGE_CORE_FIELDS_SOURCE_REQUIRED' and i['level']=='WARN'
                            and i.get('details',{}).get('field')=='From' for i in report.items))

    def test_uri_raw_property_cannot_prove_literal_source_preservation(self):
        root=metadata([], 'From')
        ET.SubElement(root[0],v.qn(v.NS_PDFMAILMETA,'Raw-From'),{v.qn(v.NS_RDF,'resource'):'/o=Exchange/cn=recipient'})
        report=v.Report('uri.pdf')
        v.compare_agent_property(root,report,'From',[dict(name='',mbox='mailto:/o=Exchange/cn=recipient')],
                                 'MESSAGE_FROM_XMP','MESSAGE_FROM_FOAF','/o=Exchange/cn=recipient')
        self.assertFalse(any(i['code']=='MESSAGE_METADATA_MATCH_SOURCE' and i['level']=='PASS'
                             and i.get('details',{}).get('field')=='From' for i in report.items))
        self.assertEqual('INCOMPLETE',report.summary()['status'])
