package eapdf;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaValidationTests {
  @Test void upstreamApplicabilityIsRecordedWithoutALocalIsoRule() throws Exception {
    assertFalse(JavaValidation.RULES.has("ISO32005_UPSTREAM"));
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      var report = new JavaValidation.Report("no-structure.pdf");
      new LogicalStructureChecks(document, report, java.util.List.of(), "s", null).run();
      var upstream = report.items.asList().stream().map(value -> value.getAsJsonObject())
          .filter(item -> "ISO32005_UPSTREAM".equals(item.get("code").getAsString())).toList();
      assertEquals(1, upstream.size());
      assertEquals("N/A", upstream.getFirst().get("level").getAsString());
      assertFalse(upstream.getFirst().has("rule_number"));
    }
  }
  private static final Path SAMPLE = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf").toAbsolutePath().normalize();
  @TempDir Path temporary;

  @Test void emptyStructureElementsMayOmitKidsButInvalidKidsStillFail() throws Exception {
    Path saved = temporary.resolve("empty-cell.pdf");
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var parent = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)
          document.getDocumentCatalog().getStructureTreeRoot().getKids().getFirst();
      var empty = new org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement("P", parent);
      parent.appendKid(empty);
      document.save(saved.toFile());
    }
    try (var document = Loader.loadPDF(saved.toFile())) {
      var report = new JavaValidation.Report(saved.toString());
      new PageContentChecks(document, report).run();
      assertTrue(report.items.asList().stream().map(v -> v.getAsJsonObject()).anyMatch(item ->
          "STRUCTURE_CORRESPONDENCE".equals(item.get("code").getAsString()) && "PASS".equals(item.get("level").getAsString())));
      var parent = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)
          document.getDocumentCatalog().getStructureTreeRoot().getKids().getFirst();
      var empty = (org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement)parent.getKids().getLast();
      empty.getCOSObject().setString(COSName.K, "invalid child");
      report = new JavaValidation.Report(saved.toString());
      new PageContentChecks(document, report).run();
      assertTrue(report.items.asList().stream().map(v -> v.getAsJsonObject()).anyMatch(item ->
          "STRUCTURE_CORRESPONDENCE".equals(item.get("code").getAsString()) && "FAIL".equals(item.get("level").getAsString())));
    }
  }

  @Test void creatorSourceProblemPreventsAnOtherwisePassingFileFromPassing() throws Exception {
    Path marked = temporary.resolve("source-incomplete.pdf");
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      String xml = new String(document.getDocumentCatalog().getMetadata().toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
      xml = xml.replace("</rdf:RDF>", "<rdf:Description rdf:about='' xmlns:c='https://thinkbox.digital/ns/eapdf/conversion/1.0/'>"
          + "<c:status>INCOMPLETE</c:status><c:problems><rdf:Seq><rdf:li>Missing source attachment</rdf:li></rdf:Seq></c:problems>"
          + "</rdf:Description></rdf:RDF>");
      document.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      document.save(marked.toFile());
    }
    JsonObject report = JavaValidation.validate(marked);
    assertEquals("PASS", report.getAsJsonObject("pdfa").get("status").getAsString());
    assertEquals("INCOMPLETE", report.getAsJsonObject("summary").get("status").getAsString());
    assertTrue(has(report, "VALIDATION_INCOMPLETE", "WARN"));
  }

  @Test void nativeEnginesCheckThePublicSampleWithoutChangingIt() throws Exception {
    byte[] before = Files.readAllBytes(SAMPLE);
    JsonObject report = JavaValidation.validate(SAMPLE);
    assertEquals("PASS", report.getAsJsonObject("pdfa").get("status").getAsString());
    assertTrue(has(report, "ISO32005_UPSTREAM", "PASS"));
    assertFalse(has(report, "VALIDATION_ERROR", "WARN"));
    assertEquals(0, report.getAsJsonObject("summary").get("fail").getAsInt());
    // The fully compared ordinary sample can PASS; unavailable required evidence still prevents PASS.
    assertEquals("PASS", report.getAsJsonObject("summary").get("status").getAsString());
    assertTrue(has(report, "VALIDATION_INCOMPLETE", "N/A"));
    for (var expected : java.util.Map.of(
        "EAPDF_VERSION", "XMP integer 1", "EAPDF_REV", "XMP integer 2024",
        "EAPDF_CONFORMANCE_VALUE", "s, si, m, mi, c or ci",
        "XMP_PRODUCER", "non-empty XMP text string", "XMP_CREATEDATE", "2000-01-01T12:00:00Z").entrySet()) {
      var item = report.getAsJsonArray("items").asList().stream().map(value -> value.getAsJsonObject())
          .filter(value -> expected.getKey().equals(value.get("code").getAsString())).findFirst().orElseThrow();
      assertTrue(item.get("message").getAsString().contains(expected.getValue()), expected.getKey());
      assertTrue(item.getAsJsonObject("details").has("value"));
    }
    assertArrayEquals(before, Files.readAllBytes(SAMPLE));
  }

  @ParameterizedTest @ValueSource(strings = {"orphan", "stale-next-key"})
  void rejectsDisconnectedParentTreeEntriesWithoutChangingThePdf(String mutation) throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(Path.of("..", "tests", "fixtures", "eapdf")), "Optional local regression corpus is not installed");
    Path fixture = Path.of("..", "tests", "fixtures", "eapdf", "validator-table11", "valid.pdf");
    byte[] before = Files.readAllBytes(fixture);
    try (var document = Loader.loadPDF(fixture.toFile())) {
      var control = new JavaValidation.Report(fixture.toString());
      new PageContentChecks(document, control).run();
      assertTrue(control.items.asList().stream().map(value -> value.getAsJsonObject()).anyMatch(item ->
          "STRUCTURE_CORRESPONDENCE".equals(item.get("code").getAsString()) && "PASS".equals(item.get("level").getAsString())));
      var structure = document.getDocumentCatalog().getStructureTreeRoot().getCOSObject();
      var tree = structure.getCOSDictionary(COSName.PARENT_TREE);
      var numbers = tree.getCOSArray(COSName.NUMS);
      assertNotNull(numbers);
      long maximum = 0;
      for (int index = 0; index < numbers.size(); index += 2) maximum = Math.max(maximum, ((org.apache.pdfbox.cos.COSInteger) numbers.getObject(index)).longValue());
      if ("orphan".equals(mutation)) {
        numbers.add(org.apache.pdfbox.cos.COSInteger.get(maximum + 1000)); numbers.add(numbers.getObject(1));
        tree.removeItem(COSName.LIMITS); structure.setLong(COSName.PARENT_TREE_NEXT_KEY, maximum + 1001);
      } else structure.setLong(COSName.PARENT_TREE_NEXT_KEY, maximum);
      var report = new JavaValidation.Report(fixture.toString());
      new PageContentChecks(document, report).run();
      assertTrue(report.items.asList().stream().map(value -> value.getAsJsonObject()).anyMatch(item ->
          "STRUCTURE_CORRESPONDENCE".equals(item.get("code").getAsString()) && "FAIL".equals(item.get("level").getAsString())));
    }
    assertArrayEquals(before, Files.readAllBytes(fixture));
  }

  @Test void invalidIdentificationStillReportsTheConcreteRequiredValue() {
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(Path.of("..", "tests", "fixtures", "eapdf")), "Optional local regression corpus is not installed");
    for (var control : java.util.Map.of("version-float.pdf", "EAPDF_VERSION", "revision-array.pdf", "EAPDF_REV").entrySet()) {
      var report = JavaValidation.validate(Path.of("..", "tests", "fixtures", "eapdf", "validator-identification", control.getKey()));
      var failure = report.getAsJsonArray("items").asList().stream().map(value -> value.getAsJsonObject())
          .filter(item -> control.getValue().equals(item.get("code").getAsString()) && "FAIL".equals(item.get("level").getAsString())).findFirst().orElseThrow();
      String required = "EAPDF_REV".equals(control.getValue()) ? "XMP integer 2024" : "XMP integer 1";
      assertTrue(failure.get("message").getAsString().contains(required));
      assertTrue(failure.getAsJsonObject("details").has("value"));
    }
  }

  @Test void catchesActualEaPdfViolationsInAPdfBoxMutatedDocument() throws Exception {
    Path broken = temporary.resolve("broken.pdf");
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("PieceInfo"), new COSDictionary());
      document.getDocumentCatalog().getViewerPreferences().setDisplayDocTitle(false);
      document.save(broken.toFile());
    }
    JsonObject report = JavaValidation.validate(broken);
    assertEquals("FAIL", report.getAsJsonObject("summary").get("status").getAsString());
    assertTrue(has(report, "CATALOG_PIECEINFO", "FAIL"));
    assertTrue(has(report, "DISPLAY_DOCTITLE", "FAIL"));
  }

  @Test void invalidPdfCannotBecomePassOrACompletedCheck() throws Exception {
    Path invalid = temporary.resolve("not-a-pdf.pdf");
    Files.writeString(invalid, "This is not a PDF");
    JsonObject report = JavaValidation.validate(invalid);
    assertEquals("FAIL", report.getAsJsonObject("summary").get("status").getAsString());
    assertTrue(has(report, "OPEN", "FAIL"));
  }

  @Test void bundledRulesMatchTheReviewedCatalogue() throws Exception {
    byte[] rules = Files.readAllBytes(Path.of("..", "validator_rule_text.json"));
    assertArrayEquals(rules, JavaValidation.resource("validator_rule_text.json",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rules))));
    assertEquals(229, JavaValidation.RULES.size());
    byte[] profile = JavaValidation.resource("config/verapdf/ISO-32005.xml", "a2181a59dcaef27d20cc3078aa8b63e63fe528908a69f985a0b56b714c62d7a1");
    assertArrayEquals(Files.readAllBytes(Path.of("..", "config", "verapdf", "ISO-32005.xml")), profile);
  }

  @ParameterizedTest @ValueSource(strings = {"3a", "3u", "4", "4e", "4f"})
  void bundledEngineChecksEveryAllowedFlavour(String flavour) {
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(Path.of("..", "tests", "fixtures", "eapdf")), "Optional local regression corpus is not installed");
    Path controls = Path.of("..", "tests", "fixtures", "eapdf", "upstream-profile-controls");
    JsonObject valid = JavaValidation.pdfa(controls.resolve(flavour + "-valid.pdf"), null);
    assertEquals("PASS", valid.get("status").getAsString(), valid.toString());
    assertEquals(flavour, valid.get("flavour").getAsString());
    JsonObject invalid = JavaValidation.pdfa(controls.resolve(flavour + "-launch.pdf"), null);
    assertEquals("FAIL", invalid.get("status").getAsString(), invalid.toString());
    assertTrue(invalid.get("failed_rules").getAsInt() > 0);
  }

  static boolean has(JsonObject report, String code, String level) {
    return report.getAsJsonArray("items").asList().stream().map(value -> value.getAsJsonObject())
        .anyMatch(item -> code.equals(item.get("code").getAsString()) && level.equals(item.get("level").getAsString()));
  }
}
