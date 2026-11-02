package eapdf;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemainingWorkflowTests {
  @TempDir
  Path tempDir;

  @Test
  void existingCliOptionsRejectUnknownMissingExtraAndInvalidNumericArguments() {
    for (String[] arguments : List.of(new String[]{"--ot", "out", "input.eml"}, new String[]{"--out"},
        new String[]{"--out", "--debug"}, new String[]{"one.eml", "two.eml"},
        new String[]{"--jobs", "zero", "input.eml"}, new String[]{"--jobs", "-1", "input.eml"},
        new String[]{"--jobs", "0", "input.eml"}, new String[]{"--archive-profile", "s", "input.eml"},
        new String[]{"--archive-output", "archive.pdf", "input.eml"})) {
      Assertions.assertThrows(Common.UserFacingException.class, () -> EmailToPDFA.parseArgs(arguments));
    }
    Assertions.assertEquals(2, EmailToPDFA.parseArgs(new String[]{"-j", "2", "--email", "a@example.org", "input.eml"}).get("jobs"));
    for (String[] arguments : List.of(new String[]{"--unknown"}, new String[]{"--limit"}, new String[]{"--limit", "-1"},
        new String[]{"--limit", "not-a-number"}, new String[]{"unexpected"}, new String[]{"--archive-profile", "invalid"})) {
      Assertions.assertThrows(Common.UserFacingException.class, () -> GmailDownloadToPdf.parseArgs(arguments));
    }
    Assertions.assertEquals(0, GmailDownloadToPdf.parseArgs(new String[]{"--limit", "0"}).get("limit"));
    Assertions.assertThrows(Common.UserFacingException.class, () -> MonthlyFilenameArchiveToPdf.parseArgs(new String[]{"--out"}));
    Assertions.assertThrows(Common.UserFacingException.class, () -> MonthlyFilenameArchiveToPdf.parseArgs(new String[]{"one", "two"}));
    Assertions.assertEquals("input", MonthlyFilenameArchiveToPdf.parseArgs(new String[]{"--debug", "input"}).get("in"));
  }

  @Test
  void emptyAndUnsupportedFoldersFailWithSkippedFileReasons() throws Exception {
    Assertions.assertThrows(Common.UserFacingException.class, () -> EmailToPDFA.collectInputs(tempDir.toString()));
    Files.writeString(tempDir.resolve("notes.txt"), "not email");
    Files.writeString(tempDir.resolve("invalid.msg"), "not Outlook or RFC822");
    ByteArrayOutputStream messages = new ByteArrayOutputStream();
    PrintStream previous = System.out;
    try {
      System.setOut(new PrintStream(messages, true, StandardCharsets.UTF_8));
      Assertions.assertThrows(Common.UserFacingException.class, () -> EmailToPDFA.collectInputs(tempDir.toString()));
    } finally {
      System.setOut(previous);
    }
    Assertions.assertTrue(messages.toString(StandardCharsets.UTF_8).contains("unsupported extension"));
    Assertions.assertTrue(messages.toString(StandardCharsets.UTF_8).contains("not an Outlook MSG or RFC 822 email"));
    Path email = tempDir.resolve("valid.eml");
    Files.writeString(email, "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Valid\r\n\r\nBody");
    Assertions.assertEquals(List.of(email.toString()), EmailToPDFA.collectInputs(tempDir.toString()));
  }

  @Test
  void parallelOutputsKeepTheSequentialInputToFilenameMapping() throws Exception {
    Path first = tempDir.resolve("first.eml");
    Path second = tempDir.resolve("second.eml");
    Files.writeString(first, "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: First\r\n\r\n" + "Larger body\r\n".repeat(150));
    Files.writeString(second, "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Second\r\n\r\nSmall body");
    for (int run = 0; run < 2; run++) {
      Path output = tempDir.resolve("parallel" + run);
      Files.createDirectories(output);
      EmailToPDFA.runParallel(List.of(first.toString(), second.toString()), output.toString(), TestSupport.fontPath(), TestSupport.iccPath(), "", List.of(), false, tempDir.toString(), 2, true, false);
      for (String filename : List.of("first__First_000001.pdf", "second__Second_000002.pdf")) {
        try (PDDocument document = Loader.loadPDF(output.resolve(filename).toFile())) {
          byte[] source = AttachmentProfilePolicyTests.bytes(AttachmentProfilePolicyTests.files(document).values().iterator().next());
          Assertions.assertArrayEquals(Files.readAllBytes(filename.startsWith("first") ? first : second), source);
        }
      }
    }
  }

  @Test
  void failedAtomicWritePreservesPreviousOutputAndCleansTemporaryFile() throws Exception {
    Path output = tempDir.resolve("archive.pdf");
    Files.writeString(output, "previous complete archive");
    Assertions.assertThrows(java.io.IOException.class, () -> Common.writeAtomically(output, temporary -> {
      Files.writeString(temporary, "partial replacement");
      throw new java.io.IOException("interrupted write");
    }));
    Assertions.assertEquals("previous complete archive", Files.readString(output));
    try (var files = Files.list(tempDir)) {
      Assertions.assertEquals(List.of(output), files.toList());
    }
    Common.writeAtomically(output, "new complete archive".getBytes(StandardCharsets.UTF_8));
    Assertions.assertEquals("new complete archive", Files.readString(output));
  }

  @Test
  void configurationRejectsUnsupportedVersionsAndWrongTypesAndWarnsForUnknownKeys() throws Exception {
    Path config = tempDir.resolve("config.json");
    for (String json : List.of("[]", "{\"configVersion\":2}", "{\"configVersion\":1.5}",
        "{\"branding\":{\"enabled\":\"false\"}}", "{\"findingAid\":true}")) {
      Files.writeString(config, json);
      Assertions.assertThrows(RuntimeException.class, () -> EapdfConfig.load(config));
    }
    Files.writeString(config, "{\"configVersion\":1,\"findingAid\":{\"pdfEnabled\":false,\"typo\":true},\"accessibility\":{\"experimental\":{\"altTextGeneratedImages\":true}}}");
    ByteArrayOutputStream warnings = new ByteArrayOutputStream();
    PrintStream previous = System.err;
    try {
      System.setErr(new PrintStream(warnings, true, StandardCharsets.UTF_8));
      Assertions.assertFalse(EapdfConfig.load(config).findingAid.pdfEnabled);
    } finally {
      System.setErr(previous);
    }
    Assertions.assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("findingAid.typo"));
    Assertions.assertTrue(warnings.toString(StandardCharsets.UTF_8).contains("unavailable"));
  }

  @Test
  void disabledCollectionFindingAidAndBrandingLeaveRequiredFrontMatterOnly() throws Exception {
    EapdfConfig config = PdfMailWriter.CONFIG;
    boolean pdfEnabled = config.findingAid.pdfEnabled;
    boolean brandingEnabled = config.branding.enabled;
    boolean excelEnabled = config.findingAid.excelEnabled;
    try {
      config.findingAid.pdfEnabled = false;
      config.branding.enabled = false;
      config.findingAid.excelEnabled = true;
      for (String profile : List.of("c", "ci")) {
        Path output = tempDir.resolve("minimal_1" + profile + ".pdf");
        EmailRecord record = AttachmentProfilePolicyTests.record("Optional features off");
        PdfMailWriter.writeEaPdfCollection(output, List.of(record), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          String text = new PDFTextStripper().getText(document);
          Assertions.assertTrue(text.contains("Email Collection"));
          Assertions.assertFalse(text.contains("Finding Aid"));
          Assertions.assertFalse(text.contains("Email converted by ThinkBox"));
          var files = AttachmentProfilePolicyTests.files(document);
          Assertions.assertFalse(files.keySet().stream().anyMatch(name -> name.endsWith(".xlsx") || name.equals("Finding_Aid.pdf")));
          PreservationRegressionTests.assertDPartTree(document);
          for (var file : files.values()) {
            if (!"Contained EA-PDF message".equals(file.getCOSObject().getString(org.apache.pdfbox.cos.COSName.DESC))) continue;
            try (PDDocument child = Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))) {
              Assertions.assertFalse(new PDFTextStripper().getText(child).contains("Email converted by ThinkBox"));
            }
          }
        }
        Assertions.assertFalse(Files.exists(PdfFindingAidSupport.findingAidSpreadsheetPath(output)));
      }
    } finally {
      config.findingAid.pdfEnabled = pdfEnabled;
      config.branding.enabled = brandingEnabled;
      config.findingAid.excelEnabled = excelEnabled;
    }
  }

  @Test
  void structuredFolderMembershipsPreserveCommasAndDistinctSanitizedNames() throws Exception {
    EmailRecord pst = AttachmentProfilePolicyTests.record("PST folder");
    pst.folderPath = "Root/Team, Finance";
    pst.folderMemberships.add(pst.folderPath);
    EmailRecord similar = AttachmentProfilePolicyTests.record("Different folder");
    similar.folderPath = "Root_Team, Finance";
    similar.folderMemberships.add(similar.folderPath);
    EmailRecord gmail = AttachmentProfilePolicyTests.record("Gmail labels");
    gmail.folderMemberships.addAll(GmailDownloadToPdf.messageLabelNames(List.of("Label_1", "Label_2"), Map.of("Label_1", "Team, Finance", "Label_2", "Projects"), "ALL_MAIL"));
    Assertions.assertEquals(List.of("Root/Team, Finance"), PdfCollectionSupport.collectionGroupNames(pst));
    Assertions.assertEquals(List.of("Team, Finance", "Projects"), PdfMailWriter.collectionGroupNames(gmail));
    var sections = PdfCollectionSupport.buildFolderSections(List.of(pst, similar, gmail));
    Assertions.assertEquals(4, sections.size());
    var folders = PdfCollectionSupport.buildCollectionFolders(sections);
    Assertions.assertEquals(4, folders.stream().map(folder -> folder.name).distinct().count());
    Assertions.assertEquals(List.of("Root/Team, Finance", "Root_Team, Finance"), sections.stream().map(section -> section.folderName).filter(name -> name.startsWith("Root")).toList());
    for (String profile : List.of("c", "ci")) {
      Path output = tempDir.resolve("folder_identity_1" + profile + ".pdf");
      PdfMailWriter.writeEaPdfCollection(output, List.of(pst, similar), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        Assertions.assertEquals(2, TestSupport.folderNames(document).size());
        Assertions.assertEquals(2, TestSupport.folderNames(document).stream().distinct().count());
        Assertions.assertEquals(2, AttachmentProfilePolicyTests.files(document).values().stream()
            .filter(file -> "Contained EA-PDF message".equals(file.getCOSObject().getString(org.apache.pdfbox.cos.COSName.DESC))).count());
      }
    }
  }

  @Test
  void collectionCompatibilityEntrypointsUseTheCompleteSharedSchema() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PdfMailWriter.addTopLevelCollectionDictionary(document, List.of());
      List<String> required = List.of("To", "From", "Sent", "Subject", "Message-ID", "Cc", "Bcc", "Size", "Attachments");
      Assertions.assertTrue(TestSupport.schemaFieldNames(document).containsAll(required));
      PdfMailWriter.addMessageCollectionDictionary(document);
      Assertions.assertTrue(TestSupport.schemaFieldNames(document).containsAll(required));
    }
  }
}
