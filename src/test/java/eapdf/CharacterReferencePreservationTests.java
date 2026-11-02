package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CharacterReferencePreservationTests {
  @TempDir Path directory;

  static byte[] message(String mime, String body) {
    return ("From: author@example.org\r\nTo: reader@example.org\r\nSubject: Character references\r\n"
        + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nMIME-Version: 1.0\r\nContent-Type: "
        + mime + "; charset=UTF-8\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void validHtmlReferencesRetainTheirLexicalValuesAndRenderTheCorrectCharacters() throws Exception {
    String html = "<p>We&#039;re ready for O&#039;Brien&#039;s review.</p>"
        + "<p>Budget: &#x00A3;120.00. R&#038;D approval.</p><p>Monday&#0160;morning. Reference &#x000041;17.</p>"
        + "<p>Literal example: &amp;#39;.</p>";
    byte[] raw = message("text/html", html);
    var record = EmlReader.readRfc822Bytes(raw, "references.eml", "references.eml", "", List.of(), false);
    assertEquals(html, record.htmlBody);
    assertTrue(record.textBody.contains("We're ready for O'Brien's review."));
    assertTrue(record.textBody.contains("£120.00"));
    assertTrue(record.textBody.contains("Literal example: &#39;."), "Decode one level, keeping escaped examples literal");
    var bytes = PdfMailWriter.buildSingleMessagePdf(directory.resolve("references.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      assertTrue(text.contains("We're ready for O'Brien's review."));
      assertTrue(text.contains("£120.00"));
      assertTrue(text.contains("R&D approval."));
      assertTrue(text.contains("A17."));
      assertTrue(text.contains("Literal example: &#39;."));
      assertFalse(text.contains("A3;"));
      assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(
          AttachmentProfilePolicyTests.files(pdf).values().iterator().next()));
    }
  }

  @Test
  void plainTextReferenceExamplesRemainLiteralAndActualNullReferencesStillDisappear() throws Exception {
    String plain = "Use &#039; or &#x0027; in HTML. Keep &#0160; as written here.";
    var record = EmlReader.readRfc822Bytes(message("text/plain", plain), "plain.eml", "plain.eml", "", List.of(), false);
    assertEquals(plain, record.textBody);
    var bytes = PdfMailWriter.buildSingleMessagePdf(directory.resolve("plain.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      assertTrue(new PDFTextStripper().getText(pdf).contains(plain));
    }
    assertEquals("BeforeAfter", Common.stripInvalidXml("Before\0&#000;&#x000;After"));
  }
}
