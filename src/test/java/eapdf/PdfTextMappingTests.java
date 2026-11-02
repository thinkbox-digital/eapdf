package eapdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PdfTextMappingTests {
  @TempDir Path directory;

  static void assertNoForbiddenMapping(byte[] bytes) throws Exception {
    boolean checked = false;
    try (var pdf = Loader.loadPDF(bytes)) {
      for (var page : pdf.getPages()) {
        for (var name : page.getResources().getFontNames()) {
          var stream = page.getResources().getFont(name).getCOSObject().getDictionaryObject(COSName.TO_UNICODE);
          if (stream instanceof COSStream cmap) {
            try (var input = cmap.createInputStream()) {
              assertFalse(new String(input.readAllBytes(), StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT).contains("<feff>"));
            }
            checked = true;
          }
        }
      }
    }
    assertTrue(checked);
  }

  @Test
  void htmlLiteralAndEncodedWordJoinersKeepOriginalsWithoutForbiddenPdfMappings() throws Exception {
    byte[] raw = ("From: author@example.org\r\nTo: reader@example.org\r\nSubject: Joined\uFEFFsubject\r\n"
        + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<p>Left\uFEFFRight</p><p>Encoded&#xFEFF;Pair</p><p>Padded&#x0FEFF;Pair</p>").getBytes(StandardCharsets.UTF_8);
    var record = EmlReader.readRfc822Bytes(raw, "joined.eml", "joined.eml", "", List.of(), false);
    String originalHtml = record.htmlBody;
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(directory.resolve("joined.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
    assertNoForbiddenMapping(bytes);
    assertEquals(originalHtml, record.htmlBody);
    assertEquals("Joined\uFEFFsubject", record.subject);
    try (var pdf = Loader.loadPDF(bytes)) {
      var source = AttachmentProfilePolicyTests.files(pdf).values().iterator().next();
      assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(source));
    }
  }

  @Test
  void bundledFallbackPreservesKoreanTextInGeneratedHeaders() throws Exception {
    String text = "호준석입니다";
    try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
      var page = new PDPage(); pdf.addPage(page);
      var font = FontSupport.loadUiFont(pdf, TestSupport.fontPath());
      assertEquals(text, PdfMailWriter.sanitizePdfText(font, text));
      try (var content = new PDPageContentStream(pdf, page)) {
        FontSupport.draw(content, font, 12, 40, 700, text);
      }
      pdf.save(output);
      try (var saved = Loader.loadPDF(output.toByteArray())) {
        assertEquals(text, new org.apache.pdfbox.text.PDFTextStripper().getText(saved).strip());
      }
    }
  }

  @Test
  void generatedPdfBoxTextUsesTheSameZeroWidthWordJoinerForDrawingAndMeasurement() throws Exception {
    try (var pdf = new PDDocument(); var output = new ByteArrayOutputStream()) {
      var page = new PDPage();
      pdf.addPage(page);
      var font = FontSupport.loadUiFont(pdf, TestSupport.fontPath());
      assertEquals(FontSupport.width(font, 12, "Joinedwords"), FontSupport.width(font, 12, "Joined\uFEFFwords"), 0.001f);
      try (var content = new PDPageContentStream(pdf, page)) {
        FontSupport.draw(content, font, 12, 40, 700, "Joined\uFEFFwords");
      }
      pdf.save(output);
      assertNoForbiddenMapping(output.toByteArray());
    }
  }
}
