"""EA-PDF 9.7 recommendation premises and evidence, not global tag counts."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import pikepdf

from test_preservation_safety import ROOT, validator, email_metadata

CONTENT='CUSTOM_CONTENTSETS_TAGGEDPDF_RECOMMENDED'
HEADERS='HEADER_FIELDS_TAGGED_RECOMMENDED'


class TaggingRecommendationTests(unittest.TestCase):
    def document(self, tree=True, fields=('name','value'), custom=True, empty_name=False):
        pdf=pikepdf.Pdf.new()
        page=pdf.add_blank_page()
        page.Resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=pikepdf.Dictionary(
            Type=pikepdf.Name.Font,Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica)))
        rootnode=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart))
        leaf=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.DPart,Parent=rootnode,
            Start=page.obj,End=page.obj,DPM=pikepdf.Dictionary(Mail_ContentSetType=pikepdf.Name.FixtureCustom)))
        rootnode.DParts=pikepdf.Array([leaf])
        pdf.Root.DPartRoot=pdf.make_indirect(pikepdf.Dictionary(DPartRootNode=rootnode))
        if not tree:
            page.Contents=pdf.make_stream(b'BT /F1 10 Tf (Subject: value) Tj ET')
            return pdf
        structure=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        root=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name.Document,P=structure))
        structure.K=root
        pdf.Root.StructTreeRoot=structure
        pdf.Root.MarkInfo=pikepdf.Dictionary(Marked=True)
        field=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
            S=pikepdf.Name('/Mail_Field') if custom else pikepdf.Name.TR,P=root,Pg=page.obj))
        root.K=field
        children=[];owners=[];content=[]
        for index,kind in enumerate(fields):
            role='Mail_FieldName' if kind=='name' else 'Mail_FieldValue'
            child=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,
                S=pikepdf.Name('/'+role) if custom else pikepdf.Name.TD,P=field,Pg=page.obj))
            if not (empty_name and kind=='name'):
                mcid=len(owners);child.K=mcid;owners.append(child)
                content.append(f'/Span <</MCID {mcid}>> BDC BT /F1 10 Tf ({kind}) Tj ET EMC')
            children.append(child)
        field.K=pikepdf.Array(children)
        page.Contents=pdf.make_stream('\n'.join(content).encode())
        page.obj.StructParents=0
        structure.ParentTree=pdf.make_indirect(pikepdf.Dictionary(Nums=pikepdf.Array([0,pikepdf.Array(owners)])))
        return pdf

    def report(self,pdf):
        report=validator.Report('tagging.pdf')
        analysis=[dict(page=i,text='Page '+str(i),lines=[],candidate_lines=['Page '+str(i)],urls=[],links=[])
                  for i in range(1,len(pdf.pages)+1)]
        with patch.object(validator,'collect_page_text_analysis',return_value=analysis):
            validator.check_additional_rule_coverage(pdf,None,b'',[],[],'s',4,report)
        return {i['code']:i for i in report.items}

    def test_absent_structure_does_not_waive_custom_content_set_recommendation(self):
        with self.document(tree=False) as pdf:
            self.assertEqual('WARN',self.report(pdf)[CONTENT]['level'])

    def test_structure_root_without_tagged_content_cannot_pass(self):
        with self.document(fields=(),custom=False) as pdf:
            pdf.pages[0].Contents=pdf.make_stream(b'BT /F1 10 Tf (untagged) Tj ET')
            self.assertEqual('WARN',self.report(pdf)[CONTENT]['level'])

    def test_paint_coverage_distinguishes_artifacts_and_unmarked_paint(self):
        with self.document() as pdf:
            page=pdf.pages[0]
            page.Contents=pdf.make_stream(page.Contents.read_bytes()+b'\n/Artifact BMC 0 0 1 1 re f EMC\n0 0 1 1 re f')
            coverage=validator.analyze_page_content(page)['paint_coverage']
            self.assertEqual(2,coverage['structured'])
            self.assertEqual(1,coverage['artifact'])
            self.assertEqual(1,coverage['unmarked'])
            self.assertEqual('WARN',self.report(pdf)[CONTENT]['level'])

    def test_form_invocation_uses_inner_marking_for_coverage(self):
        with self.document(tree=False) as pdf:
            page=pdf.pages[0]
            form=pdf.make_stream(b'/Span <</MCID 0>> BDC BT /F1 10 Tf (inside) Tj ET EMC')
            form.Type=pikepdf.Name.XObject;form.Subtype=pikepdf.Name.Form
            form.BBox=pikepdf.Array([0,0,100,100]);form.Resources=page.Resources
            page.Resources=pikepdf.Dictionary(XObject=pikepdf.Dictionary(Fm=form))
            page.Contents=pdf.make_stream(b'/Fm Do')
            coverage=validator.analyze_page_content(page)['paint_coverage']
            self.assertEqual(1,coverage['structured'])
            self.assertEqual(0,coverage['unmarked'])

    def test_annotation_without_object_reference_does_not_pass_page_coverage(self):
        with self.document() as pdf:
            pdf.pages[0].Annots=pikepdf.Array([pdf.make_indirect(pikepdf.Dictionary(
                Type=pikepdf.Name.Annot,Subtype=pikepdf.Name.Square,Rect=pikepdf.Array([0,0,10,10])))])
            row=self.report(pdf)[CONTENT]
            self.assertEqual('WARN',row['level'])
            self.assertTrue(any('Annotation' in item['reason'] for item in row['details']['unresolved']))

    def test_markinfo_and_content_correspondence_are_both_needed(self):
        for missing in ('markinfo','parent-tree'):
            with self.subTest(missing=missing),self.document() as pdf:
                if missing=='markinfo':pdf.Root.MarkInfo.Marked=False
                else:pdf.Root.StructTreeRoot.ParentTree.Nums=pikepdf.Array()
                self.assertEqual('WARN',self.report(pdf)[CONTENT]['level'])

    def test_standard_tags_do_not_activate_custom_header_recommendation(self):
        with self.document(custom=False) as pdf:
            self.assertEqual('N/A',self.report(pdf)[HEADERS]['level'])

    def test_field_value_alone_is_not_sufficient_header_evidence(self):
        with self.document(fields=('value',)) as pdf:
            row=self.report(pdf)[HEADERS]
            self.assertEqual('WARN',row['level'])
            self.assertTrue(row['details']['unresolved_fields'])

    def test_unrendered_field_name_is_not_sufficient_header_evidence(self):
        with self.document(empty_name=True) as pdf:
            row=self.report(pdf)[HEADERS]
            self.assertEqual('WARN',row['level'])
            self.assertTrue(row['details']['unresolved_fields'])

    def test_observed_pairs_do_not_prove_all_rendered_headers_were_tagged(self):
        with self.document() as pdf:
            row=self.report(pdf)[HEADERS]
            self.assertEqual('WARN',row['level'])
            self.assertEqual('partial',row['details']['assessment'])
            self.assertEqual(1,len(row['details']['verified_fields']))

    def test_observed_name_with_no_rendered_value_does_not_invent_a_value_obligation(self):
        with self.document(fields=('name',)) as pdf:
            row=self.report(pdf)[HEADERS]
            self.assertEqual([],row['details']['unresolved_fields'])
            self.assertEqual(0,row['details']['verified_fields'][0]['values'])

    def test_suppressed_custom_set_check_cannot_be_skipped_for_absent_structure(self):
        with self.document(tree=False) as pdf:
            ctx=validator.build_validation_context(pdf,None,'si',4,[],[])
            self.assertIsNone(validator.na_reason_for_code(CONTENT,ctx))

    def test_suppressed_header_check_is_na_when_no_custom_tags_are_used(self):
        with self.document(custom=False) as pdf:
            ctx=validator.build_validation_context(pdf,None,'s',4,[],[])
            self.assertIsNotNone(validator.na_reason_for_code(HEADERS,ctx))

    def body_report(self,pdf):
        source={'afrelationship':'Source','subtype':'message/rfc822','bytes':b'Message-ID: <fixture@example.invalid>\r\nContent-Type: text/plain\r\n\r\nBody',
                'objgen':(100,0),'filename':'source.eml'}
        metadata=email_metadata([{'Mail_GUID':'uuid:fixture','messageid':'&lt;fixture@example.invalid&gt;'}])
        report=validator.Report('body-absence.pdf')
        validator.check_body_media_types(pdf,metadata,[source],report)
        return report

    def test_declared_nonbody_ranges_make_body_media_comparison_inapplicable(self):
        with self.document() as pdf:
            report=self.body_report(pdf)
            row=next(i for i in report.items if i['code']=='DPM_BODY_MEDIA_TYPE_MATCH_SOURCE')
            self.assertEqual('N/A',row['level'])
            self.assertNotIn('VALIDATION_INCOMPLETE',report.seen_codes)

    def test_unknown_or_unresolved_body_ranges_remain_incomplete(self):
        for kind in ('unknown-type','body-without-owner','bad-range'):
            with self.subTest(kind=kind),self.document() as pdf:
                leaf=pdf.Root.DPartRoot.DPartRootNode.DParts[0]
                if kind=='unknown-type':del leaf.DPM.Mail_ContentSetType
                elif kind=='body-without-owner':leaf.DPM.Mail_ContentSetType=pikepdf.Name.BodyRendering
                else:del leaf.Start
                report=self.body_report(pdf)
                self.assertIn('VALIDATION_INCOMPLETE',report.seen_codes)

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for tagging fixtures')
    def test_creator_generated_tagging_corpus(self):
        with tempfile.TemporaryDirectory(prefix='.eapdf-tagging-fixtures-',dir=ROOT) as directory:
            process=subprocess.run(['python3','-B','scripts/build_eapdf_tagging_fixtures.py'],cwd=ROOT,
                env=dict(os.environ,EAPDF_TAGGING_FIXTURE_DIR=directory),capture_output=True,text=True,timeout=180)
            if os.environ.get('EAPDF_INTEGRATION_REPORT_DIR'):
                shutil.copytree(directory,Path(os.environ['EAPDF_INTEGRATION_REPORT_DIR'])/'tagging-corpus',dirs_exist_ok=True)
            self.assertEqual(0,process.returncode,process.stdout+process.stderr)
            results=json.loads((Path(directory)/'results.json').read_text())
            self.assertEqual(6,len(results))
            self.assertTrue(all(c['expected_result_observed'] for c in results))
