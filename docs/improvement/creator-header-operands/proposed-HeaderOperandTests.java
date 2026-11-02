package eapdf;

import java.nio.charset.StandardCharsets;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HeaderOperandTests {
  private COSDictionary owner(String role, String marker) {
    COSDictionary owner = new COSDictionary();
    owner.setName(COSName.S, role);
    owner.setInt(COSName.K, 0);
    if (marker != null) owner.setString(COSName.ALT, marker);
    return owner;
  }

  private COSDictionary tree(PDDocument document, COSDictionary... owners) {
    COSDictionary tree = new COSDictionary();
    COSArray children = new COSArray();
    COSArray numbers = new COSArray();
    for (int index = 0; index < owners.length; index++) {
      children.add(owners[index]);
      COSArray entry = new COSArray();
      entry.add(owners[index]);
      numbers.add(COSInteger.get(index));
      numbers.add(entry);
    }
    tree.setItem(COSName.K, children);
    COSDictionary parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, numbers);
    tree.setItem(COSName.getPDFName("ParentTree"), parentTree);
    document.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, tree);
    return tree;
  }

  private void content(PDStream stream, String text) throws Exception {
    try (var output = stream.createOutputStream()) {
      output.write(text.getBytes(StandardCharsets.US_ASCII));
    }
  }

  private String content(PDStream stream) throws Exception {
    return new String(stream.toByteArray(), StandardCharsets.US_ASCII);
  }

  @Test
  void repeatedPageMcidsUseTheirOwnParentTreeAndPreserveOtherStreams() throws Exception {
    try (PDDocument document = new PDDocument()) {
      tree(document, owner("TD", "probe:name:From"), owner("Span", null));
      PDStream first = new PDStream(document);
      PDStream second = new PDStream(document);
      String original = "/Span <</MCID 0>> BDC 1 2 m 3 4 l S EMC";
      content(first, original);
      content(second, original);
      for (int index = 0; index < 2; index++) {
        PDPage page = new PDPage();
        page.getCOSObject().setInt(COSName.STRUCT_PARENTS, index);
        page.setContents(index == 0 ? first : second);
        document.addPage(page);
      }
      SemanticStructure.headerFields(document, "probe:");
      assertTrue(content(first).startsWith("/Mail_FieldName "), content(first));
      assertTrue(content(first).contains("1 2 m\n3 4 l\nS"), content(first));
      assertEquals(original, content(second));
      assertSame(first.getCOSObject(), document.getPage(0).getCOSObject().getDictionaryObject(COSName.CONTENTS));
      String once = content(first);
      SemanticStructure.headerFields(document, "probe:");
      assertEquals(once, content(first));
    }
  }

  @Test
  void formMcidsAndNamedPropertiesStayInTheirOwnScope() throws Exception {
    try (PDDocument document = new PDDocument()) {
      tree(document, owner("Span", null), owner("TD", "probe:value:From"));
      PDPage page = new PDPage();
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      page.setResources(new PDResources());
      document.addPage(page);
      PDStream pageStream = new PDStream(document);
      content(pageStream, "/Span <</MCID 0>> BDC EMC /F Do");
      page.setContents(pageStream);
      PDFormXObject form = new PDFormXObject(document);
      form.setStructParents(1);
      form.setResources(new PDResources());
      COSDictionary properties = new COSDictionary();
      properties.setInt(COSName.MCID, 0);
      form.getResources().put(COSName.getPDFName("Header"), PDPropertyList.create(properties));
      form.getResources().put(COSName.getPDFName("Cycle"), form);
      content(form.getContentStream(), "/Span /Header BDC 1 2 m EMC");
      page.getResources().put(COSName.getPDFName("F"), form);
      var identity = form.getCOSObject();
      SemanticStructure.headerFields(document, "probe:");
      assertEquals("/Span <</MCID 0>> BDC EMC /F Do", content(pageStream));
      assertTrue(content(form.getContentStream()).startsWith("/Mail_FieldValue /Header BDC"));
      assertSame(identity, page.getResources().getXObject(COSName.getPDFName("F")).getCOSObject());
      assertEquals(0, properties.getInt(COSName.MCID));
    }
  }

  @Test
  void unownedContentAndNonHeaderAlternateTextAreNotRetagged() throws Exception {
    try (PDDocument document = new PDDocument()) {
      tree(document, owner("P", "probe:name:Body"));
      PDPage page = new PDPage();
      document.addPage(page);
      PDStream stream = new PDStream(document);
      content(stream, "/Span <</MCID 0>> BDC EMC");
      page.setContents(stream);
      SemanticStructure.headerFields(document, "probe:");
      assertEquals("/Span <</MCID 0>> BDC EMC", content(stream));
    }
  }
}
