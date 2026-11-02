"""Required GUID decoding and its actual downstream ownership consumers."""
import json,unittest
import pikepdf
import test_dpm_schema as schema
import test_content_group_owner_types as owners
from test_preservation_safety import validator as v
PRESENT='DPM_MAIL_GUID_PRESENT'
MATCH='DPM_MAIL_GUID_MATCH'

class DpmGuidDecodingTests(unittest.TestCase):
    document=schema.DpmSchemaTests.document
    check=schema.DpmSchemaTests.check

    def assess(self,value,expected):
        pdf,message,_=self.document()
        with pdf:
            message.DPM.Mail_GUID=value
            report=self.check(pdf)
            self.assertEqual({expected},{i['level'] for i in report.items if i['code']==PRESENT})
            self.assertIn('CONTENT_SET_COUNT',report.seen_codes)
            return report

    def test_unreadable_utf8_presence_and_match_are_unavailable(self):
        r=self.assess(pikepdf.String(b'\xef\xbb\xbf\xff'),'WARN')
        self.assertEqual({'WARN'},{i['level'] for i in r.items if i['code']==MATCH})
        self.assertTrue(any(i['code']=='VALIDATION_INCOMPLETE' and {PRESENT,MATCH}<=set(i.get('details',{}).get('rules',[])) for i in r.items))

    def test_unreadable_utf8_surrogate_is_unavailable(self):
        self.assess(pikepdf.String(b'\xef\xbb\xbf\xed\xa0\x80'),'WARN')

    def test_valid_utf8_guid_remains_exact(self):
        r=self.assess(pikepdf.String(b'\xef\xbb\xbf42'),'PASS')
        self.assertEqual({'PASS'},{i['level'] for i in r.items if i['code']==MATCH})

    def test_valid_utf16_guid_remains_exact(self):
        self.assess(pikepdf.String(b'\xfe\xff\x004\x002'),'PASS')

    def test_known_empty_guid_still_fails(self):
        self.assess(pikepdf.String(''),'FAIL')

    def test_known_decoded_empty_guid_still_fails(self):
        self.assess(pikepdf.String(b'\xfe\xff\xd8\x00'),'FAIL')

    def test_name_does_not_satisfy_required_string(self):
        self.assess(pikepdf.Name('/42'),'FAIL')

    def test_unreadable_wrong_name_retains_reportable_failure(self):
        r=self.assess(pikepdf.Object.parse(b'/Vendor#FF'),'FAIL')
        json.dumps(r.items,ensure_ascii=False).encode('utf8')

    def test_readable_guid_mismatch_is_not_unavailable(self):
        r=self.assess(pikepdf.String('other'),'PASS')
        self.assertEqual({'FAIL'},{i['level'] for i in r.items if i['code']==MATCH})

    def test_unreadable_guid_cannot_fall_back_to_readable_messageid(self):
        item={'dpm':{'Mail_GUID':pikepdf.String(b'\xef\xbb\xbf\xff'),'Mail_MessageID':'17'}}
        self.assertEqual({'Mail_GUID':'','Message-ID':''},v.dpart_message_identity(item,{}))

    def test_unreadable_guid_cannot_select_enclosing_email(self):
        item={'dpm':{'Mail_GUID':pikepdf.String(b'\xef\xbb\xbf\xff')},'parent_objgen':(1,0)}
        self.assertEqual({'Mail_GUID':'','Message-ID':''},v.dpart_message_identity(item,{(1,0):{'dpm':{'Mail_GUID':'other'}}}))

    def test_readable_guid_preserves_messageid_guard(self):
        item={'dpm':{'Mail_GUID':'42','Mail_MessageID':pikepdf.String(b'\xef\xbb\xbf\xff')}}
        self.assertEqual({'Mail_GUID':'42','Message-ID':''},v.dpart_message_identity(item,{}))

    def test_owner_uncertainty_is_local_to_affected_dpart(self):
        pdf,_,parts=owners.ContentGroupOwnerTypeTests().document()
        with pdf:
            parts[0].DPM.Mail_GUID=pikepdf.String(b'\xef\xbb\xbf\xff')
            elems=[];v.walk_struct_elems(pdf.Root.StructTreeRoot.K,elems)
            messages=[e for e in elems if e['type']=='Mail_Message']
            _,_,nodes=v.get_dpart_nodes(pdf)
            rows=v.structure_content_dpm_assessments(pdf,messages,nodes)
            self.assertTrue(rows[0]['references'])
            self.assertTrue(all(r['unknown_ownership'] for r in rows[0]['references']))
            self.assertTrue(rows[1]['references'])
            self.assertTrue(all(not r['unknown_ownership'] and r['dpm_guids']==['two'] for r in rows[1]['references']))

    def control(self,name):
        path=schema.ROOT/'tests/fixtures/eapdf/validator-dpm-guid-decoding'/(name+'.pdf')
        return v.validate(str(path))

    def test_unreadable_guid_keeps_annotation_ownership_unavailable(self):
        r=self.control('unreadable-utf8')
        self.assertEqual('INCOMPLETE',r.summary()['status'])
        self.assertEqual({'WARN'},{i['level'] for i in r.items if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'})
        self.assertNotIn('VALIDATION_ERROR',{i['code'] for i in r.items})

    def test_partial_global_inventory_does_not_invent_mismatch_for_unknown_owner(self):
        r=self.control('unreadable-with-other-owner-mismatch')
        self.assertEqual('FAIL',r.summary()['status'])
        item=next(i for i in r.items if i['code']=='MAIL_MESSAGE_GUID_MATCH_DPM_XMP')
        self.assertEqual('FAIL',item['level'])
        self.assertEqual(1,len(item['details']['actual']))
        self.assertTrue(item['details']['unavailable'])
        self.assertEqual({'WARN'},{i['level'] for i in r.items if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'})

    def test_independent_revision_failure_is_retained(self):
        r=self.control('unreadable-with-revision-failure')
        self.assertEqual({'EAPDF_REV'},{i['code'] for i in r.items if i['level']=='FAIL'})

    def test_unreadable_extra_leaf_guid_does_not_leave_an_incomplete_inventory_certified(self):
        import tempfile
        from pathlib import Path
        source=schema.ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-single.pdf'
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'leaf-guid.pdf'
            with pikepdf.open(source) as pdf:
                _,_,nodes=v.get_dpart_nodes(pdf)
                next(n for n in nodes if n['start'] is not None)['node'].DPM.Mail_GUID=pikepdf.String(b'\xef\xbb\xbf\xff')
                pdf.save(path,fix_metadata_version=False)
            r=v.validate(str(path))
            self.assertEqual('INCOMPLETE',r.summary()['status'])
            self.assertIn('WARN',{i['level'] for i in r.items if i['code']==MATCH})

    def test_known_missing_annotation_remains_failure_alongside_unknown_body_owner(self):
        import tempfile
        from pathlib import Path
        source=schema.ROOT/'tests/fixtures/eapdf/validator-contentset-parent/nested-multi.pdf'
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'missing-annotation.pdf'
            with pikepdf.open(source) as pdf:
                _,_,nodes=v.get_dpart_nodes(pdf)
                identities=[n for n in nodes if n['dpm'].get('Mail_GUID') is not None]
                self.assertGreater(len(identities),1)
                identities[1]['node'].DPM.Mail_GUID=pikepdf.String(b'\xef\xbb\xbf\xff')
                for page in pdf.pages:
                    if '/Annots' in page.obj:del page.obj.Annots
                pdf.save(path,fix_metadata_version=False)
            r=v.validate(str(path))
            self.assertEqual({'FAIL'},{i['level'] for i in r.items if i['code']=='ATTACHMENT_ANNOTATIONS_PRESENT'})
            self.assertTrue(any(i['code']=='VALIDATION_INCOMPLETE' and 'ATTACHMENT_ANNOTATIONS_PRESENT' in i.get('details',{}).get('rules',[]) for i in r.items))

if __name__=='__main__':unittest.main()
