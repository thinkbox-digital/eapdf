package eapdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpecRepairTests {
  @TempDir Path temporary;

  EmailRecord record(String type, String body) throws Exception {
    return EmlReader.readRfc822Bytes(("From: Alice <alice@example.org>\r\nTo: Bob <bob@example.org>\r\n"
        + "Date: Tue, 28 Jan 2003 21:20:54 -0500\r\nSubject: Preservation\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: " + type + "\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8),
        "source.eml", "source.eml", "", List.of(), false);
  }

  byte[] create(EmailRecord record) throws Exception {
    return PdfMailWriter.buildSingleMessagePdf(temporary.resolve("single.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, temporary, 1, "s").pdfBytes;
  }

  String image(int color) throws Exception {
    var bitmap = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
    for (int row = 0; row < 20; row++) for (int column = 0; column < 20; column++) bitmap.setRGB(column, row, color);
    var output = new ByteArrayOutputStream();
    javax.imageio.ImageIO.write(bitmap, "png", output);
    return "<img alt='Source description' src='data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray()) + "'/>";
  }

  @Test void imageOnlyEmailRetainsEveryRenderedImage() throws Exception {
    EmailRecord record = record("text/html; charset=UTF-8", image(0xff0000) + image(0x0000ff));
    Assertions.assertFalse(PdfMailWriter.looksEmpty(record.htmlBody));
    try (var document = Loader.loadPDF(create(record))) {
      int images = 0;
      for (var page : document.getPages()) for (var name : page.getResources().getXObjectNames()) {
        if (page.getResources().getXObject(name) instanceof PDImageXObject) images++;
      }
      Assertions.assertTrue(images >= 2, "Both source images must survive rendering");
    }
  }

  @Test void missingAndDecorativeAlternativeTextStayDistinctUntilRendering() throws Exception {
    EmailRecord record = record("text/html", "<img src='cid:missing'/><img alt='' src='cid:decorative'/>");
    String html = PdfMailWriter.buildRenderHtml(record, false, temporary);
    var images = org.jsoup.Jsoup.parse(html).select(".message-body img");
    Assertions.assertFalse(images.get(0).hasAttr("alt"));
    Assertions.assertTrue(images.get(1).hasAttr("alt"));
    Assertions.assertEquals("", images.get(1).attr("alt"));
  }

  @Test void selectedBodyMimeParametersSurviveDpmAndMixedSelection() throws Exception {
    String type = "text/plain; charset=UTF-8; format=flowed";
    EmailRecord record = record(type, "Body");
    Assertions.assertEquals(type, record.bodyMime);
    try (var document = Loader.loadPDF(create(record))) {
      var part = (org.apache.pdfbox.cos.COSDictionary) document.getPage(0).getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
      var metadata = (org.apache.pdfbox.cos.COSDictionary) part.getDictionaryObject(COSName.getPDFName("DPM"));
      Assertions.assertEquals(type, metadata.getNameAsString(COSName.getPDFName("Mail_Subtype")));
    }
    EmailRecord mixed = record("multipart/mixed; boundary=parts", "--parts\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n<p>First</p>\r\n"
        + "--parts\r\nContent-Type: text/plain; charset=US-ASCII\r\n\r\nSecond\r\n--parts--\r\n");
    Assertions.assertEquals("text/html; charset=UTF-8", mixed.bodyMime);
  }

  @Test void collectionNamesArePortableBoundedAndCaseInsensitive() {
    Assertions.assertEquals("colon_source.eml", Common.collectionFilename("colon:source.eml"));
    Assertions.assertFalse(Common.collectionFilename("trailing.").endsWith("."));
    Assertions.assertTrue(Common.collectionFilename("long".repeat(100) + ".eml").length() <= 200);
    Assertions.assertEquals("source_2.eml", PdfMailWriter.uniqueAttachmentKey(Map.of("SOURCE.EML", true), "source.eml"));
    Assertions.assertEquals("STRASSE_2.eml", PdfMailWriter.uniqueAttachmentKey(Map.of("Straße.eml", true), "STRASSE.eml"));
    Assertions.assertEquals("STRASSE_2.eml", PdfMailWriter.uniqueAttachmentKey(Map.of("Straẞe.eml", true), "STRASSE.eml"));
  }

  @Test void publicationGateRejectsWrongNonFullScreenNavigation() throws Exception {
    try (var document = Loader.loadPDF(create(record("text/plain", "Body")))) {
      PdfCreationValidator.validate(document);
      document.getDocumentCatalog().getViewerPreferences().getCOSObject().setName(COSName.getPDFName("NonFullScreenPageMode"), "UseNone");
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
    }
  }



  @Test void collectionGateRejectsInvalidFilesFoldersCollisionsAndEditableCustomFields() throws Exception {
    EmailRecord record = record("text/plain", "Body");
    record.sourceFilename = "colon:source.eml";
    record.associatedFiles.get(0).filename = record.sourceFilename;
    Path output = temporary.resolve("collection.pdf");
    PdfMailWriter.writeEaPdfCollection(output, List.of(record), TestSupport.fontPath(), TestSupport.iccPath(), false, temporary, "c");
    try (var document = Loader.loadPDF(output.toFile())) {
      PdfCreationValidator.validate(document);
      var node = document.getDocumentCatalog().getNames().getEmbeddedFiles();
      var names = new java.util.TreeMap<>(node.getNames());
      var source = names.entrySet().stream().filter(entry -> "Source".equals(entry.getValue().getCOSObject()
          .getNameAsString(COSName.getPDFName("AFRelationship")))).findFirst().orElseThrow();
      Assertions.assertEquals("colon_source.eml", source.getValue().getFileUnicode());
      source.getValue().setFileUnicode("colon:source.eml");
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
      source.getValue().setFileUnicode("colon_source.eml");
      names.put(source.getKey().toUpperCase(java.util.Locale.ROOT), source.getValue());
      node.setNames(names);
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
      names.remove(source.getKey().toUpperCase(java.util.Locale.ROOT));
      node.setNames(names);
      var collection = TestSupport.collectionDictionary(document);
      var schema = (org.apache.pdfbox.cos.COSDictionary) collection.getDictionaryObject(COSName.getPDFName("Schema"));
      var custom = new org.apache.pdfbox.cos.COSDictionary();
      custom.setBoolean(COSName.E, true);
      schema.setItem(COSName.getPDFName("Custom"), custom);
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
      schema.removeItem(COSName.getPDFName("Custom"));
      var root = (org.apache.pdfbox.cos.COSDictionary) collection.getDictionaryObject(COSName.getPDFName("Folders"));
      var child = (org.apache.pdfbox.cos.COSDictionary) root.getDictionaryObject(COSName.getPDFName("Child"));
      child.setString(COSName.NAME, "invalid:folder");
      Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
    }
  }

  @Test void mailboxExportBoundaryIsExplicitInPdfAndPremisDescriptions() throws Exception {
    for (String type : List.of("pst", "mbox")) {
      EmailRecord record = record("text/plain", "Exported body");
      record.sourceType = type;
      record.sourcePath = "original." + type;
      Assertions.assertTrue(Common.sourceDescription(record).contains("Exported RFC 822"));
      try (var document = Loader.loadPDF(create(record))) {
        String metadata = TestSupport.metadataXml(document);
        Assertions.assertTrue(metadata.contains("preprocessing:"));
        Assertions.assertTrue(metadata.contains("not embedded"));
      }
    }
  }

  @Test void unusedInlineImagesAreNeverDrawnAtInventedPositions() throws Exception {
    EmailRecord record = record("text/html", "<p>Only visible text</p>");
    AssociatedFile unused = new AssociatedFile();
    unused.filename = "hidden.png";
    unused.mime = "image/png";
    unused.inline = true;
    unused.bytes = Base64.getDecoder().decode(image(0xff0000).split("base64,")[1].split("'")[0]);
    record.associatedFiles.add(unused);
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      PdfMailWriter.drawInlineImageFallback(document, record, false);
      Assertions.assertFalse(PdfMailWriter.documentHasImageXObjects(document));
    }
  }

  @Test void auxiliaryCollectionFilenamesAreSanitizedAndRemainUniquelyLinked() throws Exception {
    try (var document = Loader.loadPDF(create(record("text/plain", "Body")))) {
      PdfCollectionSupport.addMessageCollectionDictionary(document);
      var first = PdfAttachmentSupport.embedStandaloneCatalogBytes(document, "colon:aid.xml", new byte[]{1},
          "Auxiliary", "application/xml", "Data", false);
      var second = PdfAttachmentSupport.embedStandaloneCatalogBytes(document, "COLON_AID.XML", new byte[]{2},
          "Auxiliary", "application/xml", "Data", false);
      Assertions.assertEquals("colon_aid.xml", first.getFileUnicode());
      Assertions.assertEquals("COLON_AID_2.XML", second.getFileUnicode());
      var names = document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
      Assertions.assertSame(first.getCOSObject(), names.get(first.getFileUnicode()).getCOSObject());
      Assertions.assertSame(second.getCOSObject(), names.get(second.getFileUnicode()).getCOSObject());
    }
  }

  @Test void collectionLabelsRemainSafe() {
    Assertions.assertFalse(Common.safeCollectionLabel("x".repeat(119) + ".extra", "Messages").endsWith("."));
    Assertions.assertFalse(Common.safeCollectionLabel("bad\0folder", "Messages").contains("\0"));
  }

  @Test void originalSourceNamesCannotSpoofACollectionFolderPrefix() throws Exception {
    EmailRecord record = record("text/plain", "Body");
    record.sourceFilename = "<999>source.eml";
    record.associatedFiles.get(0).filename = record.sourceFilename;
    Path output = temporary.resolve("prefix.pdf");
    PdfMailWriter.writeEaPdfCollection(output, List.of(record), TestSupport.fontPath(), TestSupport.iccPath(), false, temporary, "c");
    try (var document = Loader.loadPDF(output.toFile())) {
      Assertions.assertTrue(TestSupport.embeddedFileNames(document).contains("_999_source.eml"));
      Assertions.assertFalse(TestSupport.embeddedFileNames(document).stream().anyMatch(name -> name.startsWith("<999>")));
    }
  }


}
