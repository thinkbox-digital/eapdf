import unittest
from pathlib import Path
import pikepdf
from test_preservation_safety import validator, failed_codes

class RoleMappingReviewTests(unittest.TestCase):
    def setup_pdf(self,pdf):
        pdf.Root.Version=pikepdf.Name('/2.0')
        root=pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.StructTreeRoot,RoleMap=pikepdf.Dictionary()))
        pdf.Root.StructTreeRoot=root
        root.K=pdf.make_indirect(pikepdf.Dictionary(S=pikepdf.Name.Document))
        root.Namespaces=pikepdf.Array()
        return root

    def namespace(self,pdf,root,uri):
        ns=pdf.make_indirect(pikepdf.Dictionary(NS=uri,RoleMapNS=pikepdf.Dictionary()))
        root.Namespaces.append(ns)
        return ns

    def test_legacy_root_chain_and_overridden_standard_name(self):
        with pikepdf.Pdf.new() as pdf:
            root=self.setup_pdf(pdf);root.K.S=pikepdf.Name.CustomRoot
            root.RoleMap.CustomRoot=pikepdf.Name.Alias;root.RoleMap.Alias=pikepdf.Name.Document
            report=validator.Report('root.pdf');validator.check_logical_structure(pdf,'s',report)
            self.assertNotIn('STRUCTTREE_ROOT_DOCUMENT',failed_codes(report))
            root.RoleMap.Document=pikepdf.Name.Part
            report=validator.Report('root.pdf');validator.check_logical_structure(pdf,'s',report)
            self.assertIn('STRUCTTREE_ROOT_DOCUMENT',failed_codes(report))

    def test_namespace_chain_is_used_instead_of_conflicting_legacy_map(self):
        with pikepdf.Pdf.new() as pdf:
            root=self.setup_pdf(pdf);ns=self.namespace(pdf,root,'urn:custom');bridge=self.namespace(pdf,root,'urn:bridge');standard=self.namespace(pdf,root,validator.StructureRoles.PDF20)
            root.K.S=pikepdf.Name.CustomRoot;root.K.NS=ns
            root.RoleMap.CustomRoot=pikepdf.Name.Part
            ns.RoleMapNS.CustomRoot=pikepdf.Array([pikepdf.Name.Intermediate,bridge]);bridge.RoleMapNS.Intermediate=pikepdf.Array([pikepdf.Name.Document,standard])
            self.assertEqual(validator.StructureRoles(root,True).element(root.K),('Document',''))
            report=validator.Report('root.pdf');validator.check_logical_structure(pdf,'s',report)
            self.assertNotIn('STRUCTTREE_ROOT_DOCUMENT',failed_codes(report))
            ns.RoleMapNS.CustomRoot=pikepdf.Name.Document
            self.assertEqual(validator.StructureRoles(root,True).element(root.K),('Document',''))

    def test_invalid_targets_cycles_and_namespace_registration(self):
        with pikepdf.Pdf.new() as pdf:
            root=self.setup_pdf(pdf);ns=self.namespace(pdf,root,'urn:custom');root.K.NS=ns;root.K.S=pikepdf.Name.CustomRoot
            unregistered=pdf.make_indirect(pikepdf.Dictionary(NS=validator.StructureRoles.PDF20))
            for mapping in [pikepdf.String('Document'),pikepdf.Name.Unknown,pikepdf.Array([pikepdf.Name.Document]),pikepdf.Array([pikepdf.Name.Document,unregistered]),pikepdf.Array([pikepdf.Name.CustomRoot,ns]),pikepdf.Array([pikepdf.Name.Document,pikepdf.Dictionary(NS=validator.StructureRoles.PDF20)])]:
                ns.RoleMapNS.CustomRoot=mapping
                with self.subTest(mapping=str(mapping)):
                    self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'')
            root.K.NS=unregistered;root.K.S=pikepdf.Name.Document
            self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'')
            del root.K.NS;root.K.S=pikepdf.Name.A;root.RoleMap.A=pikepdf.Name.B;root.RoleMap.B=pikepdf.Name.A
            self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'')

    def test_root_type_and_standard_namespace_vocabulary(self):
        with pikepdf.Pdf.new() as pdf:
            root=self.setup_pdf(pdf);root.K.S=pikepdf.String('Document')
            self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'')
            root.K.NS=self.namespace(pdf,root,validator.StructureRoles.PDF20);root.K.S=pikepdf.Name.Art
            self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'')
            root.K.S=pikepdf.Name.H12
            self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'H12')
            root.K.NS=self.namespace(pdf,root,'urn:custom');root.K.S=pikepdf.Name.Document
            self.assertEqual(validator.StructureRoles(root,True).element(root.K)[0],'')

    def test_mapping_limit_is_unavailable_not_failure(self):
        with pikepdf.Pdf.new() as pdf:
            root=self.setup_pdf(pdf);root.K.S=pikepdf.Name.R0
            for i in range(4100):root.RoleMap['/R'+str(i)]=pikepdf.Name('/R'+str(i+1))
            root.RoleMap['/R4100']=pikepdf.Name.Document
            report=validator.Report('limit.pdf');validator.check_logical_structure(pdf,'s',report)
            rows=[r for r in report.items if r['code']=='STRUCTTREE_ROOT_DOCUMENT']
            self.assertEqual(rows[0]['level'],'WARN');self.assertEqual(rows[0]['details']['assessment'],'unavailable')

    def test_real_example_valid_and_wrong_namespace_target_rejected(self):
        sample=Path(__file__).resolve().parents[1]/'examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'
        with pikepdf.open(sample) as pdf:
            report=validator.Report('real.pdf');validator.check_logical_structure(pdf,'s',report)
            self.assertNotIn('ROLEMAPNS_PRESENT',failed_codes(report))
            root=pdf.Root.StructTreeRoot
            mail=next(ns for ns in root.Namespaces if str(ns.NS)=='https://pdfa.org/ns/ea-pdf/mail-1')
            mail.RoleMapNS.Mail_Message=pikepdf.Name.Part
            report=validator.Report('mutated.pdf');validator.check_logical_structure(pdf,'s',report)
            self.assertIn('ROLEMAPNS_PRESENT',failed_codes(report))
            self.assertNotIn('ROLEMAP_MAIL_TAGS',failed_codes(report))

    def test_mapping_limit_cannot_hide_another_definite_namespace_failure(self):
        sample=Path(__file__).resolve().parents[1]/'examples/eml/eml_Agenda_and_timeline_slides_1s.pdf'
        with pikepdf.open(sample) as pdf:
            root=pdf.Root.StructTreeRoot
            mail=next(ns for ns in root.Namespaces if str(ns.NS)=='https://pdfa.org/ns/ea-pdf/mail-1')
            mail.RoleMapNS.Mail_Message=pikepdf.Name.Part
            mail.RoleMapNS.Mail_ContentSet=pikepdf.Name.R0
            for i in range(4100):root.RoleMap['/R'+str(i)]=pikepdf.Name('/R'+str(i+1))
            root.RoleMap['/R4100']=pikepdf.Name.Art
            report=validator.Report('mixed.pdf');validator.check_logical_structure(pdf,'s',report)
            self.assertIn('ROLEMAPNS_PRESENT',failed_codes(report))
