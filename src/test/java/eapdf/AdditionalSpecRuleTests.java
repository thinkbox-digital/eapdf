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



  @Test void collectionAuxiliariesAreSidecarsAndCreationGateRejectsUnrelatedFiles() throws Exception {
    {
      for (String profile : List.of("c", "ci")) {
        Path output = directory.resolve("collection_" + profile + ".pdf");
        PdfMailWriter.writeEaPdfCollection(output, List.of(record()), TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          for (var file : AttachmentProfilePolicyTests.files(document).values()) {
            Assertions.assertTrue("Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))
                || file.getFileUnicode().endsWith("_1s.pdf") || file.getFileUnicode().endsWith("_1si.pdf"));
          }
          PdfCreationValidator.validate(document);
          PdfAttachmentSupport.embedStandaloneCatalogBytes(document, "misc.xml", "<misc/>".getBytes(StandardCharsets.UTF_8),
              "Not an email or source", "application/xml", "Data", false);
          Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
        }
      }
    }
  }
}
