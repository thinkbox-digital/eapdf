"""The title recommendation concerns the x-default item, including empty Text."""
import unittest
from xml.etree import ElementTree as ET
from test_preservation_safety import validator as v


class DefaultTitleTests(unittest.TestCase):
    def metadata(self, value):
        return ET.fromstring(f'<rdf:RDF xmlns:rdf="{v.NS_RDF}" xmlns:dc="{v.NS_DC}" xmlns:ex="urn:example:qualifier"><rdf:Description>{value}</rdf:Description></rdf:RDF>')

    def title(self, value):
        report = v.Report('title.pdf')
        v.check_eapdf_xmp(self.metadata(value), {}, report)
        return next(i for i in report.items if i['code'] == 'XMP_TITLE')

    def alt(self, items):
        return '<dc:title><rdf:Alt>' + items + '</rdf:Alt></dc:title>'

    def item(self, language, value):
        return f'<rdf:li xml:lang="{language}">{value}</rdf:li>'

    def test_default_value_is_selected_by_language(self):
        default = self.item('x-default', 'Default title')
        localized = self.item('fr', 'Titre')
        for items in (default + localized, localized + default):
            with self.subTest(items=items):
                row = self.title(self.alt(items))
                self.assertEqual('PASS', row['level'])
                self.assertEqual('Default title', row['details']['value'])

    def test_localized_text_does_not_satisfy_the_default_recommendation(self):
        self.assertEqual('WARN', self.title(self.alt(self.item('fr', 'Titre')))['level'])

    def test_empty_text_default_is_still_a_set_text_property(self):
        row = self.title(self.alt(self.item('x-default', '') + self.item('fr', 'Titre')))
        self.assertEqual('PASS', row['level'])
        self.assertEqual('', row['details']['value'])

    def test_plain_text_uri_and_wrong_array_kinds_do_not_supply_a_default(self):
        for value in ('<dc:title>Default title</dc:title>',
                      '<dc:title rdf:resource="urn:title"/>',
                      '<dc:title><rdf:Seq>' + self.item('x-default', 'Title') + '</rdf:Seq></dc:title>',
                      self.alt('<rdf:li xml:lang="x-default" rdf:resource="urn:title"/>')):
            with self.subTest(value=value):
                self.assertEqual('WARN', self.title(value)['level'])

    def test_missing_title_is_a_recommendation_warning(self):
        self.assertEqual('WARN', self.title('')['level'])

    def test_qualified_title_and_qualified_text_are_read_without_losing_language(self):
        value = '<dc:title rdf:parseType="Resource"><rdf:value><rdf:Alt><rdf:li rdf:parseType="Resource"><rdf:value xml:lang="x-default">Title</rdf:value><ex:note>Qualifier</ex:note></rdf:li></rdf:Alt></rdf:value><ex:note>Property qualifier</ex:note></dc:title>'
        row = self.title(value)
        self.assertEqual('PASS', row['level'])
        self.assertEqual('Title', row['details']['value'])

    def test_duplicate_defaults_remain_ambiguous(self):
        row = self.title(self.alt(self.item('x-default', 'One') + self.item('x-default', 'Two')))
        self.assertEqual('WARN', row['level'])
        self.assertEqual(['One', 'Two'], row['details']['default_values'])

    def test_language_qualifiers_follow_xml_inheritance_and_overrides(self):
        row = self.title('<dc:title xml:lang="x-default"><rdf:Alt><rdf:li>Default title</rdf:li>' + self.item('fr', 'Titre') + '</rdf:Alt></dc:title>')
        self.assertEqual('PASS', row['level'])
        self.assertEqual('Default title', row['details']['value'])


if __name__ == '__main__':
    unittest.main()
