package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class ArchiveMemoryTests {
  @Test void compactDictionariesMatchOrderedMapMutationsAndGrowth() {
    var compact = new CompactPdfDictionary(0); var regular = new COSDictionary();
    Random random = new Random(12347);
    for (int i = 0; i < 3000; i++) {
      COSName key = COSName.getPDFName("key" + random.nextInt(i < 1000 ? 12 : 50));
      if (random.nextBoolean()) {
        COSInteger value = COSInteger.get(random.nextInt(1000)); compact.setItem(key, value); regular.setItem(key, value);
      } else { compact.removeItem(key); regular.removeItem(key); }
      assertEquals(new ArrayList<>(regular.keySet()), new ArrayList<>(compact.keySet()));
      for (COSName name : regular.keySet()) assertEquals(regular.getItem(name), compact.getItem(name));
      if (i % 40 == 0) {
        compact.entrySet().removeIf(e -> e.getKey().getName().endsWith("3"));
        regular.entrySet().removeIf(e -> e.getKey().getName().endsWith("3"));
      }
    }
  }

  @Test void repeatedRealPagesShareFontsWithoutChangingTextOrPageOrder() throws Exception {
    Path sample = Path.of("examples/eml/eml_Agenda_and_timeline_slides_1s.pdf");
    try (var source = Loader.loadPDF(sample.toFile()); var target = new PDDocument(CompactStreamCache::new)) {
      var pool = new ArchiveResourcePool();
      String text = new PDFTextStripper().getText(source);
      PdfMailWriter.appendClonedPages(target, source, null, pool);
      try (var second = Loader.loadPDF(sample.toFile())) { PdfMailWriter.appendClonedPages(target, second, null, pool); }
      assertTrue(pool.reusedFonts > 0);
      var output = new ByteArrayOutputStream(); target.save(output);
      try (var loaded = Loader.loadPDF(output.toByteArray())) {
        assertEquals(source.getNumberOfPages() * 2, loaded.getNumberOfPages());
        assertEquals(text + text, new PDFTextStripper().getText(loaded));
      }
    }
  }
}
