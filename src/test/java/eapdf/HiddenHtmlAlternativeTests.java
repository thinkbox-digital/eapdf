package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HiddenHtmlAlternativeTests {
  @TempDir Path directory;
  static final String TEXT = "Invoice total is 145.00 USD. Payment is due Friday.";
  static final String SUMMARY = "Your invoice is ready";

  static byte[] message(String inlineStyle, String stylesheet) {
    String html = "<html><head>" + stylesheet + "</head><body><div class=\"summary\" style=\"" + inlineStyle
        + "\">" + SUMMARY + "</div><img src=\"https://images.example.invalid/invoice.png\" alt=\"Invoice\"></body></html>";
    return ("From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: Invoice details\r\n"
        + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nMessage-ID: <hidden-html@example.org>\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: multipart/alternative; boundary=choices\r\n\r\n"
        + EmailContentRegressionTests.section("choices", "text/plain; charset=UTF-8", "", TEXT)
        + EmailContentRegressionTests.section("choices", "text/html; charset=UTF-8", "", html) + "--choices--\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void hiddenHtmlTextDoesNotPreventTheReadableSuppliedAlternative() throws Exception {
    for (String style : List.of("display:none!important;font-size:1px;max-height:0;overflow:hidden", "display:none")) {
      byte[] raw = message(style, "");
      EmailRecord record = EmlReader.readRfc822Bytes(raw, "invoice.eml", "invoice.eml", "", List.of(), false);
      assertEquals("text/plain; charset=UTF-8", record.bodyMime);
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("invoice.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var document = Loader.loadPDF(output.pdfBytes)) {
        String text = new PDFTextStripper().getText(document);
        assertTrue(text.contains(TEXT));
        assertFalse(text.contains(SUMMARY));
        var files = AttachmentProfilePolicyTests.files(document);
        assertEquals(1, files.size());
        assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
      }
    }
  }

  @Test
  void invisibleFormattingDoesNotHideTheSuppliedReadableBody() throws Exception {
    for (String marks : List.of("\uFEFF\u200B\u2060\u00A0\u202F", "&#xFEFF;&#x200B;&#x2060;&#160;&#8239;")) {
      byte[] raw = new String(message("", ""), StandardCharsets.UTF_8).replace(SUMMARY, marks).getBytes(StandardCharsets.UTF_8);
      var record = EmlReader.readRfc822Bytes(raw, "spacing.eml", "spacing.eml", "", List.of(), false);
      assertEquals("text/plain; charset=UTF-8", record.bodyMime);
      var bytes = PdfMailWriter.buildSingleMessagePdf(directory.resolve("spacing.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
      try (var pdf = Loader.loadPDF(bytes)) {
        assertTrue(new PDFTextStripper().getText(pdf).contains(TEXT));
        assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(
            AttachmentProfilePolicyTests.files(pdf).values().iterator().next()));
      }
    }
  }

  @Test
  void visibleDisplayOverridesKeepTheAuthoredHtml() throws Exception {
    for (String[] styles : List.of(
        new String[]{"display:none", "<style>.summary{display:block!important}</style>"},
        new String[]{"display:none!important;display:block!important", ""})) {
      byte[] raw = message(styles[0], styles[1]);
      EmailRecord record = EmlReader.readRfc822Bytes(raw, "visible.eml", "visible.eml", "", List.of(), false);
      assertEquals("text/html; charset=UTF-8", record.bodyMime);
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("visible.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var document = Loader.loadPDF(output.pdfBytes)) {
        String text = new PDFTextStripper().getText(document);
        assertTrue(text.contains(SUMMARY));
        assertFalse(text.contains(TEXT));
      }
      assertArrayEquals(raw, record.sourceBytes);
    }
  }
}
