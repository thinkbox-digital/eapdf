package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdditionalSpecRuleTests {
  private boolean previousFindingAid;
  @org.junit.jupiter.api.BeforeEach void enableFindingAidFixture() {
    previousFindingAid = PdfMailWriter.CONFIG.findingAid.pdfEnabled;
    PdfMailWriter.CONFIG.findingAid.pdfEnabled = true;
  }
  @org.junit.jupiter.api.AfterEach void restoreFindingAidDefault() {
    PdfMailWriter.CONFIG.findingAid.pdfEnabled = previousFindingAid;
  }

  @TempDir Path directory;

  EmailRecord record() throws Exception {
    return EmlReader.readRfc822Bytes(("From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n"
        + "Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: File set\r\n\r\nBody").getBytes(StandardCharsets.UTF_8),
        "source.eml", "source.eml", "", List.of(), false);
  }

  @Test void rootArraysRequireExactlyOneDocumentDictionary() throws Exception {
    COSDictionary document = new COSDictionary();
    document.setName(COSName.S, "Document");
    COSDictionary root = new COSDictionary();
    root.setItem(COSName.K, document);
    PdfCreationValidator.validateStructureRoot(root);
    COSArray array = new COSArray();
    array.add(document);
    root.setItem(COSName.K, array);
    PdfCreationValidator.validateStructureRoot(root);
    array.add(document);
    Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateStructureRoot(root));
    array.clear();
    Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateStructureRoot(root));
  }

  @Test void metadataRequiresUtf8IncludingObjectMetadata() throws Exception {
    PdfCreationValidator.validateUtf8("<xmp>café</xmp>".getBytes(StandardCharsets.UTF_8));
    for (var encoding : List.of(StandardCharsets.UTF_16, StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
      Assertions.assertThrows(java.io.IOException.class,
          () -> PdfCreationValidator.validateUtf8("<xmp>café</xmp>".getBytes(encoding)));
    }
    Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateUtf8(
        "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><xmp/>".getBytes(StandardCharsets.UTF_8)));
    try (PDDocument document = new PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage();
      document.addPage(page);
      var metadata = new PDMetadata(document);
      metadata.importXMPMetadata("<xmp/>".getBytes(StandardCharsets.UTF_16));
      page.setMetadata(metadata);
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateXmpEncoding(document));
    }
  }

  @Test void streamingMetadataChecksChunkBoundariesAndLongDeclarations() throws Exception {
    for (String prefix : List.of("", "\uFEFF", " \t\r\n")) {
      for (String encoding : List.of("UTF-8", "utf8", "UtF-8")) {
        String xml=prefix+"<?xml version='1.0' "+" ".repeat(20000)+"encoding = '"+encoding+"'?><xmp>"+"é".repeat(9000)+"</xmp>";
        try(var input=new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)) {
          @Override public synchronized int read(byte[] bytes,int offset,int count) { return super.read(bytes,offset,Math.min(7,count)); }
        }) { PdfCreationValidator.validateUtf8(input); }
      }
    }
    for (String xml : List.of("<?xml "+" ".repeat(20000)+"encoding='utf-16'?><xmp/>",
        "<?xml encoding='"+"a".repeat(20000)+"'?><xmp/>", "<xmp>"+"a".repeat(20000)+"\0</xmp>"))
      Assertions.assertThrows(java.io.IOException.class,()->PdfCreationValidator.validateUtf8(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
    byte[] invalid=("<xmp>"+"a".repeat(8190)).getBytes(StandardCharsets.UTF_8);
    var bytes=new java.io.ByteArrayOutputStream();bytes.write(invalid);bytes.write(0xc3);
    Assertions.assertThrows(java.io.IOException.class,()->PdfCreationValidator.validateUtf8(new java.io.ByteArrayInputStream(bytes.toByteArray())));
  }

  @Test void relatedFilesRequireMatchingEfKeysAndFilenameStreamPairs() throws Exception {
    try (PDDocument document = new PDDocument()) {
      var stream = document.getDocument().createCOSStream();
      stream.setItem(COSName.TYPE, COSName.EMBEDDED_FILE);
      COSDictionary file = new COSDictionary();
      file.setItem(COSName.TYPE, COSName.FILESPEC);
      COSDictionary ef = new COSDictionary();
      ef.setItem(COSName.UF, stream);
      file.setItem(COSName.EF, ef);
      COSArray pairs = new COSArray();
      pairs.add(new COSString("companion.dat"));
      pairs.add(stream);
      COSDictionary rf = new COSDictionary();
      rf.setItem(COSName.UF, pairs);
      file.setItem(COSName.getPDFName("RF"), rf);
      PdfCreationValidator.validateRelatedFiles(file);
      pairs.remove(1);
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateRelatedFiles(file));
      pairs.add(stream);
      ef.removeItem(COSName.UF);
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateRelatedFiles(file));
    }
  }

  @Test void explicitSourceSetMembersArePreservedAsSupplementAndCannotBeSilentlyDropped() throws Exception {
    EmailRecord record = record();
    AssociatedFile member = new AssociatedFile();
    member.filename = "mailbox.dat";
    member.mime = "application/x-mailbox-data";
    member.bytes = "required companion".getBytes(StandardCharsets.UTF_8);
    member.afRelationship = "Supplement";
    member.sourceSetMember = true;
    member.checksumMd5 = Common.md5Hex(member.bytes);
    record.associatedFiles.add(member);
    Path output = directory.resolve("source-set.pdf");
    PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1);
    try (PDDocument document = Loader.loadPDF(output.toFile())) {
      var file = AttachmentProfilePolicyTests.files(document).get("mailbox.dat");
      Assertions.assertNotNull(file);
      Assertions.assertEquals("Supplement", file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")));
      Assertions.assertArrayEquals(member.bytes, AttachmentProfilePolicyTests.bytes(file));
      Assertions.assertTrue(TestSupport.metadataXml(document).contains("mailbox.dat"));
    }
    member.embedInPdf = false;
    Assertions.assertThrows(Common.UserFacingException.class, () -> PdfMailWriter.pdfEmbeddedFiles(record));
  }

  @Test void collectionExcelSettingWritesSidecarWithOtherEmbeddingFlagsDisabled() throws Exception {
    var config = PdfMailWriter.CONFIG.findingAid;
    boolean pdf = config.pdfEnabled, excel = config.excelEnabled, embed = config.embedExcel, embeddedPdf = config.embedPdfInCollections;
    boolean premis = PdfMailWriter.CONFIG.premis.enabled;
    try {
      config.pdfEnabled = true;
      config.excelEnabled = true;
      config.embedExcel = false;
      config.embedPdfInCollections = false;
      PdfMailWriter.CONFIG.premis.enabled = false;
      for (String profile : List.of("c", "ci")) {
        Path folder = directory.resolve(profile);
        Path output = folder.resolve("collection.pdf");
        PdfMailWriter.writeEaPdfCollection(output, List.of(record()), TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
        Path spreadsheet = folder.resolve("collection_index.xlsx");
        try (var paths = Files.list(folder)) {
          Assertions.assertEquals(java.util.Set.of(output, spreadsheet), paths.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toSet()));
        }
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(Files.newInputStream(spreadsheet))) {
          Assertions.assertEquals(1, workbook.getSheetAt(0).getLastRowNum());
        }
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          Assertions.assertFalse(AttachmentProfilePolicyTests.files(document).keySet().stream()
              .anyMatch(name -> name.endsWith("_finding_aid.pdf") || name.endsWith(".xlsx")));
          PdfCreationValidator.validate(document);
        }
      }
    } finally {
      config.pdfEnabled = pdf;
      config.excelEnabled = excel;
      config.embedExcel = embed;
      config.embedPdfInCollections = embeddedPdf;
      PdfMailWriter.CONFIG.premis.enabled = premis;
    }
  }

  @Test void collectionAuxiliariesAreSidecarsAndCreationGateRejectsUnrelatedFiles() throws Exception {
    boolean premis = PdfMailWriter.CONFIG.premis.enabled;
    try {
      PdfMailWriter.CONFIG.premis.enabled = true;
      for (String profile : List.of("c", "ci")) {
        Path output = directory.resolve("collection_" + profile + ".pdf");
        PdfMailWriter.writeEaPdfCollection(output, List.of(record()), TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
        Assertions.assertTrue(Files.exists(output.resolveSibling("collection_" + profile + "_premis.xml")));
        try (PDDocument finding = Loader.loadPDF(output.resolveSibling("collection_" + profile + "_finding_aid.pdf").toFile())) {
          Assertions.assertTrue(AttachmentProfilePolicyTests.files(finding).isEmpty());
          Assertions.assertFalse(TestSupport.metadataXml(finding).contains("pdfmailid:conformance"));
          Assertions.assertTrue(new org.apache.pdfbox.text.PDFTextStripper().getText(finding).contains("Finding Aid"));
        }
        try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
            Files.newInputStream(output.resolveSibling("collection_" + profile + "_index.xlsx")))) {
          Assertions.assertEquals(1, workbook.getSheetAt(0).getLastRowNum());
        }
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          for (var file : AttachmentProfilePolicyTests.files(document).values()) {
            Assertions.assertTrue("Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))
                || file.getFileUnicode().endsWith("_1s.pdf") || file.getFileUnicode().endsWith("_1si.pdf"));
          }
          PdfCreationValidator.validate(document);
          PdfFindingAidSupport.embedStandaloneCatalogBytes(document, "misc.xml", "<misc/>".getBytes(StandardCharsets.UTF_8),
              "Not an email or source", "application/xml", "Data", false);
          Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
        }
      }
    } finally { PdfMailWriter.CONFIG.premis.enabled = premis; }
  }
}
