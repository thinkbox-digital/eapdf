"""Every declared Content Set needs actual custom-tag coverage, beyond a count."""
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf
import test_content_group_owner_types as fixtures
from test_preservation_safety import validator as v, email_metadata

CODE = 'CONTENT_SET_MAIL_CONTENTSET_TAGGED'


class ContentSetTagCoverageTests(unittest.TestCase):
    def document(self):
        return fixtures.ContentGroupOwnerTypeTests().document(('Other', 'Other'))

    def check(self, pdf, expected):
        root = email_metadata([{'Mail_GUID': 'one'}, {'Mail_GUID': 'two'}])
        report = v.Report('coverage-focused.pdf')
        v.check_additional_rule_coverage(pdf, root, ET.tostring(root), [], [], 'm', 4, report)
        self.assertEqual({expected}, {i['level'] for i in report.items if i['code'] == CODE})
        return report

    def coverage_incomplete(self, report):
        return [i for i in report.items if i['code'] == 'VALIDATION_INCOMPLETE' and i['level'] == 'WARN'
                and CODE in (i.get('details') or {}).get('rules', [])]

    def extra_tag(self, pdf, group, empty=False, page=None):
        node = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name('/Mail_ContentSet'), P=group))
        if page is not None:
            node.Pg = page.obj
        if not empty:
            page = pdf.pages[0]
            page.Contents.write(page.Contents.read_bytes() + b'\n/P << /MCID 1 >> BDC 0 0 m 1 1 l S EMC')
            node.Pg = page.obj
            node.K = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.P, P=node, Pg=page.obj, K=1))
        group.K = pikepdf.Array([group.K, node])
        return node

    def untag_second(self, groups):
        groups[1].K.S = pikepdf.Name.Art

    def test_every_content_set_has_a_verified_tag(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.check(pdf, 'PASS')

    def test_equal_counts_cannot_hide_an_untagged_content_set(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.untag_second(groups)
            self.extra_tag(pdf, groups[0])
            self.check(pdf, 'FAIL')

    def test_additional_tag_for_same_content_set_is_not_a_count_violation(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.extra_tag(pdf, groups[0])
            self.check(pdf, 'PASS')

    def test_fewer_known_tags_leave_a_definite_missing_content_set(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.untag_second(groups)
            self.check(pdf, 'FAIL')

    def test_bare_page_declaration_cannot_certify_content_set_coverage(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.untag_second(groups)
            self.extra_tag(pdf, groups[0], empty=True, page=pdf.pages[1])
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_empty_tag_without_page_remains_unavailable(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.untag_second(groups)
            self.extra_tag(pdf, groups[0], empty=True)
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_descendant_content_overrides_a_wrapper_page_hint(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.untag_second(groups)
            node = self.extra_tag(pdf, groups[0])
            node.Pg = pdf.pages[1].obj
            self.check(pdf, 'FAIL')

    def test_invalid_declared_content_set_range_is_unavailable(self):
        pdf, groups, parts = self.document()
        with pdf:
            parts[1].DParts[0][0].Start = 'not a page'
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_overlapping_ranges_do_not_certify_unique_counterparts(self):
        pdf, groups, parts = self.document()
        with pdf:
            parts[0].DParts[0][0].End = pdf.pages[1].obj
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_contradictory_page_dpart_link_is_unavailable(self):
        pdf, groups, parts = self.document()
        with pdf:
            pdf.pages[0].obj.DPart = parts[1].DParts[0][0]
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_extra_unknown_tag_does_not_erase_verified_coverage(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.extra_tag(pdf, groups[0], empty=True)
            self.assertFalse(self.coverage_incomplete(self.check(pdf, 'PASS')))

    def test_optional_custom_tag_vocabulary_remains_optional(self):
        pdf, groups, parts = self.document()
        with pdf:
            elems = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elems)
            names = {'Mail_Message': 'Document', 'Mail_ContentGroup': 'Part', 'Mail_ContentSet': 'Art'}
            for item in elems:
                if item['type'] in names:
                    item['elem'].S = pikepdf.Name('/' + names[item['type']])
            self.check(pdf, 'N/A')

    def test_missing_dpart_inventory_does_not_make_coverage_inapplicable(self):
        pdf, groups, parts = self.document()
        with pdf:
            del pdf.Root.DPartRoot
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_resource_limit_leaves_coverage_explicitly_unavailable(self):
        pdf, groups, parts = self.document()
        with pdf:
            elems = []
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K, elems)
            sets = [e for e in elems if e['type'] == 'Mail_ContentSet']
            _, _, nodes = v.get_dpart_nodes(pdf)
            leaves = [n for n in nodes if not n['child_count']]
            connections = v.structure_content_dpm_assessments(pdf, sets, nodes)
            report = v.Report('coverage-budget.pdf')
            with patch.object(v, 'MAX_TREE_ITEMS', 1):
                v.check_content_set_tag_coverage(pdf, leaves, connections, report)
            self.assertEqual({'WARN'}, {i['level'] for i in report.items if i['code'] == CODE})
            self.assertTrue(self.coverage_incomplete(report))

    def test_known_invalid_structure_blocks_coverage_certification(self):
        pdf, groups, parts = self.document()
        with pdf:
            root = email_metadata([{'Mail_GUID': 'one'}, {'Mail_GUID': 'two'}])
            report = v.Report('invalid-correspondence.pdf')
            v.check_structure_correspondence(pdf, report)  # This focused graph has no ParentTree.
            self.assertTrue(any(i['code'] == 'STRUCTURE_CORRESPONDENCE' and i['level'] == 'FAIL'
                                for i in report.items))
            v.check_additional_rule_coverage(pdf, root, ET.tostring(root), [], [], 'm', 4, report)
            self.assertEqual({'WARN'}, {i['level'] for i in report.items if i['code'] == CODE})
            self.assertTrue(self.coverage_incomplete(report))

    def span_two(self, pdf, groups):
        first, second = groups[0].K, groups[1].K
        other = second.K
        first.K = pikepdf.Array([first.K, other])
        other.P = first
        second.S = pikepdf.Name.Art
        del second.K

    def test_one_tag_spanning_distinct_content_sets_does_not_invent_counterparts(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.span_two(pdf, groups)
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'WARN')))

    def test_known_missing_coverage_fails_alongside_unavailable_counterparts(self):
        pdf, groups, parts = self.document()
        with pdf:
            self.span_two(pdf, groups)
            page = pdf.add_blank_page()
            page.Contents = pdf.make_stream(b'/P << /MCID 0 >> BDC 0 0 m 1 1 l S EMC')
            leaf = pdf.make_indirect(pikepdf.Dictionary(Parent=parts[0], Start=page.obj,
                DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.Other, Mail_Desc='Third set')))
            parts[0].DParts[0].append(leaf)
            page.obj.DPart = leaf
            self.assertTrue(self.coverage_incomplete(self.check(pdf, 'FAIL')))


if __name__ == '__main__':
    unittest.main()
