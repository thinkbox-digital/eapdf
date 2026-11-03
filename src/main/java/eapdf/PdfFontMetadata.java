package eapdf;

import java.io.*;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

/** Normalize generated PDF 2.0 font metadata after all glyph subsets are complete. */
final class PdfFontMetadata {
  private PdfFontMetadata() {}
  static void prepare(PDDocument document) throws IOException {
    EapdfFontFinalizer.finish(document);
    Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<COSBase> pending = new ArrayDeque<>();
    pending.add(document.getDocumentCatalog().getCOSObject());
    while (!pending.isEmpty()) {
      COSBase item = pending.removeLast();
      if (!seen.add(item)) continue;
      if (item instanceof COSObject object) {
        if (object.getObject() != null) pending.add(object.getObject());
      } else if (item instanceof COSDictionary dictionary) {
        if (COSName.FONT_DESC.equals(dictionary.getCOSName(COSName.TYPE))) {
          COSBase weight = dictionary.getDictionaryObject(COSName.FONT_WEIGHT);
          if (weight instanceof COSNumber number && number.floatValue() == number.intValue())
            dictionary.setInt(COSName.FONT_WEIGHT, number.intValue());
          if (document.getVersion() >= 2.0f) dictionary.removeItem(COSName.CID_SET);
        }
        for (COSBase value : dictionary.getValues()) if (value != null) pending.add(value);
      } else if (item instanceof COSArray array) {
        for (COSBase value : array) if (value != null) pending.add(value);
      }
    }
  }
  static void save(PDDocument document, File file) throws IOException {
    prepare(document); document.save(file);
  }
  static void save(PDDocument document, OutputStream output) throws IOException {
    prepare(document); document.save(output);
  }
  static void save(PDDocument document, File file, CompressParameters compression) throws IOException {
    prepare(document); document.save(file, compression);
  }
}
