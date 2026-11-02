"""EA-PDF Table 15 simple values; generic RDF syntax remains upstream."""
import os
import unittest
from xml.etree import ElementTree as ET

from test_preservation_safety import validator as v


class IdentificationSchemaTests(unittest.TestCase):
    def check(self, field='version', value='1', kind='text'):
        root = ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:id="{v.NS_PDFMAILID}">'
            '<rdf:Description id:version="1" id:rev="2024" id:conformance="si"/></rdf:RDF>')
        description = root[0]
        del description.attrib[v.qn(v.NS_PDFMAILID, field)]
        node = ET.SubElement(description, v.qn(v.NS_PDFMAILID, field))
        if kind == 'uri':
            node.set(v.qn(v.NS_RDF, 'resource'), value)
        elif kind == 'array':
            ET.SubElement(ET.SubElement(node, v.qn(v.NS_RDF, 'Bag')), v.qn(v.NS_RDF, 'li')).text = value
        elif kind == 'qualified':
            node.set(v.qn(v.NS_RDF, 'parseType'), 'Resource')
            ET.SubElement(node, v.qn(v.NS_RDF, 'value')).text = value
        else:
            node.text = value
        report = v.Report('identification.pdf')
        v.check_eapdf_xmp(root, {}, report)
        return {r['code']: r['level'] for r in report.items}

    def test_integer_values_accept_legal_signs_zero_padding_and_xml_whitespace(self):
        for field, code, value in (('version', 'EAPDF_VERSION', '+0001'), ('version', 'EAPDF_VERSION', ' 1 '),
                                    ('rev', 'EAPDF_REV', '+02024')):
            with self.subTest(field=field, value=value):
                self.assertEqual('PASS', self.check(field, value)[code])

    def test_uri_or_array_cannot_supply_integer_or_text_simple_values(self):
        for field, code, value in (('version', 'EAPDF_VERSION', '1'), ('rev', 'EAPDF_REV', '2024'),
                                   ('conformance', 'EAPDF_CONFORMANCE_VALUE', 'si')):
            for kind in ('uri', 'array'):
                with self.subTest(field=field, kind=kind):
                    self.assertEqual('FAIL', self.check(field, value, kind)[code])

    def test_qualified_simple_values_keep_their_semantic_types(self):
        for field, code, value in (('version', 'EAPDF_VERSION', '+001'), ('rev', 'EAPDF_REV', '002024'),
                                   ('conformance', 'EAPDF_CONFORMANCE_VALUE', 'si')):
            self.assertEqual('PASS', self.check(field, value, 'qualified')[code])

    def test_conformance_text_is_exact_and_integer_syntax_is_not_python_coercion(self):
        for value in (' si ', 'SI', 'is', 'sii'):
            self.assertEqual('FAIL', self.check('conformance', value)['EAPDF_CONFORMANCE_VALUE'])
        for value in ('1.0', '１', '-1', '1_0', '\u00a01\u00a0'):
            self.assertEqual('FAIL', self.check('version', value)['EAPDF_VERSION'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),
                         'Set EAPDF_GENERATOR_CLASSPATH for independent identification corpus checks')
    def test_frozen_identification_pdf_corpus(self):
        import hashlib
        import json
        from test_preservation_safety import ROOT
        root = ROOT / 'tests/fixtures/eapdf/validator-identification'
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
