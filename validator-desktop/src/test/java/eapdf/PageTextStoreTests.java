package eapdf;

import org.apache.pdfbox.cos.COSDictionary;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PageTextStoreTests {
  @Test void preservesExactTextAcrossChunksAndPageRevisits() throws Exception {
    var first = new COSDictionary(); var second = new COSDictionary();
    String text = "header\r\n\0\ud800\ud83d\ude00" + "long line 中文\n".repeat(2000) + "\rfooter\udfff";
    try (var store = new PageTextStore()) {
      store.put(first, text); store.put(second, "");
      assertEquals(text, store.get(first)); assertEquals("", store.get(second));
      assertEquals("", store.get(new COSDictionary()));
      store.put(second, "replacement\n");
      assertEquals(text, store.get(first)); assertEquals("replacement\n", store.get(second));
      assertEquals(2, store.pages().size());
    }
  }
}
