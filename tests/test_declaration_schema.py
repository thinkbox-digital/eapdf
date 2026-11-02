"""PDF Declarations 2019 sections 7/8, distinct from the registry identifier."""
import unittest

from test_preservation_safety import validator as v


DECLARATIONS = 'http://pdfa.org/declarations/'
IDENTIFIER = 'https://pdfa.org/declarations#iso32005'


def packet(content, namespace=DECLARATIONS):
    return (f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:d="{namespace}">'
            f'<rdf:Description>{content}</rdf:Description></rdf:RDF>').encode()


def declaration(record=None, kind='Bag'):
    if record is None:
        record = f'<rdf:li rdf:parseType="Resource"><d:conformsTo>{IDENTIFIER}</d:conformsTo></rdf:li>'
    return f'<d:declarations><rdf:{kind}>{record}</rdf:{kind}></d:declarations>'


class DeclarationSchemaTests(unittest.TestCase):
    def test_canonical_namespace_and_registry_identifier_remain_distinct(self):
        self.assertTrue(v.has_ts32005_declaration(packet(declaration())))
        self.assertFalse(v.has_ts32005_declaration(packet(declaration(), 'https://pdfa.org/declarations/')))
        self.assertFalse(v.has_ts32005_declaration(packet(declaration().replace(IDENTIFIER, IDENTIFIER + '-extra'))))

    def test_equivalent_structured_and_uri_forms(self):
        for record in (f'<rdf:li d:conformsTo="{IDENTIFIER}"/>',
                       f'<rdf:li><rdf:Description d:conformsTo="{IDENTIFIER}"/></rdf:li>',
                       f'<rdf:li rdf:parseType="Resource"><d:conformsTo rdf:resource="{IDENTIFIER}"/></rdf:li>',
                       f'<rdf:li rdf:parseType="Resource"><d:conformsTo rdf:parseType="Resource"><rdf:value rdf:resource="{IDENTIFIER}"/><d:qualifier>value</d:qualifier></d:conformsTo></rdf:li>'):
            with self.subTest(record=record):
                self.assertTrue(v.has_ts32005_declaration(packet(declaration(record))))

    def test_qualified_declarations_array(self):
        value = declaration().replace('<d:declarations>', '').replace('</d:declarations>', '')
        content = '<d:declarations rdf:parseType="Resource"><rdf:value>' + value + '</rdf:value></d:declarations>'
        self.assertTrue(v.has_ts32005_declaration(packet(content)))

    def test_wrong_array_kind_scalar_and_nested_foreign_property_cannot_supply_claim(self):
        for content in (declaration(kind='Seq'), '<d:declarations>' + IDENTIFIER + '</d:declarations>',
                        '<other xmlns="urn:example:foreign">' + declaration() + '</other>',
                        declaration('<rdf:li>' + IDENTIFIER + '</rdf:li>')):
            with self.subTest(content=content):
                self.assertFalse(v.has_ts32005_declaration(packet(content)))

    def test_optional_claim_data_is_not_required_but_supplied_shape_is_checked(self):
        valid = f'<rdf:li rdf:parseType="Resource"><d:conformsTo>{IDENTIFIER}</d:conformsTo>'
        self.assertTrue(v.has_ts32005_declaration(packet(declaration())))
        for claim in ('<d:claimData><rdf:Bag/></d:claimData>',
                      '<d:claimData><rdf:Bag><rdf:li d:claimBy="Example" d:claimDate="2026"/></rdf:Bag></d:claimData>'):
            self.assertTrue(v.has_ts32005_declaration(packet(declaration(valid + claim + '</rdf:li>'))))
        for claim in ('<d:claimData>invalid scalar</d:claimData>',
                      '<d:claimData><rdf:Seq/></d:claimData>',
                      '<d:claimData><rdf:Bag><rdf:li>invalid scalar</rdf:li></rdf:Bag></d:claimData>'):
            self.assertFalse(v.has_ts32005_declaration(packet(declaration(valid + claim + '</rdf:li>'))))

    def test_supplied_claim_fields_keep_their_declared_types_and_date_precision(self):
        def claimed(field):
            return packet(declaration(f'<rdf:li rdf:parseType="Resource"><d:conformsTo>{IDENTIFIER}</d:conformsTo>'
                '<d:claimData><rdf:Bag><rdf:li rdf:parseType="Resource">' + field
                + '</rdf:li></rdf:Bag></d:claimData></rdf:li>'))
        for date in ('2026', '2026-10', '2026-10-19', '2024-02-29',
                     '2026-10-19T12:30', '2026-10-19T12:30:01.5Z', '2026-10-19T12:30-04:00'):
            with self.subTest(date=date):
                self.assertTrue(v.has_ts32005_declaration(claimed('<d:claimDate>' + date + '</d:claimDate>')))
        for field in ('<d:claimDate>2026-02-29</d:claimDate>', '<d:claimDate>2026-13</d:claimDate>',
                      '<d:claimDate>2026-10-19T24:01Z</d:claimDate>',
                      '<d:claimBy rdf:resource="urn:author"/>',
                      '<d:claimCredentials><rdf:Bag/></d:claimCredentials>',
                      '<d:claimReport>not a URI</d:claimReport>'):
            with self.subTest(field=field):
                self.assertFalse(v.has_ts32005_declaration(claimed(field)))


if __name__ == '__main__':
    unittest.main()
