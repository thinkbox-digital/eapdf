package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Small fixtures isolate adverse paths without scanning large mailbox containers. */
class SmallInputReliabilityTests {
  @TempDir Path directory;

  static EmailRecord parse(byte[] bytes) throws Exception {
    return EmlReader.readRfc822Bytes(bytes, "edge.eml", "edge.eml", "", List.of(), false);
  }

  @Test void nativeMsgAttachmentOrderDoesNotDependOnObjectHashCodes() throws Exception {
    // Check repeatability without inventing a required Outlook display order.
    List<String> first = null;
    for (int repetition = 0; repetition < 12; repetition++) {
      var record = MsgReader.readMsg("in/EmailWith2Attachments.msg", "", List.of(), false);
      var names = record.associatedFiles.stream().filter(file -> file.emailAttachment).map(file -> file.filename).toList();
      assertEquals(java.util.Set.of("Installatie handleiding.docx", "Installatiehandleiding S&I Agro.xlsx"), java.util.Set.copyOf(names));
      assertEquals(2, names.size());
      if (first == null) first = names;
      else assertEquals(first, names);
    }
  }

  @Test void invalidUtf8MutationCorpusRetainsOriginalAndReportsEveryRecovery() throws Exception {
    for (int value = 0x80; value <= 0xff; value++) {
      byte[] raw = TextDecodeWarningTests.message("text/plain; charset=UTF-8", new byte[]{'A', (byte)value, 'Z'});
      var record = parse(raw);
      assertArrayEquals(raw, record.sourceBytes);
      assertTrue(record.textBody.startsWith("A") && record.textBody.endsWith("Z"));
      assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.TEXT_RECOVERED), "Byte " + value);
    }
  }

  @Test void nestedAttachedMessagesAreRejectedAtConfiguredDepth() {
    String before = System.getProperty("eapdf.maxMimeDepth");
    try {
      System.setProperty("eapdf.maxMimeDepth", "4");
      String raw = "Content-Type: text/plain\r\n\r\nLeaf";
      for (int i = 0; i < 20; i++) raw = "Content-Type: message/rfc822\r\n\r\n" + raw;
      byte[] nested = raw.getBytes(StandardCharsets.UTF_8);
      var error = assertThrows(Common.UserFacingException.class, () -> parse(nested));
      assertTrue(error.getMessage().contains("MIME structure exceeds"));
    } finally { if (before == null) System.clearProperty("eapdf.maxMimeDepth"); else System.setProperty("eapdf.maxMimeDepth", before); }
  }

  @Test void cancellationStopsRealSourceReadingWithoutPublishingAPdf() throws Exception {
    Thread.currentThread().interrupt();
    try {
      assertThrows(Common.UserFacingException.class,
          () -> EmlReader.readEml(Path.of("in/sample.eml").toString(), "", List.of(), false));
      assertTrue(Thread.currentThread().isInterrupted());
      try (var files = java.nio.file.Files.list(directory)) { assertEquals(0, files.count()); }
    } finally { Thread.interrupted(); }
  }

  @Test void multilingualTableRetainsCellsAndExactOriginal() throws Exception {
    String html = "<html><body><h1>Résumé – Καλημέρα – Привет</h1><table style='width:900px'>"
        + "<tr><th colspan='2'>Résumé</th><th>Καλημέρα</th></tr>";
    for (int row = 1; row <= 30; row++) html += "<tr><td>R" + row + "C1</td><td>Привет</td><td>R" + row + "C3</td></tr>";
    html += "</table><p>End of multilingual table</p></body></html>";
    byte[] raw = TextDecodeWarningTests.message("text/html; charset=UTF-8", html.getBytes(StandardCharsets.UTF_8));
    var record = parse(raw);
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(directory.resolve("table.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
    try (var document = Loader.loadPDF(pdf)) {
      String text = new PDFTextStripper().getText(document);
      for (String required : List.of("Résumé", "Καλημέρα", "Привет", "R1C1", "R30C3", "End of multilingual table"))
        assertTrue(text.contains(required), required);
      var files = AttachmentProfilePolicyTests.files(document);
      assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
      WideEmailTableTests.assertTextFits(document);
    }
  }
}
