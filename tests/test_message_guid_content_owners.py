"""Message GUID consistency follows actual content, never the value being tested."""
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf
from test_preservation_safety import ROOT, validator as v, email_metadata


CODE = 'MAIL_MESSAGE_GUID_MATCH_DPM_XMP'


class MessageGuidContentOwnerTests(unittest.TestCase):
    def test_ordinary_archives_complete_ownership_with_default_limits(self):
        for profile in ('m', 'mi'):
            with self.subTest(profile=profile), pikepdf.open(ROOT / 'examples' / 'pst' / ('pst_quality-attached-message_1' + profile + '.pdf')) as pdf:
                elements = []
                v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
                targets = [item for item in elements if item['type'] in {'Mail_Message', 'Mail_ContentGroup', 'Mail_ContentSet'}]
                rows = v.structure_content_dpm_assessments(pdf, targets, v.get_dpart_nodes(pdf)[2])
                self.assertTrue(rows)
                self.assertTrue(all(row['references'] and not row['unavailable'] for row in rows))

    def document(self):
        pdf = pikepdf.Pdf.new()
        pages = [pdf.add_blank_page() for _ in range(2)]
        root = pdf.make_indirect(pikepdf.Dictionary())
        top = pdf.make_indirect(pikepdf.Dictionary(Parent=root))
        pdf.Root.DPartRoot = root
        root.DPartRootNode = top
        structure = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        document = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem, S=pikepdf.Name.Document, P=structure))
        structure.K = document
        pdf.Root.StructTreeRoot = structure
        messages, dparts, paragraphs = [], [], []
        for index, (page, guid) in enumerate(zip(pages, ['one', 'two'])):
            page.Contents = pdf.make_stream(b'/P << /MCID 0 >> BDC 0 0 m 1 1 l S EMC')
            message_part = pdf.make_indirect(pikepdf.Dictionary(Parent=top, DPM=pikepdf.Dictionary(Mail_GUID=guid)))
            leaf = pdf.make_indirect(pikepdf.Dictionary(Parent=message_part, Start=page.obj,
                DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.Other, Mail_Desc='Content')))
            message_part.DParts = pikepdf.Array([pikepdf.Array([leaf])])
            page.obj.DPart = leaf
            message = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name('/Mail_Message'), P=document,
                A=pikepdf.Dictionary(O=pikepdf.Name.UserProperties,
                    P=pikepdf.Array([pikepdf.Dictionary(N='Mail_GUID', V=guid)]))))
            paragraph = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.P, P=message, Pg=page.obj, K=0))
            message.K = paragraph
            messages.append(message)
            dparts.append(message_part)
            paragraphs.append(paragraph)
        top.DParts = pikepdf.Array([pikepdf.Array(dparts)])
        document.K = pikepdf.Array(messages)
        return pdf, messages, dparts, paragraphs

    def check(self, pdf):
        xroot = email_metadata([{'Mail_GUID': 'one'}, {'Mail_GUID': 'two'}])
        report = v.Report('owner-focused.pdf')
        v.check_additional_rule_coverage(pdf, xroot, ET.tostring(xroot), [], [], 'm', 4, report)
        findings = [item for item in report.items if item['code'] == CODE]
        self.assertEqual(1, len(findings))
        return findings[0], report

    def assert_unavailable(self, pdf):
        item, report = self.check(pdf)
        self.assertEqual('WARN', item['level'])
        self.assertTrue(any(item['code'] == 'VALIDATION_INCOMPLETE' and item['level'] == 'WARN'
                            for item in report.items))

    def test_each_attribute_matches_its_connected_content_owner(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_empty_table_cell_does_not_invent_an_unsupported_content_reference(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            empty_cell = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.TD, P=messages[0]))
            messages[0].K = pikepdf.Array([paragraphs[0], empty_cell])
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_swapping_attributes_does_not_match_other_messages_global_guids(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            messages[0].A.P[0].V = 'two'
            messages[1].A.P[0].V = 'one'
            self.assertEqual('FAIL', self.check(pdf)[0]['level'])

    def test_reusing_another_messages_guid_does_not_establish_ownership(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            messages[1].A.P[0].V = 'one'
            self.assertEqual('FAIL', self.check(pdf)[0]['level'])

    def test_structure_order_is_not_an_owner_assignment(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            messages[0].P.K = pikepdf.Array(list(reversed(messages)))
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_absent_owner_does_not_hide_a_globally_missing_guid(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            del dparts[1].DPM.Mail_GUID
            item, report = self.check(pdf)
            self.assertEqual('FAIL', item['level'])
            self.assertTrue(item['details']['unavailable'])

    def test_a_missing_content_connection_does_not_certify_global_membership(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            messages[1].K = pikepdf.Array()
            self.assert_unavailable(pdf)

    def test_an_unresolved_mcid_does_not_certify_the_declared_page(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            paragraphs[1].K = 99
            self.assert_unavailable(pdf)

    def test_actual_content_spanning_another_message_contradicts_the_attribute(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            paragraphs[0].Pg = pdf.pages[1].obj
            self.assertEqual('FAIL', self.check(pdf)[0]['level'])

    def test_a_page_hint_without_content_is_not_an_owner_connection(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            messages[1].Pg = pdf.pages[1].obj
            messages[1].K = pikepdf.Array()
            self.assert_unavailable(pdf)

    def test_ambiguous_overlapping_page_owners_are_not_selected_by_attribute(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            dparts[0].DParts[0][0].End = pdf.pages[1].obj
            self.assert_unavailable(pdf)

    def test_known_contradiction_survives_a_separate_unavailable_message(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            messages[0].A.P[0].V = 'two'
            messages[1].K = pikepdf.Array()
            self.assertEqual('FAIL', self.check(pdf)[0]['level'])

    def test_bounded_work_cannot_become_a_complete_guid_match(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf, patch.object(v, 'MAX_TREE_ITEMS', 8):
            elements = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elements)
            _, _, nodes = v.get_dpart_nodes(pdf)
            rows = v.structure_content_dpm_assessments(pdf,
                [item for item in elements if item['type'] == 'Mail_Message'], nodes)
            self.assertTrue(all(row['unavailable'] for row in rows))

    def shared_form(self, pdf, paragraphs):
        form = pdf.make_stream(b'/P << /MCID 0 >> BDC 0 0 m 1 1 l S EMC')
        form.Type = pikepdf.Name.XObject
        form.Subtype = pikepdf.Name.Form
        form.BBox = pikepdf.Array([0, 0, 10, 10])
        form.Resources = pikepdf.Dictionary()
        for page in pdf.pages:
            page.Resources.XObject = pikepdf.Dictionary(Shared=form)
        pdf.pages[0].Contents = pdf.make_stream(b'/Shared Do')
        pdf.pages[1].Contents = pdf.make_stream(b'/Shared Do /P << /MCID 1 >> BDC 0 0 m 1 1 l S EMC')
        del paragraphs[0].Pg
        paragraphs[0].K = pikepdf.Dictionary(Type=pikepdf.Name.MCR, Stm=form, MCID=0)
        paragraphs[1].K = 1

    def test_shared_form_owner_is_not_chosen_by_the_attribute_under_test(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            self.shared_form(pdf, paragraphs)
            self.assert_unavailable(pdf)

    def test_explicit_form_page_resolves_the_actual_invocation(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            self.shared_form(pdf, paragraphs)
            paragraphs[0].Pg = pdf.pages[0].obj
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_uniform_form_candidates_can_match_without_selecting_an_invocation(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            self.shared_form(pdf, paragraphs)
            dparts[1].DPM.Mail_GUID = 'one'
            messages[1].A.P[0].V = 'one'
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_annotation_content_connection_resolves_its_actual_page(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            annotation = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot,
                Subtype=pikepdf.Name.Link, Rect=pikepdf.Array([0, 0, 10, 10])))
            pdf.pages[0].obj.Annots = pikepdf.Array([annotation])
            paragraphs[0].K = pikepdf.Dictionary(Type=pikepdf.Name.OBJR, Obj=annotation)
            self.assertEqual('PASS', self.check(pdf)[0]['level'])

    def test_annotation_page_hint_cannot_override_its_actual_location(self):
        pdf, messages, dparts, paragraphs = self.document()
        with pdf:
            annotation = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Annot,
                Subtype=pikepdf.Name.Link, Rect=pikepdf.Array([0, 0, 10, 10])))
            pdf.pages[0].obj.Annots = pikepdf.Array([annotation])
            paragraphs[0].K = pikepdf.Dictionary(Type=pikepdf.Name.OBJR, Obj=annotation, Pg=pdf.pages[1].obj)
            self.assert_unavailable(pdf)


if __name__ == '__main__':
    unittest.main()
