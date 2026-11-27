package eapdf;

import java.io.IOException;
import java.io.Writer;
import java.util.BitSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontFactory;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Subset shared, full TrueType fonts once, without rewriting page character codes. */
final class ArchiveFontSubsets {
  private ArchiveFontSubsets() {}

  static boolean candidate(COSDictionary font) {
    String name = font.getNameAsString(COSName.BASE_FONT);
    return COSName.TYPE0.equals(font.getCOSName(COSName.SUBTYPE))
        && name != null && !name.contains("+")
        && COSName.IDENTITY_H.equals(font.getDictionaryObject(COSName.ENCODING));
  }

  /** Scans only the worker's own finalized pages. The result owns no PDF objects. */
  static Map<String, BitSet> scanPart(PDDocument document) throws IOException {
    Map<String, BitSet> result = new HashMap<>();
    for (var entry : scan(document).entrySet()) {
      String key = ArchiveResourcePool.fingerprint(entry.getKey());
      if (key != null) result.computeIfAbsent(key, ignored -> new BitSet()).or(entry.getValue());
    }
    return result;
  }

  private static Map<COSDictionary, BitSet> scan(PDDocument document) throws IOException {
    Map<COSDictionary, BitSet> uses = new IdentityHashMap<>();
    PDFTextStripper scan = new PDFTextStripper() {
      private final Map<COSDictionary, BitSet> fonts = new IdentityHashMap<>();
      @Override protected void showGlyph(Matrix matrix, PDFont font, int code, Vector displacement) {
        COSDictionary dictionary = font.getCOSObject();
        if (!fonts.containsKey(dictionary))
          fonts.put(dictionary, candidate(dictionary) ? uses.computeIfAbsent(dictionary, ignored -> new BitSet()) : null);
        BitSet codes = fonts.get(dictionary);
        if (codes != null) codes.set(code);
      }
      @Override public void processPage(PDPage page) throws IOException {
        ProcessingLimits.checkCancelled();
        super.processPage(page);
        ConversionProgress.fontUsePageScanned();
      }
    };
    scan.writeText(document, Writer.nullWriter());
    return uses;
  }

  static final class Usage {
    // COSDictionary uses object identity. Archive insertion order also fixes the
    // order of subset construction even when workers finish out of order.
    private final Map<COSDictionary, BitSet> fonts = new LinkedHashMap<>();

    void record(COSDictionary target, BitSet codes) {
      // A missing worker match disqualifies this dictionary for the whole archive.
      // It may be unused, or its fingerprint may have changed on serialization.
      if (codes == null) fonts.put(target, null);
      else if (!fonts.containsKey(target)) fonts.put(target, (BitSet) codes.clone());
      else if (fonts.get(target) != null) fonts.get(target).or(codes);
    }

    int apply(PDDocument archive) throws IOException {
      ConversionProgress.begin("font_subsets", fonts.size(), "fonts");
      int changed = 0;
      for (var entry : fonts.entrySet()) {
        ProcessingLimits.checkCancelled();
        boolean accepted = entry.getValue() != null && subset(archive, entry.getKey(), entry.getValue());
        if (accepted) changed++;
        ConversionProgress.fontSubsetChecked(accepted);
      }
      return changed;
    }
  }

  private static boolean subset(PDDocument document, COSDictionary dictionary, BitSet uses) throws IOException {
      if (!(PDFontFactory.createFont(dictionary) instanceof PDType0Font original)
          || original.getName().contains("+")
          || !COSName.IDENTITY_H.equals(original.getCOSObject().getDictionaryObject(COSName.ENCODING))
          || original.getFontDescriptor() == null || original.getFontDescriptor().getFontFile2() == null) return false;
      Map<Integer, String> text = new LinkedHashMap<>();
      boolean safe = true;
      for (int code = uses.nextSetBit(0); code >= 0; code = uses.nextSetBit(code + 1)) {
        String unicode = original.toUnicode(code);
        if (unicode == null || unicode.codePointCount(0, unicode.length()) != 1) { safe = false; break; }
        text.put(code, unicode);
      }
      // Keep unusual encodings intact rather than guessing a Unicode/glyph relationship.
      if (!safe || text.isEmpty()) return false;
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
      if (!safe) return false;
      for (var character : text.entrySet()) {
        if (!character.getValue().equals(subset.toUnicode(character.getKey()))) { safe = false; break; }
      }
      if (!safe) return false;
      dictionary.clear();
      dictionary.addAll(subset.getCOSObject());
      return true;
  }
}
