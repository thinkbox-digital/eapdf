package eapdf;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkedContentRepairTests {
  @Test
  void laterPageTextWithANullRendererOwnerGetsAReachableStructureReference() throws Exception {
    try (PDDocument document = new PDDocument()) {
      var font = PdfMailWriter.loadUiFont(document, TestSupport.fontPath());
      for (String text : new String[]{"First page", "Second page"}) {
        PDPage page = new PDPage();
        document.addPage(page);
        page.getCOSObject().setInt(COSName.STRUCT_PARENTS, document.getNumberOfPages() - 1);
        COSDictionary properties = new COSDictionary();
        properties.setInt(COSName.MCID, 0);
        try (var stream = new PDPageContentStream(document, page)) {
          stream.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
          stream.beginText();
          stream.setFont(font, 12);
          stream.newLineAtOffset(50, 700);
          stream.showText(text);
          stream.endText();
          stream.endMarkedContent();
        }
      }
      String visible = new PDFTextStripper().getText(document);
      COSDictionary original = new COSDictionary();
      COSDictionary root = PdfMailWriter.structElem("Document", original, document.getPage(0));
      COSDictionary first = PdfMailWriter.structElem("Span", root, document.getPage(0));
      first.setInt(COSName.K, 0);
      root.setItem(COSName.K, first);
      original.setItem(COSName.K, root);
      COSArray firstParents = new COSArray();
      firstParents.add(first);
      COSArray laterParents = new COSArray();
      laterParents.add(COSNull.NULL);
      COSArray numbers = new COSArray();
      numbers.add(COSInteger.ZERO); numbers.add(firstParents);
      numbers.add(COSInteger.ONE); numbers.add(laterParents);
      COSDictionary parentTree = new COSDictionary();
      parentTree.setItem(COSName.NUMS, numbers);
      original.setItem(COSName.getPDFName("ParentTree"), parentTree);

      COSDictionary tree = new COSDictionary();
      COSDictionary contentSet = PdfMailWriter.structElem("Mail_ContentSet", tree, document.getPage(0));
      SemanticStructure.attach(document, tree, contentSet, original, 0, 1);
      COSDictionary repaired = assertInstanceOf(COSDictionary.class, laterParents.getObject(0));
      assertEquals("Span", repaired.getNameAsString(COSName.S));
      assertEquals(0, repaired.getInt(COSName.K));
      assertSame(document.getPage(1).getCOSObject(), repaired.getDictionaryObject(COSName.PG));
      COSDictionary paragraph = assertInstanceOf(COSDictionary.class, repaired.getDictionaryObject(COSName.P));
      assertEquals("P", paragraph.getNameAsString(COSName.S));
      assertSame(contentSet, paragraph.getDictionaryObject(COSName.P));
      assertSame(repaired, assertInstanceOf(COSArray.class, paragraph.getDictionaryObject(COSName.K)).getObject(0));
      assertSame(first, firstParents.getObject(0));
      assertEquals(visible, new PDFTextStripper().getText(document));
    }
  }
}
