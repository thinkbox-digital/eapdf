package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleApplicabilityTests {
  @ParameterizedTest @ValueSource(strings = {"s", "si", "m", "mi", "c", "ci"})
  void unknownAttachmentPresenceDoesNotAbortApplicabilityOrBecomeFalse(String profile) throws Exception {
    try (var document = new PDDocument()) {
      var xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
      var root = xml.createElement("metadata"); xml.appendChild(root);
      var email = xml.createElement("email"); root.appendChild(email);
      var report = new JavaValidation.Report("unbound original"); report.profile = profile;
      var sources = List.of(new COSDictionary());
      var emails = List.of(email);
      var metadata = new MetadataChecks(report, List.of(), sources, emails, List.of(), List.of(), profile);
      assertNull(metadata.attachmentPresence(), "An unbound original cannot establish whether attachments exist");
      Set<String> modes = Set.of("PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS", "PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS",
          "PROFILE_NFSM_SINGLE_NO_ATTACHMENTS", "PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS");
      report.seen.addAll(JavaValidation.RULES.keySet()); report.seen.removeAll(modes);
      var applicability = new RuleApplicability(document, report, root, List.of(), sources,
          emails, List.of(), List.of(), metadata);
      assertDoesNotThrow(applicability::finish);
      var rows = report.items.asList().stream().map(value -> value.getAsJsonObject())
          .filter(row -> modes.contains(row.get("code").getAsString())).toList();
      assertEquals(4, rows.size());
      String expected = profile.startsWith("s") ? "WARN" : "N/A";
      assertTrue(rows.stream().allMatch(row -> expected.equals(row.get("level").getAsString())), rows.toString());
    }
  }
}
