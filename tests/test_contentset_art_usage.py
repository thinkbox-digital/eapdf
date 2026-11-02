"""Art represents connected Content Sets, including implicit Mail_ContentSet Art."""
import unittest
from unittest.mock import patch
from xml.etree import ElementTree as ET
import pikepdf
import test_contentset_tag_coverage as coverage
from test_preservation_safety import validator as v, email_metadata

CODE = 'MAIL_CONTENTSET_ART_ONLY'

class ContentSetArtUsageTests(unittest.TestCase):
    def check(self, pdf, expected):
        root = email_metadata([{'Mail_GUID': 'one'}, {'Mail_GUID': 'two'}])
        report = v.Report('art-focused.pdf')
        v.check_additional_rule_coverage(pdf, root, ET.tostring(root), [], [], 'm', 4, report)
        self.assertEqual({expected}, {i['level'] for i in report.items if i['code'] == CODE})
        return report

    document = coverage.ContentSetTagCoverageTests.document
    extra_tag = coverage.ContentSetTagCoverageTests.extra_tag
    untag_second = coverage.ContentSetTagCoverageTests.untag_second
    span_two = coverage.ContentSetTagCoverageTests.span_two

class ArtTests(ContentSetArtUsageTests):
    def test_structure_k_is_optional_but_invalid_children_still_fail(self):
        from pathlib import Path
        path = Path(__file__).resolve().parents[1] / 'tests/fixtures/eapdf/validator-art-usage/empty-standard-article.pdf'
        with pikepdf.open(path) as pdf:
            report = v.Report(path)
            v.check_structure_correspondence(pdf, report)
            self.assertEqual({'PASS'}, {i['level'] for i in report.items if i['code'] == 'STRUCTURE_CORRESPONDENCE'})
            empty = next(obj for obj in pdf.objects if isinstance(obj, pikepdf.Dictionary)
                         and obj.get('/S') == pikepdf.Name.Art and '/K' not in obj)
            empty.K = pikepdf.String('invalid child')
            report = v.Report(path)
            v.check_structure_correspondence(pdf, report)
            self.assertEqual({'FAIL'}, {i['level'] for i in report.items if i['code'] == 'STRUCTURE_CORRESPONDENCE'})

    def unavailable(self, report):
        return any(i['code'] == 'VALIDATION_INCOMPLETE' and CODE in i.get('details', {}).get('rules', [])
                   for i in report.items)

    def test_mixed_explicit_and_implicit_articles_are_valid(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.untag_second(groups)
            self.check(pdf, 'PASS')

    def test_all_implicit_articles_are_valid(self):
        pdf, _, _ = self.document()
        with pdf: self.check(pdf, 'PASS')

    def test_all_explicit_articles_are_valid(self):
        pdf, groups, _ = self.document()
        with pdf:
            for group in groups: group.K.S = pikepdf.Name.Art
            self.check(pdf, 'PASS')

    def test_additional_article_for_same_set_is_not_a_count_failure(self):
        pdf, groups, _ = self.document()
        with pdf:
            groups[0].K.S = pikepdf.Name.Art
            self.extra_tag(pdf, groups[0]).S = pikepdf.Name.Art
            self.check(pdf, 'PASS')

    def test_equal_article_counts_do_not_certify_cross_set_content(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.span_two(pdf, groups)
            # Keep a second nonempty article, so the old literal-Art count equals two.
            groups[0].K.S = pikepdf.Name.Art
            groups[1].K.K = pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name.P, P=groups[1].K, Pg=pdf.pages[1].obj, K=0))
            self.check(pdf, 'FAIL')

    def test_implicit_article_spanning_distinct_sets_fails(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.span_two(pdf, groups)
            groups[1].K.S = pikepdf.Name.Div
            self.check(pdf, 'FAIL')

    def test_known_cross_set_failure_survives_an_unrelated_unknown_article(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.span_two(pdf,groups)
            groups[1].K.S=pikepdf.Name.Div
            self.extra_tag(pdf,groups[0],empty=True)
            self.assertTrue(self.unavailable(self.check(pdf,'FAIL')))

    def test_empty_article_cannot_be_certified_by_page_hint(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.extra_tag(pdf, groups[0], empty=True, page=pdf.pages[1]).S = pikepdf.Name.Art
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_extra_unknown_article_does_not_disappear_behind_verified_articles(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.extra_tag(pdf, groups[0], empty=True)
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_invalid_dpart_range_stays_unavailable(self):
        pdf, _, parts = self.document()
        with pdf:
            parts[0].DParts[0][0].Start = 'not a page'
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_contradictory_page_link_stays_unavailable(self):
        pdf, _, parts = self.document()
        with pdf:
            pdf.pages[0].obj.DPart = parts[1].DParts[0][0]
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_no_dpart_inventory_does_not_certify_articles(self):
        pdf, _, _ = self.document()
        with pdf:
            del pdf.Root.DPartRoot
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_no_article_vocabulary_is_inapplicable(self):
        pdf, groups, _ = self.document()
        with pdf:
            for group in groups: group.K.S = pikepdf.Name.Div
            self.check(pdf, 'N/A')

    def test_foreign_namespace_article_role_is_unavailable(self):
        pdf, groups, _ = self.document()
        with pdf:
            node=groups[0].K; node.S=pikepdf.Name.Art
            node.NS=pdf.make_indirect(pikepdf.Dictionary(NS='urn:foreign',RoleMapNS=pikepdf.Dictionary(Art=pikepdf.Name.P)))
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_other_role_mapping_to_art_is_not_silently_ignored(self):
        pdf, groups, _ = self.document()
        with pdf:
            groups[0].K.S=pikepdf.Name('/VendorArticle')
            pdf.Root.StructTreeRoot.RoleMap.VendorArticle=pikepdf.Name.Art
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_unreadable_namespace_stays_unavailable(self):
        pdf, groups, _ = self.document()
        with pdf:
            groups[0].K.NS=pdf.make_indirect(pikepdf.Dictionary(NS=pikepdf.String(b'\xef\xbb\xbf\xff')))
            self.assertTrue(self.unavailable(self.check(pdf, 'WARN')))

    def test_known_standard_namespace_article_is_supported(self):
        pdf, groups, _ = self.document()
        with pdf:
            groups[0].K.S=pikepdf.Name.Art
            groups[0].K.NS=pdf.make_indirect(pikepdf.Dictionary(NS='http://iso.org/pdf/ssn'))
            self.check(pdf, 'PASS')

    def test_unknown_role_does_not_invent_a_cross_set_article_failure(self):
        pdf, groups, _ = self.document()
        with pdf:
            self.span_two(pdf,groups)
            groups[0].K.NS=pdf.make_indirect(pikepdf.Dictionary(NS='urn:foreign'))
            groups[1].K.S=pikepdf.Name.Div
            self.assertTrue(self.unavailable(self.check(pdf,'WARN')))

    def test_art_in_pdf2_namespace_is_not_the_ea_pdf17_article(self):
        pdf, groups, _ = self.document()
        with pdf:
            groups[0].K.S=pikepdf.Name.Art
            groups[0].K.NS=pdf.make_indirect(pikepdf.Dictionary(NS='http://iso.org/pdf2/ssn'))
            self.assertTrue(self.unavailable(self.check(pdf,'WARN')))

    def test_budget_limit_remains_unavailable(self):
        pdf, _, _ = self.document()
        with pdf:
            elems=[]
            v.walk_struct_elems(pdf.Root.StructTreeRoot.K,elems)
            _,_,nodes=v.get_dpart_nodes(pdf)
            leaves=[n for n in nodes if not n['child_count']]
            rows=v.structure_content_dpm_assessments(pdf,[e for e in elems if e['type']=='Mail_ContentSet'],nodes)
            report=v.Report('art-budget.pdf')
            with patch.object(v,'MAX_TREE_ITEMS',1):
                v.check_content_set_art_usage(pdf,leaves,rows,report)
            self.assertEqual({'WARN'},{i['level'] for i in report.items if i['code']==CODE})
            self.assertTrue(self.unavailable(report))

    def test_known_invalid_structure_blocks_certification(self):
        pdf, _, _ = self.document()
        with pdf:
            root=email_metadata([{'Mail_GUID':'one'},{'Mail_GUID':'two'}])
            report=v.Report('invalid-art-correspondence.pdf')
            v.check_structure_correspondence(pdf,report)
            v.check_additional_rule_coverage(pdf,root,ET.tostring(root),[],[],'m',4,report)
            self.assertEqual({'WARN'},{i['level'] for i in report.items if i['code']==CODE})
            self.assertTrue(self.unavailable(report))

if __name__ == '__main__': unittest.main()
