package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class OutlookSourceTests {
  static byte[] source(String name) throws Exception {
    Path path = Path.of("..", "in", name);
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(path), "Optional source fixture absent: " + name);
    return Files.readAllBytes(path);
  }

  @Test void originalStoredHeadersAndBodyTypesAreAvailable() throws Exception {
    var evidence = OutlookSource.inspect(source("TxtSampleEmail.msg"));
    assertNotNull(evidence);
    assertTrue(new String(evidence.headers(), StandardCharsets.UTF_8).contains("Subject: TxtSampleEmail"));
    assertEquals(List.of("text/plain", "text/rtf"), evidence.bodyTypes());
    assertTrue(evidence.attachments().isEmpty());
  }

  @Test void exactByValuePayloadIsInspectedWithoutInventingMissingMimeType() throws Exception {
    byte[] bytes = source("TxtSampleEmailWithAttachment.msg");
    var evidence = OutlookSource.inspect(bytes);
    assertNotNull(evidence); assertEquals(1, evidence.attachments().size());
    var attachment = evidence.attachments().getFirst();
    assertEquals(List.of("SampleAttachment.pdf"), attachment.names());
    assertEquals("", attachment.contentType());
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(bytes));
         var stream = ((org.apache.poi.poifs.filesystem.DirectoryNode) storage.getRoot().getEntry("__attach_version1.0_#00000000"))
             .createDocumentInputStream("__substg1.0_37010102")) {
      byte[] original = stream.readAllBytes();
      assertEquals(original.length, attachment.size());
      assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(original)), attachment.md5());
    }
  }

  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {
      "test_internal.msg", "sample.eml"})
  void unsupportedEvidenceDoesNotBecomeAPass(String name) throws Exception {
    assertNull(OutlookSource.inspect(source(name)));
  }

  @ParameterizedTest @CsvSource({"Subject,Wrong,MESSAGE_METADATA_MATCH_SOURCE", "SizeInBytes,1,MESSAGE_METADATA_MATCH_SOURCE",
      "Sent,2026-01-01T00:00:00Z,MESSAGE_METADATA_MATCH_SOURCE", "messageid,wrong-id,MESSAGE_METADATA_MATCH_SOURCE"})
  void sourceContradictionsAreRejected(String property, String value, String code) throws Exception {
    var report = compare(property, value);
    assertTrue(report.items.asList().stream().map(item -> item.getAsJsonObject()).anyMatch(item ->
        code.equals(item.get("code").getAsString()) && "FAIL".equals(item.get("level").getAsString())), report.items.toString());
  }

  @Test void originalMetadataBindsToTheMsgAndPasses() throws Exception {
    var report = compare("Subject", "TxtSampleEmail");
    assertTrue(report.items.asList().stream().map(item -> item.getAsJsonObject()).anyMatch(item ->
        "MESSAGE_SOURCE_COVERAGE".equals(item.get("code").getAsString()) && "PASS".equals(item.get("level").getAsString())));
    assertTrue(report.items.asList().stream().map(item -> item.getAsJsonObject()).noneMatch(item ->
        "FAIL".equals(item.get("level").getAsString())), report.items.toString());
  }

  private JavaValidation.Report compare(String property, String value) throws Exception {
    byte[] bytes = source("TxtSampleEmail.msg");
    String xml = "<rdf:RDF xmlns:rdf='" + EaPdfChecks.RDF + "' xmlns:m='" + EaPdfChecks.MAIL + "' xmlns:foaf='" + EaPdfChecks.FOAF + "'>"
        + "<rdf:li rdf:parseType='Resource'><m:Mail_GUID>uuid:source</m:Mail_GUID><m:Subject>TxtSampleEmail</m:Subject>"
        + "<m:messageid>&lt;6BA412B7BBD2FD42861293C22F31BF8F1AECAB77@pifmbx04.Leeds.gov.uk&gt;</m:messageid>"
        + "<m:Sent>2017-10-23T08:31:27+01:00</m:Sent><m:SizeInBytes>" + bytes.length + "</m:SizeInBytes>"
        + "<m:To><rdf:Seq><rdf:li rdf:parseType='Resource'><foaf:mbox rdf:resource='mailto:Chris.Wilson@leeds.gov.uk'/></rdf:li></rdf:Seq></m:To>"
        + "<m:From><rdf:Seq><rdf:li rdf:parseType='Resource'><foaf:mbox rdf:resource='mailto:Chris.Wilson@leeds.gov.uk'/></rdf:li></rdf:Seq></m:From>"
        + "</rdf:li></rdf:RDF>";
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    var email = (org.w3c.dom.Element) document.getDocumentElement().getFirstChild();
    ((org.w3c.dom.Element) email.getElementsByTagNameNS(EaPdfChecks.MAIL, property).item(0)).setTextContent(value);
    var spec = new COSDictionary();
    var file = new EaPdfChecks.Embedded("source.msg", spec, null, bytes, "source.msg", "application/vnd.ms-outlook",
        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes)), "Source");
    var report = new JavaValidation.Report("MSG comparison");
    new MetadataChecks(report, List.of(file), List.of(spec), List.of(email), List.of(), List.of(), "s").emails();
    return report;
  }

  @Test void nativeCorePreservesTheStoredSubmissionInstantAndDoesNotClaimHeaders() throws Exception {
    var core = OutlookSource.nativeCore(source("2019.10.08 DEP response.msg"));
    assertNotNull(core);
    assertEquals(java.time.Instant.parse("2019-10-08T14:07:48.3287079Z"), core.sent());
    assertEquals("RE: Application of Jeffry Spinney, 126 Golden Ridge Road, Alna", core.text().get("Subject"));
    assertEquals(68096, core.size());
    assertNull(OutlookSource.inspect(source("2019.10.08 DEP response.msg")));
    assertNull(OutlookSource.nativeCore(source("TxtSampleEmail.msg")));
    assertEquals(1, OutlookSource.nativeCore(source("test_internal.msg")).attachments().size());
  }

  @ParameterizedTest @CsvSource({"Subject,Wrong,MESSAGE_METADATA_MATCH_SOURCE", "SizeInBytes,1,MESSAGE_METADATA_MATCH_SOURCE",
      "Sent,2019-10-16T12:40:52Z,MESSAGE_METADATA_MATCH_SOURCE", "Sent,2019-10-08T14:07:48Z,MESSAGE_METADATA_MATCH_SOURCE", "NumberAttachments,1,MESSAGE_METADATA_MATCH_SOURCE"})
  void nativeCoreRejectsDefiniteContradictions(String property, String value, String code) throws Exception {
    byte[] bytes = source("2019.10.08 DEP response.msg");
    var core = OutlookSource.nativeCore(bytes); assertNotNull(core);
    String xml = "<rdf:RDF xmlns:rdf='" + EaPdfChecks.RDF + "' xmlns:m='" + EaPdfChecks.MAIL + "'>"
        + "<rdf:li rdf:parseType='Resource'><m:Mail_GUID>uuid:source</m:Mail_GUID><m:Subject>" + core.text().get("Subject") + "</m:Subject>"
        + "<m:messageid>&lt;BL0PR0901MB2404E6978B5DD7E702A36251849A0@BL0PR0901MB2404.namprd09.prod.outlook.com&gt;</m:messageid>"
        + "<m:Sent>" + core.sent() + "</m:Sent><m:SizeInBytes>" + bytes.length + "</m:SizeInBytes>"
        + "<m:NumberAttachments>0</m:NumberAttachments></rdf:li></rdf:RDF>";
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    var email = (org.w3c.dom.Element) document.getDocumentElement().getFirstChild();
    ((org.w3c.dom.Element) email.getElementsByTagNameNS(EaPdfChecks.MAIL, property).item(0)).setTextContent(value);
    var spec = new COSDictionary();
    var file = new EaPdfChecks.Embedded("source.msg", spec, null, bytes, "source.msg", "application/vnd.ms-outlook", "", "Source");
    var report = new JavaValidation.Report("native MSG comparison");
    new MetadataChecks(report, List.of(file), List.of(spec), List.of(email), List.of(), List.of(), "s").emails();
    assertTrue(report.items.asList().stream().map(item -> item.getAsJsonObject()).anyMatch(item ->
        code.equals(item.get("code").getAsString()) && "FAIL".equals(item.get("level").getAsString())), report.items.toString());
    assertTrue(report.items.asList().stream().map(item -> item.getAsJsonObject()).anyMatch(item ->
        "MESSAGE_SOURCE_COVERAGE".equals(item.get("code").getAsString()) && "PASS".equals(item.get("level").getAsString())));
  }

  @Test void redirectAnnotationsAreComparedUsingTheirStoredDestinations() {
    var destinations = PageContentChecks.annotationUriCandidates("https://webmail.example.org/redirect?URL=http%3a%2f%2fwww.example.org%2f");
    assertTrue(PageContentChecks.urlHasMatchingAnnotation("www.example.org", destinations, "www.example.org"));
    assertFalse(PageContentChecks.urlHasMatchingAnnotation("www.other.org", destinations, "www.other.org"));
    assertFalse(PageContentChecks.annotationUriCandidates("https://webmail.example.org/?ignored=http%3a%2f%2fwww.other.org%2f").contains("http://www.other.org"));
    assertTrue(PageContentChecks.urlHasMatchingAnnotation("https://example.org/path", java.util.Set.of("https://example.org/path/continued"),
        "https://example.org/path\n/continued\n"));
  }

  @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "wrong", "duplicate", "local-case"})
  void nativeRecipientContradictionsAreRejected(String change) throws Exception {
    var report = nativeComparison((email, attachment) -> {
      var to = (org.w3c.dom.Element) email.getElementsByTagNameNS(EaPdfChecks.MAIL, "To").item(0);
      var seq = (org.w3c.dom.Element) to.getFirstChild();
      var agent = (org.w3c.dom.Element) to.getElementsByTagNameNS(EaPdfChecks.FOAF, "Agent").item(0);
      if ("missing".equals(change)) email.removeChild(to);
      else if ("duplicate".equals(change)) seq.appendChild(seq.getFirstChild().cloneNode(true));
      else {
        String mailbox = agent.getAttributeNS(EaPdfChecks.FOAF, "mbox");
        if ("local-case".equals(change)) {
          int at = mailbox.lastIndexOf('@');
          mailbox = mailbox.substring(0, at).toUpperCase(java.util.Locale.ROOT) + mailbox.substring(at);
        } else mailbox = "mailto:wrong@example.org";
        agent.setAttributeNS(EaPdfChecks.FOAF, "foaf:mbox", mailbox);
      }
    });
    assertFailure(report, "missing".equals(change) ? "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED" : "MESSAGE_METADATA_MATCH_SOURCE");
  }

  @ParameterizedTest @CsvSource({"CheckSum,00000000000000000000000000000000", "SizeInBytes,1", "Filename,wrong.bin"})
  void originalAttachmentFactsDoNotPretendToValidateAbsentStoredBytes(String property, String value) throws Exception {
    var report = nativeComparison((email, attachment) ->
        ((org.w3c.dom.Element) attachment.getElementsByTagNameNS(EaPdfChecks.MAIL, property).item(0)).setTextContent(value));
    if ("Filename".equals(property)) assertFailure(report, "ATTACHMENT_XMP_FILENAME_MATCH_SOURCE");
    else assertFalse(report.items.asList().stream().anyMatch(item -> java.util.Set.of("ATTACHMENT_XMP_SIZE_MATCH", "ATTACHMENT_XMP_CHECKSUM_MATCH").contains(item.getAsJsonObject().get("code").getAsString())
        && "FAIL".equals(item.getAsJsonObject().get("level").getAsString())), "Only original metadata was compared; no stored native bytes were supplied to this helper.");
  }

  @Test void matchingNativeAddressesAndAttachmentsDoNotPretendHeadersWereAvailable() throws Exception {
    var report = nativeComparison((email, attachment) -> {
      var to = (org.w3c.dom.Element) email.getElementsByTagNameNS(EaPdfChecks.MAIL, "To").item(0);
      var agent = (org.w3c.dom.Element) to.getElementsByTagNameNS(EaPdfChecks.FOAF, "Agent").item(0);
      String mailbox = agent.getAttributeNS(EaPdfChecks.FOAF, "mbox");
      int at = mailbox.lastIndexOf('@');
      agent.setAttributeNS(EaPdfChecks.FOAF, "foaf:mbox", mailbox.substring(0, at) + mailbox.substring(at).toUpperCase(java.util.Locale.ROOT));
    });
    assertTrue(report.items.asList().stream().noneMatch(item -> "FAIL".equals(item.getAsJsonObject().get("level").getAsString())), report.items.toString());
    assertFalse(report.items.asList().stream().anyMatch(item -> "ATTACHMENT_XMP_CONTENTTYPE_MATCH".equals(item.getAsJsonObject().get("code").getAsString())
        && "WARN".equals(item.getAsJsonObject().get("level").getAsString())), report.items.toString());
    assertTrue(report.items.asList().stream().anyMatch(item -> "MESSAGE_SOURCE_COVERAGE".equals(item.getAsJsonObject().get("code").getAsString())
        && "PASS".equals(item.getAsJsonObject().get("level").getAsString())));
  }

  @Test void unknownRecipientTypeAndDifferentRepresentedAuthorRemainUnknown() throws Exception {
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(source("message.msg")))) {
      var directory = (org.apache.poi.poifs.filesystem.DirectoryNode) storage.getRoot().getEntry("__recip_version1.0_#00000000");
      byte[] bytes;
      try (var input = directory.createDocumentInputStream("__properties_version1.0")) { bytes = input.readAllBytes(); }
      var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      for (int index = 8; index + 16 <= bytes.length; index += 16) if (buffer.getInt(index) == 0x0c150003) buffer.putInt(index + 8, 99);
      directory.getEntry("__properties_version1.0").delete();
      directory.createDocument("__properties_version1.0", new ByteArrayInputStream(bytes));
      var output = new java.io.ByteArrayOutputStream(); storage.writeFilesystem(output);
      var core = OutlookSource.nativeCore(output.toByteArray()); assertNotNull(core);
      assertFalse(core.addresses().containsKey("To")); assertFalse(core.addresses().containsKey("Cc")); assertFalse(core.addresses().containsKey("Bcc"));
      assertTrue(core.addresses().containsKey("From"));
    }
    assertFalse(OutlookSource.nativeCore(source("2019.10.08 DEP response.msg")).addresses().containsKey("From"));
  }

  @Test void emptyRawTextCannotReplaceKnownNativeMailboxes() throws Exception {
    assertFailure(nativeComparison((email, attachment) -> nativeField(email, "Raw-To", "")), "MESSAGE_METADATA_MATCH_SOURCE");
  }

  @Test void nestedMessagesHaveContentEvidenceAndCidIsNotAlwaysInline() throws Exception {
    var nested = OutlookSource.nativeCore(source("EmailWithInnerMailAndAttachments.MSG"));
    assertNotNull(nested); assertNotNull(nested.attachments());
    assertTrue(nested.attachments().stream().anyMatch(a -> !a.nestedContent().isEmpty()));
    var ordinary = OutlookSource.inspect(source("2019.10.07 Weary comments.msg"));
    assertNotNull(ordinary); assertNotNull(ordinary.attachments());
    assertFalse(ordinary.attachments().isEmpty());
  }

  private static void assertFailure(JavaValidation.Report report, String code) {
    assertTrue(report.items.asList().stream().anyMatch(item -> code.equals(item.getAsJsonObject().get("code").getAsString())
        && "FAIL".equals(item.getAsJsonObject().get("level").getAsString())), report.items.toString());
  }

  private JavaValidation.Report nativeComparison(java.util.function.BiConsumer<org.w3c.dom.Element, org.w3c.dom.Element> change) throws Exception {
    byte[] bytes = source("message.msg"); var core = OutlookSource.nativeCore(bytes); assertNotNull(core);
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().newDocument();
    var email = document.createElementNS(EaPdfChecks.RDF, "rdf:li"); document.appendChild(email);
    var values = new java.util.HashMap<>(core.text());
    values.put("Mail_GUID", "uuid:source"); values.put("Sent", core.sent().toString());
    values.put("SizeInBytes", String.valueOf(core.size())); values.put("NumberAttachments", String.valueOf(core.attachments().size()));
    for (var entry : values.entrySet()) nativeField(email, entry.getKey(), entry.getValue());
    if (!core.reply().isEmpty()) {
      var property = nativeField(email, "In-Reply-To", "");
      var bag = document.createElementNS(EaPdfChecks.RDF, "rdf:Bag"); property.appendChild(bag);
      var item = document.createElementNS(EaPdfChecks.RDF, "rdf:li"); item.setTextContent(core.reply()); bag.appendChild(item);
    }
    for (var entry : core.addresses().entrySet()) {
      var property = nativeField(email, entry.getKey(), "");
      var seq = document.createElementNS(EaPdfChecks.RDF, "rdf:Seq"); property.appendChild(seq);
      for (String address : entry.getValue()) {
        var item = document.createElementNS(EaPdfChecks.RDF, "rdf:li"); seq.appendChild(item);
        var agent = document.createElementNS(EaPdfChecks.FOAF, "foaf:Agent"); item.appendChild(agent);
        agent.setAttributeNS(EaPdfChecks.FOAF, "foaf:mbox", "mailto:" + address);
      }
    }
    var attachment = document.createElementNS(EaPdfChecks.RDF, "rdf:li");
    var original = core.attachments().getFirst();
    nativeField(attachment, "Filename", original.names().getFirst()); nativeField(attachment, "SizeInBytes", String.valueOf(original.size()));
    nativeField(attachment, "Content-Type", original.contentType()); nativeField(attachment, "CheckSum", original.md5());
    nativeField(attachment, "Mail_GUID", "uuid:source");
    change.accept(email, attachment);
    var spec = new COSDictionary();
    var file = new EaPdfChecks.Embedded("source.msg", spec, null, bytes, "source.msg", "application/vnd.ms-outlook", "", "Source");
    var report = new JavaValidation.Report("native MSG address and attachment comparison");
    new MetadataChecks(report, List.of(file), List.of(spec), List.of(email), List.of(), List.of(attachment), "s").emails();
    return report;
  }

  private static org.w3c.dom.Element nativeField(org.w3c.dom.Element parent, String field, String value) {
    var element = parent.getOwnerDocument().createElementNS(EaPdfChecks.MAIL, "m:" + field);
    element.setTextContent(value); parent.appendChild(element); return element;
  }

}
