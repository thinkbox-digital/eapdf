package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AttachmentProfilePolicyTests {
  static final byte[] PAYLOAD = "Original native attachment bytes".getBytes(StandardCharsets.UTF_8);

  @TempDir
  Path tempDir;

  static EmailRecord record(String subject) throws Exception {
    EmailRecord record = new EmailRecord();
    record.sourceType = "pst";
    record.subject = subject;
    record.textBody = "Email body";
    record.htmlBody = "<p>Email body</p>";
    record.rawDateHeader = "Tue, 13 Oct 2026 12:00:00 +0000";
    record.sentXmp = "2026-10-13T12:00:00Z";
    AssociatedFile attachment = new AssociatedFile();
    attachment.filename = "native.txt";
    attachment.mime = "text/plain";
    attachment.afRelationship = "Data";
    attachment.description = "Original PST attachment";
    attachment.emailAttachment = true;
    attachment.bytes = PAYLOAD;
    record.associatedFiles.add(attachment);
    Common.finalizeRecordMetadata(record);
    return record;
  }

  @Test
  void sourceAttachmentSummaryListsEachFilenameWithoutLinks() throws Exception {
    EmailRecord synthesized = record("Attachment summary");
    synthesized.sourceFilename = "original & source.eml";
    synthesized.associatedFiles.get(0).filename = "first & <notes>.txt";
    AssociatedFile empty = new AssociatedFile();
    empty.filename = "empty.txt";
    empty.mime = "text/plain";
    empty.emailAttachment = true;
    empty.afRelationship = "Data";
    empty.bytes = new byte[0];
    synthesized.associatedFiles.add(empty);
    byte[] source = Common.synthesizeEml(synthesized);
    EmailRecord parsed = EmlReader.readRfc822Bytes(source, "original.eml", synthesized.sourceFilename, "", List.of(), false);
    for (EmailRecord record : List.of(synthesized, parsed)) {
      AssociatedFile unrelated = new AssociatedFile();
      unrelated.filename = "not-an-email-attachment.xml";
      unrelated.afRelationship = "Supplement";
      record.associatedFiles.add(unrelated);
      for (String profile : List.of("s", "m")) {
        String html = PdfMailWriter.buildRenderHtml(record, false, tempDir, profile);
        var document = org.jsoup.Jsoup.parse(html);
        var parent = document.selectFirst(".attachments .att-parent");
        var child = document.selectFirst(".attachments .att-child");
        Assertions.assertNotNull(parent);
        Assertions.assertNotNull(child);
        Assertions.assertTrue(parent.text().contains("Source file:"));
        Assertions.assertTrue(parent.text().contains(synthesized.sourceFilename));
        Assertions.assertEquals("Source file attachments: first & <notes>.txt empty.txt", child.text());
        Assertions.assertEquals(2, child.children().size(), "Each attachment has its own line");
        Assertions.assertTrue(document.select(".attachments a").isEmpty(), "File lists are plain text");
        Assertions.assertTrue(html.contains("first &amp; &lt;notes&gt;.txt"));
        Assertions.assertFalse(child.text().contains(unrelated.filename));
        Assertions.assertSame(parent, child.previousElementSibling());
      }
    }
    EmailRecord withoutAttachments = record("No attachments");
    withoutAttachments.associatedFiles.clear();
    var noAttachments = org.jsoup.Jsoup.parse(PdfMailWriter.buildRenderHtml(withoutAttachments, false, tempDir, "s"));
    Assertions.assertNotNull(noAttachments.selectFirst(".att-parent"));
    Assertions.assertNull(noAttachments.selectFirst(".att-child"));
    var isolated = org.jsoup.Jsoup.parse(PdfMailWriter.buildRenderHtml(parsed, false, tempDir, "si"));
    Assertions.assertNull(isolated.selectFirst(".att-parent"));
    Assertions.assertNull(isolated.selectFirst(".att-child"));
  }

  @Test
  void nativeAttachmentsStayOnTheMessageContinuationWithoutDuplicateBodyBadges() throws Exception {
    EmailRecord original = record("Listed native attachment");
    byte[] source = Common.synthesizeEml(original);
    for (String profile : List.of("s", "si")) {
      EmailRecord record = EmlReader.readRfc822Bytes(source, "original.eml", "original.eml", "", List.of(), false);
      byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("listed-" + profile + ".pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, profile).pdfBytes;
      try (PDDocument document = Loader.loadPDF(pdf)) {
        Assertions.assertEquals(2, document.getNumberOfPages());
        Assertions.assertTrue(document.getPage(0).getAnnotations().isEmpty(), "No extra icon on the email page");
        Assertions.assertEquals(1, document.getPage(1).getAnnotations().size(), "One native annotation per attachment");
        var firstPart = document.getPage(0).getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
        Assertions.assertSame(firstPart, document.getPage(1).getCOSObject().getDictionaryObject(COSName.getPDFName("DPart")),
            "Attachment continuation is part of its owning email body rendering");
        var stripper = new org.apache.pdfbox.text.PDFTextStripper();
        stripper.setStartPage(1);
        stripper.setEndPage(1);
        String firstPage = stripper.getText(document).replaceAll("(?U)\\s+", " ");
        if (profile.equals("s")) {
          Assertions.assertTrue(firstPage.contains("Source file: original.eml"));
          Assertions.assertTrue(firstPage.contains("Source file attachments: native.txt"));
          Assertions.assertEquals(1, files(document).size());
          Assertions.assertArrayEquals(source, bytes(files(document).values().iterator().next()));
        } else {
          Assertions.assertTrue(files(document).isEmpty());
        }
        assertMessageAttachments(document, profile.equals("si"), PAYLOAD);
        PdfCreationValidator.validate(document);
      }
    }
  }

  @Test
  void filenameNormalizationRemovesNulsWithoutChangingOtherCharactersOrPayloads() throws Exception {
    String filename = " Résumé & 中文 📎.txt ";
    Assertions.assertEquals(filename, Common.normalizeAttachmentFilename("\0" + filename + "\0\0"));
    Assertions.assertEquals("", Common.normalizeAttachmentFilename(null));
    Assertions.assertEquals("", Common.normalizeAttachmentFilename("\0\0"));
    EmailRecord record = record("Filename normalization");
    AssociatedFile attachment = record.associatedFiles.get(0);
    attachment.filename = filename + "\0";
    Common.finalizeRecordMetadata(record);
    Assertions.assertEquals(filename, attachment.filename);
    Assertions.assertArrayEquals(PAYLOAD, attachment.bytes);
  }

  @Test
  void serializedNamesAndSourceEmailFilenamesContainNoNuls() throws Exception {
    for (String profile : List.of("s", "si", "m", "mi")) {
      EmailRecord record = record("Clean filename");
      record.associatedFiles.get(0).filename = "AreaHotels.txt\0\0";
      Path output = tempDir.resolve("clean_1" + profile + ".pdf");
      byte[] pdf;
      if (profile.startsWith("s")) {
        pdf = PdfMailWriter.buildSingleMessagePdf(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, profile).pdfBytes;
      } else {
        PdfMailWriter.writeEaPdfArchive(output, List.of(record, record("Second email")), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        pdf = java.nio.file.Files.readAllBytes(output);
      }
      try (PDDocument document = Loader.loadPDF(pdf)) {
        for (Map.Entry<String, PDComplexFileSpecification> entry : files(document).entrySet()) {
          Assertions.assertFalse(entry.getKey().contains("\0"));
          Assertions.assertFalse(entry.getValue().getFile().contains("\0"));
          Assertions.assertFalse(entry.getValue().getFileUnicode().contains("\0"));
        }
        if (profile.endsWith("i")) {
          Assertions.assertFalse(files(document).containsKey("AreaHotels.txt"));
          assertMessageAttachments(document, true, PAYLOAD);
          Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfmailmeta:Filename>AreaHotels.txt</pdfmailmeta:Filename>"));
        } else {
          boolean found = false;
          for (PDComplexFileSpecification file : files(document).values()) {
            if (!"Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))) continue;
            EmailRecord decoded = EmlReader.readRfc822Bytes(bytes(file), "embedded.eml", "embedded.eml", "", List.of(), false);
            found |= decoded.associatedFiles.stream().anyMatch(attachment -> "AreaHotels.txt".equals(attachment.filename) && Arrays.equals(PAYLOAD, attachment.bytes));
          }
          Assertions.assertTrue(found);
        }
      }
    }
  }

  @Test
  void allSixProfilesKeepNativeAttachmentsOutOfCatalogListings() throws Exception {
    for (String profile : List.of("s", "si", "m", "mi", "c", "ci")) {
      EmailRecord first = record("First email");
      Path output = tempDir.resolve("sample_1" + profile + ".pdf");
      boolean isolated = profile.endsWith("i");
      if (profile.startsWith("s")) {
        byte[] bytes = PdfMailWriter.buildSingleMessagePdf(output, first, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, profile).pdfBytes;
        try (PDDocument document = Loader.loadPDF(bytes)) {
          assertMessageAttachments(document, isolated, PAYLOAD);
        }
      } else if (profile.startsWith("m")) {
        PdfMailWriter.writeEaPdfArchive(output, List.of(first, record("Second email")), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          assertMessageAttachments(document, isolated, PAYLOAD);
        }
      } else {
        PdfMailWriter.writeEaPdfCollection(output, List.of(first, record("Second email")), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          int inspected = 0;
          for (PDComplexFileSpecification file : files(document).values()) {
            if (!"Contained EA-PDF message".equals(file.getCOSObject().getString(COSName.DESC))) continue;
            try (PDDocument child = Loader.loadPDF(bytes(file))) {
              assertMessageAttachments(child, isolated, PAYLOAD);
              inspected++;
            }
          }
          Assertions.assertEquals(2, inspected);
        }
      }
    }
  }

  @Test
  void nonIsolatedRealPstAttachmentBytesSurviveInsideEmbeddedEmail() throws Exception {
    List<EmailRecord> records = PstReader.readPst(TestSupport.fixturePath("test.pst").toString(), "", List.of(), false);
    Assertions.assertTrue(records.stream().flatMap(candidate -> candidate.associatedFiles.stream()).noneMatch(file -> file.filename.contains("\0")));
    EmailRecord record = records.stream().filter(candidate -> candidate.associatedFiles.stream().anyMatch(file -> file.emailAttachment)).findFirst().orElseThrow();
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("pst_1s.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      Map<String, PDComplexFileSpecification> embedded = files(document);
      Assertions.assertEquals(2, embedded.size());
      AssociatedFile headers = record.associatedFiles.stream().filter(file -> file.sourceSetMember).findFirst().orElseThrow();
      Assertions.assertArrayEquals(headers.data(), bytes(embedded.get(headers.filename)));
      PDComplexFileSpecification source = embedded.values().stream().filter(file ->
          "Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))).findFirst().orElseThrow();
      Assertions.assertEquals("Source", source.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")));
      Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfmailmeta:Filename>message.eml</pdfmailmeta:Filename>"));
      EmailRecord decoded = EmlReader.readRfc822Bytes(bytes(source), "embedded.eml", "embedded.eml", "", List.of(), false);
      for (AssociatedFile original : record.associatedFiles) {
        if (!original.emailAttachment) continue;
        Assertions.assertTrue(decoded.associatedFiles.stream().anyMatch(file -> file.emailAttachment && Arrays.equals(original.bytes, file.bytes)),
            "Native bytes must remain inside the source email: " + original.filename);
      }
    }
  }

  @Test
  void defaultPstProfilePreservesExportAndAttachments() throws Exception {
    EmailRecord record = record("Default PST profile");
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("pst.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1).pdfBytes;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfmailid:conformance>s</pdfmailid:conformance>"));
      assertMessageAttachments(document, false, PAYLOAD);
    }
  }

  @Test
  void generatedListsAndLinksFollowTheSameProfilePolicy() throws Exception {
    for (String profile : List.of("s", "si")) {
      EmailRecord record = record("Attachment list");
      String html = PdfMailWriter.buildRenderHtml(record, false, tempDir, profile);
      Assertions.assertTrue(html.contains("native.txt"));
      Assertions.assertTrue(PdfMailWriter.emailAttachmentFiles(record).isEmpty());
      Assertions.assertEquals(!profile.endsWith("i"), html.contains("Source file:"));
      Assertions.assertEquals(1, record.attachmentCount);
    }
  }



  @Test
  void nonIsolatedPolicyAlsoAppliesToOriginalEmlSourcesWithoutChangingTheirBytes() throws Exception {
    EmailRecord original = record("EML source");
    byte[] sourceBytes = Common.synthesizeEml(original);
    EmailRecord record = EmlReader.readRfc822Bytes(sourceBytes, "original.eml", "original.eml", "", List.of(), false);
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("eml_1s.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      Assertions.assertEquals(1, files(document).size());
      Assertions.assertArrayEquals(sourceBytes, bytes(files(document).values().iterator().next()));
      assertMessageAttachments(document, false, PAYLOAD);
    }
  }

  static Map<String, PDComplexFileSpecification> files(PDDocument document) throws Exception {
    var names = document.getDocumentCatalog().getNames();
    if (names == null || names.getEmbeddedFiles() == null || names.getEmbeddedFiles().getNames() == null) return Map.of();
    return names.getEmbeddedFiles().getNames();
  }

  static byte[] bytes(PDComplexFileSpecification file) throws Exception {
    return file.getEmbeddedFileUnicode().toByteArray();
  }

  static void assertMessageAttachments(PDDocument document, boolean isolated, byte[] payload) throws Exception {
    boolean foundNative = false;
    boolean foundSource = false;
    for (PDComplexFileSpecification file : files(document).values()) {
      boolean source = "Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")));
      if (source) {
        foundSource = true;
        Assertions.assertFalse(isolated);
        EmailRecord decoded = EmlReader.readRfc822Bytes(bytes(file), "embedded.eml", "embedded.eml", "", List.of(), false);
        Assertions.assertTrue(decoded.associatedFiles.stream().anyMatch(attachment -> attachment.emailAttachment && Arrays.equals(payload, attachment.bytes)));
      } else if (Arrays.equals(payload, bytes(file))) {
        foundNative = true;
        Assertions.fail("Native attachments must not be separately embedded: " + file.getFileUnicode());
      } else {
        Assertions.assertTrue(file.getFileUnicode().endsWith(".xlsx") || file.getFileUnicode().equals("Finding_Aid.pdf"),
            "Unexpected separate file: " + file.getFileUnicode());
      }
    }
    Assertions.assertFalse(foundNative);
    Assertions.assertEquals(!isolated, foundSource);
    String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
    Assertions.assertFalse(text.contains("Attachment contents omitted"));
    Assertions.assertFalse(text.contains("These empty placeholders"));
    Assertions.assertFalse(text.contains("Original email attachments are available from"));
    if (isolated) Assertions.assertTrue(text.contains(
        "These placeholders identify attachments; their native bytes are not embedded separately."));
    int references = 0;
    int noticeReferences = 0;
    for (var page : document.getPages()) {
      for (var annotation : page.getAnnotations()) {
        if (!(annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment attachment)) continue;
        PDComplexFileSpecification file = (PDComplexFileSpecification) attachment.getFile();
        if (isolated) {
          if (!Common.nz(annotation.getContents()).startsWith("Attachment contents omitted: ")) continue;
          Assertions.assertEquals(0, bytes(file).length);
        } else {
          if (!Common.nz(annotation.getContents()).startsWith("Original email attachment: ")) continue;
          Assertions.assertTrue(bytes(file).length > 0);
          Assertions.assertArrayEquals(payload, bytes(file));
          Assertions.assertTrue(files(document).values().stream().noneMatch(source -> source.getCOSObject() == file.getCOSObject()),
              "Native annotations must not replace or duplicate catalog source entries");
          noticeReferences++;
        }
        Assertions.assertNotNull(file.getEmbeddedFile().getModDate());
        Assertions.assertTrue(page.getCropBox().contains(annotation.getRectangle().getLowerLeftX(), annotation.getRectangle().getLowerLeftY()));
        Assertions.assertTrue(page.getCropBox().contains(annotation.getRectangle().getUpperRightX(), annotation.getRectangle().getUpperRightY()));
        references++;
      }
    }
    Assertions.assertTrue(references > 0, "Attachments must retain visible references");
    if (!isolated) {
      Assertions.assertTrue(noticeReferences > 0, "Native attachments must be directly extractable from page annotations");
      for (var key : document.getDocument().getXrefTable().keySet()) {
        var object = document.getDocument().getObjectFromPool(key);
        if (object.getObject() instanceof org.apache.pdfbox.cos.COSStream stream
            && "EmbeddedFile".equals(stream.getNameAsString(COSName.TYPE))) {
          try (var input = stream.createInputStream()) {
            Assertions.assertTrue(input.readAllBytes().length > 0, "Source-backed outputs must not create empty embedded files");
          }
        }
      }
    }
  }
}
