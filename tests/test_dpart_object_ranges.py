"""DPart dictionaries, unique page ownership and depth-first page order."""
import unittest

import pikepdf
from test_preservation_safety import validator as v, failed_codes


class DpartObjectRangeTests(unittest.TestCase):
    def document(self):
        pdf = pikepdf.Pdf.new()
        pages = [pdf.add_blank_page().obj for _ in range(3)]
        root = pdf.make_indirect(pikepdf.Dictionary())
        node = pdf.make_indirect(pikepdf.Dictionary(Parent=root))
        first = pdf.make_indirect(pikepdf.Dictionary(Parent=node, Start=pages[0], End=pages[1]))
        second = pdf.make_indirect(pikepdf.Dictionary(Parent=node, Start=pages[2]))
        root.DPartRootNode = node
        node.DParts = pikepdf.Array([pikepdf.Array([first, second])])
        pdf.Root.DPartRoot = root
        for page, owner in zip(pages, [first, first, second]):
            page.DPart = owner
        return pdf, root, node, first, second

    def report(self, pdf, structure=False):
        root, node, nodes = v.get_dpart_nodes(pdf)
        report = v.Report('dpart-ranges.pdf')
        if structure:
            v.check_dpart_object_structure(root, node, nodes, report)
        else:
            v.check_page_dpart_links(pdf, nodes, report)
        return report

    def stream_replacement(self, pdf, original):
        root, node, nodes = v.get_dpart_nodes(pdf)
        replacement = pdf.make_stream(b'policy')
        for key, value in original.items():
            replacement[key] = value
        if original.objgen == root.objgen:
            pdf.Root.DPartRoot = replacement
            node.Parent = replacement
        elif original.objgen == node.objgen:
            root.DPartRootNode = replacement
            for child in nodes:
                if child['parent'] is not None and child['parent'].objgen == original.objgen:
                    child['node'].Parent = replacement
        return replacement

    def test_root_stream_cannot_satisfy_dictionary_requirement(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            self.stream_replacement(pdf, root)
            self.assertIn('DPART_OBJECT_STRUCTURE', failed_codes(self.report(pdf, structure=True)))

    def test_root_node_stream_cannot_satisfy_dictionary_requirement(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            self.stream_replacement(pdf, node)
            self.assertIn('DPART_OBJECT_STRUCTURE', failed_codes(self.report(pdf, structure=True)))

    def test_plain_dictionaries_need_no_optional_type_entries(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            self.assertFalse(failed_codes(self.report(pdf, structure=True)))
            self.assertFalse(failed_codes(self.report(pdf)))

    def test_overlapping_owners_fail_even_when_every_link_is_in_range(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            second.Start = pdf.pages[1].obj
            second.End = pdf.pages[2].obj
            self.assertIn('PAGE_DPART_PRESENT', failed_codes(self.report(pdf)))

    def test_reversed_disjoint_ranges_fail_despite_valid_links(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            node.DParts = pikepdf.Array([pikepdf.Array([second, first])])
            self.assertIn('PAGE_DPART_PRESENT', failed_codes(self.report(pdf)))

    def test_group_boundaries_preserve_depth_first_order(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            node.DParts = pikepdf.Array([pikepdf.Array([first]), pikepdf.Array([second])])
            self.assertFalse(failed_codes(self.report(pdf)))
            node.DParts = pikepdf.Array([pikepdf.Array([second]), pikepdf.Array([first])])
            self.assertIn('PAGE_DPART_PRESENT', failed_codes(self.report(pdf)))

    def test_multiple_dpart_levels_preserve_leaf_order(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            branch = pdf.make_indirect(pikepdf.Dictionary(Parent=node, DParts=pikepdf.Array([pikepdf.Array([first])])))
            first.Parent = branch
            node.DParts = pikepdf.Array([pikepdf.Array([branch, second])])
            self.assertFalse(failed_codes(self.report(pdf)))
            node.DParts = pikepdf.Array([pikepdf.Array([second, branch])])
            self.assertIn('PAGE_DPART_PRESENT', failed_codes(self.report(pdf)))

    def test_missing_coverage_still_fails(self):
        pdf, root, node, first, second = self.document()
        with pdf:
            del first.End
            self.assertIn('PAGE_DPART_PRESENT', failed_codes(self.report(pdf)))


if __name__ == '__main__':
    unittest.main()
