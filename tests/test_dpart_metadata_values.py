"""Inherited DPM data types, key spelling, name UTF8 and corrected DPart Metadata policy."""
from decimal import Decimal
import unittest
from unittest.mock import patch
import pikepdf
from test_preservation_safety import validator as v, failed_codes
import test_dpart_optional_entry_types as fixtures


class DpartMetadataValueTests(unittest.TestCase):
    def document(self):
        pdf,root,node,leaf=fixtures.DpartOptionalEntryTests().document()
        leaf.DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.BodyRendering,Mail_Subtype=pikepdf.Name('/text/plain'))
        return pdf,root,node,leaf
    def check(self,pdf):
        root,node,nodes=v.get_dpart_nodes(pdf);report=v.Report('dpm-data.pdf')
        v.check_dpart_object_structure(root,node,nodes,report);return report

    def test_record_level_must_identify_an_actual_level(self):
        for value,invalid in [(-1,True),(2,True),(100,True),(0,False),(1,False)]:
            with self.subTest(value=value):
                pdf,root,node,leaf=self.document()
                with pdf:
                    root.RecordLevel=value
                    self.assertEqual(invalid,'DPART_OBJECT_STRUCTURE' in failed_codes(self.check(pdf)))

    def test_corrected_metadata_entry_does_not_carry_streams_or_other_values(self):
        for stream in (True,False):
            pdf,root,node,leaf=self.document()
            with pdf:
                leaf.Metadata=pdf.make_stream(b'<metadata/>') if stream else pikepdf.String('metadata')
                self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_dpm_stream_values_fail_at_every_depth(self):
        for where in ('direct','dictionary','array'):
            pdf,root,node,leaf=self.document()
            with pdf:
                stream=pdf.make_stream(b'policy')
                leaf.DPM.VendorPolicy=stream if where=='direct' else pikepdf.Dictionary(Data=stream) if where=='dictionary' else pikepdf.Array([stream])
                self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_null_array_members_are_not_permitted_dpm_values(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            leaf.DPM.VendorPolicy=pikepdf.Array([None])
            self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_dictionary_null_values_are_omitted_even_when_the_key_would_not_be_a_token(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            leaf.DPM.VendorPolicy=pikepdf.Object.parse(b'<< /bad#20key null /Enabled true >>')
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_all_name_values_require_utf8_including_unknown_and_nested_fields(self):
        for where in ('direct','dictionary','array'):
            pdf,root,node,leaf=self.document()
            with pdf:
                bad=pikepdf.Object.parse(b'/#ff')
                leaf.DPM.VendorPolicy=bad if where=='direct' else pikepdf.Dictionary(Flag=bad) if where=='dictionary' else pikepdf.Array([bad])
                self.assertIn('DPM_NAME_TYPES',failed_codes(self.check(pdf)))

    def test_name_values_need_not_be_xml_tokens(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            leaf.DPM.VendorPolicy=pikepdf.Name('/flag value/中文')
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_dictionary_keys_are_checked_recursively(self):
        for nested in (False,True):
            pdf,root,node,leaf=self.document()
            with pdf:
                target=pikepdf.Dictionary() if nested else leaf.DPM
                target['/bad key']='value'
                if nested:leaf.DPM.VendorPolicy=target
                self.assertIn('DPART_OBJECT_STRUCTURE',failed_codes(self.check(pdf)))

    def test_unresolved_key_spelling_stays_incomplete(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            leaf.DPM['/1Vendor']='value'
            report=self.check(pdf)
            self.assertFalse(failed_codes(report))
            self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_shared_and_cyclic_data_dictionaries_are_not_invented_tree_violations(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            shared=pdf.make_indirect(pikepdf.Dictionary(Enabled=True));shared.Self=shared
            leaf.DPM.VendorA=shared;leaf.DPM.VendorB=shared
            self.assertFalse(failed_codes(self.check(pdf)))

    def test_value_traversal_limits_remain_incomplete(self):
        for setting,value in [('MAX_TREE_ITEMS',5),('MAX_TREE_DEPTH',1)]:
            pdf,root,node,leaf=self.document()
            with pdf,patch.object(v,setting,value):
                leaf.DPM.VendorPolicy=pikepdf.Dictionary(Data=pikepdf.Array(list(range(20))))
                report=self.check(pdf)
                self.assertFalse(failed_codes(report))
                self.assertEqual('INCOMPLETE',report.summary()['status'])

    def test_permitted_extension_values_preserve_their_types_and_names(self):
        pdf,root,node,leaf=self.document()
        with pdf:
            leaf.DPM.VendorPolicy=pikepdf.Array([True,42,Decimal('1.25'),pikepdf.String('policy'),pikepdf.Name('/flag value'),pikepdf.Dictionary(Enabled=False)])
            self.assertFalse(failed_codes(self.check(pdf)))

if __name__=='__main__':unittest.main()
