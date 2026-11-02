"""Independent specification cases for the six previously unmapped file records."""
from scripts.eapdf_rule_review import load_review_rules

import hashlib
import json
import os
import unittest
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import ROOT, validator as v, failed_codes


def levels(report, code):
    return [r['level'] for r in report.items if r['code'] == code]


class ContainerContentSetTests(unittest.TestCase):
    def document(self, kind):
        pdf = pikepdf.Pdf.new()
        page = pdf.add_blank_page()
        root = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPartRoot))
        node = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=root))
        leaf = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart, Parent=node,
            Start=page.obj, DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name('/' + kind),
                Mail_Desc='Content set', Mail_Subtype=pikepdf.Name('/text/plain'))))
        root.DPartRootNode = node
        node.DParts = pikepdf.Array([leaf])
        pdf.Root.DPartRoot = root
        page.DPart = leaf
        return pdf

    def test_all_four_previously_missing_container_prohibitions(self):
        for profile in ('c', 'ci'):
            for kind in ('AttachmentRendering', 'BodyRendering', 'EmailHeaderRendering', 'RawEmailRendering'):
                with self.subTest(profile=profile, kind=kind), self.document(kind) as pdf:
                    report = v.Report('container.pdf')
                    v.check_dpart_and_content_sets(pdf, None, profile, report, set())
                    self.assertEqual(['FAIL'], levels(report, 'DPM_CONTAINER_CONTENTSET_TYPES'))

    def test_permitted_and_extension_types_are_not_a_closed_allowlist(self):
        for kind in ('FrontMatter', 'ConversionReport', 'PolicySettings', 'Provenance', 'Other', 'VendorExtension'):
            with self.subTest(kind=kind), self.document(kind) as pdf:
                report = v.Report('allowed.pdf')
                v.check_dpart_and_content_sets(pdf, None, 'ci', report, set())
                self.assertEqual(['PASS'], levels(report, 'DPM_CONTAINER_CONTENTSET_TYPES'))

    def test_email_content_sets_remain_valid_for_mail_profiles(self):
        for profile in ('s', 'si', 'm', 'mi'):
            with self.subTest(profile=profile), self.document('BodyRendering') as pdf:
                report = v.Report('mail.pdf')
                v.check_dpart_and_content_sets(pdf, None, profile, report, set())
                self.assertNotIn('DPM_CONTAINER_CONTENTSET_TYPES', failed_codes(report))


class EmbeddedMediaAndDateTests(unittest.TestCase):
    def document(self, parameters=None, associated=True, date='D:20261019000000Z', placement='catalog'):
        pdf = pikepdf.Pdf.new()
        page = pdf.add_blank_page()
        data = b'preserved bytes'
        stream = pdf.make_stream(data)
        stream.Type = pikepdf.Name.EmbeddedFile
        stream.Subtype = pikepdf.Name('/application/octet-stream')
        stream.Params = pikepdf.Dictionary(Size=len(data), CheckSum=pikepdf.String(hashlib.md5(data).digest()))
        if date is not None:
            stream.Params.ModDate = date
        if parameters is not None:
            stream.Mail_MediaTypeParameters = parameters
        spec = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec, F='source.bin', UF='source.bin',
            EF=pikepdf.Dictionary(F=stream, UF=stream), AFRelationship=pikepdf.Name.Data))
        pdf.Root.Names = pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Names=pikepdf.Array(['source.bin', spec])))
        if associated:
            if placement == 'catalog':
                pdf.Root.AF = pikepdf.Array([spec])
            elif placement == 'object':
                # Direct dictionaries under a live object are not in pdf.objects.
                pdf.Root.Extra = pikepdf.Dictionary(Nested=pikepdf.Dictionary(AF=pikepdf.Array([spec])))
            elif placement == 'marked':
                page.Resources.Properties = pikepdf.Dictionary(AFResource=pikepdf.Dictionary(MCAF=pikepdf.Array([spec])))
                page.Contents = pdf.make_stream(b'/AF /AFResource BDC EMC')
        return pdf, stream, spec

    def findings(self, pdf):
        report = v.Report('embedded.pdf')
        v.check_embedded_files(pdf, None, 'ci', 4, report)
        return report

    def test_associated_files_require_moddate_at_each_attachment_point(self):
        for placement in ('catalog', 'object', 'marked'):
            pdf, _, _ = self.document(date=None, placement=placement)
            with self.subTest(placement=placement), pdf:
                self.assertIn('ASSOCIATED_FILE_MODDATE', failed_codes(self.findings(pdf)))

    def test_unassociated_embedded_file_is_not_subject_to_moddate_presence(self):
        pdf, _, _ = self.document(associated=False, date=None)
        with pdf:
            self.assertEqual(['N/A'], levels(self.findings(pdf), 'ASSOCIATED_FILE_MODDATE'))

    def test_associated_moddate_is_a_pdf_date_string(self):
        for date, valid in (('D:2026', True), ('D:20260229000000Z', False),
                            (' D:2026', False), (42, False), (pikepdf.Name('/D:2026'), False)):
            pdf, _, _ = self.document(date=date)
            with self.subTest(date=date), pdf:
                self.assertEqual(['PASS' if valid else 'FAIL'], levels(self.findings(pdf), 'ASSOCIATED_FILE_MODDATE'))

    def test_parameters_require_a_stream_text_string(self):
        for parameters in (42, pikepdf.Name('/charset=UTF-8'), pikepdf.Array(['charset=UTF-8'])):
            pdf, _, _ = self.document(parameters=parameters)
            with self.subTest(parameters=parameters), pdf:
                self.assertIn('EMBEDDED_MEDIATYPE_RECONSTRUCTION', failed_codes(self.findings(pdf)))

    def test_params_dictionary_is_not_the_canonical_parameter_location(self):
        pdf, stream, spec = self.document()
        with pdf:
            stream.Params.Mail_MediaTypeParameters = 'charset=UTF-8'
            # An unrelated extension key is not forbidden, but cannot supply
            # the stream-level EA-PDF parameter value.
            self.assertEqual('application/octet-stream;',
                             v.parse_filespec('source.bin', spec)['reconstructed_media_type'])
            self.assertEqual(['PASS'], levels(self.findings(pdf), 'EMBEDDED_MEDIATYPE_RECONSTRUCTION'))

    def test_all_associated_ef_streams_require_moddate(self):
        pdf, _, spec = self.document()
        with pdf:
            alternate = pdf.make_stream(b'alternate platform bytes')
            alternate.Type = pikepdf.Name.EmbeddedFile
            alternate.Subtype = pikepdf.Name('/application/octet-stream')
            spec.EF.F = alternate
            self.assertIn('ASSOCIATED_FILE_MODDATE', failed_codes(self.findings(pdf)))

    def test_media_reconstruction_checks_alternate_ef_streams(self):
        pdf, _, spec = self.document()
        with pdf:
            alternate = pdf.make_stream(b'alternate platform bytes')
            alternate.Type = pikepdf.Name.EmbeddedFile
            alternate.Subtype = pikepdf.Name('/application/octet-stream')
            alternate.Mail_MediaTypeParameters = 17
            alternate.Params = pikepdf.Dictionary(ModDate='D:2026')
            spec.EF.F = alternate
            self.assertIn('EMBEDDED_MEDIATYPE_RECONSTRUCTION', failed_codes(self.findings(pdf)))

    def test_utf16_parameters_and_name_escapes_are_decoded_without_changing_values(self):
        text = 'name="résumé  final.txt"'
        parameters = pikepdf.String(b'\xfe\xff' + text.encode('utf-16be'))
        pdf, stream, spec = self.document(parameters=parameters)
        with pdf:
            stream.Subtype = pikepdf.Object.parse(b'/application#2Foctet-stream')
            info = v.parse_filespec('source.bin', spec)
            self.assertEqual(text, info['media_type_parameters'])
            self.assertEqual('application/octet-stream;' + text, info.get('reconstructed_media_type'))
            self.assertEqual(['PASS'], levels(self.findings(pdf), 'EMBEDDED_MEDIATYPE_RECONSTRUCTION'))

    def test_invalid_utf8_name_is_not_silently_repaired(self):
        pdf, stream, _ = self.document(parameters='name=example')
        with pdf:
            stream.Subtype = pikepdf.Object.parse(b'/application#2Fx-#FF')
            self.assertIn('EMBEDDED_MEDIATYPE_RECONSTRUCTION', failed_codes(self.findings(pdf)))

    def test_source_asset_comparison_uses_reconstructed_type_with_parameters(self):
        parameters = 'name="résumé  final.txt"'
        pdf, _, spec = self.document(parameters=parameters)
        with pdf:
            spec.AFRelationship = pikepdf.Name.Source
            info = v.parse_filespec('source.bin', spec)
            root = ET.Element(v.qn(v.NS_RDF, 'RDF'))
            desc = ET.SubElement(root, v.qn(v.NS_RDF, 'Description'))
            assets = ET.SubElement(desc, v.qn(v.NS_PDFMAILMETA, 'assets'))
            record = ET.SubElement(ET.SubElement(assets, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li'),
                                   {v.qn(v.NS_RDF, 'parseType'): 'Resource'})
            for name, value in dict(Filename='source.bin', SizeInBytes=str(info['size']), Checksum=info['md5'],
                                    ContentType='application/octet-stream;' + parameters).items():
                ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, name)).text = value
            report = v.Report('source.pdf')
            v.check_asset_metadata(root, [info], [info], 's', report)
            self.assertEqual(['PASS'], levels(report, 'ASSET_CONTENTTYPE_MATCH'))

    def test_parameter_values_remain_case_and_whitespace_sensitive(self):
        pdf, _, spec = self.document(parameters='name="A  B"')
        with pdf:
            info = v.parse_filespec('source.bin', spec)
            annotation = {'file': info, 'filename': 'source.bin'}
            for expected, match in (('name="A  B"', True), ('name="a  b"', False), ('name="A B"', False)):
                attachment = {'Filename': 'source.bin', 'SizeInBytes': str(info['size']),
                              'Content-Type': 'application/octet-stream;' + expected}
                with self.subTest(expected=expected):
                    self.assertEqual(match, v.attachment_record_matches_annotation(attachment, annotation))

    def test_media_type_under_test_cannot_select_an_attachment_record(self):
        pdf, _, spec = self.document()
        with pdf:
            info = v.parse_filespec('source.bin', spec)
            records = [{'Filename': 'source.bin', 'Content-Type': content_type}
                       for content_type in ('application/octet-stream', 'text/plain')]
            ambiguous = []
            self.assertIsNone(v.matching_attachment_for_embedded(info, records, ambiguous))
            self.assertEqual(2, ambiguous[0]['candidate_count'])
            self.assertEqual(records[1], v.matching_attachment_for_embedded(info, records[1:]))

    def test_authoritative_catalog_maps_all_six_records_to_executed_checks(self):
        review = load_review_rules()['VALIDATION_INCOMPLETE']['specification_review']
        ids = {'MEDIATYPE-RECONSTRUCTION', 'ASSOCIATED-MODDATE',
               'T8-NO-AttachmentRendering', 'T8-NO-BodyRendering',
               'T8-NO-EmailHeaderRendering', 'T8-NO-RawEmailRendering'}
        for row in review['inventory_records']:
            if row['id'] in ids:
                self.assertTrue(row['candidate_rule_codes'], row['id'])
                for code in row['candidate_rule_codes']:
                    self.assertIn(code, v.ALL_RULE_CODES)
                    self.assertEqual('custom_validator', load_review_rules()[code]['validation_scope']['owner'])

    def test_attachment_xmp_comparison_includes_parameters(self):
        for actual, expected, valid in (('name="A  B"', 'name="A  B"', True),
                                        ('name="A  B"', 'name="A B"', False),
                                        ('name=one', 'name=two', False),
                                        ('CHARSET=utf-8', 'charset=UTF-8', True)):
            pdf, _, spec = self.document(parameters=actual)
            with self.subTest(actual=actual, expected=expected), pdf:
                root = ET.Element(v.qn(v.NS_RDF, 'RDF'))
                desc = ET.SubElement(root, v.qn(v.NS_RDF, 'Description'))
                assets = ET.SubElement(desc, v.qn(v.NS_PDFMAILMETA, 'attachments'))
                record = ET.SubElement(ET.SubElement(assets, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li'),
                                       {v.qn(v.NS_RDF, 'parseType'): 'Resource'})
                ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, 'Filename')).text = 'source.bin'
                ET.SubElement(record, v.qn(v.NS_PDFMAILMETA, 'Content-Type')).text = 'application/octet-stream;' + expected
                report = v.Report('attachments.pdf')
                info = v.parse_filespec('source.bin', spec)
                # This is a native attachment of a mail PDF, not a collection member.
                v.check_additional_rule_coverage(pdf, root, b'', [info], [], 'si', 4, report)
                self.assertEqual(not valid, 'EMBEDDED_MEDIA_TYPE_MATCH_XMP' in failed_codes(report))

    def test_arbitrary_object_af_must_also_be_listed_in_name_tree(self):
        pdf, _, _ = self.document(placement='object')
        with pdf:
            del pdf.Root.Names
            self.assertIn('AF_IN_NAME_TREE', failed_codes(self.findings(pdf)))

    def test_moddate_observation_follows_a_nested_stream_and_indirect_cycle(self):
        pdf, _, spec = self.document(associated=False, date=None)
        with pdf:
            form = pdf.make_stream(b'')
            form.AF = pikepdf.Array([spec])
            form.Owner = pdf.Root
            pdf.Root.Extra = pikepdf.Dictionary(Form=form)
            self.assertIn('ASSOCIATED_FILE_MODDATE', failed_codes(self.findings(pdf)))


class FileRequirementCorpusTests(unittest.TestCase):
    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for independent PDF/A corpus verification')
    def test_frozen_file_requirement_probes(self):
        directory = ROOT / 'tests/fixtures/eapdf/validator-file-requirements'
        manifest = json.loads((directory / 'expectations.json').read_text())
        for case in manifest['cases']:
            path = directory / case['file']
            with self.subTest(file=case['file']):
                self.assertEqual(case['sha256'], hashlib.sha256(path.read_bytes()).hexdigest())
                result = v.validate_one(str(path), write_html=False)
                self.assertEqual('PASS', result['pdfa']['status'])
                self.assertEqual(set(case['expected_failures']),
                                 {r['code'] for r in result['items'] if r['level'] == 'FAIL'})
                self.assertEqual('FAIL' if case['expected_failures'] else 'PASS', result['summary']['status'])


if __name__ == '__main__':
    unittest.main()
