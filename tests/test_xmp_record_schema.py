"""Canonical Table 16/19 fields and semantic values, independent of PDF creation."""
import hashlib
import json
import os
import unittest
from xml.etree import ElementTree as ET

from test_preservation_safety import ROOT, validator as v, failed_codes


def records(prop, fields):
    root = ET.Element(v.qn(v.NS_RDF, 'RDF'))
    description = ET.SubElement(root, v.qn(v.NS_RDF, 'Description'))
    bag = ET.SubElement(ET.SubElement(description, v.qn(v.NS_PDFMAILMETA, prop)), v.qn(v.NS_RDF, 'Bag'))
    record = ET.SubElement(bag, v.qn(v.NS_RDF, 'li'), {v.qn(v.NS_RDF, 'parseType'): 'Resource'})
    for name, value in fields.items():
        field = ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, name))
        if isinstance(value, v.XmpUri):
            field.set(v.qn(v.NS_RDF, 'resource'), value)
        elif isinstance(value, list):
            for item in value:
                ET.SubElement(ET.SubElement(field, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = item
        else:
            field.text = str(value)
    return root


class XmpRecordSchemaTests(unittest.TestCase):
    asset = dict(Filename='source.eml', SizeInBytes='42', ContentType='message/rfc822', Checksum='0' * 32)
    attachment = {'Filename': 'file.txt', 'SizeInBytes': '0', 'Content-Type': 'text/plain', 'Mail_GUID': 'owner'}

    def test_attachment_container_is_required_even_without_sources_or_attachments(self):
        for profile in ('s', 'si', 'm', 'mi', 'c', 'ci'):
            for shape in ('missing', 'Bag', 'Seq', 'text'):
                with self.subTest(profile=profile, shape=shape):
                    root = records('email', {'Sent': '2024-01-01T12:00:00Z', 'Mail_GUID': 'owner'})
                    if shape != 'missing':
                        prop = ET.SubElement(root[0], v.qn(v.NS_PDFMAILMETA, 'attachments'))
                        if shape == 'text':
                            prop.text = 'not an attachment array'
                        else:
                            ET.SubElement(prop, v.qn(v.NS_RDF, shape))
                    report = v.Report('attachment-container.pdf')
                    v.check_message_metadata(root, [], profile, report)
                    self.assertEqual(shape != 'Bag', 'ATTACHMENT_XMP_CONTAINER' in failed_codes(report))

    def asset_report(self, values):
        report = v.Report('asset.pdf')
        v.check_asset_metadata(records('assets', values), [], [], 'si', report)
        return report

    def attachment_report(self, values):
        report = v.Report('attachment.pdf')
        v.validate_attachment_records(v.flatten_attachments(records('attachments', values)), [], report)
        return report

    def test_asset_aliases_cannot_replace_required_canonical_fields(self):
        for canonical, alias in (('Checksum', 'CheckSum'), ('ContentType', 'Format')):
            values = dict(self.asset)
            values[alias] = values.pop(canonical)
            with self.subTest(alias=alias):
                self.assertIn('ASSET_XMP_FIELDS', failed_codes(self.asset_report(values)))

    def test_asset_text_fields_require_normal_strings(self):
        for field in ('Filename', 'ContentType', 'Checksum'):
            for value in ([self.asset[field]], v.XmpUri(self.asset[field])):
                with self.subTest(field=field, value=value):
                    self.assertIn('ASSET_XMP_FIELDS', failed_codes(self.asset_report(dict(self.asset, **{field: value}))))

    def test_asset_sizes_are_nonnegative_ascii_integers(self):
        for value in ('-1', '1_000', '１２', v.XmpUri('42'), ['42']):
            with self.subTest(value=value):
                self.assertIn('ASSET_SIZE_TYPE', failed_codes(self.asset_report(dict(self.asset, SizeInBytes=value))))
        for value in ('0', '+42', '0042'):
            with self.subTest(value=value):
                self.assertNotIn('ASSET_SIZE_TYPE', failed_codes(self.asset_report(dict(self.asset, SizeInBytes=value))))

    def test_optional_number_messages_is_required_to_have_a_valid_type_when_supplied(self):
        self.assertFalse(any(r['code'] == 'ASSET_NUMBERMESSAGES_TYPE' for r in self.asset_report(self.asset).items))
        for value in ('', '-1', '1_000', v.XmpUri('1'), ['1']):
            with self.subTest(value=value):
                self.assertIn('ASSET_NUMBERMESSAGES_TYPE', failed_codes(self.asset_report(dict(self.asset, NumberMessages=value))))
        for value in ('0', '+001', '-0'):
            with self.subTest(value=value):
                self.assertNotIn('ASSET_NUMBERMESSAGES_TYPE', failed_codes(self.asset_report(dict(self.asset, NumberMessages=value))))

    def test_attachment_aliases_cannot_supply_content_type(self):
        for alias in ('ContentType', 'Format'):
            values = dict(self.attachment)
            values[alias] = values.pop('Content-Type')
            with self.subTest(alias=alias):
                self.assertIn('ATTACHMENT_XMP_CONTENTTYPE', failed_codes(self.attachment_report(values)))

    def test_attachment_text_fields_are_not_uri_or_array_values(self):
        for field, code in (('Filename', 'ATTACHMENT_XMP_FILENAME'), ('Content-Type', 'ATTACHMENT_XMP_CONTENTTYPE')):
            for value in ([self.attachment[field]], v.XmpUri(self.attachment[field])):
                with self.subTest(field=field, value=value):
                    self.assertIn(code, failed_codes(self.attachment_report(dict(self.attachment, **{field: value}))))

    def test_present_optional_checksum_must_have_the_declared_md5_form(self):
        for value in ('', 'bad', ['0' * 32], v.XmpUri('0' * 32)):
            with self.subTest(value=value):
                self.assertIn('ATTACHMENT_XMP_CHECKSUM_FORM',
                              failed_codes(self.attachment_report(dict(self.attachment, CheckSum=value))))
        for values in (self.attachment, dict(self.attachment, CheckSum='aB' * 16),
                       dict(self.attachment, Checksum='unrelated extension field')):
            self.assertNotIn('ATTACHMENT_XMP_CHECKSUM_FORM', failed_codes(self.attachment_report(values)))

    def test_messageid_alias_or_bad_shape_cannot_waive_required_guid(self):
        base = dict(self.attachment)
        del base['Mail_GUID']
        for name, value in (('MessageID', '17'), ('Message-ID', ['17']), ('Message-ID', v.XmpUri('17'))):
            with self.subTest(name=name, value=value):
                self.assertIn('ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID',
                              failed_codes(self.attachment_report(dict(base, **{name: value}))))
        self.assertNotIn('ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID',
                         failed_codes(self.attachment_report(dict(base, **{'Message-ID': '17'}))))

    def test_content_type_preserves_malformed_source_text_as_text(self):
        report = self.attachment_report(dict(self.attachment, **{'Content-Type': 'broken source header value'}))
        self.assertNotIn('ATTACHMENT_XMP_CONTENTTYPE', failed_codes(report))
        self.assertNotEqual(v.media_type_signature('broken source header value'), v.media_type_signature('text/plain'))

    def test_bad_checksum_shape_does_not_abort_source_comparison(self):
        values = dict(self.attachment, CheckSum=['0' * 32])
        summary = {'mail_guid': 'owner', 'mime_attachments': [
            {'filename': 'file.txt', 'size_in_bytes': 0, 'content_type': 'text/plain', 'checksum': '0' * 32}]}
        report = v.Report('source.pdf')
        v.validate_attachment_records(v.flatten_attachments(records('attachments', values)), summary, report)
        self.assertIn('ATTACHMENT_XMP_CHECKSUM_FORM', failed_codes(report))


class XmpRecordCorpusTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for independent PDF/A corpus checks')
    def test_frozen_dpm_and_xmp_record_probes(self):
        for directory in ('validator-dpm-schema', 'validator-xmp-records'):
            base = ROOT / 'tests/fixtures/eapdf' / directory
            for case in json.loads((base / 'expectations.json').read_text())['cases']:
                path = base / case['file']
                with self.subTest(directory=directory, file=case['file']):
                    self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                    result = v.validate_one(str(path), write_html=False)
                    self.assertEqual('PASS', result['pdfa']['status'])
                    self.assertEqual(set(case['expected_failures']),
                                     {r['code'] for r in result['items'] if r['level'] == 'FAIL'})
                    self.assertEqual('FAIL' if case['expected_failures'] else 'PASS', result['summary']['status'])


if __name__ == '__main__':
    unittest.main()
