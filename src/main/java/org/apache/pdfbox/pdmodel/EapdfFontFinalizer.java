package org.apache.pdfbox.pdmodel;

import java.io.IOException;
import org.apache.pdfbox.pdmodel.font.PDFont;

/** Finalize the same subset queue consumed by PDDocument.save before metadata repair. */
public final class EapdfFontFinalizer {
  private EapdfFontFinalizer() {}
  public static void finish(PDDocument document) throws IOException {
    for (PDFont font : document.getFontsToSubset()) font.subset();
    document.getFontsToSubset().clear();
  }
}
