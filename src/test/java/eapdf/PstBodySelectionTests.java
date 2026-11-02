package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PstBodySelectionTests {
  @TempDir Path directory;
  static final String PLAIN = "Original plain message";
  static final String HTML = "<p>Original HTML message</p>";
  static final String RTF = "{\\rtf1\\ansi Original rich text message\\par}";

  static EmailRecord record(int nativeBody) throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("PST body selection");
    record.sourceType = "pst";
    PstReader.populatePstBody(record, PLAIN, HTML, RTF, nativeBody);
    return record;
  }

  @Test
  void declaredNativeBodyControlsVisibleTextAndBodyMetadata() throws Exception {
    String[] expected = {PLAIN, "Original rich text message", "Original HTML message"};
    String[] media = {"text/plain", "text/rtf", "text/html"};
    for (int type = 1; type <= 3; type++) {
      EmailRecord record = record(type);
      assertEquals(media[type - 1] + "; charset=UTF-8", record.bodyMime);
      String visible = org.jsoup.Jsoup.parse(record.htmlBody).text();
      assertEquals(expected[type - 1], visible);
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("native-" + type + ".pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var pdf = Loader.loadPDF(output.pdfBytes)) {
        String text = new PDFTextStripper().getText(pdf);
        assertTrue(text.contains(expected[type - 1]));
        for (String other : expected) if (!other.equals(expected[type - 1])) assertFalse(text.contains(other));
        boolean bodyMetadata = false;
        for (var page : pdf.getPages()) {
          var part = (org.apache.pdfbox.cos.COSDictionary) page.getCOSObject()
              .getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("DPart"));
          var properties = (org.apache.pdfbox.cos.COSDictionary) part
              .getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("DPM"));
          if (properties != null) bodyMetadata |= record.bodyMime.equals(properties
              .getNameAsString(org.apache.pdfbox.cos.COSName.getPDFName("Mail_Subtype")));
        }
        assertTrue(bodyMetadata);
      }
    }
  }

  @Test
  void nativeRtfIsRetainedInTheExportWhenPlainAndHtmlAlternativesExist() throws Exception {
    EmailRecord record = record(2);
    assertEquals(RTF, record.rtfBody);
    var exported = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
        new ByteArrayInputStream(Common.synthesizeEml(record)));
    assertEquals(RTF, exportedRtf(exported));
  }

  static String exportedRtf(jakarta.mail.Part part) throws Exception {
    if (part.isMimeType("text/rtf")) return new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (part.isMimeType("multipart/*")) {
      var parts = (jakarta.mail.Multipart) part.getContent();
      for (int i = 0; i < parts.getCount(); i++) {
        String rtf = exportedRtf(parts.getBodyPart(i));
        if (rtf != null) return rtf;
      }
    }
    return null;
  }

  @Test
  void missingNativeBodyReportsFallbackAndUnspecifiedBodyKeepsExistingSelection() throws Exception {
    EmailRecord record = new EmailRecord();
    record.subject = "PST body fallback";
    PstReader.populatePstBody(record, PLAIN, "", "", 3);
    assertEquals("text/plain; charset=UTF-8", record.bodyMime);
    assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("native body 3 is unavailable")));
    EmailRecord legacy = record(0);
    assertEquals("text/html; charset=UTF-8", legacy.bodyMime);
    assertEquals("Original HTML message", org.jsoup.Jsoup.parse(legacy.htmlBody).text());
  }
}
