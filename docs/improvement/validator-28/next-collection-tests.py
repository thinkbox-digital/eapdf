"""EA-PDF 9.5 and ISO 32000-2:2020 pp454-455 naming evidence."""
import unittest

import pikepdf

from test_preservation_safety import validator as v


class CollectionNameTests(unittest.TestCase):
    def document(self, file_names=(), folder_names=(), root_id=0, shared_spec=False):
        pdf = pikepdf.Pdf.new()
        pdf.add_blank_page()
        root = pdf.make_indirect(pikepdf.Dictionary(ID=root_id, Name='Collection'))
        pdf.Root.Collection = pikepdf.Dictionary(Folders=root)
        previous = None
        for index, name in enumerate(folder_names, 1):
            child = pdf.make_indirect(pikepdf.Dictionary(ID=root_id + index, Name=name, Parent=root))
            if previous is None:
                root.Child = child
            else:
                previous.Next = child
            previous = child
        stream = pdf.make_stream(b'payload')
        stream.Subtype = pikepdf.Name('/text/plain')
        entries = pikepdf.Array()
        shared = pdf.make_indirect(pikepdf.Dictionary(F='native.txt', UF='native.txt', EF=pikepdf.Dictionary(F=stream)))
        for name in file_names:
            spec = shared if shared_spec else pdf.make_indirect(pikepdf.Dictionary(shared))
            entries.extend([name, spec])
        pdf.Root.Names = pikepdf.Dictionary(EmbeddedFiles=pikepdf.Dictionary(Names=entries))
        return pdf

    def rows(self, pdf):
        report = v.Report('collection-names.pdf')
        v.check_additional_rule_coverage(pdf, None, b'', v.get_embedded_files(pdf), [], 'ci', 4, report)
        return {item['code']: item for item in report.items if item['code'].startswith('COLLECTION_FILENAMES_')}

    def test_distinct_whitespace_folder_names_are_not_aliases(self):
        for names in [('a  b', 'A B'), (' report', 'report '), ('a\u00a0b', 'A B')]:
            with self.subTest(names=names), self.document(folder_names=names) as pdf:
                self.assertEqual('PASS', self.rows(pdf)['COLLECTION_FILENAMES_UNIQUE_CASEFOLD']['level'])

    def test_identical_and_case_only_sibling_names_fail(self):
        for names in [('same', 'same'), ('A', 'a'), ('Straße', 'STRASSE')]:
            with self.subTest(names=names), self.document(folder_names=names) as pdf:
                self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_UNIQUE_CASEFOLD']['level'])

    def test_whitespace_is_valid_filename_data(self):
        with self.document(file_names=[' '], folder_names=[' ']) as pdf:
            row = self.rows(pdf)['COLLECTION_FILENAMES_VALID']
            self.assertEqual('PASS', row['level'])
            self.assertEqual(1, row['details']['files'])
            self.assertEqual(' ', row['details']['file_names'][0]['name'])

    def test_empty_filename_cannot_be_omitted_from_validation(self):
        with self.document(file_names=['']) as pdf:
            self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_VALID']['level'])

    def test_filename_character_length_is_measured_before_cleanup(self):
        for name, level in [('a' * 255, 'PASS'), ('a' * 256, 'FAIL'), ('a' + ' ' * 255, 'FAIL')]:
            with self.subTest(name=name), self.document(file_names=[name]) as pdf:
                self.assertEqual(level, self.rows(pdf)['COLLECTION_FILENAMES_VALID']['level'])

    def test_distinct_name_tree_aliases_of_same_spec_are_all_checked(self):
        for names, level in [(['A.pdf', 'a.pdf'], 'FAIL'), (['a  b.pdf', 'A B.pdf'], 'PASS')]:
            with self.subTest(names=names), self.document(file_names=names, shared_spec=True) as pdf:
                rows = self.rows(pdf)
                self.assertEqual(level, rows['COLLECTION_FILENAMES_UNIQUE_CASEFOLD']['level'])
                self.assertEqual(2, rows['COLLECTION_FILENAMES_VALID']['details']['files'])

    def test_implicit_root_and_explicit_root_share_naming_scope(self):
        with self.document(file_names=['A.pdf', '<7>a.pdf'], root_id=7) as pdf:
            self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_UNIQUE_CASEFOLD']['level'])

    def test_different_folders_allow_equal_names(self):
        with self.document(file_names=['<1>a.pdf', '<2>A.pdf'], folder_names=['one', 'two']) as pdf:
            self.assertEqual('PASS', self.rows(pdf)['COLLECTION_FILENAMES_UNIQUE_CASEFOLD']['level'])

    def test_linefeed_in_filename_does_not_hide_folder_prefix(self):
        with self.document(file_names=['<1>a\nb.pdf'], folder_names=['one']) as pdf:
            row = self.rows(pdf)['COLLECTION_FILENAMES_VALID']
            self.assertEqual('PASS', row['level'])
            self.assertEqual('a\nb.pdf', row['details']['file_names'][0]['name'])

    def test_unicode_digits_are_not_pdf_folder_prefix_digits(self):
        with self.document(file_names=['<١>a.pdf'], folder_names=['one']) as pdf:
            self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_VALID']['level'])

    def test_folder_name_requires_pdf_text_string(self):
        for value in (123, pikepdf.Name.Folder, pikepdf.Array(['folder'])):
            with self.subTest(value=value), self.document(folder_names=[value]) as pdf:
                self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_VALID']['level'])

    def test_non_text_name_tree_key_is_not_a_filename(self):
        with self.document(file_names=[123]) as pdf:
            self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_VALID']['level'])

    def test_forbidden_characters_and_final_period_fail(self):
        for name in ['a\x00b', 'a/b', 'a\\b', 'a:b', 'a*b', 'a"b', 'a<b', 'a>b', 'a|b', 'a.']:
            with self.subTest(name=name), self.document(file_names=[name]) as pdf:
                self.assertEqual('FAIL', self.rows(pdf)['COLLECTION_FILENAMES_VALID']['level'])


if __name__ == '__main__':
    unittest.main()
