package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import static eapdf.EaPdfChecks.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.junit.jupiter.api.Test;

class ReexaminationTests {
  @org.junit.jupiter.api.BeforeEach void requireLocalCorpus() {
    org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(Path.of("../tests/fixtures/eapdf/validator-contentset-parent/orphan-front-noncollection.pdf")), "Optional local regression corpus is not installed");
  }
  private static final Path FIXTURE = Path.of("..", "tests", "fixtures", "eapdf",
      "validator-contentset-parent", "orphan-front-noncollection.pdf");

  @Test void globalContentRequiresIndependentOwnershipEvidence() throws Exception {
    for (String variant : new String[] {"global", "email-type", "email-identity", "disconnected", "wrong-parent"}) {
      try (var document = Loader.loadPDF(FIXTURE.toFile())) {
        COSDictionary root = dict(dict(document.getDocumentCatalog().getCOSObject(), "StructTreeRoot"), "K");
        COSDictionary front = (COSDictionary) ((COSArray) get(root, "K")).getObject(0);
        assertEquals("Mail_ContentSet", name(get(front, "S")));
        COSDictionary metadata = dict(dict(dict(front, "Pg"), "DPart"), "DPM");
        if ("email-type".equals(variant)) metadata.setName("Mail_ContentSetType", "BodyRendering");
        if ("email-identity".equals(variant)) metadata.setString("Mail_GUID", "email-owner");
        if ("disconnected".equals(variant)) front.setInt(COSName.K, 999999);
        if ("wrong-parent".equals(variant)) dict(dict(front, "Pg"), "DPart").setItem(COSName.getPDFName("Parent"),
            dict(document.getDocumentCatalog().getCOSObject(), "DPartRoot"));
        var report = new JavaValidation.Report(FIXTURE.toString()); report.pdfa = new com.google.gson.JsonObject();
        new EaPdfChecks(document, report, new JavaValidation.Children(), 0).run(FIXTURE);
        var levels = report.items.asList().stream().map(v -> v.getAsJsonObject())
            .filter(row -> "MAIL_CONTENTSET_NESTED_UNDER_MESSAGE".equals(row.get("code").getAsString()))
            .map(row -> row.get("level").getAsString()).collect(java.util.stream.Collectors.toSet());
        String expected = "global".equals(variant) ? "PASS" : "disconnected".equals(variant) ? "WARN" : "FAIL";
        assertTrue(levels.contains(expected), variant + ": " + levels);
        if (!"PASS".equals(expected)) assertFalse(levels.equals(Set.of("PASS")), variant);
      }
    }
  }

  @Test void collectionExitModeDependsOnPdfVersionAndRemainsAName() throws Exception {
    Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_archive_1ci.pdf");
    for (int part : new int[] {3, 4}) for (String supplied : new String[] {"UseOutlines", "UseAttachments", "text"}) {
      try (var document = Loader.loadPDF(sample.toFile())) {
        document.setVersion(part == 3 ? 1.7f : 2.0f);
        var xmp = document.getDocumentCatalog().getMetadata();
        String xml = new String(xmp.toByteArray(), StandardCharsets.UTF_8)
            .replace("<pdfaid:part>4</pdfaid:part>", "<pdfaid:part>" + part + "</pdfaid:part>");
        xmp.importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        COSDictionary prefs = dict(document.getDocumentCatalog().getCOSObject(), "ViewerPreferences");
        if ("text".equals(supplied)) prefs.setString("NonFullScreenPageMode", part == 3 ? "UseOutlines" : "UseAttachments");
        else prefs.setName("NonFullScreenPageMode", supplied);
        var report = new JavaValidation.Report(sample.toString()); report.pdfa = new com.google.gson.JsonObject();
        new EaPdfChecks(document, report, new JavaValidation.Children(), 0).run(sample);
        var row = report.items.asList().stream().map(v -> v.getAsJsonObject())
            .filter(v -> "PROFILE_NFSM_CONTAINER".equals(v.get("code").getAsString())).findFirst().orElseThrow();
        assertEquals((part == 3 ? "UseOutlines" : "UseAttachments").equals(supplied) ? "PASS" : "FAIL", row.get("level").getAsString());
      }
    }
  }
}
