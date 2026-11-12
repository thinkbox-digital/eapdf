package eapdf;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarkedContentRepairTests {
  @Test
  void nestedGroupsAndSurplusClosuresRetainTheRootAndTextOwnership() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);
      page.setResources(new PDResources());
      var font = PdfMailWriter.loadUiFont(document, TestSupport.fontPath());
      String fontName = page.getResources().add(font).getName();
      String content = "EMC /Table BMC /TR BMC /TD BMC /Artifact BMC EMC "
          + "BT /" + fontName + " 12 Tf 50 700 Td (Inside group) Tj ET "
          + "EMC EMC EMC EMC "
          + "BT /" + fontName + " 12 Tf 50 650 Td (After group) Tj ET";
      var stream = new org.apache.pdfbox.pdmodel.common.PDStream(document);
      try (var output = stream.createOutputStream()) {
        output.write(content.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      }
      page.setContents(stream);
      String visible = new PDFTextStripper().getText(document);
      COSDictionary tree = new COSDictionary();
      COSDictionary contentSet = PdfMailWriter.structElem("Mail_ContentSet", tree, page);
      SemanticStructure.attach(document, tree, contentSet, null, 0, 0);

      COSArray rootChildren = assertInstanceOf(COSArray.class, contentSet.getDictionaryObject(COSName.K));
      assertEquals(2, rootChildren.size());
      COSDictionary table = assertInstanceOf(COSDictionary.class, rootChildren.getObject(0));
      assertEquals("Table", table.getNameAsString(COSName.S));
      COSDictionary row = firstChild(table), cell = firstChild(row), inside = firstChild(cell);
      assertEquals("TR", row.getNameAsString(COSName.S));
      assertEquals("TD", cell.getNameAsString(COSName.S));
      assertSame(cell, inside.getDictionaryObject(COSName.P));
      COSDictionary after = assertInstanceOf(COSDictionary.class, rootChildren.getObject(1));
      assertSame(contentSet, after.getDictionaryObject(COSName.P));

      COSDictionary parents = assertInstanceOf(COSDictionary.class, tree.getDictionaryObject(COSName.getPDFName("ParentTree")));
      COSArray numbers = assertInstanceOf(COSArray.class, parents.getDictionaryObject(COSName.NUMS));
      COSArray owners = assertInstanceOf(COSArray.class, numbers.getObject(1));
      assertEquals(2, owners.size());
      assertSame(inside, owners.getObject(0));
      assertSame(after, owners.getObject(1));
      assertEquals(visible, new PDFTextStripper().getText(document));
    }
  }

  private static COSDictionary firstChild(COSDictionary parent) {
    COSArray children = assertInstanceOf(COSArray.class, parent.getDictionaryObject(COSName.K));
    assertEquals(1, children.size());
    return assertInstanceOf(COSDictionary.class, children.getObject(0));
  }

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
  @Test
  void importedAnnotationReferencesUseTheirActualPages() throws Exception {
    try (PDDocument document = new PDDocument()) {
      PDPage first = new PDPage(), second = new PDPage();
      document.addPage(first); document.addPage(second);
      COSDictionary original = new COSDictionary();
      COSDictionary root = PdfMailWriter.structElem("Document", original, first);
      COSDictionary owner = PdfMailWriter.structElem("Link", root, first);
      COSArray references = new COSArray(), numbers = new COSArray();
      for (int index = 0; index < 2; index++) {
        PDPage page = document.getPage(index);
        var annotation = new org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink();
        annotation.getCOSObject().setInt(COSName.getPDFName("StructParent"), index);
        page.getAnnotations().add(annotation);
        COSDictionary reference = new COSDictionary();
        reference.setName(COSName.TYPE, "OBJR");
        reference.setItem(COSName.OBJ, annotation.getCOSObject());
        references.add(reference);
        numbers.add(COSInteger.get(index)); numbers.add(owner);
      }
      owner.setItem(COSName.K, references);
      root.setItem(COSName.K, owner);
      original.setItem(COSName.K, root);
      COSDictionary parents = new COSDictionary();
      parents.setItem(COSName.NUMS, numbers);
      original.setItem(COSName.getPDFName("ParentTree"), parents);
      COSDictionary tree = new COSDictionary();
      COSDictionary contentSet = PdfMailWriter.structElem("Mail_ContentSet", tree, first);
      SemanticStructure.attach(document, tree, contentSet, original, 0, 1);
      for (int index = 0; index < 2; index++) {
        COSDictionary reference = assertInstanceOf(COSDictionary.class, references.getObject(index));
        assertInstanceOf(COSObject.class, reference.getItem(COSName.PG));
        assertSame(document.getPage(index).getCOSObject(), reference.getDictionaryObject(COSName.PG));
        assertSame(document.getPage(index).getAnnotations().get(0).getCOSObject(), reference.getDictionaryObject(COSName.OBJ));
      }
    }
  }

}
