package eapdf;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfWriterArchiveTests {

  @TempDir
  Path tempDir;

  @Test
  void writeEaPdfArchiveProducesValidOutlineDestinations() throws Exception {
    Path mboxPath = TestSupport.firstInputWithExtension(".mbox");
    List<EmailRecord> records = MboxReader.readMbox(
        mboxPath.toString(),
        "",
        List.of(),
        false);

    // PST readers commonly provide the same source filename for every email.
    for (EmailRecord record : records) {
      record.sourceFilename = "message.eml";
      for (AssociatedFile file : record.associatedFiles)
        if ("Source".equals(file.afRelationship)) file.filename = "message.eml";
    }
    Path out = tempDir.resolve("test_1m.pdf");
    PdfMailWriter.writeEaPdfArchive(out, records, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir.resolve("debug"), "m");

    Assertions.assertTrue(Files.exists(out));

    try (PDDocument doc = Loader.loadPDF(out.toFile())) {
      PreservationRegressionTests.assertDPartTree(doc);
      PreservationRegressionTests.assertPageStructureBindings(doc);
      String xmp = TestSupport.metadataXml(doc);
      Assertions.assertTrue(xmp.contains("<pdfmailid:conformance>m</pdfmailid:conformance>"));
      Assertions.assertEquals("UseOutlines", doc.getDocumentCatalog().getCOSObject().getNameAsString(org.apache.pdfbox.cos.COSName.PAGE_MODE));
      TestSupport.assertAfPresent(doc);
      // Check the saved PDF, including deduplicated files shared by several emails.
      String pageText = new org.apache.pdfbox.text.PDFTextStripper().getText(doc);
      Assertions.assertFalse(pageText.contains("Source file: message.eml"));
      var owners = PdfMailWriter.messageFileOwners(doc);
      for (var plan : PdfMailWriter.buildArchiveEmbeddedFilePlans(records)) {
        if ("Source".equals(plan.relationship)) Assertions.assertTrue(pageText.contains("Source file: " + plan.filename), "Rendered source name differs: " + plan.filename);
        for (var owner : plan.owners) {
          var part = owners.get(owner.mailGuid);
          Assertions.assertNotNull(part, "Missing email part: " + owner.mailGuid);
          var files = (org.apache.pdfbox.cos.COSArray) part.getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("AF"));
          Assertions.assertNotNull(files, "Missing email file associations");
          boolean found = false;
          for (int i = 0; i < files.size(); i++) {
            var file = (org.apache.pdfbox.cos.COSDictionary) files.getObject(i);
            if (plan.filename.equals(file.getString(org.apache.pdfbox.cos.COSName.UF))) found = true;
          }
          Assertions.assertTrue(found, "Missing owner for " + plan.filename);
        }
      }
      TestSupport.assertOutlineDestinationsResolve(doc);
    }
  }

  @Test
  void blacklistsConnectionFailuresAndHtmlImageResponses() {
    Assertions.assertTrue(PdfMailWriter.shouldBlacklistRemoteAssetFailure(new ConnectException("Connection refused")));
    Assertions.assertTrue(PdfMailWriter.shouldBlacklistRemoteAssetFailure(new SocketTimeoutException("connect timed out")));
    Assertions.assertEquals("Connection refused", PdfMailWriter.describeRemoteAssetFailure(new ConnectException("Connection refused")));

    byte[] html = "<html><body>tracking page</body></html>".getBytes(StandardCharsets.UTF_8);
    Assertions.assertTrue(PdfMailWriter.shouldBlacklistRemoteAssetResponse("https://example.com/logo.gif", html));
    Assertions.assertEquals("Remote asset returned HTML instead of binary content",
        PdfMailWriter.describeRemoteAssetResponse("https://example.com/logo.gif", html));

    byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    Assertions.assertFalse(PdfMailWriter.shouldBlacklistRemoteAssetResponse("https://example.com/logo.png", png));
  }

  @Test
  void perFileLogCaptureWritesSummaryOnly() throws Exception {
    Path out = tempDir.resolve("summary.pdf");
    EmailRecord record = new EmailRecord();
    record.subject = "Monthly Sample";
    record.sourcePath = "gmail://message-123";

    try (Common.PerFileLogCapture capture = Common.beginPerFileLog(out)) {
      capture.append("Mar 28, 2026 12:00:38 PM org.apache.pdfbox.pdmodel.font.PDType1Font <init>\n");
      capture.append("WARNING: Using fallback font LiberationSans for base font Symbol\n");
      capture.append("WARNING: Using fallback font LiberationSans for base font Symbol\n");
      capture.append("com.openhtmltopdf.css-parse WARNING:: ignored malformed CSS declaration\n");
      capture.append("ERROR StatusLogger Log4j2 could not find a logging implementation. Please add log4j-core to the classpath.\n");
      Common.recordBlacklistedRemoteAsset("https://example.com/logo.gif", "Connection refused", record);
      Common.recordBlacklistedRemoteAsset("https://example.com/logo.gif", "previously blacklisted", record);
    }

    Path logPath = tempDir.resolve("logs").resolve("summary.log");
    String log = Files.readString(logPath);

    Assertions.assertTrue(log.contains("Warnings recorded: 2"));
    Assertions.assertTrue(log.contains("Errors recorded: 0"));
    Assertions.assertTrue(log.contains("Blacklisted remote asset encounters: 2"));
    Assertions.assertTrue(log.contains("2x Using fallback font LiberationSans for base font Symbol"));
    Assertions.assertTrue(log.contains("https://example.com/logo.gif"));
    Assertions.assertTrue(log.contains("reason: Connection refused"));
    Assertions.assertTrue(log.contains("subject: Monthly Sample"));
    Assertions.assertTrue(log.contains("source: gmail://message-123"));
    Assertions.assertFalse(log.contains("org.apache.pdfbox.pdmodel.font.PDType1Font <init>"));
    Assertions.assertFalse(log.contains("css-parse WARNING::"));
    Assertions.assertFalse(log.contains("StatusLogger"));
  }
}
