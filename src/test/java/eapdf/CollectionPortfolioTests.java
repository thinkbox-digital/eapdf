package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionPortfolioTests {
  private boolean previousFindingAid;
  @org.junit.jupiter.api.BeforeEach void enableFindingAidFixture() {
    previousFindingAid = PdfMailWriter.CONFIG.findingAid.pdfEnabled;
    PdfMailWriter.CONFIG.findingAid.pdfEnabled = true;
  }
  @org.junit.jupiter.api.AfterEach void restoreFindingAidDefault() {
    PdfMailWriter.CONFIG.findingAid.pdfEnabled = previousFindingAid;
  }


  @TempDir
  Path tempDir;

  @Test
  void writeEaPdfCollectionCreatesTopLevelFoldersAndExpectedSchema() throws Exception {
    List<EmailRecord> records = new ArrayList<>();
    Path emlPath = TestSupport.firstInputWithExtension(".eml");

    EmailRecord first = EmlReader.readEml(emlPath.toString(), "", List.of("greg@thinkbox.digital"), false);
    first.folderPath = "Inbox";
    first.subject = "Inbox One";
    records.add(first);

    EmailRecord second = EmlReader.readEml(emlPath.toString(), "", List.of("greg@thinkbox.digital"), false);
    second.folderPath = "Inbox";
    second.subject = "Inbox Two";
    records.add(second);

    EmailRecord third = EmlReader.readEml(emlPath.toString(), "", List.of("greg@thinkbox.digital"), false);
    third.folderPath = "Sent";
    third.subject = "Sent One";
    records.add(third);

    Path out = tempDir.resolve("test_1c.pdf");
    PdfMailWriter.writeEaPdfCollection(out, records, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir.resolve("debug"), "c");

    Assertions.assertTrue(Files.exists(out));

    try (PDDocument doc = Loader.loadPDF(out.toFile())) {
      PreservationRegressionTests.assertDPartTree(doc);
      Assertions.assertSame(doc.getPage(0).getCOSObject(),
          doc.getDocumentCatalog().getDocumentOutline().getFirstChild().findDestinationPage(doc).getCOSObject());
      String xmp = TestSupport.metadataXml(doc);
      Assertions.assertTrue(xmp.contains("<pdfmailid:conformance>c</pdfmailid:conformance>"));
      Assertions.assertEquals("UseAttachments", doc.getDocumentCatalog().getCOSObject().getNameAsString(COSName.PAGE_MODE));
      TestSupport.assertCollectionHasNoDefaultDocument(doc);
      TestSupport.assertAfPresent(doc);

      COSDictionary collection = TestSupport.collectionDictionary(doc);
      Assertions.assertNotNull(collection);
      Assertions.assertEquals("T", collection.getNameAsString(COSName.getPDFName("View")));
      Assertions.assertEquals(List.of("FileName", "Messages", "adobe:Order", "To", "From", "Sent", "Subject", "Message-ID", "Cc", "Bcc", "Size", "Attachments"), TestSupport.schemaFieldNames(doc));
      Assertions.assertEquals(List.of("Inbox", "Sent"), TestSupport.folderNames(doc));

      var embeddedNames = TestSupport.embeddedFileNames(doc);
      Assertions.assertEquals(6, embeddedNames.size());
      for (var file : AttachmentProfilePolicyTests.files(doc).values()) {
        var embedded = file.getEmbeddedFileUnicode();
        Assertions.assertEquals(COSName.FLATE_DECODE, embedded.getCOSObject().getDictionaryObject(COSName.FILTER));
        byte[] decoded = embedded.toByteArray();
        Assertions.assertEquals(decoded.length, embedded.getSize());
        Assertions.assertEquals(Common.md5Hex(decoded), ((org.apache.pdfbox.cos.COSString) embedded.getCOSObject().getCOSDictionary(COSName.PARAMS).getDictionaryObject(COSName.CHECK_SUM)).toHexString().toLowerCase(java.util.Locale.ROOT));
      }
      Assertions.assertFalse(embeddedNames.contains("Finding_Aid.pdf"));
      Assertions.assertFalse(embeddedNames.stream().anyMatch(name -> name.endsWith("_index.xlsx")));
      Assertions.assertFalse(embeddedNames.contains("test_1c_finding_aid.pdf"));
      Assertions.assertTrue(Files.exists(tempDir.resolve("test_1c_finding_aid.pdf")));
      Assertions.assertTrue(Files.exists(tempDir.resolve("test_1c_index.xlsx")));
      Assertions.assertEquals(3, embeddedNames.stream().filter(name -> name.startsWith("<")).count());
      Assertions.assertEquals(3, AttachmentProfilePolicyTests.files(doc).values().stream()
          .filter(file -> "Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))).count());
    }
  }

  @Test
  void collectionKeepsCaseVariantFoldersAndMatchesEverySourceAsset() throws Exception {
    for (String profile : List.of("c", "ci")) {
      EmailRecord first = AttachmentProfilePolicyTests.record("Uppercase folder");
      first.folderPath = "Work, work";
      EmailRecord second = AttachmentProfilePolicyTests.record("Lowercase folder");
      second.folderPath = "work";
      Path output = tempDir.resolve("case_1" + profile + ".pdf");
      PdfMailWriter.writeEaPdfCollection(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        Assertions.assertEquals(List.of("Work"), TestSupport.folderNames(document));
        int messages = 0;
        int sources = 0;
        java.util.Set<String> identifiers = new java.util.HashSet<>();
        String metadata = TestSupport.metadataXml(document);
        for (var file : AttachmentProfilePolicyTests.files(document).values()) {
          if ("Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))) {
            sources++;
            Assertions.assertTrue(metadata.contains("<pdfmailmeta:Filename>" + file.getFileUnicode() + "</pdfmailmeta:Filename>"));
            byte[] sourceBytes = AttachmentProfilePolicyTests.bytes(file);
            Assertions.assertTrue(metadata.contains("<pdfmailmeta:Checksum>" + Common.md5Hex(sourceBytes) + "</pdfmailmeta:Checksum>"));
            Assertions.assertTrue(first.associatedFiles.stream().anyMatch(source -> java.util.Arrays.equals(source.bytes, sourceBytes))
                || second.associatedFiles.stream().anyMatch(source -> java.util.Arrays.equals(source.bytes, sourceBytes)));
          }
          if (!"Contained EA-PDF message".equals(file.getCOSObject().getString(COSName.DESC))) continue;
          try (PDDocument child = Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))) {
            String childMetadata = TestSupport.metadataXml(child);
            Assertions.assertTrue(childMetadata.contains(first.mailGuid) || childMetadata.contains(second.mailGuid));
            if (childMetadata.contains(first.mailGuid)) identifiers.add(first.mailGuid);
            if (childMetadata.contains(second.mailGuid)) identifiers.add(second.mailGuid);
          }
          messages++;
        }
        Assertions.assertEquals(2, messages);
        Assertions.assertEquals(java.util.Set.of(first.mailGuid, second.mailGuid), identifiers);
        Assertions.assertEquals(profile.equals("c") ? 2 : 0, sources);
      }
    }
  }
}
