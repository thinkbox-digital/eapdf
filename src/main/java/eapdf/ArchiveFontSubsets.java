package eapdf;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Subset shared, full TrueType fonts once, without rewriting page character codes. */
final class ArchiveFontSubsets {
  private ArchiveFontSubsets() {}

  static int apply(PDDocument document) throws IOException {
    Map<COSDictionary, Set<Integer>> uses = new IdentityHashMap<>();
    Map<COSDictionary, PDFont> fontObjects = new IdentityHashMap<>();
    PDFTextStripper scan = new PDFTextStripper() {
      @Override protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) {
        fontObjects.putIfAbsent(font.getCOSObject(), font);
        uses.computeIfAbsent(font.getCOSObject(), ignored -> new TreeSet<>()).add(code);
      }
    };
    scan.writeText(document, Writer.nullWriter());
    int changed = 0;
    for (var entry : uses.entrySet()) {
      if (!(fontObjects.get(entry.getKey()) instanceof PDType0Font original)
          || original.getName().contains("+")
          || !COSName.IDENTITY_H.equals(original.getCOSObject().getDictionaryObject(COSName.ENCODING))
          || original.getFontDescriptor() == null || original.getFontDescriptor().getFontFile2() == null) continue;
      Map<Integer, String> text = new HashMap<>();
      boolean safe = true;
      for (int code : entry.getValue()) {
        String unicode = original.toUnicode(code);
        if (unicode == null || unicode.codePointCount(0, unicode.length()) != 1) { safe = false; break; }
        text.put(code, unicode);
      }
      // Keep unusual encodings intact rather than guessing a Unicode/glyph relationship.
      if (!safe || text.isEmpty()) continue;
      PDType0Font subset;
      try (var input = original.getFontDescriptor().getFontFile2().createInputStream()) {
        subset = PDType0Font.load(document, input, true);
      }
      for (var character : text.entrySet()) {
        byte[] bytes = subset.encode(character.getValue());
        int code = bytes.length == 2 ? ((bytes[0] & 255) << 8) | (bytes[1] & 255) : -1;
        if (code != character.getKey() || subset.getWidth(code) != original.getWidth(code)) {
          safe = false; break;
        }
        subset.addToSubset(character.getValue().codePointAt(0));
      }
      // Finish even a rejected candidate so it cannot leave an unfinalized font in the document.
      subset.subset();
      if (!safe) continue;
      for (var character : text.entrySet()) {
        if (!character.getValue().equals(subset.toUnicode(character.getKey()))) { safe = false; break; }
      }
      if (!safe) continue;
      COSDictionary dictionary = original.getCOSObject();
      dictionary.clear();
      dictionary.addAll(subset.getCOSObject());
      changed++;
    }
    return changed;
  }
}
