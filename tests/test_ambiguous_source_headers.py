"""Universal contradictions do not require choosing an ambiguous source owner."""
import copy
import unittest
from unittest.mock import patch
from test_preservation_safety import validator as v, failed_codes, email_metadata
from test_foaf_mailbox_uris import metadata


class AmbiguousSourceHeaderTests(unittest.TestCase):
    def sources(self, second=None):
        raw = b'From: sender@example.org\r\nTo: known@example.org\r\nMessage-ID: <same@example.org>\r\nSubject: Same\r\nDate: Tue, 28 Jan 2003 21:20:54 -0500\r\n\r\nA'
        second = raw[:-1]+b'B' if second is None else second(raw)
        return [dict(afrelationship='Source',subtype='message/rfc822',filename=str(i)+'.eml',objgen=(i,0),bytes=data) for i,data in enumerate((raw,second),1)]

    def root(self, files, to='known@example.org', fields=None, sender='sender@example.org'):
        record={'Mail_GUID':'uuid:one','Message-ID':'&lt;same@example.org&gt;','SizeInBytes':str(len(files[0]['bytes'])),
                'Subject':'Same','Sent':'2003-01-29T02:20:54Z'}
        record.update(fields or {})
        root=email_metadata([record]);item=root.find('.//'+v.qn(v.NS_RDF,'li'))
        for field,mailbox in [('To',to),('From',sender)]:
            if mailbox is not None:item.append(metadata(['mailto:'+mailbox],field)[0][0])
        return root

    def check(self, files, **kwargs):
        root=self.root(files,**kwargs);report=v.Report('candidates.pdf')
        v.check_message_metadata(root,files,'s',report)
        return report

    def test_every_original_contradicts_the_same_recipient(self):
        report=self.check(self.sources(),to='wrong@example.org')
        self.assertEqual({'MESSAGE_METADATA_MATCH_SOURCE'},failed_codes(report))
        failure=next(i for i in report.items if i['code']=='MESSAGE_METADATA_MATCH_SOURCE' and i['level']=='FAIL')
        self.assertEqual('To',failure['details']['field'])
        self.assertEqual('unassigned',failure['details']['source_ownership'])
        self.assertEqual(2,failure['details']['candidate_count'])
        self.assertFalse(any(i['code']=='MESSAGE_SOURCE_COVERAGE' and i['level']=='PASS' for i in report.items))

    def test_subject_raw_text_and_size_universal_contradictions_are_retained(self):
        for fields in [{'Subject':'Different'},{'Raw-To':'wrong@example.org'},
                       {'Raw-From':''},{'SizeInBytes':'99999'}]:
            with self.subTest(field=next(iter(fields))):
                rows=self.check(self.sources(),fields=fields).items
                self.assertTrue(any(i['code']=='MESSAGE_METADATA_MATCH_SOURCE' and i['level']=='FAIL'
                                    and i['details']['field']==next(iter(fields)) for i in rows))

    def test_one_compatible_candidate_blocks_an_unsupported_field_failure(self):
        files=self.sources(lambda raw:raw.replace(b'known@example.org',b'Known@example.org')[:-1]+b'B')
        report=self.check(files,to='Known@example.org')
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_matching_ambiguous_headers_do_not_assign_an_owner(self):
        report=self.check(self.sources())
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_unsupported_original_blocks_a_universal_proof(self):
        files=self.sources();files.append(dict(afrelationship='Source',subtype='application/vnd.ms-outlook',filename='unknown.msg',objgen=(3,0),bytes=b'opaque'))
        report=self.check(files,to='wrong@example.org')
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_unknown_extra_original_also_blocks_a_tentative_mismatching_owner(self):
        files=self.sources()[:1];files.append(dict(afrelationship='Source',subtype='application/vnd.ms-outlook',filename='unknown.msg',objgen=(3,0),bytes=b'opaque'))
        report=self.check(files,to='wrong@example.org')
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_recovered_header_inventory_cannot_establish_every_candidate(self):
        files=self.sources(lambda raw:raw.replace(b'Subject:',b'broken header\r\nSubject:'))
        report=self.check(files,to='wrong@example.org')
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_unreadable_other_field_does_not_hide_a_known_recipient_contradiction(self):
        files=self.sources(lambda raw:raw.replace(b'sender@example.org',b'j\xffrg@example.org'))
        report=self.check(files,to='wrong@example.org')
        fields={i['details']['field'] for i in report.items if i['code']=='MESSAGE_METADATA_MATCH_SOURCE' and i['level']=='FAIL'}
        self.assertIn('To',fields)
        self.assertNotIn('From',fields)

    def test_unavailable_date_does_not_become_a_universal_instant_failure(self):
        def extended(raw):
            value=raw.replace(b'Tue, 28 Jan 2003 21:20:54 -0500',b'1 Jan 10000 12:00:00 +0000')
            return value+b' '*(len(raw)-len(value))
        files=self.sources(extended)
        report=self.check(files,fields={'Sent':'2003-01-29T02:20:55Z'})
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_different_contradicted_fields_reject_the_tuple_without_naming_a_false_owner(self):
        files=self.sources(lambda raw:raw.replace(b'known@example.org',b'other@example.org').replace(b'Subject: Same',b'Subject: Else'))
        report=self.check(files,to='other@example.org')
        self.assertEqual({'MESSAGE_SOURCE_COVERAGE'},failed_codes(report))
        failure=next(i for i in report.items if i['code']=='MESSAGE_SOURCE_COVERAGE' and i['level']=='FAIL')
        self.assertEqual('unassigned',failure['details']['source_ownership'])

    def test_previously_consumed_source_is_not_excluded_from_universal_evidence(self):
        files=self.sources(lambda raw:raw.replace(b'<same@example.org>',b'<else@example.org>'))
        root=self.root(files);bag=root.find('.//'+v.qn(v.NS_PDFMAILMETA,'email')+'/'+v.qn(v.NS_RDF,'Bag'))
        second=copy.deepcopy(bag[0]);second.find(v.qn(v.NS_PDFMAILMETA,'Mail_GUID')).text='uuid:two';bag.append(second)
        report=v.Report('repeated.pdf');v.check_message_metadata(root,files,'m',report)
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_probe_budget_stops_without_a_partial_universal_failure(self):
        files=self.sources();root=self.root(files,to='wrong@example.org');record=v.flatten_email_records(root)[0]
        report=v.Report('limited.pdf')
        with patch.object(v,'MAX_TREE_ITEMS',1):v.check_ambiguous_source_header_values(root,record,files,'s',report)
        self.assertFalse(failed_codes(report))
        self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_supplied_schema_failure_is_independent_of_unknown_originals(self):
        files=self.sources();files.append(dict(afrelationship='Source',subtype='application/octet-stream',filename='unknown.bin',objgen=(3,0),bytes=b'unknown'))
        self.assertIn('MESSAGE_TO_FOAF',failed_codes(self.check(files,to='bad..address@example.org')))
