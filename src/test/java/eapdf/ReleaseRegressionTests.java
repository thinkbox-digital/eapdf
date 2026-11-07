package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseRegressionTests {
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
  void realPstAttachedEmailPreservesItsBodyAndNestedDocx() throws Exception {
    var records = PstReader.readPst(TestSupport.fixturePath("attached-message.pst").toString(), "", List.of(), false);
    EmailRecord parent = records.stream().filter(record -> "FW: First email".equals(record.subject)).findFirst().orElseThrow();
    AssociatedFile attached = parent.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals("message/rfc822", attached.mime);
    EmailRecord child = EmlReader.readRfc822Bytes(attached.data(), "child", attached.filename, "", List.of(), false);
    Assertions.assertEquals("First email", child.subject);
    Assertions.assertTrue(Common.textBody(child).contains("Docx file attached."));
    Assertions.assertFalse(Common.nz(child.from).isBlank());
    AssociatedFile nested = child.associatedFiles.stream().filter(file -> "attachment.docx".equals(file.filename)).findFirst().orElseThrow();
    boolean found = false;
    try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(nested.data()))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals("word/document.xml")) {
          Assertions.assertTrue(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("This is a docx attachment."));
          found = true;
        }
      }
    }
    Assertions.assertTrue(found);
    try (PayloadStore store = new PayloadStore()) {
      store.stage(parent);
      Path output = tempDir.resolve("pst.pdf");
      PdfMailWriter.buildSingleMessagePdf(output, parent, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s", output);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        AssociatedFile source = parent.associatedFiles.stream().filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow();
        var embeddedSource = AttachmentProfilePolicyTests.files(document).values().stream()
            .filter(file -> "Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship"))))
            .findFirst().orElseThrow();
        Assertions.assertArrayEquals(source.data(), AttachmentProfilePolicyTests.bytes(embeddedSource));
      }
    }
  }

  @Test
  void checkpointSurvivesRestartAndRedownloadsCorruptedSource() throws Exception {
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var service = GmailDownloadRecoveryTests.service(attempts, 200);
    Path cache;
    try (GmailResumeStore resume = new GmailResumeStore(tempDir, "account:selection", List.of("message-id"))) {
      cache = resume.directory;
      var message = GmailDownloadToPdf.downloadMessage(service, "me", "message-id", resume);
      Assertions.assertNotNull(message.getRaw());
      if (Files.getFileStore(cache).supportsFileAttributeView("posix")) {
        Assertions.assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(cache));
        Assertions.assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(cache.resolve("manifest.json")));
      }
    }
    try (GmailResumeStore resume = new GmailResumeStore(tempDir, "account:selection", List.of("new-message"))) {
      Assertions.assertEquals(List.of("message-id"), resume.selectedIds());
      GmailDownloadToPdf.downloadMessage(service, "me", "message-id", resume);
      Assertions.assertEquals(1, attempts.get());
      Path source;
      try (var files = Files.list(cache)) {
        source = files.filter(path -> path.toString().endsWith(".eml")).findFirst().orElseThrow();
      }
      Files.writeString(source, "corrupted");
      GmailDownloadToPdf.downloadMessage(service, "me", "message-id", resume);
      Assertions.assertEquals(2, attempts.get());
    }
  }

  @Test
  void resumeIdentityUsesAssetContentsRatherThanTemporaryExtractionPaths() throws Exception {
    Path firstFont = tempDir.resolve("first.ttf");
    Path secondFont = tempDir.resolve("second.ttf");
    Path firstIcc = tempDir.resolve("first.icc");
    Path secondIcc = tempDir.resolve("second.icc");
    Files.copy(Path.of(TestSupport.fontPath()), firstFont);
    Files.copy(firstFont, secondFont);
    Files.copy(Path.of(TestSupport.iccPath()), firstIcc);
    Files.copy(firstIcc, secondIcc);
    String first = GmailDownloadToPdf.resumeScope("account@example.org", java.util.Map.of(), tempDir.toString(), null, firstFont.toString(), firstIcc.toString());
    String second = GmailDownloadToPdf.resumeScope("account@example.org", java.util.Map.of(), tempDir.toString(), null, secondFont.toString(), secondIcc.toString());
    Assertions.assertEquals(first, second);
    Files.writeString(secondIcc, "changed output intent");
    Assertions.assertNotEquals(first, GmailDownloadToPdf.resumeScope("account@example.org", java.util.Map.of(), tempDir.toString(), null, secondFont.toString(), secondIcc.toString()));
  }

  @Test
  void restartedArchiveCanBeReassembledEntirelyFromCheckpointedEmails() throws Exception {
    List<String> ids = List.of("first", "second");
    try (GmailResumeStore resume = new GmailResumeStore(tempDir, "archive-snapshot", ids)) {
      for (String id : ids) {
        byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: " + id + "\r\n\r\nPreserved " + id + " body").getBytes(StandardCharsets.UTF_8);
        resume.rememberDownload(id, new com.google.api.services.gmail.model.Message().setId(id)
            .setRaw(Base64.getUrlEncoder().encodeToString(raw)).setLabelIds(List.of("INBOX")));
      }
    }
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var unavailable = GmailDownloadRecoveryTests.service(attempts, 404);
    try (GmailResumeStore resume = new GmailResumeStore(tempDir, "archive-snapshot", List.of());
         PayloadStore store = new PayloadStore()) {
      List<EmailRecord> records = new ArrayList<>();
      for (String id : resume.selectedIds()) {
        var message = GmailDownloadToPdf.downloadMessage(unavailable, "me", id, resume);
        EmailRecord record = EmlReader.readRfc822Bytes(Base64.getUrlDecoder().decode(message.getRaw()), "gmail://" + id, id + ".eml", "", List.of(), false);
        store.stage(record);
        records.add(record);
      }
      Path output = tempDir.resolve("resumed_1m.pdf");
      PdfMailWriter.writeEaPdfArchive(output, records, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, "m");
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        String text = new PDFTextStripper().getText(document);
        Assertions.assertTrue(text.contains("Preserved first body"));
        Assertions.assertTrue(text.contains("Preserved second body"));
      }
    }
    Assertions.assertEquals(0, attempts.get());
  }

  @Test
  void checkpointsVerifyOutputsAndKeepAccountsSeparate() throws Exception {
    Path output = tempDir.resolve("message_000001.pdf");
    Files.writeString(output, "completed output");
    try (GmailResumeStore first = new GmailResumeStore(tempDir, "first-account", List.of("same-id"))) {
      first.rememberCompleted("same-id", List.of(output));
      Assertions.assertTrue(first.completed("same-id", tempDir));
      Assertions.assertThrows(Exception.class, () -> new GmailResumeStore(tempDir, "first-account", List.of()));
      try (GmailResumeStore second = new GmailResumeStore(tempDir, "second-account", List.of("same-id"))) {
        Assertions.assertFalse(second.completed("same-id", tempDir));
      }
    }
    try (GmailResumeStore resume = new GmailResumeStore(tempDir, "first-account", List.of())) {
      Assertions.assertTrue(resume.completed("same-id", tempDir));
      Files.writeString(output, "modified output!");
      Assertions.assertFalse(resume.completed("same-id", tempDir));
    }
  }

  @Test
  void fontsPreserveSeveralWritingSystemsInBodyAndGeneratedText() throws Exception {
    String text = "Café Ελληνικά Кириллица Հայերեն ქართული";
    byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Languages\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + text).getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "languages.eml", "languages.eml", "", List.of(), false);
    Path output = tempDir.resolve("languages.pdf");
    PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
    try (PDDocument document = Loader.loadPDF(output.toFile())) {
      Assertions.assertTrue(new PDFTextStripper().getText(document).contains(text));
    }
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      var font = PdfMailWriter.loadUiFont(document, TestSupport.fontPath());
      try (PDPageContentStream content = new PDPageContentStream(document, page)) {
        PdfMailWriter.drawText(content, font, 12, 40, 700, text);
      }
      Assertions.assertEquals(text, new PDFTextStripper().getText(document).trim());
    }
  }

  @Test
  void missingGlyphsAreReportedWithCodePointAndField() throws Exception {
    String missing = new String(Character.toChars(0x10FFFF));
    String subject = jakarta.mail.internet.MimeUtility.encodeText(missing, "UTF-8", "B");
    EmailRecord record = EmlReader.readRfc822Bytes(("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: " + subject + "\r\n\r\nBody").getBytes(StandardCharsets.UTF_8), "missing.eml", "missing.eml", "", List.of(), false);
    PdfMailWriter.writeEaPdfA3(tempDir.resolve("missing.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
    Assertions.assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("subject") && warning.contains("U+10FFFF")));
  }

  @Test
  void limitsRejectOversizedMessagesBodiesAndMetadataWithoutOutOfMemory() throws Exception {
    String messageSetting = System.getProperty("eapdf.maxMessageBytes");
    String bodySetting = System.getProperty("eapdf.maxBodyChars");
    String metadataSetting = System.getProperty("eapdf.maxMetadataBytes");
    try {
      System.setProperty("eapdf.maxMessageBytes", "64");
      Assertions.assertThrows(Common.UserFacingException.class, () -> EmlReader.readRfc822Bytes(new byte[65], "large.eml", "large.eml", "", List.of(), false));
      Assertions.assertThrows(Common.UserFacingException.class, () -> MboxPreservationTests.parse(MboxPreservationTests.ENVELOPE + "Subject: Large\n\n" + "x".repeat(100), "auto"));
      System.setProperty("eapdf.maxMessageBytes", "4096");
      System.setProperty("eapdf.maxBodyChars", "32");
      Assertions.assertThrows(Common.UserFacingException.class, () -> EmlReader.readRfc822Bytes(("Subject: Large\r\n\r\n" + "x".repeat(100)).getBytes(StandardCharsets.UTF_8), "large.eml", "large.eml", "", List.of(), false));
      System.setProperty("eapdf.maxMetadataBytes", "100");
      try (var job = Common.beginJob()) {
        Assertions.assertThrows(Common.UserFacingException.class, () -> Common.stageRecord(new EmailRecord()));
      }
    } finally {
      restore("eapdf.maxMessageBytes", messageSetting);
      restore("eapdf.maxBodyChars", bodySetting);
      restore("eapdf.maxMetadataBytes", metadataSetting);
    }
  }

  @Test
  void enabledAccessibilityPreservesHeadingsTablesAndAnnotationConnections() throws Exception {
    boolean previous = PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures;
    try {
      PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = true;
      EmailRecord first = EmlReader.readRfc822Bytes(("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: First\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
          + "<h1>Real heading</h1><p>Real paragraph <a href='https://example.org'>link</a></p>"
          + "<table><tr><th>Column</th></tr><tr><td>Cell</td></tr></table>").getBytes(StandardCharsets.UTF_8), "first.eml", "first.eml", "", List.of(), false);
      EmailRecord second = EmlReader.readRfc822Bytes("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Second\r\n\r\nSecond body".getBytes(StandardCharsets.UTF_8), "second.eml", "second.eml", "", List.of(), false);
      for (String profile : List.of("s", "si", "m", "mi", "c", "ci")) {
        Path output = tempDir.resolve("semantic_1" + profile + ".pdf");
        if (profile.startsWith("s")) {
          first.embedSourceMessage = profile.equals("s");
          PdfMailWriter.writeEaPdfA3(output, first, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
        } else if (profile.startsWith("m")) PdfMailWriter.writeEaPdfArchive(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        else PdfMailWriter.writeEaPdfCollection(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfuaid:part>2</pdfuaid:part>"));
          Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfuaid:rev>2024</pdfuaid:rev>"));
          var tree = (COSDictionary)document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
          List<String> roles = new ArrayList<>();
          collectRoles(tree.getDictionaryObject(COSName.K), roles);
          COSDictionary root = (COSDictionary)tree.getDictionaryObject(COSName.K);
          COSDictionary namespace = (COSDictionary)root.getDictionaryObject(COSName.getPDFName("NS"));
          Assertions.assertEquals("http://iso.org/pdf2/ssn", namespace.getString(COSName.getPDFName("NS")));
          var outline = document.getDocumentCatalog().getDocumentOutline().getFirstChild();
          Assertions.assertTrue(outline.getAction().getCOSObject().containsKey(COSName.getPDFName("SD")));
          Assertions.assertTrue(roles.contains("Table"), profile + roles);
          Assertions.assertTrue(roles.contains("TH"), profile + roles);
          Assertions.assertTrue(roles.contains("TD"), profile + roles);
          Assertions.assertTrue(roles.contains("H1"), profile + roles);
          for (PDPage page : document.getPages()) {
            Assertions.assertTrue(page.getCOSObject().containsKey(COSName.getPDFName("StructParents")));
            for (var annotation : page.getAnnotations()) Assertions.assertTrue(annotation.getCOSObject().containsKey(COSName.getPDFName("StructParent")));
          }
        }
      }
    } finally {
      PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = previous;
    }
  }

  static void collectRoles(COSBase value, List<String> roles) {
    if (value instanceof COSObject reference) value = reference.getObject();
    if (value instanceof COSArray array) {
      for (COSBase child : array) collectRoles(child, roles);
    } else if (value instanceof COSDictionary dictionary && dictionary.containsKey(COSName.S)) {
      roles.add(dictionary.getNameAsString(COSName.S));
      collectRoles(dictionary.getDictionaryObject(COSName.K), roles);
    }
  }

  static void restore(String key, String value) {
    if (value == null) System.clearProperty(key);
    else System.setProperty(key, value);
  }
}
