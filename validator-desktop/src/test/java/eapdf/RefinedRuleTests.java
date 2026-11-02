package eapdf;

import static eapdf.EaPdfChecks.*;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.*;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

/** Small mutations of the real EML example isolate additional EA-PDF assertions. */
class RefinedRuleTests {
  private static final Path SAMPLE = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf");

  private static JavaValidation.Report inspect(PDDocument document) throws Exception {
    var report = new JavaValidation.Report(SAMPLE.toString());
    report.pdfa = new JsonObject();
    new EaPdfChecks(document, report, new JavaValidation.Children(), 0).run(SAMPLE);
    return report;
  }
  private static List<JsonObject> rows(JavaValidation.Report report, String code) {
    return report.items.asList().stream().map(value -> value.getAsJsonObject())
        .filter(value -> code.equals(value.get("code").getAsString())).toList();
  }
  private static boolean level(List<JsonObject> rows, String level) {
    return rows.stream().anyMatch(row -> level.equals(row.get("level").getAsString()));
  }
  private static org.w3c.dom.Document metadata(PDDocument document) throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    return factory.newDocumentBuilder().parse(new ByteArrayInputStream(document.getDocumentCatalog().getMetadata().toByteArray()));
  }
  private static void metadata(PDDocument document, org.w3c.dom.Document xml) throws Exception {
    var bytes = new ByteArrayOutputStream();
    TransformerFactory.newInstance().newTransformer().transform(new DOMSource(xml), new StreamResult(bytes));
    document.getDocumentCatalog().getMetadata().importXMPMetadata(bytes.toByteArray());
  }
  private static Element firstRecord(org.w3c.dom.Document xml, String property) {
    return children(children((Element) xml.getElementsByTagNameNS(MAIL, property).item(0)).getFirst()).getFirst();
  }
  private static COSDictionary nativeSpec(PDDocument document) throws Exception {
    for (var page : document.getPages()) for (var annotation : page.getAnnotations()) {
      COSDictionary spec = dict(annotation.getCOSObject(), "FS");
      if (spec != null && !Set.of("Source", "Alternative").contains(name(get(spec, "AFRelationship")))) return spec;
    }
    throw new AssertionError("The real example must contain its native attachment");
  }
  private static COSStream stream(COSDictionary spec) {
    COSDictionary ef = dict(spec, "EF"); return (COSStream) (get(ef, "UF") == null ? get(ef, "F") : get(ef, "UF"));
  }

  @ParameterizedTest @ValueSource(strings = {"To", "From", "Subject", "Message-ID"})
  void omittedNonemptyOriginalFieldsFailTheirSpecificCheck(String property) throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); Element email = firstRecord(xml, "email");
      Element value = fieldElement(email, "Message-ID".equals(property) ? "messageid" : property);
      assertNotNull(value, property); value.getParentNode().removeChild(value); metadata(document, xml);
      String code = "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED";
      assertTrue(level(rows(inspect(document), code), "FAIL"), code);
    }
  }

  @Test void matchingMetadataAndStreamOmissionsDoNotHideOriginalParameters() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); Element attachment = firstRecord(xml, "attachments");
      String original = field(attachment, "Content-Type"); assertTrue(original.contains(";"));
      fieldElement(attachment, "Content-Type").setTextContent(original.substring(0, original.indexOf(';')));
      metadata(document, xml); stream(nativeSpec(document)).removeItem(COSName.getPDFName("Mail_MediaTypeParameters"));
      var failures = rows(inspect(document), "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED");
      assertTrue(failures.stream().anyMatch(row -> "FAIL".equals(row.get("level").getAsString())
          && row.getAsJsonObject("details").has("expected_parameters")));
    }
  }

  @Test void parameterTypeRemainsMandatoryWhenItsTextLooksPlausible() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      stream(nativeSpec(document)).setItem(COSName.getPDFName("Mail_MediaTypeParameters"), COSInteger.ONE);
      assertTrue(level(rows(inspect(document), "EMBEDDED_MEDIATYPE_PARAMETERS_TYPE"), "FAIL"));
    }
  }

  @Test void readableOriginalWithoutMimeDeclarationDoesNotInventAParameterRequirement() throws Exception {
    try (var document = Loader.loadPDF(Path.of("..", "examples", "msg", "msg_TxtSampleEmailWithAttachment_1s.pdf").toFile())) {
      var findings = rows(inspect(document), "SOURCE_MEDIATYPE_PARAMETERS_MATCH");
      assertFalse(level(findings, "FAIL")); assertFalse(level(findings, "WARN"));
      assertTrue(findings.stream().anyMatch(row -> "N/A".equals(row.get("level").getAsString())
          && row.get("message").getAsString().contains("no stored media-type declaration")));
    }
  }

  @Test void omittedRecommendedChecksumAndSizeAreWarningsRatherThanFailures() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var params = dict(stream(nativeSpec(document)), "Params");
      params.removeItem(COSName.getPDFName("CheckSum")); params.removeItem(COSName.SIZE);
      var result = inspect(document);
      assertTrue(level(rows(result, "EMBEDDED_MD5_RECOMMENDED"), "WARN"));
      assertTrue(level(rows(result, "EMBEDDED_SIZE_RECOMMENDED"), "WARN"));
      assertFalse(level(rows(result, "EMBEDDED_MD5_BINARY_FORMAT"), "FAIL"));
    }
  }

  @ParameterizedTest @ValueSource(ints = {1, 2})
  void suppliedAssetMessageCountChecksRenderedRecordsNotJustIntegerSyntax(int count) throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); Element asset = firstRecord(xml, "assets");
      var number = xml.createElementNS(MAIL, "pdfmailmeta:NumberMessages"); number.setTextContent(Integer.toString(count)); asset.appendChild(number);
      metadata(document, xml);
      var report = inspect(document);
      assertTrue(level(rows(report, "ASSET_NUMBERMESSAGES_TYPE"), "PASS"), "The supplied count has valid integer syntax");
      var results = rows(report, "ASSET_NUMBERMESSAGES_MATCH_RENDERED");
      var compared = results.stream().filter(row -> row.get("details").isJsonObject()
          && row.getAsJsonObject("details").has("check")
          && "rendered_message_count".equals(row.getAsJsonObject("details").get("check").getAsString())).toList();
      assertEquals(1, compared.size()); assertEquals(count == 1 ? "PASS" : "FAIL", compared.getFirst().get("level").getAsString());
      assertEquals(1, compared.getFirst().getAsJsonObject("details").get("rendered_messages").getAsInt());
    }
  }

  @Test void globallyMatchingGuidCannotProveUntaggedContentOwnership() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); Element email = firstRecord(xml, "email");
      var report = new JavaValidation.Report("ownership");
      var result = ContentOwnership.assess(document, List.of(email), "s", report, ContentOwnership.Evidence.empty());
      assertFalse(result.complete()); assertTrue(level(rows(report, "DPM_EMAIL_CONTENT_SET_OWNERSHIP"), "WARN"));
      assertFalse(level(rows(report, "DPM_EMAIL_CONTENT_SET_OWNERSHIP"), "PASS"));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"associated", "unassociated", "wrong"})
  void attachmentRenderingRequiresIndependentSourceBytes(String variant) throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      String original = field(firstRecord(metadata(document), "attachments"), "Content-Type");
      COSDictionary part = AttachmentChecks.leaves(document).stream()
          .filter(leaf -> "BodyRendering".equals(name(get(dict(leaf, "DPM"), "Mail_ContentSetType"))))
          .findFirst().orElseThrow();
      dict(part, "DPM").setName("Mail_ContentSetType", "AttachmentRendering");
      dict(part, "DPM").setName("Mail_Subtype", "wrong".equals(variant) ? "image/png" : original);
      if (!"unassociated".equals(variant)) {
        COSDictionary spec = nativeSpec(document); spec.setName("AFRelationship", "Source");
        COSArray association = new COSArray(); association.add(spec); part.setItem(COSName.getPDFName("AF"), association);
      }
      var results = rows(inspect(document), "DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE").stream()
          .filter(row -> row.get("details").isJsonObject() && row.getAsJsonObject("details").has("check")
              && "attachment_source_media_type".equals(row.getAsJsonObject("details").get("check").getAsString())).toList();
      assertEquals(1, results.size());
      assertEquals("associated".equals(variant) ? "PASS" : "wrong".equals(variant) ? "FAIL" : "WARN", results.getFirst().get("level").getAsString());
    }
  }

  @Test void unreadableContentSetTypeCannotProveAttachmentRenderingIsAbsent() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      COSDictionary part = AttachmentChecks.leaves(document).stream()
          .filter(leaf -> "BodyRendering".equals(name(get(dict(leaf, "DPM"), "Mail_ContentSetType"))))
          .findFirst().orElseThrow();
      dict(part, "DPM").removeItem(COSName.getPDFName("Mail_ContentSetType"));
      var findings = rows(inspect(document), "DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE").stream()
          .filter(row -> row.get("details").isJsonObject()
              && "attachment_source_media_type".equals(JavaValidation.text(row.getAsJsonObject("details"), "check"))).toList();
      assertTrue(level(findings, "WARN")); assertFalse(level(findings, "N/A"));
    }
  }

  @Test void attachmentMustRemainEntirelyInsideTheCropBox() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      boolean changed = false;
      for (var page : document.getPages()) for (var annotation : page.getAnnotations()) if (annotation instanceof PDAnnotationFileAttachment) {
        var rectangle = annotation.getRectangle(); rectangle.setUpperRightX(page.getCropBox().getUpperRightX() + 1);
        annotation.setRectangle(rectangle);
        assertTrue(annotation.getRectangle().getUpperRightX() > page.getCropBox().getUpperRightX()); changed = true;
      }
      assertTrue(changed); var report = inspect(document);
      assertTrue(level(rows(report, "ATTACHMENT_ANNOTATIONS_PRESENT"), "PASS"));
      assertFalse(level(rows(report, "ATTACHMENT_ANNOTATIONS_PRESENT"), "FAIL"));
      assertTrue(level(rows(report, "ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX"), "FAIL"));
    }
  }

  @Test void specificationShowsCurrentAssertionsAndFindingShowsAnOccurrenceResult() {
    String specification = SpecificationReferences.text("SOURCE_MEDIATYPE_PARAMETERS_MATCH");
    assertTrue(specification.contains("This rule checks:"));
    assertTrue(specification.contains("Stored Mail_MediaTypeParameters"));
    assertTrue(specification.contains("Strength: SHALL")); assertTrue(specification.contains("Evidence needed:"));
    assertTrue(specification.contains("Prerequisite:")); assertTrue(specification.contains("Required" ) || specification.contains("preserve"));
    var group = ValidatorGui.groupFindings(List.of(new ValidatorGui.Finding("PASS", "ASSET_NUMBERMESSAGES_TYPE", "Integer syntax was checked.", "declared=2"))).getFirst();
    assertTrue(group.detailText().contains("Other occurrences of this rule may have separate results"));
    assertTrue(group.detailText().contains("Integer syntax was checked.")); assertTrue(group.detailText().contains("declared=2"));
  }

  @Test void sourceValueFailureDoesNotInvalidateItsTextTypeOrSourceCoverage() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); fieldElement(firstRecord(xml, "email"), "Subject").setTextContent("Wrong subject"); metadata(document, xml);
      var report = inspect(document);
      assertTrue(level(rows(report, "MESSAGE_SOURCE_COVERAGE"), "PASS"));
      assertTrue(level(rows(report, "MESSAGE_SUBJECT_XMP"), "PASS"));
      assertFalse(level(rows(report, "MESSAGE_SUBJECT_XMP"), "FAIL"));
      assertTrue(rows(report, "MESSAGE_METADATA_MATCH_SOURCE").stream().anyMatch(row -> "FAIL".equals(row.get("level").getAsString())
          && "Subject".equals(JavaValidation.text(row.getAsJsonObject("details"), "field"))));
    }
  }

  @Test void emailArrayShapeAndProfileCardinalityHaveIndependentResults() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); var email = firstRecord(xml, "email"); email.getParentNode().appendChild(email.cloneNode(true)); metadata(document, xml);
      var report = inspect(document);
      assertTrue(level(rows(report, "EMAIL_XMP_CONTAINER"), "PASS"));
      assertFalse(level(rows(report, "EMAIL_XMP_CONTAINER"), "FAIL"));
      assertTrue(level(rows(report, "EMAIL_XMP_CARDINALITY"), "FAIL"));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"absent", "0"})
  void attachmentMetadataDoesNotEstablishAnOriginalCount(String value) throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); var email = firstRecord(xml, "email");
      var count = fieldElement(email, "NumberAttachments"); assertNotNull(count);
      if ("absent".equals(value)) email.removeChild(count); else count.setTextContent(value);
      var report = new JavaValidation.Report("isolated-count.pdf");
      new MetadataChecks(report, List.of(), List.of(), List.of(email), List.of(),
          List.of(firstRecord(xml, "attachments")), "si").emails();
      assertFalse(level(rows(report, "MESSAGE_ATTACHMENT_COUNT_XMP"), "FAIL"));
      assertFalse(rows(report, "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED").stream().anyMatch(row ->
          "FAIL".equals(JavaValidation.text(row, "level"))
          && "NumberAttachments".equals(JavaValidation.text(row.getAsJsonObject("details"), "field"))));
      if ("0".equals(value)) assertTrue(level(rows(report, "MESSAGE_ATTACHMENT_COUNT_XMP"), "PASS"));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"absent", "0"})
  void originalAttachmentCountRemainsRequiredWithoutAttachmentMetadata(String value) throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); var email = firstRecord(xml, "email");
      var count = fieldElement(email, "NumberAttachments"); assertNotNull(count);
      if ("absent".equals(value)) email.removeChild(count); else count.setTextContent(value);
      var attachment = firstRecord(xml, "attachments"); attachment.getParentNode().removeChild(attachment);
      metadata(document, xml); var report = inspect(document);
      assertFalse(level(rows(report, "MESSAGE_ATTACHMENT_COUNT_XMP"), "FAIL"));
      String code = "absent".equals(value) ? "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED" : "MESSAGE_METADATA_MATCH_SOURCE";
      assertTrue(rows(report, code).stream().anyMatch(row -> "FAIL".equals(JavaValidation.text(row, "level"))
          && "NumberAttachments".equals(JavaValidation.text(row.getAsJsonObject("details"), "field"))));
    }
  }

  @Test void matchingXmpAndPdfParametersCannotProveOriginalParameterFidelity() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); var attachment = firstRecord(xml, "attachments");
      String original = field(attachment, "Content-Type"); String changed = original.substring(0, original.indexOf(';')) + ";name=changed.bin";
      fieldElement(attachment, "Content-Type").setTextContent(changed); metadata(document, xml);
      stream(nativeSpec(document)).setString("Mail_MediaTypeParameters", "name=changed.bin");
      var report = inspect(document);
      assertTrue(level(rows(report, "EMBEDDED_MEDIATYPE_PARAMETERS_TYPE"), "PASS"));
      assertTrue(level(rows(report, "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED"), "PASS"));
      assertFalse(level(rows(report, "EMBEDDED_MEDIA_TYPE_MATCH_XMP"), "FAIL"));
      assertTrue(level(rows(report, "SOURCE_MEDIATYPE_PARAMETERS_MATCH"), "FAIL"));
    }
  }

  @Test void unavailableRecommendationIsAdvisoryAndReportIdentifiesItsCatalogue() {
    var report = new JavaValidation.Report("recommendation"); report.pdfa = new JsonObject(); report.pdfa.addProperty("status", "PASS");
    report.unavailable("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED", "The optional original-byte recommendation cannot be established.");
    assertFalse(level(rows(report, "VALIDATION_INCOMPLETE"), "WARN"));
    assertEquals("PASS", report.summary().get("status").getAsString());
    assertEquals(JavaValidation.RULEBOOK_SHA256, report.summary().get("rulebook_sha256").getAsString());
  }

  @Test void storedByteAgreementAndOriginalBytePreservationAreDifferentOutcomes() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      byte[] changed = "Different retained attachment bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] digest = java.security.MessageDigest.getInstance("MD5").digest(changed);
      var payload = stream(nativeSpec(document));
      try (var output = payload.createOutputStream(COSName.FLATE_DECODE)) { output.write(changed); }
      dict(payload, "Params").setLong("Size", changed.length);
      dict(payload, "Params").setItem("CheckSum", new COSString(digest));
      var xml = metadata(document); var attachment = firstRecord(xml, "attachments");
      fieldElement(attachment, "SizeInBytes").setTextContent(Integer.toString(changed.length));
      fieldElement(attachment, "CheckSum").setTextContent(java.util.HexFormat.of().formatHex(digest)); metadata(document, xml);
      var report = inspect(document);
      assertTrue(level(rows(report, "ATTACHMENT_XMP_SIZE_MATCH"), "PASS"));
      assertTrue(level(rows(report, "ATTACHMENT_XMP_CHECKSUM_MATCH"), "PASS"));
      assertTrue(level(rows(report, "ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED"), "WARN"));
      assertFalse(level(rows(report, "ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED"), "FAIL"));
      assertFalse(rows(report, "VALIDATION_INCOMPLETE").stream().anyMatch(row -> row.get("details").isJsonObject()
          && "ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED".equals(JavaValidation.text(row.getAsJsonObject("details"), "rule"))));
    }
  }

  @Test void failedChildInspectionDoesNotTurnPartialCountsIntoExactZero() throws Exception {
    Path collection = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_archive_1c.pdf");
    try (var document = Loader.loadPDF(collection.toFile())) {
      COSArray entries = (COSArray) get(dict(dict(document.getDocumentCatalog().getCOSObject(), "Names"), "EmbeddedFiles"), "Names");
      COSDictionary member = null;
      for (int i = 1; i < entries.size(); i += 2) {
        var candidate = (COSDictionary) entries.getObject(i);
        if ("application/pdf".equals(name(get(stream(candidate), "Subtype")))) member = candidate;
      }
      assertNotNull(member);
      byte[] bytes; try (var input = stream(member).createInputStream()) { bytes = input.readAllBytes(); }
      var changed = new ByteArrayOutputStream();
      try (var child = Loader.loadPDF(bytes)) {
        child.getDocumentCatalog().getMetadata().importXMPMetadata("<broken>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        child.save(changed);
      }
      try (var output = stream(member).createOutputStream(COSName.FLATE_DECODE)) { output.write(changed.toByteArray()); }
      var params = dict(stream(member), "Params");
      params.setLong(COSName.SIZE, changed.size());
      params.setItem(COSName.getPDFName("CheckSum"), new COSString(java.security.MessageDigest.getInstance("MD5").digest(changed.toByteArray())));
      var report = new JavaValidation.Report(collection.toString()); report.pdfa = new JsonObject();
      new EaPdfChecks(document, report, new JavaValidation.Children(), 0).run(collection);
      assertTrue(level(rows(report, "COLLECTION_CHILD_VALIDATION"), "PASS"));
      assertTrue(level(rows(report, "COLLECTION_CHILD_VALIDATION"), "WARN"));
      var counts = rows(report, "ASSET_NUMBERMESSAGES_MATCH_RENDERED");
      assertFalse(level(counts, "FAIL"));
      assertEquals(2, counts.stream().filter(row -> "WARN".equals(row.get("level").getAsString())).count());
    }
  }

  @Test void identicalSourceOccurrencesDoNotEachInheritTheCombinedChildCount() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var xml = metadata(document); Element asset = firstRecord(xml, "assets");
      Element number = xml.createElementNS(MAIL, "pdfmailmeta:NumberMessages"); number.setTextContent("1"); asset.appendChild(number);
      Element duplicate = (Element) asset.cloneNode(true); fieldElement(duplicate, "Filename").setTextContent("duplicate.eml");
      COSArray associations = (COSArray) get(document.getDocumentCatalog().getCOSObject(), "AF");
      COSDictionary source = null;
      for (COSBase value : associations) if (resolve(value) instanceof COSDictionary spec && "Source".equals(name(get(spec, "AFRelationship")))) source = spec;
      assertNotNull(source);
      byte[] bytes; try (var input = stream(source).createInputStream()) { bytes = input.readAllBytes(); }
      String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes));
      var second = new COSDictionary(source); second.setString("F", "duplicate.eml"); second.setString("UF", "duplicate.eml");
      var firstFile = new Embedded("", source, stream(source), bytes, field(asset, "Filename"), "message/rfc822", digest, "Source");
      var secondFile = new Embedded("", second, stream(source), bytes, "duplicate.eml", "message/rfc822", digest, "Source");
      var report = new JavaValidation.Report("duplicate-source-occurrences");
      var checks = new MetadataChecks(report, List.of(firstFile, secondFile), List.of(source, second), List.of(), List.of(asset, duplicate), List.of(), "c");
      checks.assets();
      JsonObject child = JavaValidation.validate(SAMPLE); assertEquals("PASS", child.getAsJsonObject("summary").get("status").getAsString());
      checks.childCounts(child);
      JsonObject another = child.deepCopy();
      for (var row : another.getAsJsonArray("items")) if ("MESSAGE_SOURCE_COVERAGE".equals(JavaValidation.text(row.getAsJsonObject(), "code"))
          && row.getAsJsonObject().get("details").isJsonObject())
        row.getAsJsonObject().getAsJsonObject("details").addProperty("Mail_GUID", "uuid:00000000-0000-4000-8000-000000000002");
      checks.childCounts(another); checks.finishChildren(true);
      checks.renderedCounts(new ContentOwnership.Result(Set.of(), true));
      var counts = rows(report, "ASSET_NUMBERMESSAGES_MATCH_RENDERED");
      assertFalse(level(counts, "FAIL"));
      assertEquals(2, counts.stream().filter(row -> "WARN".equals(row.get("level").getAsString())).count());
    }
  }
}
