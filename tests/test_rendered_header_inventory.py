"""Observed field text and source identity cannot be inferred from tag counts."""
from html import escape
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import pikepdf

from test_preservation_safety import ROOT, email_metadata, validator


class RenderedHeaderInventoryTests(unittest.TestCase):
    def source(self,subject='Source A',extra=''):
        return ('From: Sender <sender@example.invalid>\r\nTo: Reader <reader@example.invalid>\r\n'
                'Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nMessage-ID: <duplicate@example.invalid>\r\n'
                f'Subject: {subject}\r\n{extra}Content-Type: text/plain\r\n\r\nBody.').encode()

    def document(self,label='Subject:',value='Source A',form=False,extra_text='Source B'):
        pdf=pikepdf.Pdf.new();page=pdf.add_blank_page()
        resources=pikepdf.Dictionary(Font=pikepdf.Dictionary(F1=pikepdf.Dictionary(Type=pikepdf.Name.Font,
            Subtype=pikepdf.Name.Type1,BaseFont=pikepdf.Name.Helvetica,Encoding=pikepdf.Name.WinAnsiEncoding)))
        page.Resources=resources
        tree=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot))
        root=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name.Document,P=tree))
        owner=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/Mail_Message'),P=root,
            A=pikepdf.Dictionary(O=pikepdf.Name.UserProperties,P=pikepdf.Array([
                pikepdf.Dictionary(N='Mail_GUID',V='uuid:first'),pikepdf.Dictionary(N='Mail_MessageID',V='<duplicate@example.invalid>')]))))
        field=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/Mail_Field'),P=owner,Pg=page.obj))
        owner.K=field;root.K=owner;tree.K=root;pdf.Root.StructTreeRoot=tree
        stream_content=b'\n'.join([
            b'/Span <</MCID 0>> BDC BT /F1 10 Tf 20 100 Td '+pikepdf.String(label).unparse()+b' Tj ET EMC',
            b'/Span <</MCID 1>> BDC BT /F1 10 Tf 20 80 Td '+pikepdf.String(value).unparse()+b' Tj ET EMC',
            b'BT /F1 10 Tf 20 60 Td '+pikepdf.String(extra_text).unparse()+b' Tj ET'])
        scope=page.obj
        if form:
            scope=pdf.make_stream(stream_content);scope.Type=pikepdf.Name.XObject;scope.Subtype=pikepdf.Name.Form
            scope.Resources=resources;scope.BBox=pikepdf.Array([0,0,200,200])
            page.Resources=pikepdf.Dictionary(XObject=pikepdf.Dictionary(Fm=scope));page.Contents=pdf.make_stream(b'/Fm Do')
        else:page.Contents=pdf.make_stream(stream_content)
        children=[]
        for index,tag in enumerate(('Mail_FieldName','Mail_FieldValue')):
            child=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructElem,S=pikepdf.Name('/'+tag),P=field,Pg=page.obj))
            child.K=pikepdf.Dictionary(Type=pikepdf.Name.MCR,Pg=page.obj,Stm=scope,MCID=index) if form else index
            children.append(child)
        field.K=pikepdf.Array(children)
        return pdf

    def evidence(self,pdf):
        with tempfile.TemporaryDirectory(prefix='.eapdf-inventory-unit-',dir=ROOT) as directory:
            path=Path(directory)/'fields.pdf';pdf.save(path)
            with pikepdf.Pdf.open(path) as reopened:
                elements=[];tree=reopened.Root.StructTreeRoot
                validator.walk_struct_elems(tree.K,elements,class_map=tree.get('/ClassMap'))
                analysis=validator.collect_page_text_analysis(str(path),field_sequences=validator.structure_field_text_sequences(reopened))
                return elements,analysis

    def compare(self,label='Subject:',value='Source A',subject='Source A',extra='',form=False,second=False,
                owner_guid='uuid:first',duplicate_xmp=False,missing_source=False,conflicting_guid=False):
        with self.document(label,value,form=form) as pdf:
            owner=pdf.Root.StructTreeRoot.K.K
            if owner_guid is None:del owner.A.P[0]
            else:owner.A.P[0].V=owner_guid
            if conflicting_guid:owner.A.P.append(pikepdf.Dictionary(N='Mail_GUID',V='uuid:second'))
            elements,analysis=self.evidence(pdf)
        raws=[self.source(subject,extra)]+([self.source('Source B')] if second else [])
        sources=[dict(afrelationship='Source',subtype='message/rfc822',bytes=raw,objgen=(100+i,0),filename=f'{i}.eml') for i,raw in enumerate(raws)]
        records=[dict(Mail_GUID=f'uuid:{"first" if i==0 else "second"}',messageid='&lt;duplicate@example.invalid&gt;',
                      Subject=escape(subject if i==0 else 'Source B'),SizeInBytes=str(len(raw)),Sent='2003-01-29T02:20:54Z')
                 for i,raw in enumerate(raws)]
        if duplicate_xmp:records.append(dict(records[0]))
        return validator.observed_header_source_inventory(elements,analysis,email_metadata(records),[] if missing_source else sources)

    def test_decoded_scope_text_excludes_unrelated_body_text(self):
        for form in (False,True):
            with self.subTest(form=form),self.document(form=form) as pdf:
                _,analysis=self.evidence(pdf)
                rows=[row for page in analysis for row in page['rendered_fields']]
                self.assertEqual(['Subject:','Source A'],[row['text'].strip() for row in rows])
                self.assertTrue(all('Source B' not in row['text'] for row in rows))

    def test_owning_guid_controls_duplicate_message_id_comparison(self):
        rows=self.compare(value='Source B',second=True)
        self.assertEqual('different',rows[0]['comparison'])
        self.assertEqual('uuid:first',rows[0]['Mail_GUID'])
        self.assertEqual('Source A',rows[0]['expected'])

    def test_missing_guid_cannot_choose_between_duplicate_message_ids(self):
        self.assertEqual('unavailable',self.compare(value='Source B',second=True,owner_guid=None)[0]['comparison'])

    def test_unique_xmp_owner_and_retained_source_are_required(self):
        for kwargs in (dict(duplicate_xmp=True),dict(missing_source=True)):
            with self.subTest(kwargs=kwargs):self.assertEqual('unavailable',self.compare(**kwargs)[0]['comparison'])

    def test_matching_decoded_and_folded_header_text_is_observed_equal(self):
        rows=self.compare(subject='=?UTF-8?Q?caf=C3=A9?=',value='café')
        self.assertEqual('equal',rows[0]['comparison'])
        self.assertEqual('equal',self.compare(subject='Source  A',value='Source A')[0]['comparison'])

    def test_ambiguous_repeated_headers_and_unknown_labels_are_unavailable(self):
        for kwargs in (dict(label='X-Project:',value='one',extra='X-Project: one\r\nX-Project: one\r\n'),
                       dict(label='Archive Global ID:',value='generated')):
            with self.subTest(kwargs=kwargs):self.assertEqual('unavailable',self.compare(**kwargs)[0]['comparison'])

    def test_equivalent_date_instant_and_mailbox_formatting(self):
        self.assertEqual('equal',self.compare(label='Date:',value='2003-01-29T02:20:54Z')[0]['comparison'])
        self.assertEqual('equal',self.compare(label='From:',value='"Sender" <sender@example.invalid>')[0]['comparison'])

    def test_form_without_resources_does_not_borrow_an_unrelated_font_map(self):
        with self.document(form=True) as pdf:
            del pdf.pages[0].Resources.XObject.Fm.Resources
            _,analysis=self.evidence(pdf)
            rows=[row for page in analysis for row in page['rendered_fields']]
            self.assertTrue(all(row.get('reason') and row['text'] is None for row in rows))

    def test_same_mcid_on_another_page_cannot_supply_field_text(self):
        with self.document() as pdf:
            first=pdf.pages[0];other=pdf.add_blank_page();other.Resources=first.Resources
            other.Contents=pdf.make_stream(first.Contents.read_bytes().replace(b'Source A',b'Intruder'))
            _,analysis=self.evidence(pdf)
            rows=[row for page in analysis for row in page.get('rendered_fields',[])]
            self.assertEqual(['Subject:','Source A'],[row['text'].strip() for row in rows])

    def test_reused_form_requires_an_unambiguous_page(self):
        with self.document(form=True) as pdf:
            first=pdf.pages[0];other=pdf.add_blank_page();other.Resources=first.Resources
            other.Contents=pdf.make_stream(b'/Fm Do')
            field=pdf.Root.StructTreeRoot.K.K.K
            # A declared page makes the otherwise shared Form reference resolvable.
            _,analysis=self.evidence(pdf)
            self.assertTrue(all(row['text'] for page in analysis for row in page.get('rendered_fields',[])))
            del field.Pg
            for child in field.K:
                del child.Pg;del child.K.Pg
            _,analysis=self.evidence(pdf)
            rows=[row for page in analysis for row in page.get('rendered_fields',[])]
            self.assertEqual(2,len(rows))
            self.assertTrue(all(row['text'] is None and 'ambiguous' in row['reason'] for row in rows))

    def test_empty_field_cannot_borrow_matching_body_text(self):
        row=self.compare(value='')[0]
        self.assertEqual('unavailable',row['comparison'])
        self.assertIn('No reliable decoded text',row['reason'])

    def test_conflicting_owner_identity_is_unavailable(self):
        row=self.compare(second=True,conflicting_guid=True)[0]
        self.assertEqual('unavailable',row['comparison'])
        self.assertIn('conflicting',row['reason'])

    def test_boolean_mcid_is_not_integer_one(self):
        with self.document(form=True) as pdf:
            field=pdf.Root.StructTreeRoot.K.K.K
            field.K[1].K.MCID=True
            _,analysis=self.evidence(pdf)
            rows=[row for page in analysis for row in page['rendered_fields']]
            self.assertIsNone(rows[1]['text'])
            self.assertIn('nonnegative integer',rows[1]['reason'])

    @unittest.skipUnless(os.environ.get('EAPDF_GENERATOR_CLASSPATH'),'Set EAPDF_GENERATOR_CLASSPATH for inventory fixtures')
    def test_creator_generated_inventory_corpus(self):
        with tempfile.TemporaryDirectory(prefix='.eapdf-header-inventory-',dir=ROOT) as directory:
            process=subprocess.run(['python3','-B','scripts/build_eapdf_header_inventory_fixtures.py'],cwd=ROOT,
                env=dict(os.environ,EAPDF_HEADER_INVENTORY_FIXTURE_DIR=directory),capture_output=True,text=True,timeout=240)
            if os.environ.get('EAPDF_INTEGRATION_REPORT_DIR'):
                shutil.copytree(directory,Path(os.environ['EAPDF_INTEGRATION_REPORT_DIR'])/'header-inventory-corpus',dirs_exist_ok=True)
            self.assertEqual(0,process.returncode,process.stdout+process.stderr)
            results=json.loads((Path(directory)/'results.json').read_text())
            self.assertEqual(6,len(results));self.assertTrue(all(row['expected_result_observed'] for row in results))
