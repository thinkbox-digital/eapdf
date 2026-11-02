"""Supplied optional DPart entries retain the types and hierarchy count in Tables408/409."""
import unittest
from decimal import Decimal
import pikepdf
from test_preservation_safety import validator as v, failed_codes


class DpartOptionalEntryTests(unittest.TestCase):
    def document(self):
        pdf=pikepdf.Pdf.new();page=pdf.add_blank_page()
        root=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name('/DPartRoot')))
        node=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name('/DPart'),Parent=root))
        leaf=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name('/DPart'),Parent=node,Start=page.obj))
        root.DPartRootNode=node;node.DParts=pikepdf.Array([pikepdf.Array([leaf])]);pdf.Root.DPartRoot=root
        return pdf,root,node,leaf
    def check(self,pdf):
        root,node,nodes=v.get_dpart_nodes(pdf);report=v.Report('dpart-optional.pdf')
        v.check_dpart_object_structure(root,node,nodes,report);return report

    def test_optional_entries_may_be_omitted(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            for item in (root,node,leaf):del item.Type
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_canonical_name_types_and_matching_node_name_count_pass(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            root.RecordLevel=1;root.NodeNameList=pikepdf.Array([pikepdf.Name('/Root'),pikepdf.Name('/Content')])
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_supplied_type_values_are_pdf_names_with_exact_keywords(self):
        for target in ('root','node','leaf'):
            for wrong in ('text','name'):
                with self.subTest(target=target,wrong=wrong):
                    pdf,root,node,leaf=self.document()
                    with pdf:
                        item={'root':root,'node':node,'leaf':leaf}[target]
                        expected='DPartRoot' if target=='root' else 'DPart'
                        item.Type=pikepdf.String(expected) if wrong=='text' else pikepdf.Name('/Other')
                        self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_record_level_requires_an_actual_integer(self):
        for value in (True,False,pikepdf.String('1'),Decimal('1.0')):
            with self.subTest(value=value):
                pdf,root,node,leaf=self.document()
                with pdf:
                    root.RecordLevel=value
                    self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_name_list_requires_an_array_of_names(self):
        for value in (pikepdf.String('Root Content'),pikepdf.Dictionary(),pikepdf.Array(['Root','Content'])):
            with self.subTest(value=value):
                pdf,root,node,leaf=self.document()
                with pdf:
                    root.NodeNameList=value
                    self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_name_list_length_matches_levels_not_number_of_nodes(self):
        for count in (0,1,3):
            with self.subTest(count=count):
                pdf,root,node,leaf=self.document()
                with pdf:
                    root.NodeNameList=pikepdf.Array([pikepdf.Name('/Level'+str(i)) for i in range(count)])
                    self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))
        pdf,root,node,leaf=self.document()
        with pdf:
            other=pdf.make_indirect(pikepdf.Dictionary(Parent=node,Start=pdf.add_blank_page().obj))
            node.DParts[0].append(other)
            root.NodeNameList=pikepdf.Array([pikepdf.Name('/Root'),pikepdf.Name('/Content')])
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_unicode_pdf_names_are_not_restricted_to_ascii(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            root.NodeNameList=pikepdf.Array([pikepdf.Name('/根'),pikepdf.Name('/部分')])
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_invalid_unicode_type_names_remain_reportable(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            root.Type=pikepdf.Object.parse(b'/#ff')
            report=self.check(pdf)
            self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(report))
            self.assertIn('raw_hex',str(report.items))

    def test_definitely_invalid_xml_token_characters_fail(self):
        for name in ('bad space','bad/name','name&',''):
            with self.subTest(name=name):
                pdf,root,node,leaf=self.document()
                with pdf:
                    root.NodeNameList=pikepdf.Array([pikepdf.Name('/'+name),pikepdf.Name('/Content')])
                    self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_unresolved_token_or_decoding_evidence_does_not_become_pass(self):
        for name in (pikepdf.Name('/1Level'),pikepdf.Object.parse(b'/#ff')):
            with self.subTest(name_bytes=bytes(name)):
                pdf,root,node,leaf=self.document()
                with pdf:
                    root.NodeNameList=pikepdf.Array([name,pikepdf.Name('/Content')])
                    report=self.check(pdf)
                    self.assertNotIn('DPART_OBJECT_STRUCTURE',failed_codes(report))
                    self.assertEqual('INCOMPLETE',report.summary()['status'])

if __name__=='__main__':unittest.main()
