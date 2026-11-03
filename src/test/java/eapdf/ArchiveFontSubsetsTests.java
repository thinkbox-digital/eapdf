package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ArchiveFontSubsetsTests {
  @Test void sharedDictionaryCollectsCharactersFromEveryPageBeforeSubsetting() throws Exception {
    byte[] original;
    try (var document = new PDDocument(); var input = new FileInputStream(TestSupport.fontPath())) {
      var font = PDType0Font.load(document, input, false);
      for (String text : List.of("ABCDE 0123456789", "vwxyz $8.7 MM (50)", "é Ω @-+")) {
        var page = new PDPage();
        page.setResources(new PDResources());
        document.addPage(page);
        try (var content = new PDPageContentStream(document, page)) {
          content.beginText();content.setFont(font, 12);content.newLineAtOffset(40, 700);
          content.showText(text);content.endText();
        }
      }
      var output = new ByteArrayOutputStream();document.save(output);original = output.toByteArray();
    }
    byte[] optimized;
    String expected;
    List<int[]> pixels = new ArrayList<>();
    try (var document = Loader.loadPDF(original)) {
      expected = new PDFTextStripper().getText(document);
      var renderer = new PDFRenderer(document);
      for (int i=0;i<document.getNumberOfPages();i++) {
        var image=renderer.renderImageWithDPI(i,96);
        pixels.add(image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
      }
      assertEquals(1, ArchiveFontSubsets.apply(document));
      var output = new ByteArrayOutputStream();document.save(output);optimized=output.toByteArray();
    }
    assertTrue(optimized.length < original.length / 2);
    try (var document = Loader.loadPDF(optimized)) {
      assertEquals(expected,new PDFTextStripper().getText(document));
      var renderer = new PDFRenderer(document);
      for (int i=0;i<document.getNumberOfPages();i++) {
        var image=renderer.renderImageWithDPI(i,96);
        assertArrayEquals(pixels.get(i),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()),"page "+i);
      }
      assertEquals(0,ArchiveFontSubsets.apply(document),"existing subsets are left intact");
    }
  }
}
