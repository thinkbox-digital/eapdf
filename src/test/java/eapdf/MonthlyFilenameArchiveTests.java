package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MonthlyFilenameArchiveTests {
  @TempDir
  Path tempDir;

  @Test
  void oneMessageMonthWritesSingleMessageProfileAndContinuesToLaterMonths() throws Exception {
    Path input = Files.createDirectory(tempDir.resolve("input"));
    Path output = tempDir.resolve("output");
    for (String date : List.of("20260101", "20260102", "20260201", "20260301", "20260302")) {
      Files.writeString(input.resolve(date + ".eml"),
          "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nFrom: sender@example.org\r\nTo: recipient@example.org\r\n"
              + "Message-ID: <" + date + "@example.org>\r\nSubject: Email " + date
              + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody " + date + "\r\n");
    }
    Path log = tempDir.resolve("monthly.log");
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
        MonthlyFilenameArchiveToPdf.class.getName(), "--out", output.toString(),
        "--font", TestSupport.fontPath(), "--icc", TestSupport.iccPath(), input.toString())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      Assertions.assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Monthly conversion timed out");
      Assertions.assertEquals(0, process.exitValue(), Files.readString(log));
    } finally {
      if (process.isAlive()) process.destroyForcibly();
    }
    for (String month : List.of("2026-01", "2026-02", "2026-03")) {
      String profile = month.equals("2026-02") ? "s" : "m";
      try (PDDocument document = Loader.loadPDF(output.resolve(month + "_1" + profile + ".pdf").toFile())) {
        Assertions.assertTrue(TestSupport.metadataXml(document).contains(
            "<pdfmailid:conformance>" + profile + "</pdfmailid:conformance>"));
        String text = new PDFTextStripper().getText(document);
        String datePrefix = month.replace("-", "");
        Assertions.assertTrue(text.contains("Body " + datePrefix + "01"));
        if (profile.equals("m")) Assertions.assertTrue(text.contains("Body " + datePrefix + "02"));
        if (profile.equals("s")) {
          var source = AttachmentProfilePolicyTests.files(document).values().stream()
              .filter(file -> file.getFilename().endsWith("20260201.eml"))
              .findFirst().orElseThrow();
          Assertions.assertArrayEquals(Files.readAllBytes(input.resolve("20260201.eml")),
              AttachmentProfilePolicyTests.bytes(source));
        }
      }
    }
    Assertions.assertFalse(Files.exists(output.resolve("2026-02_1m.pdf")));
    String messages = Files.readString(log);
    Assertions.assertTrue(messages.contains("done. monthly archives written: 2"));
    Assertions.assertTrue(messages.contains("single-message monthly PDFs written: 1"));
  }
}
