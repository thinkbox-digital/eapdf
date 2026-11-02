import io
import unittest
from xml.etree import ElementTree as ET

import pikepdf

from test_preservation_safety import validator, failed_codes, email_metadata


class AdditionalSpecRulesTests(unittest.TestCase):
    def file(self, pdf, name, data=b"source", relationship="Source"):
        stream=pdf.make_stream(data)
        stream.Type=pikepdf.Name.EmbeddedFile
        stream.Subtype=pikepdf.Name("/message/rfc822")
        return pdf.make_indirect(pikepdf.Dictionary(Type=pikepdf.Name.Filespec,
            F=name, UF=name, AFRelationship=pikepdf.Name("/"+relationship),
            EF=pikepdf.Dictionary(F=stream, UF=stream)))

    def test_utf8_enforcement_is_not_repeated_as_a_local_rule(self):
        for encoding in ("utf-8", "utf-16", "utf-16-le", "utf-32", "iso-8859-1"):
            with self.subTest(encoding=encoding), pikepdf.Pdf.new() as pdf:
                text='<?xml version="1.0" encoding="'+encoding+'"?><xmp>café</xmp>'
                data=text.encode(encoding)
                if encoding in ("utf-8","utf-16","iso-8859-1"):
                    ET.fromstring(data)
                pdf.Root.Metadata=pdf.make_stream(data)
                report=validator.Report("pdfa3.pdf")
                validator.check_xmp_utf8(pdf,report)
                self.assertFalse(any(row["code"] == "XMP_UTF8" for row in report.items))

    def test_upstream_metadata_checks_do_not_reappear_for_page_metadata(self):
        with pikepdf.Pdf.new() as pdf:
            page=pdf.add_blank_page()
            pdf.Root.Metadata=pdf.make_stream(b"<xmp/>")
            page.obj.Metadata=pdf.make_stream(b'<?xml version="1.0" encoding="ISO-8859-1"?><xmp/>')
            report=validator.Report("object.pdf")
            validator.check_xmp_utf8(pdf,report)
            self.assertFalse(any(row["code"] == "XMP_UTF8" for row in report.items))
            for data in (b"\xef\xbb\xbf<xmp/>", b'<?xml version="1.0" encoding="UTF-8"?><xmp/>'):
                self.assertEqual("",validator.utf8_xmp_error(data))
            self.assertTrue(validator.utf8_xmp_error(b"<xmp>\xff</xmp>"))

    def test_structure_root_dictionary_or_exactly_one_document_array(self):
        with pikepdf.Pdf.new() as pdf:
            document=pikepdf.Dictionary(S=pikepdf.Name.Document)
            for value,valid in ((document,True),(pikepdf.Array([document]),True),
                                (pikepdf.Array(),False),(pikepdf.Array([document,document]),False),
                                (pikepdf.Array([pikepdf.Array([document])]),False),
                                (pikepdf.Array([pikepdf.Dictionary(S=pikepdf.Name.Part)]),False)):
                pdf.Root.StructTreeRoot=pikepdf.Dictionary(K=value)
                report=validator.Report("structure.pdf")
                validator.check_logical_structure(pdf,"s",report)
                self.assertEqual(not valid,"STRUCTTREE_ROOT_DOCUMENT" in failed_codes(report))

    def test_source_set_requires_every_known_companion_and_accepts_both_forms(self):
        for representation in ("missing","rf","supplement","independent-source"):
            with self.subTest(representation=representation), pikepdf.Pdf.new() as pdf:
                primary=self.file(pdf,"mail.idx")
                companion=self.file(pdf,"mail.dat",relationship="Supplement" if representation=="supplement" else "Source")
                pdf.Root.AF=pikepdf.Array([primary])
                if representation=="rf":
                    primary.RF=pikepdf.Dictionary(UF=pikepdf.Array(["mail.dat",companion.EF.UF]))
                elif representation in ("supplement","independent-source"):
                    pdf.Root.AF.append(companion)
                report=validator.Report("set.pdf")
                validator.check_source_file_sets(pdf,None,validator.get_af_specs(pdf),report,{"mail.idx":["mail.dat"]})
                self.assertEqual(representation in ("missing","independent-source"),"SOURCE_FILE_SET" in failed_codes(report))

    def test_malformed_rf_fails_and_independent_sources_do_not_imply_sets(self):
        with pikepdf.Pdf.new() as pdf:
            primary=self.file(pdf,"one.eml")
            pdf.Root.AF=pikepdf.Array([primary,self.file(pdf,"two.eml")])
            report=validator.Report("independent.pdf")
            validator.check_source_file_sets(pdf,None,validator.get_af_specs(pdf),report)
            self.assertEqual("N/A",next(x["level"] for x in report.items if x["code"]=="SOURCE_FILE_SET"))
            for rf in (pikepdf.Array(),pikepdf.Dictionary(),pikepdf.Dictionary(UF=pikepdf.Array(["missing-stream"])),
                       pikepdf.Dictionary(DOS=pikepdf.Array(["file",primary.EF.UF])),
                       pikepdf.Dictionary(UF=pikepdf.Array([pikepdf.Name.Bad,primary.EF.UF]))):
                primary.RF=rf
                report=validator.Report("bad-rf.pdf")
                validator.check_source_file_sets(pdf,None,validator.get_af_specs(pdf),report)
                self.assertIn("SOURCE_FILE_SET",failed_codes(report))

    def test_collection_rejects_arbitrary_files_even_without_collection_items(self):
        plain=io.BytesIO()
        with pikepdf.Pdf.new() as child:
            child.add_blank_page()
            child.save(plain)
        for name,data in (("notes.txt",b"text"),("premis.xml",b"<premis/>"),("index.xlsx",b"PK"),("finding.pdf",plain.getvalue())):
            for profile in ("c","ci"):
                with self.subTest(name=name,profile=profile), pikepdf.Pdf.new() as pdf:
                    file=self.file(pdf,name,data,"Data")
                    pdf.Root.AF=pikepdf.Array([file])
                    report=validator.Report("collection.pdf")
                    validator.check_collection_content(pdf,[],validator.get_af_specs(pdf),profile,report)
                    self.assertIn("COLLECTION_CONTENT_TYPES",failed_codes(report))

    def test_declared_source_supplement_is_exempt_but_unrelated_supplement_is_not(self):
        with pikepdf.Pdf.new() as pdf:
            primary=self.file(pdf,"mail.idx")
            companion=self.file(pdf,"mail.dat",b"companion","Supplement")
            pdf.Root.AF=pikepdf.Array([primary,companion])
            files=validator.get_af_specs(pdf)
            metadata=email_metadata([])
            assets=ET.SubElement(metadata.find(validator.qn(validator.NS_RDF,"Description")),validator.qn(validator.NS_PDFMAILMETA,"assets"))
            bag=ET.SubElement(assets,validator.qn(validator.NS_RDF,"Bag"))
            item=ET.SubElement(bag,validator.qn(validator.NS_RDF,"li"))
            for key,value in {"Filename":"C:\\original\\mail.dat","SizeInBytes":"9","Checksum":validator.md5hex(b"companion"),"ContentType":"message/rfc822"}.items():
                ET.SubElement(item,validator.qn(validator.NS_PDFMAILMETA,key)).text=value
            report=validator.Report("supplement.pdf")
            related,supplements=validator.check_source_file_sets(pdf,metadata,files,report)
            validator.check_collection_content(pdf,[],files,"c",report,related,supplements)
            self.assertEqual(set(),failed_codes(report))
            report=validator.Report("unrelated.pdf")
            validator.check_collection_content(pdf,[],files,"c",report)
            self.assertIn("COLLECTION_CONTENT_TYPES",failed_codes(report))

    def test_new_rules_are_numbered_and_documented(self):
        for code in ("SOURCE_FILE_SET","COLLECTION_CONTENT_TYPES"):
            self.assertIn(code,validator.ALL_RULE_CODES)
            self.assertTrue(validator.RULE_TEXT[code]["spec_excerpt"])
        self.assertIn("exactly one",validator.RULE_TEXT["STRUCTTREE_ROOT_DOCUMENT"]["spec_excerpt"])

    def test_apostrophe_wrapped_pst_recipients_require_exact_raw_preservation(self):
        raw="'jimboman@bigfoot.com'"
        self.assertFalse(validator.valid_smtp_mailbox(raw))
        self.assertTrue(validator.valid_smtp_mailbox("o'connor@example.org"))
        for actual,valid in ((raw,True),("changed@example.org",False)):
            metadata=email_metadata([{"Raw-To":actual}])
            report=validator.Report("pst-recipient.pdf")
            validator.compare_agent_property(metadata,report,"To",[{"name":"","mbox":"mailto:"+raw}],
                "MESSAGE_TO_XMP","MESSAGE_TO_FOAF",raw)
            self.assertEqual(valid,not failed_codes(report))
            self.assertEqual(not valid,any(item["code"]=="VALIDATION_INCOMPLETE" for item in report.items))
