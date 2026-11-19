package eapdf;

import java.awt.image.BufferedImage;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** Pixel checks at column centers avoid dependence on font rasterization. */
class RowspanBorderTests {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"separate", "collapse"})
  void finalEaPdfPreservesTableTextAcrossPages(String border) throws Exception {
    var config = EapdfConfig.get();
    boolean accessibility = config.accessibility.addPdfUaFeatures;
    config.accessibility.addPdfUaFeatures = true;
    try {
      var rows = new StringBuilder();
      for (int i = 0; i < 60; i++) rows.append("<tr>")
          .append(i == 0 ? "<td rowspan=\"60\">Spanning cell</td>" : "")
          .append("<td>Row %02d</td></tr>".formatted(i));
      String html = "<html><body><table style=\"border-collapse:" + border + ";width:100%\">"
          + "<thead><tr><th>Group</th><th>Repeated header</th></tr></thead>"
          + "<tfoot><tr><td>Footer</td><td>Repeated footer</td></tr></tfoot><tbody>" + rows
          + "</tbody></table><style>td {height:40px;padding:4px;border:2px solid red}</style></body></html>";
      String source = "From: sender@example.com\r\nTo: recipient@example.com\r\n"
          + "Date: Mon, 06 Jan 2020 10:00:00 +0000\r\nMessage-ID: <rowspan@example.com>\r\n"
          + "Subject: Rowspan regression\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html;
      var record = EmlReader.readRfc822Bytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "rowspan.eml", "rowspan.eml", "", java.util.List.of(), false);
      String evidence = System.getenv("EAPDF_DEPENDENCY_EVIDENCE_DIR");
      var destination = evidence == null ? directory : java.nio.file.Path.of(evidence);
      java.nio.file.Files.createDirectories(destination);
      var output = destination.resolve("eml_rowspan_" + border + "_1s.pdf");
      var expected = PreservationInventory.capture(java.util.List.of(record), "s");
      byte[] bytes = PdfMailWriter.buildSingleMessagePdf(output, record, TestSupport.fontPath(),
          TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
      java.nio.file.Files.write(output, bytes);
      try (var pdf = Loader.loadPDF(bytes)) {
        PdfCreationValidator.validate(pdf, expected);
        assertTrue(pdf.getNumberOfPages() > 1);
        String text = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
        assertTrue(text.contains("Spanning cell"));
        for (int i = 0; i < 60; i++) assertTrue(text.contains("Row %02d".formatted(i)), "Row " + i);
        for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
          var stripper = new org.apache.pdfbox.text.PDFTextStripper();
          stripper.setStartPage(page); stripper.setEndPage(page);
          assertTrue(stripper.getText(pdf).contains("Repeated header"), "Header on page " + page);
        }
      }
    } finally {
      config.accessibility.addPdfUaFeatures = accessibility;
    }
  }

  static Stream<Arguments> layouts() {
    return Stream.of("separate", "collapse").flatMap(border ->
        Stream.of("between", "inside", "header-footer").map(layout -> Arguments.of(border, layout)));
  }

  @ParameterizedTest @MethodSource("layouts")
  void rowspanFragmentsAlignWithAdjacentRows(String border, String layout) throws Exception {
    var config = EapdfConfig.get();
    boolean accessibility = config.accessibility.addPdfUaFeatures;
    config.accessibility.addPdfUaFeatures = true;
    try {
      byte[] pdf = PdfMailWriter.renderXhtmlPdf(html(border, layout), "", TestSupport.fontPath());
      try (var document = Loader.loadPDF(pdf)) {
        assertTrue(document.getNumberOfPages() > 1, "Must exercise a page break");
        var renderer = new PDFRenderer(document);
        for (int page = 0; page < document.getNumberOfPages(); page++) {
          BufferedImage image = renderer.renderImageWithDPI(page, 96);
          int left = image.getWidth() / 4, right = 3 * image.getWidth() / 4;
          int top = edge(image, left, false), bottom = edge(image, left, true);
          assertTrue(top >= 0, "Cell must be painted on page " + page);
          assertEquals(edge(image, right, false), top, "Top edge on page " + page);
          assertEquals(edge(image, right, true), bottom, "Bottom edge on page " + page);
        }
      }
    } finally {
      config.accessibility.addPdfUaFeatures = accessibility;
    }
  }

  private static int edge(BufferedImage image, int x, boolean bottom) {
    for (int i = 0; i < image.getHeight(); i++) {
      int y = bottom ? image.getHeight() - 1 - i : i;
      int rgb = image.getRGB(x, y);
      int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
      if ((r > 150 && g < 100 && b < 100) || (g > 120 && r < 100 && b < 100)
          || (b > 150 && r < 100 && g < 100)) return y;
    }
    return -1;
  }

  static String html(String border, String layout) {
    String rows;
    if (layout.equals("inside")) rows = "<tr><td rowspan=\"2\"></td><td>"
        + "<div style=\"height:20px\"></div>".repeat(16) + "</td></tr><tr><td></td></tr>";
    else rows = "<tr><td rowspan=\"6\"></td><td></td></tr>" + "<tr><td></td></tr>".repeat(5);
    String sections = layout.equals("header-footer")
        ? "<thead><tr><th></th><th></th></tr></thead><tfoot><tr><td></td><td></td></tr></tfoot>" : "";
    return """
        <html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>Rowspan borders</title><style>
        @page { size:100px 100px; margin:0; } body { margin:0; }
        table { -fs-table-paginate:paginate; width:100%%; border-spacing:0; border-collapse:%s; }
        td { height:18px; padding:2px; border:2px solid red; background:#0000c8; }
        td[rowspan] { background:#00aa00; }
        th { height:12px; border:2px solid black; }
        tfoot td { height:12px; border:0; background:none; }
        </style></head><body><table>%s<tbody>%s</tbody></table></body></html>
        """.formatted(border, sections, rows);
  }
}
