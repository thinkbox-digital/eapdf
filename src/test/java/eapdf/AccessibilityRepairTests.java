package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AccessibilityRepairTests {
  @TempDir Path temp;

  @Test
  void legacyHeadingsAndOrphanListItemsKeepValidStructureAndAllText() throws Exception {
    var record = EmlReader.readRfc822Bytes(("From: a@example.org\r\nTo: b@example.org\r\n"
        + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Legacy structure\r\n"
        + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
        + "<h2>Heading<p></h2><table><tr><td><div><li>First item</li>"
        + "<li>Second item</li></div><ul><li>Normal item</li></ul></td></tr></table>")
        .getBytes(StandardCharsets.UTF_8), "structure.eml", "structure.eml", "", List.of(), false);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("structure.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      for (String expected : List.of("Heading", "First item", "Second item", "Normal item")) assertTrue(text.contains(expected));
      int[] counts = new int[2];
      checkHeadingLists(pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject().getDictionaryObject(COSName.K), "", counts);
      assertEquals(2, counts[0], "One recovered list and one existing list");
      assertEquals(3, counts[1], "Every list item is retained");
    }
  }

  private void checkHeadingLists(COSBase value, String parent, int[] counts) {
    if (value instanceof COSObject ref) value = ref.getObject();
    if (value instanceof COSArray array) {
      for (COSBase child : array) checkHeadingLists(child, parent, counts);
    } else if (value instanceof COSDictionary element && element.containsKey(COSName.S)) {
      String role = element.getNameAsString(COSName.S);
      if ("LI".equals(role)) { assertEquals("L", parent); counts[1]++; }
      if ("L".equals(role)) counts[0]++;
      if (parent.matches("H[1-6]?")) assertNotEquals("P", role);
      checkHeadingLists(element.getDictionaryObject(COSName.K), "Div".equals(role) ? parent : role, counts);
    }
  }

  @Test
  void linkDescriptionsAndInheritedLanguageAreImprovedOnlyWhenEnabled() throws Exception {
    boolean previous = PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures;
    String visible = null;
    try {
      for (boolean enabled : new boolean[]{false, true}) {
        PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = enabled;
        String html = "<html xml:lang='fr'><body><p>Bonjour <a href='https://example.org'>visiter</a> "
            + "<a href='https://example.org/help' title='Aide du site'>aide</a></p>"
            + "<p xml:lang='de'>Guten Tag</p></body></html>";
        EmailRecord record = EmlReader.readRfc822Bytes(("From: a@example.org\r\nTo: b@example.org\r\n"
            + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Languages and links\r\n"
            + "Content-Type: text/html; charset=UTF-8\r\n\r\n" + html).getBytes(StandardCharsets.UTF_8),
            "languages.eml", "languages.eml", "", List.of(), false);
        var canonical = org.jsoup.Jsoup.parse(PdfMailWriter.canonicalizeEmailHtml(html, record, false));
        assertEquals(enabled ? "fr" : "", canonical.selectFirst(".ea-email-body").attr("lang"));
        assertEquals(enabled ? "de" : "", canonical.selectFirst("p:contains(Guten)").attr("lang"));
        byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("languages-" + enabled + ".pdf"), record,
            TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
        try (var pdf = Loader.loadPDF(bytes)) {
          String text = new PDFTextStripper().getText(pdf);
          if (visible == null) visible = text;
          else assertEquals(visible, text);
          var tree = pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
          var numbers = (COSArray)((COSDictionary)tree.getDictionaryObject(COSName.getPDFName("ParentTree"))).getDictionaryObject(COSName.NUMS);
          int checked = 0;
          for (var page : pdf.getPages()) for (var annotation : page.getAnnotations()) {
            if (!(annotation instanceof PDAnnotationLink)) continue;
            int key = annotation.getCOSObject().getInt(COSName.getPDFName("StructParent"));
            COSDictionary owner = null;
            for (int i = 0; i < numbers.size(); i += 2)
              if (((COSNumber)numbers.getObject(i)).intValue() == key) owner = (COSDictionary)numbers.getObject(i + 1);
            assertNotNull(owner);
            String alt = owner.getString(COSName.ALT);
            if (enabled) assertEquals(alt, annotation.getContents());
            if (annotation.getContents().equals("visiter")) assertEquals(enabled ? "visiter" : "", alt);
            checked++;
          }
          assertEquals(2, checked);
          if (enabled) {
            assertTrue(hasLanguage(tree.getDictionaryObject(COSName.K), "fr"));
            assertTrue(hasLanguage(tree.getDictionaryObject(COSName.K), "de"));
          }
          String metadata = TestSupport.metadataXml(pdf);
          assertEquals(enabled, metadata.contains("<pdfuaid:part>2</pdfuaid:part>"));
          assertEquals(enabled, metadata.contains("<pdfuaid:rev>2024</pdfuaid:rev>"));
        }
      }
    } finally { PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = previous; }
  }

  private boolean hasLanguage(COSBase value, String language) {
    if (value instanceof COSObject ref) value = ref.getObject();
    if (value instanceof COSArray array) {
      for (var child : array) if (hasLanguage(child, language)) return true;
    } else if (value instanceof COSDictionary dictionary) {
      return language.equals(dictionary.getString(COSName.LANG)) || hasLanguage(dictionary.getDictionaryObject(COSName.K), language);
    }
    return false;
  }

  @Test
  void emptyHeaderAndBodyCellsKeepTheirTableColumns() throws Exception {
    boolean previous = PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures;
    try {
      PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = true;
      var record = EmlReader.readRfc822Bytes(("From: a@example.org\r\n"
          + "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Blank cells\r\n"
          + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
          + "<table><tr><th>A</th><th>B</th></tr><tr><td></td><td>present</td></tr></table>")
          .getBytes(StandardCharsets.UTF_8), "blank.eml", "blank.eml", "", List.of(), false);
      byte[] bytes = PdfMailWriter.buildSingleMessagePdf(temp.resolve("blank.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, temp, 1, "s").pdfBytes;
      try (var pdf = Loader.loadPDF(bytes)) {
        assertTrue(checkTwoColumnRows(pdf.getDocumentCatalog().getStructureTreeRoot().getCOSObject()) >= 6);
        assertTrue(new PDFTextStripper().getText(pdf).contains("present"));
      }
    } finally { PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = previous; }
  }

  private int checkTwoColumnRows(COSBase value) {
    if (value instanceof COSObject reference) value = reference.getObject();
    if (value instanceof COSArray array) {
      int rows = 0;
      for (var child : array) rows += checkTwoColumnRows(child);
      return rows;
    }
    if (!(value instanceof COSDictionary dictionary)) return 0;
    var children = dictionary.getDictionaryObject(COSName.K);
    boolean row = java.util.Set.of("TR", "Mail_Field").contains(dictionary.getNameAsString(COSName.S, ""));
    if (row) assertEquals(2, assertInstanceOf(COSArray.class, children).size());
    return (row ? 1 : 0) + checkTwoColumnRows(children);
  }

  @Test
  void generatedRulesAreArtifactsOnlyWhenEnabled() throws Exception {
    boolean previous = PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures;
    try {
      for (boolean enabled : new boolean[]{false, true}) {
        PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = enabled;
        try (var pdf = new PDDocument()) {
          var page = new PDPage();pdf.addPage(page);
          try (var content = new PDPageContentStream(pdf, page)) { PdfMailWriter.drawRule(content, 10, 10, 100, 10); }
          var tokens = new PDFStreamParser(page).parse();
          assertEquals(enabled, tokens.contains(COSName.ARTIFACT));
          assertEquals(1, tokens.stream().filter(t -> t instanceof Operator op && "S".equals(op.getName())).count());
        }
      }
    } finally { PdfMailWriter.CONFIG.accessibility.addPdfUaFeatures = previous; }
  }
}
