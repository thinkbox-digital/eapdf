package eapdf;

import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PatchedHtmlRendererTests {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  @Test void finalCaptionRemainsAssociatedWithItsTable() throws Exception {
    boolean previous = PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures;
    try {
      PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = true;
      var record = EmlReader.readEml("src/test/resources/eapdf/patched-renderer-table.eml", "", java.util.List.of(), false);
      var output = directory.resolve("table.pdf");
      PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1);
      try (var document = Loader.loadPDF(output.toFile())) {
        var root = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
        assertTrue(checkCaptions(root.getDictionaryObject(COSName.K)) > 0);
        assertTrue(new PDFTextStripper().getText(document).contains("Résumé"));
      }
    } finally { PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = previous; }
  }

  private static int checkCaptions(COSBase value) {
    if (value instanceof COSObject object) value = object.getObject();
    int count = 0;
    if (value instanceof COSArray array) for (COSBase child : array) count += checkCaptions(child);
    else if (value instanceof COSDictionary element && element.containsKey(COSName.S)) {
      if ("Caption".equals(element.getNameAsString(COSName.S))) {
        assertEquals("Table", element.getCOSDictionary(COSName.P).getNameAsString(COSName.S)); count++;
      }
      count += checkCaptions(element.getDictionaryObject(COSName.K));
    }
    return count;
  }

  @Test void pdf2BodyPreservesUnicodeSpacesAndUa2StructureNamespace() throws Exception {
    boolean previous = PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures;
    try {
      PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = true;
      byte[] bytes = PdfMailWriter.renderXhtmlPdf("""
          <html xmlns="http://www.w3.org/1999/xhtml" lang="en"><head><title>Unicode spacing</title>
          <meta name="author" content="EA-PDF regression test organization"/></head><body>
          <h1>Unicode spacing</h1><p style="letter-spacing:-0.25px">office affine efficient café naïve Ελληνικά 日本語</p>
          <table><caption>Values</caption><tr><th scope="col">Name</th><th scope="col">Value</th></tr>
          <tr><td>Résumé</td><td>42</td></tr></table></body></html>
          """, "", TestSupport.fontPath());
      assertEquals("%PDF-2.0", new String(bytes, 0, 8, StandardCharsets.US_ASCII));
      try (var document = Loader.loadPDF(bytes)) {
        String extracted = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        assertTrue(extracted.contains("office affine efficient café naïve Ελληνικά 日本語"), extracted);
        String xmp = TestSupport.metadataXml(document);
        assertTrue(xmp.contains("<pdfaid:part>4</pdfaid:part>"));
        assertTrue(xmp.contains("<pdfuaid:part>2</pdfuaid:part>"));
        assertFalse(xmp.contains("<pdfaid:conformance>A</pdfaid:conformance>"));
        var tree = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.STRUCT_TREE_ROOT);
        assertNotNull(tree);
        var namespaces = tree.getCOSArray(COSName.getPDFName("Namespaces"));
        assertNotNull(namespaces);
        var namespace = (COSDictionary) namespaces.getObject(0);
        assertEquals("http://iso.org/pdf2/ssn", namespace.getString(COSName.getPDFName("NS")));
        for (var page : document.getPages()) for (var name : page.getResources().getFontNames()) {
          var font = page.getResources().getFont(name);
          assertTrue(font.isEmbedded(), font.getName());
          assertNotNull(font.getCOSObject().getDictionaryObject(COSName.TO_UNICODE), font.getName());
        }
      }
    } finally { PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = previous; }
  }
}
