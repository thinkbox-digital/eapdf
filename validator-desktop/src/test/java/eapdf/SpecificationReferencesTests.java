package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SpecificationReferencesTests {
  @Test void pieceInfoShowsActualSourceTextAndBothPageNumbers() {
    String text = SpecificationReferences.text("CATALOG_PIECEINFO");
    assertTrue(text.contains("Section 9.1; printed page(s) 62; PDF page(s) 69"));
    assertTrue(text.contains("All EA-PDF files SHALL NOT contain a PieceInfo entry in the document catalog dictionary."));
    assertTrue(text.contains("Reviewed summary (paraphrase)"));
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
