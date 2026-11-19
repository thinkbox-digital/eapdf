package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;

class NestedCoreFieldTests {
  private PDDocument fixture(boolean imageOnly, boolean nestedLink) throws Exception {
    byte[] bytes;
    try (var document = new PDDocument()) {
      var page = new PDPage(); document.addPage(page);
      var root = new PDStructureTreeRoot(); document.getDocumentCatalog().setStructureTreeRoot(root);
      var documentElement = new PDStructureElement("Document", root); root.appendKid(documentElement);
      var field = new PDStructureElement("Mail_FieldValue", documentElement); documentElement.appendKid(field);
      PDStructureElement parent = field;
      if (nestedLink) {
        var link = new PDStructureElement("Link", parent); parent.appendKid(link); parent = link;
      }
      var span = new PDStructureElement("Span", parent); parent.appendKid(span); span.setPage(page);
      span.getCOSObject().setInt(COSName.K, 0);
      page.getCOSObject().setInt(COSName.STRUCT_PARENTS, 0);
      var owners = new COSArray(); owners.add(span.getCOSObject());
      var numbers = new COSArray(); numbers.add(COSInteger.ZERO); numbers.add(owners);
      var parentTree = new COSDictionary(); parentTree.setItem(COSName.NUMS, numbers);
      root.getCOSObject().setItem(COSName.PARENT_TREE, parentTree);
      root.getCOSObject().setInt(COSName.PARENT_TREE_NEXT_KEY, 1);
      var roles = new COSDictionary(); roles.setName(COSName.getPDFName("Mail_FieldValue"), "TD");
      root.getCOSObject().setItem(COSName.ROLE_MAP, roles);
      var properties = new COSDictionary(); properties.setInt(COSName.MCID, 0);
      try (var content = new PDPageContentStream(document, page)) {
        content.beginMarkedContent(COSName.getPDFName("Span"), PDPropertyList.create(properties));
        if (imageOnly) {
          var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
          content.drawImage(LosslessFactory.createFromImage(document, image), 30, 700, 20, 20);
        } else {
          content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(30, 700); content.showText("recipient@example.org"); content.endText();
        }
        content.endMarkedContent();
      }
      var output = new ByteArrayOutputStream(); document.save(output); bytes = output.toByteArray();
    }
    return Loader.loadPDF(bytes);
  }

  private JavaValidation.Report inspect(PDDocument document) throws Exception {
    var report = new JavaValidation.Report("nested-core-fields.pdf"); report.profile = "s";
    new PageContentChecks(document, report).run();
    return report;
  }

  private String level(JavaValidation.Report report, String code) {
    return report.items.asList().stream().map(value -> value.getAsJsonObject())
        .filter(item -> code.equals(item.get("code").getAsString()))
        .map(item -> item.get("level").getAsString()).findFirst().orElse("ABSENT");
  }

  private PDStructureElement field(PDDocument document) {
    var parent = (PDStructureElement) document.getDocumentCatalog().getStructureTreeRoot().getKids().getFirst();
    return (PDStructureElement) parent.getKids().getFirst();
  }

  @Test void nestedSpanAndLinkTextCountsAsCoreFieldText() throws Exception {
    for (boolean link : new boolean[] {false, true}) try (var document = fixture(false, link)) {
      var report = inspect(document);
      assertEquals("PASS", level(report, "STRUCTURE_CORRESPONDENCE"));
      assertEquals("PASS", level(report, "CORE_FIELDS_TEXT_OBJECTS"));
    }
  }

  @Test void nestedImageOnlyHeaderStillFailsTextObjectRequirement() throws Exception {
    try (var document = fixture(true, true)) {
      var report = inspect(document);
      assertEquals("PASS", level(report, "STRUCTURE_CORRESPONDENCE"));
      assertEquals("FAIL", level(report, "CORE_FIELDS_TEXT_OBJECTS"));
    }
  }

  @Test void forgedOffRootParentCannotSupplyAHeaderAncestor() throws Exception {
    try (var document = fixture(false, false)) {
      var field = field(document); var span = (PDStructureElement) field.getKids().getFirst();
      var detached = new COSDictionary(); detached.setName(COSName.S, "Mail_FieldValue");
      detached.setItem(COSName.P, document.getDocumentCatalog().getStructureTreeRoot());
      span.getCOSObject().setItem(COSName.P, detached);
      var report = inspect(document);
      assertEquals("FAIL", level(report, "STRUCTURE_CORRESPONDENCE"));
      assertNotEquals("PASS", level(report, "CORE_FIELDS_TEXT_OBJECTS"));
    }
  }

  @Test void parentCycleIsBoundedAndCannotSupplyHeaderEvidence() throws Exception {
    try (var document = fixture(false, false)) {
      var field = field(document); var span = (PDStructureElement) field.getKids().getFirst();
      field.getCOSObject().setItem(COSName.P, span);
      var report = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> inspect(document));
      assertEquals("FAIL", level(report, "STRUCTURE_CORRESPONDENCE"));
      assertNotEquals("PASS", level(report, "CORE_FIELDS_TEXT_OBJECTS"));
    }
  }

  @Test void mismatchedParentTreeCannotAuthenticateHeaderOwnership() throws Exception {
    try (var document = fixture(false, false)) {
      var root = document.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      var numbers = root.getCOSDictionary(COSName.PARENT_TREE).getCOSArray(COSName.NUMS);
      ((COSArray) numbers.getObject(1)).set(0, field(document).getCOSObject());
      var report = inspect(document);
      assertEquals("FAIL", level(report, "STRUCTURE_CORRESPONDENCE"));
      assertNotEquals("PASS", level(report, "CORE_FIELDS_TEXT_OBJECTS"));
    }
  }
}
