package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class CreatorRuleRefinementTests {
  private static final String MAIL = "http://www.pdfa.org/eapdf/ns/meta/";
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  @TempDir Path directory;

  EmailRecord record() throws Exception {
    String source = "From: Sender <sender@example.org>\r\nTo: Recipient <recipient@example.org>\r\n"
        + "Cc: Copy <copy@example.org>\r\nBcc: Private <private@example.org>\r\n"
        + "Subject: A complete original subject with literal & and <characters>\r\n"
        + "Date: Fri, 30 Oct 2026 12:00:00 +0000\r\nMessage-ID: <Original@Example.org>\r\n"
        + "In-Reply-To: <Parent@Example.org>\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8; format=flowed\r\n\r\nOriginal body.";
    return EmlReader.readRfc822Bytes(source.getBytes(StandardCharsets.UTF_8), "source.eml", "source.eml", "", List.of(), false);
  }

  byte[] single(EmailRecord record) throws Exception {
    return PdfMailWriter.buildSingleMessagePdf(directory.resolve("single.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
  }

  org.w3c.dom.Document metadata(PDDocument document) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(document.getDocumentCatalog().getMetadata().toByteArray()));
  }

  void replaceMetadata(PDDocument document, org.w3c.dom.Document xml) throws Exception {
    ByteArrayOutputStream data = new ByteArrayOutputStream();
    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(xml), new StreamResult(data));
    document.getDocumentCatalog().getMetadata().importXMPMetadata(data.toByteArray());
  }

  @Test void sourceAndCompanionMediaParametersRetainCaseAndQuotedTextInEveryEmbeddingPath() throws Exception {
    String sourceType = "message/rfc822; profile=\"CaseSensitive; padded \"";
    String companionType = "text/plain; charset=UTF-8; name=\"CaseSensitive; padded \"";
    for (String profile : List.of("s", "m", "c")) {
      EmailRecord first = record();
      first.associatedFiles.stream().filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow().rawContentType = sourceType;
      AssociatedFile companion = new AssociatedFile();
      companion.filename = "original-headers.txt";
      companion.mime = "text/plain";
      companion.rawContentType = companionType;
      companion.afRelationship = "Supplement";
      companion.sourceSetMember = true;
      companion.bytes = "Original companion data".getBytes(StandardCharsets.UTF_8);
      first.associatedFiles.add(companion);
      Path output = directory.resolve(profile + ".pdf");
      byte[] bytes;
      if (profile.equals("s")) bytes = single(first);
      else {
        List<EmailRecord> records = List.of(first, record());
        if (profile.equals("m")) PdfMailWriter.writeEaPdfArchive(output, records, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
        else PdfMailWriter.writeEaPdfCollection(output, records, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
        bytes = Files.readAllBytes(output);
      }
      try (PDDocument document = Loader.loadPDF(bytes)) {
        boolean sourceFound = false, companionFound = false;
        for (var file : AttachmentProfilePolicyTests.files(document).values()) {
          String relationship = file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship"));
          if (!List.of("Source", "Supplement").contains(relationship)) continue;
          var stream = file.getEmbeddedFileUnicode();
          if (stream == null) stream = file.getEmbeddedFile();
          Assertions.assertFalse(stream.getSubtype().contains(";"));
          var parameters = stream.getCOSObject().getDictionaryObject(COSName.getPDFName("Mail_MediaTypeParameters"));
          if (parameters == null) continue;
          Assertions.assertInstanceOf(COSString.class, parameters);
          String expected = relationship.equals("Source") ? sourceType : companionType;
          Assertions.assertEquals(expected.substring(expected.indexOf(';') + 1).trim(), ((COSString) parameters).getString());
          sourceFound |= relationship.equals("Source");
          companionFound |= relationship.equals("Supplement");
        }
        Assertions.assertTrue(sourceFound);
        Assertions.assertTrue(companionFound);
        var xml = metadata(document);
        var assets = (Element) xml.getElementsByTagNameNS(MAIL, "assets").item(0);
        var entries = assets.getElementsByTagNameNS(RDF, "li");
        for (int index = 0; index < entries.getLength(); index++) {
          Element entry = (Element) entries.item(index);
          String type = entry.getElementsByTagNameNS(MAIL, "ContentType").item(0).getTextContent();
          if (type.equals(companionType)) Assertions.assertEquals(0, entry.getElementsByTagNameNS(MAIL, "NumberMessages").getLength(),
              "A companion file does not establish its own rendered-message count");
        }
        PdfCreationValidator.validate(document);
      }
    }
  }

  @Test void inputInventoryRejectsMissingOrTruncatedOriginalHeaders() throws Exception {
    EmailRecord record = record();
    var expected = PreservationInventory.capture(List.of(record), "s");
    byte[] bytes = single(record);
    for (String field : List.of("Subject", "messageid", "Raw-From", "Raw-To", "Raw-Cc", "Raw-Bcc", "In-Reply-To", "To")) {
      try (PDDocument document = Loader.loadPDF(bytes)) {
        var xml = metadata(document);
        var node = xml.getElementsByTagNameNS(MAIL, field).item(0);
        Assertions.assertNotNull(node, field);
        if (field.equals("Subject")) node.setTextContent("Truncated");
        else node.getParentNode().removeChild(node);
        replaceMetadata(document, xml);
        var failure = Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document, expected));
        Assertions.assertTrue(failure.getMessage().contains("header " + field + " differs from input"), failure.getMessage());
      }
    }
    try (PDDocument document = Loader.loadPDF(bytes)) { PdfCreationValidator.validate(document, expected); }
  }

  @Test void inputInventoryRejectsChangedAddressEvenWhenRawHeaderRemains() throws Exception {
    EmailRecord record = record();
    var expected = PreservationInventory.capture(List.of(record), "s");
    try (PDDocument document = Loader.loadPDF(single(record))) {
      var xml = metadata(document);
      var to = (Element) xml.getElementsByTagNameNS(MAIL, "To").item(0);
      var agent = (Element) to.getElementsByTagNameNS(RDF, "Description").item(0);
      agent.setAttributeNS("http://xmlns.com/foaf/0.1/", "foaf:mbox", "mailto:wrong@example.org");
      replaceMetadata(document, xml);
      var failure = Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document, expected));
      Assertions.assertTrue(failure.getMessage().contains("header To differs from input"), failure.getMessage());
    }
  }

  @Test void bodyMediaTypeMustKeepTheCapturedSourceParameters() throws Exception {
    EmailRecord record = record();
    var expected = PreservationInventory.capture(List.of(record), "s");
    try (PDDocument document = Loader.loadPDF(single(record))) {
      var part = (COSDictionary) document.getPage(0).getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
      part.getCOSDictionary(COSName.getPDFName("DPM")).setName(COSName.getPDFName("Mail_Subtype"), "text/plain; charset=UTF-8");
      var failure = Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document, expected));
      Assertions.assertTrue(failure.getMessage().contains("Body rendering media type differs from input"), failure.getMessage());
    }
  }

  @Test void aBodyRenderingCannotLoseItsMessageOwnership() throws Exception {
    EmailRecord record = record();
    var expected = PreservationInventory.capture(List.of(record), "s");
    try (PDDocument document = Loader.loadPDF(single(record))) {
      var part = (COSDictionary) document.getPage(0).getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
      var parent = part.getCOSDictionary(COSName.getPDFName("Parent"));
      parent.getCOSDictionary(COSName.getPDFName("DPM")).removeItem(COSName.getPDFName("Mail_GUID"));
      var failure = Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document, expected));
      Assertions.assertTrue(failure.getMessage().contains("Body rendering has no expected message owner"), failure.getMessage());
    }
  }

  @Test void clippedAttachmentIconsAreRejectedAndRecommendedParametersRemainOptional() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("CropBox and optional checksum");
    try (PDDocument document = Loader.loadPDF(single(record))) {
      for (var page : document.getPages()) for (var annotation : page.getAnnotations()) {
        if (!(annotation instanceof PDAnnotationFileAttachment attachment)) continue;
        var file = (org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification) attachment.getFile();
        var embedded = file.getEmbeddedFileUnicode();
        var parameters = embedded.getCOSObject().getCOSDictionary(COSName.PARAMS);
        parameters.removeItem(COSName.getPDFName("CheckSum"));
        parameters.removeItem(COSName.SIZE);
        PdfCreationValidator.validate(document);
        parameters.setInt(COSName.SIZE, 987654);
        Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document),
            "A supplied incorrect size remains invalid");
        parameters.removeItem(COSName.SIZE);
        PDRectangle original = annotation.getRectangle();
        annotation.setRectangle(new PDRectangle(page.getCropBox().getUpperRightX() - 2, original.getLowerLeftY(), 13, 10));
        var failure = Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
        Assertions.assertTrue(failure.getMessage().contains("outside CropBox"), failure.getMessage());
        return;
      }
      Assertions.fail("Expected an attachment icon");
    }
  }
}
