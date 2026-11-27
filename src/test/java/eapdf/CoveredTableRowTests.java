package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoveredTableRowTests {
  @TempDir Path directory;

  @Test
  void spanningCellsStopBeforeFollowingDataRow() throws Exception {
    String source = "From: sender@example.com\r\nTo: recipient@example.com\r\n"
        + "Date: Mon, 06 Jan 2020 10:00:00 +0000\r\nMessage-ID: <covered-row@example.com>\r\n"
        + "Subject: Covered table row\r\nMIME-Version: 1.0\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<html><body><table><thead><tr><th scope='col'>A</th><th scope='col'>B</th></tr></thead>"
        + "<tbody><tr><td rowspan='2'>One</td><td rowspan='2'>Two</td></tr>"
        + "<tr></tr><tr><td>Three</td><td>Four</td></tr></tbody></table></body></html>";
    var record = EmlReader.readRfc822Bytes(source.getBytes(StandardCharsets.UTF_8),
        "covered-row.eml", "covered-row.eml", "", List.of(), false);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(directory.resolve("eml_covered_row_1s.pdf"),
        record, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;

    try (var pdf = Loader.loadPDF(bytes)) {
      var body = descendants(pdf.getDocumentCatalog().getStructureTreeRoot())
          .filter(element -> "TBody".equals(element.getStructureType()))
          .filter(element -> descendants(element).anyMatch(cell -> rowSpan(cell) == 2))
          .findFirst().orElseThrow();
      var rows = children(body).toList();
      assertEquals(List.of("TR", "TR", "TR"), rows.stream().map(PDStructureElement::getStructureType).toList());
      assertEquals(List.of(2, 2), children(rows.get(0)).map(CoveredTableRowTests::rowSpan).toList());
      assertEquals(List.of(), rows.get(1).getKids());
      assertEquals(List.of(1, 1), children(rows.get(2)).map(CoveredTableRowTests::rowSpan).toList());
    }
  }

  private static Stream<PDStructureElement> children(PDStructureNode node) {
    return node.getKids().stream().filter(PDStructureElement.class::isInstance).map(PDStructureElement.class::cast);
  }

  private static Stream<PDStructureElement> descendants(PDStructureNode node) {
    return children(node).flatMap(child -> Stream.concat(Stream.of(child), descendants(child)));
  }

  private static int rowSpan(PDStructureElement element) {
    var attributes = element.getAttributes();
    for (int index = 0; index < attributes.size(); index++) {
      if (attributes.getObject(index) instanceof PDTableAttributeObject table) return table.getRowSpan();
    }
    return 1;
  }
}
