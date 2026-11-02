"""Independent rule outcomes must not borrow evidence or severity from neighbors."""
import hashlib
import unittest
from unittest.mock import patch

import pikepdf
from test_preservation_safety import ROOT, email_metadata, validator as v
import test_rule_refinement_evidence as evidence_fixtures
import test_message_guid_content_owners as ownership_fixtures


class TopLevelRulePredicateTests(unittest.TestCase):
    helpers=evidence_fixtures.RuleRefinementEvidenceTests()

    def levels(self,report,code):
        return {item['level'] for item in report.items if item['code']==code}

    def test_report_captures_loaded_catalogue_digest(self):
        report=v.Report('digest.pdf')
        expected=hashlib.sha256((ROOT/'validator_rule_text.json').read_bytes()).hexdigest()
        with patch.object(v,'RULEBOOK_SHA256','changed-after-report-construction'):
            self.assertEqual(expected,report.summary()['rulebook_sha256'])

    def test_unreadable_metadata_keeps_annotation_predicates_unavailable(self):
        with pikepdf.Pdf.new() as pdf:
            report=v.Report('unreadable-metadata.pdf')
            v.check_additional_rule_coverage(pdf,None,b'',[],[],'s',4,report)
        for code in ('ATTACHMENT_ANNOTATIONS_PRESENT','ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX'):
            self.assertEqual({'WARN'},self.levels(report,code))

    def test_collection_native_byte_comparisons_belong_to_owning_children(self):
        with pikepdf.Pdf.new() as pdf:
            context=v.build_validation_context(pdf,None,'c',4,[],[])
            context.update(attachment_xmp_count=1,attachment_checksum_count=1)
            for code in ('ATTACHMENT_XMP_SIZE_MATCH','ATTACHMENT_XMP_CHECKSUM_MATCH'):
                self.assertIn('owning contained email PDFs',v.na_reason_for_code(code,context))

    def test_parameter_type_presence_and_original_value_are_independent(self):
        for supplied,kind,presence,fidelity in [(None,'N/A','FAIL','N/A'),
                (pikepdf.Name('/name=x'),'FAIL','PASS','N/A'),
                (pikepdf.String('name=changed'),'PASS','PASS','FAIL'),
                (pikepdf.String('name=original'),'PASS','PASS','PASS')]:
            with self.subTest(supplied=str(supplied)),pikepdf.Pdf.new() as pdf:
                _,file=self.helpers.embed(pdf,b'payload','data.txt','text/plain','Data')
                if supplied is not None:file['stream'].Mail_MediaTypeParameters=supplied
                report=v.Report('parameters.pdf')
                v.check_native_media_parameters(file,None,report)
                v.check_native_media_parameters(file,'text/plain;name=original',report,'original attachment bytes')
                self.assertEqual({kind},self.levels(report,'EMBEDDED_MEDIATYPE_PARAMETERS_TYPE'))
                self.assertEqual({presence},self.levels(report,'EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED'))
                self.assertEqual({fidelity},self.levels(report,'SOURCE_MEDIATYPE_PARAMETERS_MATCH'))

    def test_global_identifier_membership_does_not_prove_content_ownership(self):
        pdf,messages,parts,_=ownership_fixtures.MessageGuidContentOwnerTests().document()
        with pdf:
            parts[0].DPM.Mail_GUID='two';parts[1].DPM.Mail_GUID='one'
            metadata=email_metadata([{'Mail_GUID':'one'},{'Mail_GUID':'two'}]);report=v.Report('swapped.pdf')
            v.check_dpart_and_content_sets(pdf,metadata,'m',report,[])
            v.check_content_ownership_evidence(pdf,metadata,report)
            self.assertEqual({'PASS'},self.levels(report,'DPM_MAIL_GUID_MATCH'))
            self.assertIn('FAIL',self.levels(report,'DPM_EMAIL_CONTENT_SET_OWNERSHIP'))

    def test_body_and_attachment_media_have_independent_applicability(self):
        for kind,body,attachment in [('BodyRendering','WARN','N/A'),('AttachmentRendering','N/A','WARN')]:
            pdf,_,_=evidence_fixtures.owned_body_document(kind)
            with pdf:
                report=v.Report('media.pdf');v.check_body_media_types(pdf,self.helpers.metadata(),[],report,'s')
                self.assertEqual({body},self.levels(report,'DPM_BODY_MEDIA_TYPE_MATCH_SOURCE'))
                self.assertEqual({attachment},self.levels(report,'DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE'))

    def test_integer_count_can_pass_while_rendered_count_fails(self):
        pdf,_,_=evidence_fixtures.owned_body_document()
        with pdf:
            source,summary,_,_=self.helpers.originals(pdf);metadata=self.helpers.metadata();self.helpers.asset(metadata,source,2)
            report=v.Report('count.pdf');report._email_source_summaries={1:summary}
            v.check_asset_metadata(metadata,[source],[source],'s',report)
            ownership=v.check_content_ownership_evidence(pdf,metadata,report)
            v.check_rendered_asset_counts(metadata,[source],report,ownership)
            self.assertEqual({'PASS'},self.levels(report,'ASSET_NUMBERMESSAGES_TYPE'))
            self.assertEqual({'FAIL'},self.levels(report,'ASSET_NUMBERMESSAGES_MATCH_RENDERED'))

    def test_valid_empty_bag_has_valid_shape_but_invalid_cardinality(self):
        report=v.Report('empty.pdf');v.check_required_email_metadata(email_metadata([]),'si',report)
        self.assertEqual({'PASS'},self.levels(report,'EMAIL_XMP_CONTAINER'))
        self.assertEqual({'FAIL'},self.levels(report,'EMAIL_XMP_CARDINALITY'))

    def test_malformed_container_does_not_supply_a_trustworthy_zero_count(self):
        report=v.Report('wrong-array.pdf');v.check_required_email_metadata(email_metadata([], 'Seq'),'si',report)
        self.assertEqual({'FAIL'},self.levels(report,'EMAIL_XMP_CONTAINER'))
        self.assertEqual({'WARN'},self.levels(report,'EMAIL_XMP_CARDINALITY'))

    def test_source_conditioned_presence_and_supplied_value_fidelity_differ(self):
        source={'subject':'original','size_in_bytes':0,'mime_attachment_count':0}
        absent=v.Report('absent.pdf');v.check_source_field_values({},source,absent)
        changed=v.Report('changed.pdf');v.check_source_field_values({'Subject':'changed'},source,changed)
        self.assertEqual({'FAIL'},self.levels(absent,'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
        self.assertEqual({'PASS'},self.levels(changed,'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
        self.assertEqual({'FAIL'},self.levels(changed,'MESSAGE_METADATA_MATCH_SOURCE'))
        for item in changed.items:self.assertEqual('Subject',item['details']['field'])

    def test_known_smtp_agent_cannot_be_replaced_by_matching_raw_text(self):
        for field in ('To','From'):
            with self.subTest(field=field):
                metadata=email_metadata([{'Mail_GUID':'owner','Raw-'+field:'person@example.org'}])
                report=v.Report('raw-only.pdf')
                v.compare_agent_property(metadata,report,field,[{'mbox':'person@example.org','name':''}],
                    'MESSAGE_'+field.upper()+'_XMP','MESSAGE_'+field.upper()+'_FOAF','person@example.org')
                self.assertEqual({'FAIL'},self.levels(report,'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
                failure=next(item for item in report.items if item['code']=='MESSAGE_CORE_FIELDS_SOURCE_REQUIRED')
                self.assertEqual(field,failure['details']['field'])

    def test_attachment_count_type_does_not_infer_presence_from_metadata_records(self):
        for supplied in (None,'0'):
            record={'Mail_GUID':'owner','Sent':'2026-10-31T12:00:00Z'}
            if supplied is not None:record['NumberAttachments']=supplied
            metadata=email_metadata([record])
            bag=metadata.find('.//'+v.qn(v.NS_PDFMAILMETA,'attachments')+'/'+v.qn(v.NS_RDF,'Bag'))
            attachment=v.ET.SubElement(bag,v.qn(v.NS_RDF,'li'),{v.qn(v.NS_RDF,'parseType'):'Resource'})
            v.ET.SubElement(attachment,v.qn(v.NS_PDFMAILMETA,'Mail_GUID')).text='owner'
            report=v.Report('isolated.pdf');v.check_required_email_metadata(metadata,'si',report)
            self.assertNotIn('FAIL',self.levels(report,'MESSAGE_ATTACHMENT_COUNT_XMP'))
            source={'subject':'','size_in_bytes':0,'mime_attachment_count':1}
            v.check_source_field_values(record,source,report)
            self.assertEqual({'FAIL'} if supplied is None else {'PASS'},self.levels(report,'MESSAGE_CORE_FIELDS_SOURCE_REQUIRED'))
            if supplied is not None:
                self.assertEqual({'PASS'},self.levels(report,'MESSAGE_ATTACHMENT_COUNT_XMP'))
                self.assertIn('FAIL',self.levels(report,'MESSAGE_METADATA_MATCH_SOURCE'))

    def test_different_candidate_fields_do_not_create_a_universal_value_failure(self):
        files=[{'afrelationship':'Source','filename':'one.eml'},{'afrelationship':'Source','filename':'two.eml'}]
        summaries=[{'source_filename':'one.eml'},{'source_filename':'two.eml'}]
        facts=[[{'level':'FAIL','code':'MESSAGE_METADATA_MATCH_SOURCE','message':'mismatch','details':{'field':field}}]
               for field in ('Subject','Sent')]
        report=v.Report('ambiguous.pdf')
        with patch.object(v,'summarize_source_emails',return_value=summaries), \
             patch.object(v,'summarize_source_email',side_effect=summaries), \
             patch.object(v,'source_header_candidate_failures',side_effect=facts):
            v.check_ambiguous_source_header_values(None,{},files,'s',report)
        self.assertEqual({'FAIL'},self.levels(report,'MESSAGE_SOURCE_COVERAGE'))
        self.assertFalse(self.levels(report,'MESSAGE_METADATA_MATCH_SOURCE'))

    def native_comparison(self,size='7',checksum=None):
        checksum=checksum or v.md5hex(b'payload')
        attachment={'Filename':'note.txt','SizeInBytes':size,'Content-Type':'text/plain','CheckSum':checksum,'Mail_GUID':'owner'}
        file={'ufilename':'note.txt','filename':'note.txt','size':7,'md5':v.md5hex(b'payload'),
              'bytes':b'payload','reconstructed_media_type':'text/plain','afrelationship':'Data'}
        report=v.Report('native.pdf');v.check_attachment_stream_consistency([attachment],[file],report)
        return report,attachment,file

    def test_native_size_and_checksum_have_separate_results(self):
        report,_,_=self.native_comparison(size='9')
        self.assertEqual({'FAIL'},self.levels(report,'ATTACHMENT_XMP_SIZE_MATCH'))
        self.assertEqual({'PASS'},self.levels(report,'ATTACHMENT_XMP_CHECKSUM_MATCH'))
        report,_,_=self.native_comparison(checksum='0'*32)
        self.assertEqual({'PASS'},self.levels(report,'ATTACHMENT_XMP_SIZE_MATCH'))
        self.assertEqual({'FAIL'},self.levels(report,'ATTACHMENT_XMP_CHECKSUM_MATCH'))

    def test_original_byte_difference_is_advisory_with_valid_stored_metadata(self):
        report,attachment,file=self.native_comparison()
        original={'filename':'note.txt','original_filenames':['note.txt'],'size_in_bytes':8,
                  'checksum':v.md5hex(b'original'),'content_type':'text/plain'}
        v.validate_attachment_records([attachment],{'attachments':[original],'message_id':'source',
            'mail_guid':'owner','native_files':[file]},report)
        self.assertEqual({'WARN'},self.levels(report,'ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED'))
        self.assertEqual({'PASS'},self.levels(report,'ATTACHMENT_XMP_SIZE_MATCH'))
        self.assertEqual({'PASS'},self.levels(report,'ATTACHMENT_XMP_CHECKSUM_MATCH'))
        self.assertEqual('PASS',report.summary()['status'])

    def test_annotation_native_candidates_participate_without_inventing_an_owner(self):
        for scope,expected in [(None,'PASS'),({'owner'},'PASS'),({'other-email'},'WARN')]:
            with self.subTest(scope=scope):
                report,attachment,file=self.native_comparison()
                file['_scoped_email_guids']=scope
                report._native_attachment_files=[file]
                original={'filename':'note.txt','original_filenames':['note.txt'],'size_in_bytes':7,
                          'checksum':v.md5hex(b'payload'),'content_type':'text/plain'}
                # The original summary has no catalog AF native copy; annotation
                # candidates come from the PDF inventory, independently of it.
                v.validate_attachment_records([attachment],{'attachments':[original],
                    'message_id':'source','mail_guid':'owner','native_files':[]},report)
                self.assertEqual({expected},self.levels(report,'ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED'))
                self.assertEqual('PASS',report.summary()['status'])

    def test_all_original_names_can_contradict_without_selecting_an_occurrence(self):
        attachment={'Filename':'type.txt','SizeInBytes':'7','Content-Type':'text/plain','Mail_GUID':'owner'}
        named={'original_filenames':[' A  B.txt ','TYPE.txt'],'filename':' A  B.txt ',
               'size_in_bytes':7,'content_type':'text/plain','checksum':v.md5hex(b'payload')}
        for originals,expected in [([named],'FAIL'),([named,dict(named,original_filenames=[],size_in_bytes=9)],'WARN')]:
            report=v.Report('filename-candidates.pdf')
            v.validate_attachment_records([attachment],{'attachments':originals,'message_id':'source',
                'mail_guid':'owner'},report)
            self.assertEqual({expected},self.levels(report,'ATTACHMENT_XMP_FILENAME_MATCH_SOURCE'))

    def test_inventory_recommendation_requires_distinct_original_coverage(self):
        _,attachment,_=self.native_comparison()
        original={'filename':'note.txt','original_filenames':['note.txt'],'size_in_bytes':7,
                  'checksum':v.md5hex(b'payload'),'content_type':'text/plain'}
        other=dict(original,filename='other.txt',original_filenames=['other.txt'],checksum=v.md5hex(b'other'))
        for originals,expected in [([original],'PASS'),([original,other],'WARN')]:
            report=v.Report('inventory.pdf')
            v.validate_attachment_records([attachment,dict(attachment)],{'attachments':originals,
                'message_id':'source','mail_guid':'owner'},report)
            self.assertEqual({expected},self.levels(report,'ATTACHMENT_XMP_INVENTORY_RECOMMENDED'))
            item=next(i for i in report.items if i['code']=='ATTACHMENT_XMP_INVENTORY_RECOMMENDED')
            self.assertEqual(1,item['details']['represented_originals'])

    def test_annotation_presence_passes_while_geometry_fails(self):
        report=v.validate(str(ROOT/'tests/fixtures/eapdf/validator-attachment-owner-mid/outside-annotations.pdf'))
        self.assertEqual({'PASS'},self.levels(report,'ATTACHMENT_ANNOTATIONS_PRESENT'))
        self.assertEqual({'FAIL'},self.levels(report,'ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX'))

    def test_embedded_size_and_checksum_absence_are_separate_recommendations(self):
        with pikepdf.open(ROOT/'tests/fixtures/eapdf/validator-operands/valid.pdf') as pdf:
            report=v.Report('omitted-params.pdf');root,_,_=v.parse_xmp(v.read_metadata_stream(pdf),report)
            for file in v.embedded_file_candidates(pdf,v.get_embedded_files(pdf),v.get_af_specs(pdf)):
                params=v.dict_get(file['stream'],'/Params',None)
                for key in ('/Size','/CheckSum'):
                    if params is not None and key in params:del params[key]
            v.check_embedded_files(pdf,root,'s',4,report)
            for prefix in ('EMBEDDED_SIZE','EMBEDDED_MD5'):
                self.assertEqual({'WARN'},self.levels(report,prefix+'_RECOMMENDED'))
                self.assertEqual({'N/A'},self.levels(report,prefix+'_MATCH'))


if __name__=='__main__':unittest.main()
