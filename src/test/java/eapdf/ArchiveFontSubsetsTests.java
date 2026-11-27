package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ArchiveFontSubsetsTests {
  @Test void workersMergeDisjointGlyphsAcrossSavedPartsBeforeSubsetting() throws Exception {
    int originalSize;
    byte[] optimized;
    String expected;
    List<int[]> pixels = new ArrayList<>();
    try (var document = new PDDocument()) {
      var pool = new ArchiveResourcePool();
      var usage = new ArchiveFontSubsets.Usage();
      for (String text : List.of("ABCDE 0123456789", "vwxyz $8.7 MM (50)", "é Ω @-+")) {
        try (var part = new PDDocument(); var input = new FileInputStream(TestSupport.fontPath())) {
          var font = PDType0Font.load(part, input, false);
          var page = new PDPage();
          page.setResources(new PDResources());
          part.addPage(page);
          try (var content = new PDPageContentStream(part, page)) {
            content.beginText();content.setFont(font, 12);content.newLineAtOffset(40, 700);
            content.showText(text);content.endText();
          }
          var bytes = new ByteArrayOutputStream();
          PdfFontMetadata.save(part, bytes);
          var glyphs = ArchiveFontSubsets.scanPart(part);
          assertEquals(1, glyphs.size());
          try (var loaded = Loader.loadPDF(bytes.toByteArray())) {
            var dictionary = pool.cloner(document, glyphs, usage).cloneForNewDocument(loaded.getPage(0).getCOSObject());
            dictionary.removeItem(COSName.PARENT);
            document.addPage(new PDPage(dictionary));
          }
        }
      }
      assertEquals(2, pool.reusedFonts);
      var original = new ByteArrayOutputStream();
      document.save(original);
      originalSize = original.size();
      expected = new PDFTextStripper().getText(document);
      var renderer = new PDFRenderer(document);
      for (int i=0;i<document.getNumberOfPages();i++) {
        var image=renderer.renderImageWithDPI(i,96);
        pixels.add(image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()));
      }
      assertEquals(1, usage.apply(document));
      var output = new ByteArrayOutputStream();document.save(output);optimized=output.toByteArray();
    }
    assertTrue(optimized.length < originalSize / 2);
    try (var document = Loader.loadPDF(optimized)) {
      assertEquals(expected,new PDFTextStripper().getText(document));
      var renderer = new PDFRenderer(document);
      for (int i=0;i<document.getNumberOfPages();i++) {
        var image=renderer.renderImageWithDPI(i,96);
        assertArrayEquals(pixels.get(i),image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth()),"page "+i);
      }
      assertTrue(ArchiveFontSubsets.scanPart(document).isEmpty(), "existing subsets are left intact");
    }
  }

  @Test void aMissingWorkerMatchDisqualifiesTheSharedDictionary() throws Exception {
    try (var document = new PDDocument()) {
      var dictionary = new COSDictionary();
      var codes = new BitSet();
      codes.set(10);
      var usage = new ArchiveFontSubsets.Usage();
      usage.record(dictionary, codes);
      usage.record(dictionary, null);
      usage.record(dictionary, codes);
      assertEquals(0, usage.apply(document));
    }
  }
}
