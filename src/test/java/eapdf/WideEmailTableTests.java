package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class WideEmailTableTests {
  @TempDir Path directory;

  static byte[] message(String sizing, int rows) {
    StringBuilder body = new StringBuilder("<html><head>");
    if (sizing.equals("shared")) body.append("<style>table{width:900px;table-layout:fixed}</style>");
    body.append("</head><body><p>Quarterly totals</p><table ");
    body.append(sizing.equals("shared") ? "" : sizing.equals("attribute") ? "width=\"900\""
        : "style=\"width:" + sizing + "px;table-layout:fixed\"");
    body.append("><thead><tr>");
    for (int column = 1; column <= 6; column++) body.append("<th>Column ").append(column).append("</th>");
    body.append("</tr></thead><tbody>");
    for (int row = 1; row <= rows; row++) {
      body.append("<tr>");
      for (int column = 1; column <= 6; column++) {
        body.append("<td>R").append(row).append("C").append(column).append("</td>");
      }
      body.append("</tr>");
    }
    body.append("</tbody></table><p>End of report</p></body></html>");
    return ("From: author@example.org\r\nTo: recipient@example.org\r\nSubject: Quarterly totals\r\n"
        + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nMessage-ID: <wide-table@example.org>\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
  }

  byte[] pdf(byte[] raw) throws Exception {
    var record = EmlReader.readRfc822Bytes(raw, "totals.eml", "totals.eml", "", List.of(), false);
    return PdfMailWriter.buildSingleMessagePdf(directory.resolve("totals.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
  }

  static void assertTextFits(PDDocument document) throws Exception {
    new PDFTextStripper() {
      @Override protected void processTextPosition(TextPosition text) {
        float width = document.getPage(getCurrentPageNo() - 1).getMediaBox().getWidth();
        assertTrue(text.getXDirAdj() + text.getWidthDirAdj() <= width - 46f,
            "Text was cut off at the right page margin: " + text.getUnicode());
        super.processTextPosition(text);
      }
      @Override protected void writeString(String text, List<TextPosition> positions) throws java.io.IOException {
        if (getCurrentPageNo() == document.getNumberOfPages()
            && (text.contains("R") && text.contains("C") || text.contains("End of report") || text.contains("Source file:"))) {
          float limit = getCurrentPage().getMediaBox().getHeight() - 46f;
          for (var position : positions) assertTrue(position.getYDirAdj() <= limit, "Body exceeds bottom margin");
        }
        super.writeString(text, positions);
      }
    }.getText(document);
  }

  @Test
  void fullTableFitsForInlineSharedAndLegacyWidthsWithExactOriginal() throws Exception {
    for (String sizing : List.of("900", "shared", "attribute")) {
      byte[] raw = message(sizing, 2);
      try (var document = Loader.loadPDF(pdf(raw))) {
        assertTrue(document.getPage(0).getMediaBox().getWidth() > 612f);
        String text = new PDFTextStripper().getText(document);
        assertTrue(text.contains("Column 6"));
        assertTrue(text.contains("R2C6"));
        assertTextFits(document);
        var files = AttachmentProfilePolicyTests.files(document);
        assertEquals(1, files.size());
        assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
      }
    }
  }

  @Test
  void normalTableKeepsLetterPagesAndLongWideTableKeepsRowsWithinMargins() throws Exception {
    try (var document = Loader.loadPDF(pdf(message("600", 2)))) {
      assertEquals(612f, document.getPage(0).getMediaBox().getWidth(), 0.1f);
      assertTextFits(document);
    }
    try (var document = Loader.loadPDF(pdf(message("900", 45)))) {
      assertTrue(document.getNumberOfPages() > 1);
      String text = new PDFTextStripper().getText(document);
      for (int row = 1; row <= 45; row++) assertTrue(text.contains("R" + row + "C6"));
      assertTextFits(document);
    }
  }
}
