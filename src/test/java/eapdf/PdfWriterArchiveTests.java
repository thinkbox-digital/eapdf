package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
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
  void perFileLogCaptureWritesSummaryOnly() throws Exception {
    Path out = tempDir.resolve("summary.pdf");
    try (Common.PerFileLogCapture capture = Common.beginPerFileLog(out)) {
      capture.append("Mar 28, 2026 12:00:38 PM org.apache.pdfbox.pdmodel.font.PDType1Font <init>\n");
      capture.append("WARNING: Using fallback font LiberationSans for base font Symbol\n");
      capture.append("WARNING: Using fallback font LiberationSans for base font Symbol\n");
      capture.append("com.openhtmltopdf.css-parse WARNING:: ignored malformed CSS declaration\n");
      capture.append("ERROR StatusLogger Log4j2 could not find a logging implementation. Please add log4j-core to the classpath.\n");
      Common.recordBlockedRemoteAsset("https://example.com/logo.gif?tracking=private-value");
      Common.recordBlockedRemoteAsset("https://example.com/logo.gif?tracking=private-value");
    }

    Path logPath = tempDir.resolve("logs").resolve("summary.log");
    String log = Files.readString(logPath);

    Assertions.assertTrue(log.contains("Warnings recorded: 2"));
    Assertions.assertTrue(log.contains("Errors recorded: 0"));
    Assertions.assertTrue(log.contains("Blocked remote asset encounters: 2"));
    Assertions.assertTrue(log.contains("Distinct blocked remote assets tracked: 1"));
    Assertions.assertTrue(log.contains("2x Using fallback font LiberationSans for base font Symbol"));
    Assertions.assertFalse(log.contains("example.com"));
    Assertions.assertFalse(log.contains("private-value"));
    Assertions.assertFalse(log.contains("org.apache.pdfbox.pdmodel.font.PDType1Font <init>"));
    Assertions.assertFalse(log.contains("css-parse WARNING::"));
    Assertions.assertFalse(log.contains("StatusLogger"));
  }
}
