package eapdf;

import java.util.List;
import eapdf.PdfMailWriter.MessageSegment;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveStructureTests {
  @Test
  void sharedEmbeddedFileRetainsBothMessageOwners() throws Exception {
    EmailRecord first = new EmailRecord(); first.mailGuid = "first"; first.embedSourceMessage = true;
    EmailRecord second = new EmailRecord(); second.mailGuid = "second"; second.embedSourceMessage = true;
    AssociatedFile file = new AssociatedFile(); file.filename = "shared.txt"; file.mime = "text/plain";
    file.afRelationship = "Supplement"; file.sourceSetMember = true; file.bytes = new byte[]{65,66};
    first.associatedFiles.add(file); second.associatedFiles.add(file);
    var plans = PdfMailWriter.buildArchiveEmbeddedFilePlans(List.of(first, second));
    assertEquals(3, plans.size());
    assertEquals(List.of(first, second), plans.stream().filter(p -> p.relationship.equals("Supplement")).findFirst().orElseThrow().owners);
    try (PDDocument doc = new PDDocument()) {
      doc.addPage(new PDPage()); doc.addPage(new PDPage());
      PdfMailWriter.addArchiveDPartStructure(doc, List.of(new MessageSegment(first,1,1),new MessageSegment(second,2,2)),0);
      PdfMailWriter.embedAssociatedFiles(doc,List.of(first,second),false);
      var owners = PdfMailWriter.messageFileOwners(doc);
      var firstFiles = (COSArray)owners.get("first").getDictionaryObject(COSName.getPDFName("AF"));
      var secondFiles = (COSArray)owners.get("second").getDictionaryObject(COSName.getPDFName("AF"));
      assertEquals(2, firstFiles.size()); assertEquals(2, secondFiles.size());
      assertSame(firstFiles.getObject(0), secondFiles.getObject(0));
    }
  }

  @Test
  void archiveFrontMatterBelongsToTheDocumentInBothTrees() throws Exception {
    try (PDDocument document = new PDDocument()) {
      for (int index = 0; index < 3; index++) document.addPage(new PDPage());
      EmailRecord first = new EmailRecord();
      first.mailGuid = "first-owner";
      EmailRecord second = new EmailRecord();
      second.mailGuid = "second-owner";
      var segments = List.of(new MessageSegment(first, 2, 2), new MessageSegment(second, 3, 3));
      PdfMailWriter.addArchiveDPartStructure(document, segments, 1);
      PdfMailWriter.addArchiveLogicalStructure(document, segments, 1);

      COSName dpart = COSName.getPDFName("DPart");
      COSName parent = COSName.getPDFName("Parent");
      COSDictionary frontPart = TestSupport.derefDict(document.getPage(0).getCOSObject().getDictionaryObject(dpart));
      COSDictionary firstBody = TestSupport.derefDict(document.getPage(1).getCOSObject().getDictionaryObject(dpart));
      COSDictionary secondBody = TestSupport.derefDict(document.getPage(2).getCOSObject().getDictionaryObject(dpart));
      COSDictionary firstOwner = TestSupport.derefDict(firstBody.getDictionaryObject(parent));
      assertSame(firstOwner.getDictionaryObject(parent), frontPart.getDictionaryObject(parent));
      assertNotSame(firstOwner, frontPart.getDictionaryObject(parent));
      assertNotSame(firstOwner, secondBody.getDictionaryObject(parent));
      assertEquals(first.mailGuid, TestSupport.derefDict(firstOwner.getDictionaryObject(COSName.getPDFName("DPM")))
          .getString(COSName.getPDFName("Mail_GUID")));

      COSDictionary tree = TestSupport.derefDict(document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
      COSDictionary root = TestSupport.derefDict(tree.getDictionaryObject(COSName.K));
      COSArray messages = assertInstanceOf(COSArray.class, root.getDictionaryObject(COSName.K));
      assertEquals(3, messages.size());
      COSDictionary front = TestSupport.derefDict(messages.get(0));
      COSDictionary message = TestSupport.derefDict(messages.get(1));
      assertEquals("Mail_Message", message.getNameAsString(COSName.S));
      assertEquals("Mail_ContentSet", front.getNameAsString(COSName.S));
      assertSame(root, front.getDictionaryObject(COSName.P));
      assertSame(document.getPage(0).getCOSObject(), front.getDictionaryObject(COSName.PG));
    }
  }
}
