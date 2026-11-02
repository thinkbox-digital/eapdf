package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleAlignmentTests {
  private static Path samplePath(String profile) {
    String kind = profile.equals("m") || profile.equals("mi") ? "pst" : "eml";
    return Path.of("..", "examples", kind, (kind.equals("pst") ? "pst_quality-attached-message" : "eml_Agenda_and_timeline_slides" + (profile.equals("c") || profile.equals("ci") ? "_archive" : "")) + "_1" + profile + ".pdf");
  }

  @ParameterizedTest @ValueSource(strings = {"m", "mi"})
  void ordinaryArchivesCompleteOwnershipWithDefaultLimits(String profile) {
    var result = JavaValidation.validate("m".equals(profile) ? Path.of("..", "tests/quality/fixtures/pst_source_counts_legacy.pdf") : samplePath(profile));
    for (String code : java.util.List.of("CONTENT_SET_MAIL_CONTENTSET_TAGGED", "MAIL_CONTENTSET_ART_ONLY",
        "MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT", "MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT")) {
      var rows = result.getAsJsonArray("items").asList().stream().map(item -> item.getAsJsonObject())
          .filter(item -> code.equals(item.get("code").getAsString())).toList();
      assertFalse(rows.isEmpty(), code);
      assertTrue(rows.stream().allMatch(item -> "PASS".equals(item.get("level").getAsString())), code);
    }
    if ("m".equals(profile)) {
      // This frozen example claims NumberMessages for transport-header companions.
      // Their source-set membership does not prove how many emails each rendered.
      assertEquals("INCOMPLETE", result.getAsJsonObject("summary").get("status").getAsString());
      var incomplete = result.getAsJsonArray("items").asList().stream().map(item -> item.getAsJsonObject())
          .filter(item -> "VALIDATION_INCOMPLETE".equals(item.get("code").getAsString()) && "WARN".equals(item.get("level").getAsString())).toList();
      assertFalse(incomplete.isEmpty());
      assertTrue(incomplete.stream().allMatch(item -> "ASSET_NUMBERMESSAGES_MATCH_RENDERED".equals(item.getAsJsonObject("details").get("rule").getAsString())));
      var counts = result.getAsJsonArray("items").asList().stream().map(item -> item.getAsJsonObject())
          .filter(item -> "ASSET_NUMBERMESSAGES_MATCH_RENDERED".equals(item.get("code").getAsString()) && "WARN".equals(item.get("level").getAsString())).toList();
      assertTrue(counts.stream().allMatch(item -> item.getAsJsonObject("details").get("file").getAsString().startsWith("transport-headers_")));
    }
  }

  @ParameterizedTest @ValueSource(strings = {"s", "si", "m", "mi", "c", "ci"})
  void attachmentContainerIsRequiredInEveryProfile(String profile) throws Exception {
    Path sample = samplePath(profile).toAbsolutePath().normalize();
    try (var document = Loader.loadPDF(sample.toFile())) {
      var metadata = document.getDocumentCatalog().getMetadata();
      String original = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
      String pattern = "(?s)<pdfmailmeta:attachments>.*?</pdfmailmeta:attachments>";
      assertTrue(original.matches("(?s).*" + pattern + ".*"));
      for (String replacement : new String[] {"", "<pdfmailmeta:attachments><rdf:Bag/></pdfmailmeta:attachments>",
          "<pdfmailmeta:attachments><rdf:Seq/></pdfmailmeta:attachments>", "<pdfmailmeta:attachments>not an array</pdfmailmeta:attachments>"}) {
        metadata.importXMPMetadata(original.replaceFirst(pattern, replacement).getBytes(StandardCharsets.UTF_8));
        var report = new JavaValidation.Report(sample.toString());
        report.pdfa = new com.google.gson.JsonObject();
        new EaPdfChecks(document, report, new JavaValidation.Children(), 0).run(Path.of(report.path));
        var rows = report.items.asList().stream().map(value -> value.getAsJsonObject())
            .filter(row -> "ATTACHMENT_XMP_CONTAINER".equals(row.get("code").getAsString())).toList();
        assertFalse(rows.isEmpty());
        boolean invalid = rows.stream().anyMatch(row -> "FAIL".equals(row.get("level").getAsString()));
        assertEquals(!replacement.contains("rdf:Bag"), invalid);
      }
    }
  }

  @ParameterizedTest @ValueSource(strings = {"text/plain", "broken original type", "", "uri", "array", "missing"})
  void attachmentContentTypeRetainsFreeTextWithoutAcceptingWrongXmpTypes(String value) throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().newDocument();
    var record = xml.createElementNS(EaPdfChecks.RDF, "rdf:li");
    if (!"missing".equals(value)) {
      var field = xml.createElementNS(EaPdfChecks.MAIL, "pdfmailmeta:Content-Type"); record.appendChild(field);
      if ("uri".equals(value)) field.setAttributeNS(EaPdfChecks.RDF, "rdf:resource", "text/plain");
      else if ("array".equals(value)) field.appendChild(xml.createElementNS(EaPdfChecks.RDF, "rdf:Bag"));
      else field.setTextContent(value);
    }
    var report = new JavaValidation.Report("attachment-type.pdf");
    new MetadataChecks(report, java.util.List.of(), java.util.List.of(), java.util.List.of(),
        java.util.List.of(), java.util.List.of(record), "si").assets();
    var finding = report.items.asList().stream().map(item -> item.getAsJsonObject())
        .filter(item -> "ATTACHMENT_XMP_CONTENTTYPE".equals(item.get("code").getAsString())).findFirst().orElseThrow();
    assertEquals(java.util.Set.of("text/plain", "broken original type").contains(value) ? "PASS" : "FAIL", finding.get("level").getAsString());
  }
}
