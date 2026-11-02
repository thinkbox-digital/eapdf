"""Independent ownership and source facts must support semantic rule results."""
import base64
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET

import pikepdf
import test_message_guid_content_owners as ownership_fixtures
from test_preservation_safety import email_metadata, validator as v


def owned_body_document(kind="BodyRendering",subtype="text/plain",guid="one"):
    """A real MCID connects the logical message identity to its DPart page."""
    pdf,messages,parts,_=ownership_fixtures.MessageGuidContentOwnerTests().document()
    del pdf.pages[1]
    parts[0].Parent.DParts=pikepdf.Array([pikepdf.Array([parts[0]])])
    parts[0].DPM.Mail_GUID=guid
    messages[0].A.P[0].V=guid
    pdf.Root.StructTreeRoot.K.K=pikepdf.Array([messages[0]])
    leaf=parts[0].DParts[0][0]
    leaf.DPM.Mail_ContentSetType=pikepdf.Name('/'+kind)
    leaf.DPM.Mail_Subtype=pikepdf.Name('/'+subtype)
    return pdf,parts[0],leaf


class RuleRefinementEvidenceTests(unittest.TestCase):
    document=staticmethod(owned_body_document)

    def metadata(self):
        return email_metadata([{"Mail_GUID":"one","Message-ID":"&lt;one@example.org&gt;"}])

    def findings(self,report,code,check=None):
        return [item for item in report.items if item['code']==code and
                (check is None or (item.get('details') or {}).get('check')==check)]

    def embed(self,pdf,data,name,subtype,relationship):
        stream=pdf.make_stream(data);stream.Subtype=pikepdf.Name('/'+subtype)
        stream.Params=pikepdf.Dictionary(Size=len(data))
        spec=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,F=name,UF=name,
            AFRelationship=pikepdf.Name('/'+relationship),EF=pikepdf.Dictionary(F=stream)))
        return spec,v.parse_filespec(name,spec)

    def originals(self,pdf,content_type='text/plain; charset=UTF-8'):
        payload=b'native payload'
        raw=(b'Message-ID: <one@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=x\r\n\r\n'
             b'--x\r\nContent-Type: text/plain\r\n\r\nBody\r\n--x\r\nContent-Type: '+content_type.encode()+
             b'\r\nContent-Disposition: attachment; filename=note.txt\r\nContent-Transfer-Encoding: base64\r\n\r\n'+
             base64.b64encode(payload)+b'\r\n--x--\r\n')
        source_spec,source=self.embed(pdf,raw,'one.eml','message/rfc822','Source')
        pdf.Root.AF=pikepdf.Array([source_spec])
        native_spec,native=self.embed(pdf,payload,'note.txt','text/plain','Data')
        return source,v.summarize_source_emails([source])[0],native_spec,native

    def asset(self,metadata,source,count):
        desc=list(metadata)[0]
        bag=ET.SubElement(ET.SubElement(desc,v.qn(v.NS_PDFMAILMETA,'assets')),v.qn(v.NS_RDF,'Bag'))
        item=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
        for name,value in {'Filename':source['filename'],'Checksum':source['md5'],
                           'SizeInBytes':str(source['size']),'NumberMessages':str(count),'ContentType':source['subtype']}.items():
            ET.SubElement(item,v.qn(v.NS_PDFMAILMETA,name)).text=value

    def test_correct_identifiers_do_not_hide_swapped_email_content(self):
        pdf,messages,parts,_=ownership_fixtures.MessageGuidContentOwnerTests().document()
        with pdf:
            parts[0].DPM.Mail_GUID='two';parts[1].DPM.Mail_GUID='one'
            metadata=email_metadata([{'Mail_GUID':'one'},{'Mail_GUID':'two'}]);report=v.Report('swapped.pdf')
            evidence=v.check_content_ownership_evidence(pdf,metadata,report)
            self.assertFalse(evidence['complete'])
            self.assertEqual({'FAIL'},{item['level'] for item in self.findings(report,'DPM_EMAIL_CONTENT_SET_OWNERSHIP')})

    def test_content_connection_is_required_but_unknown_guid_is_not_a_false_missing_failure(self):
        for mode in ['no-structure','unreadable-guid','missing-guid']:
            with self.subTest(mode=mode):
                pdf,parent,_=self.document()
                with pdf:
                    if mode=='no-structure':del pdf.Root.StructTreeRoot
                    elif mode=='unreadable-guid':parent.DPM.Mail_GUID=pikepdf.String(b'\xef\xbb\xbf\xff')
                    else:del parent.DPM.Mail_GUID
                    report=v.Report(mode+'.pdf');v.check_content_ownership_evidence(pdf,self.metadata(),report)
                    levels={item['level'] for item in self.findings(report,'DPM_EMAIL_CONTENT_SET_OWNERSHIP')}
                    self.assertIn('FAIL' if mode=='missing-guid' else 'WARN',levels)
                    if mode!='missing-guid':self.assertNotIn('FAIL',levels)

    def test_numbermessages_counts_bound_rendered_messages_not_just_integer_syntax(self):
        for count,complete,expected in [(1,True,'PASS'),(2,True,'FAIL'),(1,False,'WARN')]:
            with self.subTest(count=count,complete=complete):
                pdf,_,_=self.document()
                with pdf:
                    source,summary,_,_=self.originals(pdf);metadata=self.metadata();self.asset(metadata,source,count)
                    report=v.Report('count.pdf');report._email_source_summaries={1:summary if complete else None}
                    ownership=v.check_content_ownership_evidence(pdf,metadata,report)
                    self.assertTrue(ownership['complete'])
                    v.check_rendered_asset_counts(metadata,[source],report,ownership)
                    self.assertEqual([expected],[item['level'] for item in self.findings(report,'ASSET_NUMBERMESSAGES_MATCH_RENDERED','rendered_message_count')])

    def test_original_parameter_presence_type_and_significant_values(self):
        for supplied,code,expected in [(None,'EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED','FAIL'),
                                  (pikepdf.Name('/name=A'),'EMBEDDED_MEDIATYPE_PARAMETERS_TYPE','FAIL'),
                                  (pikepdf.String('name="A B"'),'SOURCE_MEDIATYPE_PARAMETERS_MATCH','FAIL'),
                                  (pikepdf.String(' name="A  B"'),'SOURCE_MEDIATYPE_PARAMETERS_MATCH','PASS')]:
            with self.subTest(supplied=str(supplied)):
                with pikepdf.Pdf.new() as pdf:
                    _,file=self.embed(pdf,b'data','native.txt','text/plain','Data')
                    if supplied is not None:file['stream'].Mail_MediaTypeParameters=supplied
                    report=v.Report('parameters.pdf')
                    v.check_native_media_parameters(file,None,report)
                    v.check_native_media_parameters(file,'text/plain; name="A  B"',report,'original attachment bytes')
                    self.assertEqual([expected],[item['level'] for item in self.findings(report,code)])

    def test_matching_xmp_and_native_parameter_omissions_still_fail_against_original(self):
        pdf,_,_=self.document()
        with pdf:
            source,summary,_spec,native=self.originals(pdf);metadata=self.metadata()
            bag=metadata.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            record=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
            for name,value in {'Filename':'note.txt','SizeInBytes':str(native['size']),'Content-Type':'text/plain',
                               'CheckSum':native['md5'],'Mail_GUID':'one'}.items():
                ET.SubElement(record,v.qn(v.NS_PDFMAILMETA,name)).text=value
            report=v.Report('omitted-parameters.pdf');report._email_source_summaries={1:summary}
            v.check_additional_rule_coverage(pdf,metadata,ET.tostring(metadata),[source,native],[source],'s',4,report)
            failures=[item for item in self.findings(report,'EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED') if item['level']=='FAIL']
            self.assertTrue(any((item.get('details') or {}).get('evidence')=='original attachment bytes' for item in failures))

    def test_attachment_rendering_needs_explicit_native_source_association(self):
        for associated,subtype,expected in [(True,'text/plain; charset=utf-8','PASS'),
                                          (True,'image/png','FAIL'),(False,'text/plain; charset=utf-8','WARN')]:
            with self.subTest(associated=associated,subtype=subtype):
                pdf,_,leaf=self.document('AttachmentRendering',subtype)
                with pdf:
                    source,summary,spec,native=self.originals(pdf)
                    if associated:
                        spec.AFRelationship=pikepdf.Name.Source;leaf.AF=pikepdf.Array([spec])
                    else:
                        annotation=pdf.make_indirect(pikepdf.Dictionary(Subtype=pikepdf.Name.FileAttachment,FS=spec))
                        pdf.pages[0].Annots=pikepdf.Array([annotation])
                    report=v.Report('rendering.pdf');report._email_source_summaries={1:summary};report._embedded_media_candidates=[native]
                    v.check_body_media_types(pdf,self.metadata(),[source],report,'s')
                    self.assertEqual([expected],[item['level'] for item in self.findings(report,'DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE','attachment_source_media_type')])

    def test_known_original_mime_absence_does_not_invent_parameter_obligations(self):
        pdf,_,_=self.document()
        with pdf:
            source,summary,_spec,native=self.originals(pdf);metadata=self.metadata()
            summary['mime_attachments'][0]['content_type']=''
            summary['mime_attachments'][0]['content_type_available']=False
            self.assertEqual({''},v.original_attachment_media_types(native,summary))
            unknown=dict(native,md5='0'*32)
            self.assertEqual(set(),v.original_attachment_media_types(unknown,summary))
            bag=metadata.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            record=ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
            for name,value in {'Filename':'note.txt','SizeInBytes':str(native['size']),'Content-Type':'text/plain',
                               'CheckSum':native['md5'],'Mail_GUID':'one'}.items():
                ET.SubElement(record,v.qn(v.NS_PDFMAILMETA,name)).text=value
            report=v.Report('absent-original-mime.pdf');report._email_source_summaries={1:summary}
            v.check_additional_rule_coverage(pdf,metadata,ET.tostring(metadata),[source,native],[source],'s',4,report)
            self.assertEqual(['N/A'],[item['level'] for item in self.findings(report,
                             'SOURCE_MEDIATYPE_PARAMETERS_MATCH','original_parameter_fidelity')])

    def test_missing_original_is_na_only_when_deliberately_omitted_in_isolated_profile(self):
        for profile,expected in [('s','WARN'),('si','N/A')]:
            pdf,_,_=self.document()
            with pdf:
                report=v.Report('source-scope.pdf');v.check_body_media_types(pdf,self.metadata(),[],report,profile)
                self.assertEqual([expected],[item['level'] for item in self.findings(report,'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE','body_source_media_type')])

    def test_unknown_content_type_cannot_prove_attachment_rendering_absent(self):
        for content_type in (None,pikepdf.String('AttachmentRendering')):
            pdf,_,leaf=self.document()
            with pdf:
                if content_type is None:del leaf.DPM.Mail_ContentSetType
                else:leaf.DPM.Mail_ContentSetType=content_type
                report=v.Report('unknown-content-type.pdf')
                v.check_body_media_types(pdf,self.metadata(),[],report,'s')
                self.assertEqual(['WARN'],[item['level'] for item in self.findings(report,
                                 'DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE','attachment_source_media_type')])

    def test_completed_child_proof_requires_all_message_bindings(self):
        result={'summary':{'status':'PASS'},'pdfa':{'status':'PASS'},'items':[
            {'level':'PASS','code':'EMAIL_XMP_CARDINALITY','details':{'count':1}},
            {'level':'PASS','code':'MESSAGE_SOURCE_COVERAGE','details':{'Mail_GUID':'one','source_md5':'a'*32,'source_size':42}}]}
        self.assertEqual({'one':('a'*32,42)},v.child_rendered_source_proof(result))
        result['items'][0]['details']['count']=2
        self.assertIsNone(v.child_rendered_source_proof(result))
        result['items'][0]['details']['count']=1;result['summary']['status']='INCOMPLETE'
        self.assertIsNone(v.child_rendered_source_proof(result))

    def test_collection_asset_count_uses_proved_distinct_child_identities(self):
        pdf,_,_=self.document()
        with pdf:
            source,_,_,_=self.originals(pdf);metadata=self.metadata();self.asset(metadata,source,2)
            for complete,expected in [(True,'FAIL'),(False,'WARN')]:
                report=v.Report('collection-count.pdf');report._collection_count_proof={
                    'complete':complete,'messages':{'one':(source['md5'],source['size'])}}
                v.check_collection_asset_counts(metadata,[source],report)
                self.assertEqual([expected],[item['level'] for item in self.findings(report,'ASSET_NUMBERMESSAGES_MATCH_RENDERED')])

    def test_identical_outer_source_occurrences_do_not_borrow_one_aggregate_count(self):
        pdf,_,_=self.document()
        with pdf:
            source,_,_,_=self.originals(pdf)
            _,duplicate=self.embed(pdf,source['bytes'],'second.eml','message/rfc822','Source')
            metadata=self.metadata();self.asset(metadata,source,1)
            report=v.Report('identical-sources.pdf');report._collection_count_proof={
                'complete':True,'messages':{'one':(source['md5'],source['size'])}}
            v.check_collection_asset_counts(metadata,[source,duplicate],report)
            self.assertEqual(['WARN'],[item['level'] for item in self.findings(report,'ASSET_NUMBERMESSAGES_MATCH_RENDERED')])

    def test_child_exception_or_unprocessed_member_cannot_prove_zero_messages(self):
        for child in [{'filename':'broken.pdf','subtype':'application/pdf','bytes':b'%PDF-broken'},
                      {'filename':'unprocessed.bin','subtype':'application/octet-stream','bytes':b'unknown'}]:
            with self.subTest(child=child['filename']),tempfile.TemporaryDirectory(prefix='eapdf-count-evidence-') as folder:
                pdf,_,_=self.document()
                with pdf:
                    source,_,_,_=self.originals(pdf);metadata=self.metadata();self.asset(metadata,source,0)
                    context=v.ChildValidationContext();context.temporary_root=Path(folder)
                    report=v.Report('unprocessed-child.pdf')
                    with patch.object(v,'validate',side_effect=OSError('test child read failure')):
                        v.check_collection_children([child],'c',report,context,0)
                    self.assertFalse(report._collection_count_proof['complete'])
                    v.check_collection_asset_counts(metadata,[source],report)
                    self.assertEqual(['WARN'],[item['level'] for item in self.findings(report,'ASSET_NUMBERMESSAGES_MATCH_RENDERED')])


if __name__=='__main__':unittest.main()
