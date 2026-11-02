package eapdf;

import static eapdf.EaPdfChecks.*;
import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.w3c.dom.Element;

/** Compares independently connected logical content with DPart email ancestry. */
final class ContentOwnership {
  record Evidence(Map<COSDictionary, Set<String>> owners, Set<COSDictionary> documentWide) {
    static Evidence empty() { return new Evidence(Map.of(), Set.of()); }
  }
  record Result(Set<String> renderedGuids, boolean complete) { }
  private static final String CODE = "DPM_EMAIL_CONTENT_SET_OWNERSHIP";
  private static final Set<String> EMAIL_CONTENT = Set.of("BodyRendering", "AttachmentRendering", "EmailHeaderRendering", "RawEmailRendering");

  static String ancestorGuid(COSDictionary part) {
    Set<COSDictionary> visited = identitySet();
    String owner = null;
    for (COSDictionary node = dict(part, "Parent"); node != null; node = dict(node, "Parent")) {
      if (!visited.add(node) || visited.size() > 256) return null;
      COSBase value = get(dict(node, "DPM"), "Mail_GUID");
      if (value == null) continue;
      String guid = decodedString(value);
      if (guid == null || guid.isEmpty() || owner != null && !owner.equals(guid)) return null;
      owner = guid;
    }
    return owner;
  }

  private static boolean hasAncestorIdentity(COSDictionary part) {
    Set<COSDictionary> seen = identitySet();
    for (COSDictionary node = dict(part, "Parent"); node != null; node = dict(node, "Parent")) {
      if (!seen.add(node) || seen.size() > 256) return true;
      COSDictionary dpm = dict(node, "DPM");
      if (get(dpm, "Mail_GUID") != null || get(dpm, "Mail_MessageID") != null) return true;
    }
    return false;
  }

  static Result assess(PDDocument document, List<Element> emails, String profile,
                       JavaValidation.Report report, Evidence evidence) throws IOException {
    if (!Set.of("s", "si", "m", "mi").contains(profile)) return new Result(Set.of(), true);
    Set<String> represented = new HashSet<>(), declared = new HashSet<>();
    boolean complete = true, unresolvedAncestry = false;
    var pageIndex = new AttachmentChecks.PageIndex(document);
    var leaves = AttachmentChecks.leaves(document);
    for (COSDictionary part : leaves) {
      if (evidence.documentWide().contains(part)) continue;
      String owner = ancestorGuid(part);
      boolean unknownAncestor = owner == null && hasAncestorIdentity(part);
      unresolvedAncestry |= unknownAncestor;
      Set<String> independent = evidence.owners().getOrDefault(part, Set.of());
      String type = name(get(dict(part, "DPM"), "Mail_ContentSetType"));
      COSBase leafIdentity = get(dict(part, "DPM"), "Mail_GUID");
      boolean validRange = !pageIndex.range(part).isEmpty();
      var details = new LinkedHashMap<String, Object>();
      details.put("assertion", "DPM-EMAIL-OWNERSHIP"); details.put("content_set_type", type);
      details.put("dpm_owner", owner == null ? "unavailable" : owner); details.put("independent_owners", independent);
      if (owner != null && validRange) declared.add(owner);
      boolean contradictory = owner == null && !unknownAncestor && EMAIL_CONTENT.contains(type)
          || owner != null && leafIdentity instanceof COSString && decodedString(leafIdentity) != null
             && !owner.equals(decodedString(leafIdentity))
          || owner != null && independent.size() == 1 && !independent.contains(owner);
      if (contradictory) {
        complete = false;
        report.check(CODE, false, "The content set's independently established owner or leaf identity contradicts its email DPart ancestry.", details);
      } else if (!validRange || owner == null || independent.size() != 1) {
        complete = false;
        report.unavailable(CODE, "Content-set ownership cannot be proved from DPart identifiers alone.", details);
      } else {
        represented.add(owner);
        report.check(CODE, true, "The content set's independently connected email GUID agrees with its DPart ancestor.", details);
      }
    }
    for (Element email : emails) {
      String guid = field(email, "Mail_GUID");
      if (!simple(fieldElement(email, "Mail_GUID")) || guid.isEmpty()) { complete = false; continue; }
      if (!declared.contains(guid)) {
        complete = false;
        if (unresolvedAncestry) report.unavailable(CODE, "Unreadable or ambiguous DPart ancestry prevents establishing this email's content-set coverage.",
            Map.of("assertion", "DPM-EMAIL-OWNERSHIP", "Mail_GUID", guid));
        else report.check(CODE, false, "An email XMP record has no reachable content set under its email DPart node.",
            Map.of("assertion", "DPM-EMAIL-OWNERSHIP", "Mail_GUID", guid));
      }
    }
    if (leaves.isEmpty()) {
      complete = false;
      report.unavailable(CODE, "No reachable content sets establish email ownership.", Map.of("assertion", "DPM-EMAIL-OWNERSHIP"));
    }
    return new Result(Set.copyOf(represented), complete);
  }
}
