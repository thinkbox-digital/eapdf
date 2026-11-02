"""EA Table12 value restrictions apply to EA mail fields; generic V remains any."""
import tempfile
import unittest
from decimal import Decimal
from pathlib import Path

import pikepdf
from test_preservation_safety import ROOT, validator as v


class GenericUserPropertyScopeTests(unittest.TestCase):
    def check(self, prop, tag='Mail_Message', stream=False):
        with pikepdf.open(ROOT / 'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
            elements = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
            element = next(e['elem'] for e in elements if e['type'] == tag)
            if stream:
                prop.V = pdf.make_stream(b'vendor metadata')
            element.A.P.append(prop)
            with tempfile.TemporaryDirectory(prefix='.eapdf-generic-property-', dir=ROOT) as directory:
                path = Path(directory) / 'focused.pdf'
                pdf.save(path, static_id=True, fix_metadata_version=False)
                report = v.validate(str(path))
        failures = {i['code'] for i in report.items if i['level'] == 'FAIL'}
        self.assertNotIn('VALIDATION_ERROR', failures)
        return failures, report

    def assert_legal(self, value, name='Vendor_Data', tag='Mail_Message'):
        failures, report = self.check(pikepdf.Dictionary(N=name, V=value), tag)
        self.assertEqual(set(), failures)
        self.assertEqual('PASS', report.summary()['status'])

    def test_vendor_name_value_is_permitted(self):
        self.assert_legal(pikepdf.Name.Example)

    def test_vendor_empty_array_value_is_present_and_permitted(self):
        self.assert_legal(pikepdf.Array())

    def test_vendor_dictionary_value_is_permitted(self):
        self.assert_legal(pikepdf.Dictionary(Label='value'))

    def test_opaque_vendor_name_bytes_do_not_require_text_decoding(self):
        self.assert_legal(pikepdf.Object.parse(b'/Vendor#FF'))

    def test_mail_header_name_value_still_fails_ea_scalar_constraint(self):
        self.assertEqual({'USERPROPS_TYPE'}, self.check(pikepdf.Dictionary(N='Mail_X-Extra', V=pikepdf.Name.Example))[0])

    def test_vendor_unicode_text_remains_permitted(self):
        self.assert_legal('Unicode résumé')

    def test_vendor_missing_value_still_fails_required_entry(self):
        self.assertEqual({'USERPROPS_TYPE'}, self.check(pikepdf.Dictionary(N='Vendor_Data'))[0])

    def test_parsed_null_value_still_means_absent_required_entry(self):
        prop = pikepdf.Object.parse(b'<< /N (Vendor_Data) /V null >>')
        self.assertIsNone(v.dict_get(prop, '/V', None))
        attr = pikepdf.Dictionary(O=pikepdf.Name.UserProperties, P=pikepdf.Array([prop]))
        report = v.Report('parsed-null-property.pdf')
        v.check_user_properties([dict(type='Mail_Message', attrs=attr)], report)
        self.assertTrue(any(i['code'] == 'USERPROPS_TYPE' and i['level'] == 'FAIL' for i in report.items))

    def test_false_boolean_value_is_present(self):
        self.assert_legal(False)

    def test_vendor_real_value_remains_permitted(self):
        self.assert_legal(Decimal('1.5'))

    def test_vendor_stream_value_is_permitted(self):
        failures, report = self.check(pikepdf.Dictionary(N='Vendor_Data', V='placeholder'), stream=True)
        self.assertEqual(set(), failures)
        self.assertEqual('PASS', report.summary()['status'])

    def test_empty_ascii_extension_name_keeps_generic_value_scope(self):
        self.assert_legal(pikepdf.Array(['data']), name='')

    def test_generic_value_scope_does_not_relax_raw_name_encoding(self):
        prop = pikepdf.Dictionary(N=pikepdf.String(b'\xfe\xff' + 'Vendor_Data'.encode('utf-16be')), V=pikepdf.Array(['data']))
        self.assertEqual({'USERPROPS_NAME_ASCII'}, self.check(prop)[0])

    def test_known_integer_field_still_rejects_array_value(self):
        self.assertEqual({'USERPROPS_TYPE'}, self.check(pikepdf.Dictionary(N='Mail_SizeInBytes', V=pikepdf.Array()))[0])

    def test_known_string_field_still_rejects_name_value(self):
        self.assertEqual({'USERPROPS_TYPE'}, self.check(pikepdf.Dictionary(N='Mail_Subject', V=pikepdf.Name.Example))[0])

    def test_mail_header_tag_scope_is_preserved(self):
        prop = pikepdf.Dictionary(N='Mail_X-Extra', V='text')
        self.assertEqual({'USERPROPS_TAG_SCOPE'}, self.check(prop, 'Mail_ContentSet')[0])

    def test_vendor_metadata_is_permitted_on_a_content_set(self):
        self.assert_legal(pikepdf.Dictionary(Label='value'), tag='Mail_ContentSet')


if __name__ == '__main__':
    unittest.main()
