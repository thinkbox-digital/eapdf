package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.swing.SwingUtilities;
import javax.swing.text.StyleConstants;
import org.junit.jupiter.api.Test;

class SpecificationReferencesTests {
  @Test void pieceInfoShowsActualSourceTextAndBothPageNumbers() {
    String text = SpecificationReferences.text("CATALOG_PIECEINFO");
    assertTrue(text.contains("Section 9.1; printed page(s) 62; PDF page(s) 69"));
    assertTrue(text.contains("All EA-PDF files SHALL NOT contain a PieceInfo entry in the document catalog dictionary."));
    assertTrue(text.contains("Reviewed summary (paraphrase)"));
  }

  @Test void aSplitTableShowsBothCitedPagesInPdfOrder() {
    var pages = SpecificationReferences.reference("MAIL_MESSAGE_CHILDREN").pages();
    assertEquals(List.of(82, 83), pages.stream().map(SpecificationReferences.Page::physical).toList());
  }

  @Test void selectingAnotherRuleKeepsSummaryBodyInRegularWeight() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      var pane = new SpecificationPane();
      try {
        pane.showRule("OPEN");
        pane.showRule("PROFILE_RULES_SKIPPED");

        assertTrue(isBoldAt(pane, "EA-PDF 1.0 Specification"));
        assertTrue(isBoldAt(pane, "Reviewed summary (paraphrase):"));
        assertFalse(isBoldAt(pane, "When relevant underlying feature or diagnostic condition: Profile-specific checks"));
      } finally {
        pane.showRule(null);
      }
    });
  }

  @Test void pageModeSummaryShowsRequirementsWithoutReviewMetadata() {
    String summary = SpecificationReferences.reference("PAGEMODE_PRESENT").summary();

    assertTrue(summary.lines().noneMatch(line ->
        line.startsWith("Assertion ") || line.startsWith("Scope:") || line.startsWith("Authority:")));
    assertTrue(summary.contains("Strength: SHALL"));
    assertTrue(summary.contains("Applies when: s or si without email attachments"));
    assertTrue(summary.contains("Requirement (paraphrase): Catalog PageMode and ViewerPreferences NonFullScreenPageMode are UseOutlines."));
    assertTrue(summary.contains("Evidence needed: PDF objects and XMP"));
  }

  private static boolean isBoldAt(SpecificationPane pane, String text) {
    int offset = pane.getText().indexOf(text);
    assertTrue(offset >= 0, text);
    return StyleConstants.isBold(pane.getStyledDocument().getCharacterElement(offset).getAttributes());
  }

  @Test void everyReviewedRuleHasAReferenceWithoutInventingPagesForPolicy() throws Exception {
    try (var stream = getClass().getResourceAsStream("/validator/validator_rule_text.json")) {
      var rules = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
      assertEquals(229, rules.size());
      for (String code : rules.keySet()) assertFalse(SpecificationReferences.text(code).startsWith("No reviewed"), code);
    }
    String open = SpecificationReferences.text("OPEN");
    assertTrue(open.contains("no direct specification section or page"));
    assertFalse(open.contains("── Printed page"));
    assertTrue(SpecificationReferences.text("PDFA_FIRST_PASS").contains("PDF/A-3a"));
    assertTrue(SpecificationReferences.text("UNKNOWN").startsWith("No reviewed"));
  }
}
