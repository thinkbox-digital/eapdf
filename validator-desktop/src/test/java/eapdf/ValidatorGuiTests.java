package eapdf;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Report contract tests shared by the desktop and Java validation worker. */
class ValidatorGuiTests {
  @Test void groupsRepeatedFileResultsWithoutHidingFailuresOrFileDetails() {
    List<ValidatorGui.Finding> findings = new ArrayList<>();
    for (int i = 0; i < 500; i++) findings.add(new ValidatorGui.Finding("PASS", "EMBEDDED_STREAM", "Embedded stream.", "file-" + i));
    findings.add(new ValidatorGui.Finding("FAIL", "EMBEDDED_STREAM", "Embedded stream.", "missing-file"));
    findings.add(new ValidatorGui.Finding("PASS", "EMBEDDED_STREAM", "Another assertion.", "other-file"));
    findings.add(new ValidatorGui.Finding("PASS", "OPEN", "Embedded stream.", "separate-rule"));
    var groups = ValidatorGui.groupFindings(findings);
    assertEquals(4, groups.size());
    assertEquals(503, groups.stream().mapToInt(ValidatorGui.FindingGroup::count).sum());
    var repeated = groups.stream().filter(g -> g.count() == 500).findFirst().orElseThrow();
    assertTrue(repeated.detailText().contains("Occurrences: 500"));
    assertTrue(repeated.detailText().contains("file-0"));
    assertTrue(repeated.detailText().contains("file-499"));
    assertTrue(groups.stream().anyMatch(g -> "FAIL".equals(g.level()) && g.detailText().contains("missing-file")));
    assertThrows(UnsupportedOperationException.class, () -> repeated.occurrences().clear());
  }

  @Test void deduplicatesIdenticalDetailBlocksAndSortsGroupsInSpecificationOrder() {
    var same = new ValidatorGui.Finding("PASS", "EMBEDDED_STREAM", "Stream.", "same details");
    var groups = ValidatorGui.groupFindings(List.of(
        new ValidatorGui.Finding("PASS", "OPEN", "Open.", ""), same, same,
        new ValidatorGui.Finding("PASS", "PDFA_FIRST_PASS", "PDF/A.", ""),
        new ValidatorGui.Finding("PASS", "ISO32005_UPSTREAM", "ISO.", "")));
    assertEquals("PDFA_FIRST_PASS", groups.get(0).code());
    assertEquals("ISO32005_UPSTREAM", groups.get(1).code());
    assertEquals("EMBEDDED_STREAM", groups.get(2).code());
    assertEquals(2, groups.get(2).count());
    assertTrue(groups.get(2).detailText().contains("Details 1 (2 occurrences):"));
    assertEquals(groups.get(2).detailText().indexOf("same details"), groups.get(2).detailText().lastIndexOf("same details"));
  }
  @Test void ruleFilterUsesLiteralCaseInsensitiveKeywordsAndCanonicalNumbers() {
    assertTrue(ValidatorGui.matchesRule("ATTACHMENT_XMP_CHECKSUM_FORM", "attachment CheckSum"));
    assertTrue(ValidatorGui.matchesRule("OPEN", ""));
    assertFalse(ValidatorGui.matchesRule("ATTACHMENT_XMP_CHECKSUM_FORM", "attachment size"));
    assertFalse(ValidatorGui.matchesRule("OPEN", "["));
    assertEquals(JavaValidation.ruleNumber("ATTACHMENT_ANNOTATIONS_PRESENT"),
        new ValidatorGui.Finding("PASS", "ATTACHMENT_ANNOTATIONS_PRESENT", "", "").ruleNumber());
    assertNull(new ValidatorGui.Finding("PASS", "PDFA_FIRST_PASS", "", "").ruleNumber());
  }

  private static final String REPORT = """
      {"summary":{"status":"PASS","verification_spec":"1s",
       "validation_scope":"Automated checks, not independent EA-PDF certification"},
       "pdfa":{"status":"PASS","ran":true,"compliant":true,"profile_allowed":true},
       "items":[{"level":"PASS","code":"OPEN","message":"Opened PDF","details":{"pages":1}}]}
      """;

  @Test void preservesReportAndUntrustedTextAsPlainData() throws Exception {
    var root = JsonParser.parseString(REPORT).getAsJsonObject();
    root.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("message", "<html><img src='https://example.invalid/'>");
    var result = ValidatorGui.Result.parse(root.toString());
    assertEquals("PASS", result.status());
    assertEquals("1s", result.profile());
    assertEquals(root.toString(), result.json());
    assertTrue(result.findings().getFirst().message().startsWith("<html>"));
    assertTrue(result.findings().getFirst().details().contains("\"pages\": 1"));
  }

  @Test void rejectsPassWithMissingOrUnsuccessfulPdfa() {
    for (String replacement : new String[]{"\"status\":\"SKIP\"", "\"ran\":false", "\"compliant\":false", "\"profile_allowed\":false"}) {
      var root = JsonParser.parseString(REPORT).getAsJsonObject();
      var field = JsonParser.parseString("{" + replacement + "}").getAsJsonObject();
      field.entrySet().forEach(entry -> root.getAsJsonObject("pdfa").add(entry.getKey(), entry.getValue()));
      assertThrows(IOException.class, () -> ValidatorGui.Result.parse(root.toString()));
    }
    assertThrows(IOException.class, () -> ValidatorGui.Result.parse(REPORT.replace("\"ran\":true,", "")));
  }

  @Test void rejectsPassContradictingFindings() {
    assertThrows(IOException.class, () -> ValidatorGui.Result.parse(REPORT.replace("\"level\":\"PASS\"", "\"level\":\"FAIL\"")));
    assertThrows(IOException.class, () -> ValidatorGui.Result.parse(REPORT.replace("\"level\":\"PASS\"", "\"level\":\"WARN\"")
        .replace("\"code\":\"OPEN\"", "\"code\":\"VALIDATION_INCOMPLETE\"")));
  }

  @Test void retainsIncompleteAndFailureWithoutReinterpretingThem() throws Exception {
    for (String state : new String[]{"FAIL", "INCOMPLETE"}) {
      var root = JsonParser.parseString(REPORT).getAsJsonObject();
      root.getAsJsonObject("summary").addProperty("status", state);
      assertEquals(state, ValidatorGui.Result.parse(root.toString()).status());
    }
  }

  @Test void refusesTruncatedOrUnrecognizedReports() {
    for (String json : new String[]{"", "{}", "[]", REPORT.substring(0, REPORT.length() / 2),
        REPORT.replace("\"status\":\"PASS\"", "\"status\":\"SUCCESS\""),
        REPORT.replace("\"level\":\"PASS\"", "\"level\":\"UNKNOWN\"")}) {
      assertThrows(IOException.class, () -> ValidatorGui.Result.parse(json));
    }
  }
}
