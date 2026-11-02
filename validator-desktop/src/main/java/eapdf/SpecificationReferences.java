package eapdf;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Bundled, reviewed clause mappings and text extracted from the pinned specification. */
final class SpecificationReferences {
  private static final JsonObject DATA = load();

  private static JsonObject load() {
    try (var stream = SpecificationReferences.class.getResourceAsStream("/validator/specification-references.json")) {
      if (stream == null) throw new IllegalStateException("Bundled specification references are missing");
      return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception error) {
      throw new ExceptionInInitializerError(error);
    }
  }

  static String text(String code) {
    JsonObject rules = DATA.getAsJsonObject("rules");
    if (!rules.has(code)) return "No reviewed specification reference is available for " + code + ".";
    JsonObject rule = rules.getAsJsonObject(code);
    JsonObject runtime = JavaValidation.RULES.has(code) ? JavaValidation.RULES.getAsJsonObject(code) : null;
    StringBuilder text = new StringBuilder(DATA.get("title").getAsString()).append("\n\n")
        .append(JavaValidation.ruleNumber(code) == null ? code : "Rule " + JavaValidation.ruleNumber(code) + " — " + code).append("\n\n")
        .append("Reviewed summary (paraphrase):\n").append(runtime == null ? rule.get("summary").getAsString()
            : runtime.get("spec_excerpt").getAsString()).append("\n\n");
    if (runtime != null) {
      JsonObject scope = runtime.getAsJsonObject("validation_scope");
      if (scope != null && scope.has("prerequisite") && "verapdf_pdfa_pass".equals(scope.get("prerequisite").getAsString()))
        text.append("Prerequisite: the finished file passes veraPDF for an EA-PDF-permitted PDF/A profile. This rule checks additional EA-PDF conditions.\n\n");
      if (scope != null && scope.has("check")) {
        JsonObject check = scope.getAsJsonObject("check");
        text.append("This rule checks:\n").append(check.get("assertion").getAsString()).append("\n")
            .append("Strength: ").append(check.get("strength").getAsString()).append("\n")
            .append("Applies when: ").append(check.get("applies_when").getAsString()).append("\n")
            .append("Evidence needed: ").append(check.get("evidence_needed").getAsString()).append("\n\n")
            .append("Linked source context (may include duties reported by other rules):\n\n");
      }
      JsonObject review = runtime.getAsJsonObject("requirements_review");
      if (review != null && review.has("assertions")) for (JsonElement value : review.getAsJsonArray("assertions")) {
        JsonObject assertion = value.getAsJsonObject();
        text.append("Assertion ").append(assertion.get("id").getAsString()).append("\n")
            .append("Strength: ").append(assertion.get("strength").getAsString()).append("\n")
            .append("Scope: ").append(assertion.get("scope").getAsString()).append("\n")
            .append("Applies when: ").append(assertion.get("applies_when").getAsString()).append("\n")
            .append("Requirement (paraphrase): ").append(assertion.get("assertion").getAsString()).append("\n")
            .append("Evidence needed: ").append(assertion.get("evidence_needed").getAsString()).append("\n")
            .append("Authority: ").append(assertion.get("authority").getAsString()).append("\n");
        if (assertion.has("local_interpretation")) text.append("Project interpretation, not published errata: ")
            .append(assertion.get("local_interpretation").getAsString()).append("\n");
        text.append("\n");
      }
    }
    Set<Integer> pages = new LinkedHashSet<>();
    for (JsonElement value : rule.getAsJsonArray("requirements")) {
      JsonObject requirement = value.getAsJsonObject();
      String section = requirement.get("section").getAsString();
      if ("audit-policy".equals(section)) {
        text.append("Validation policy — no direct specification section or page.\n");
      } else {
        text.append("Section ").append(section);
        if (!requirement.get("table").isJsonNull()) text.append("; Table ").append(requirement.get("table").getAsString());
        text.append("; printed page(s) ").append(numbers(requirement, "printed_pages"))
            .append("; PDF page(s) ").append(numbers(requirement, "pdf_pages")).append("\n");
      }
      text.append(requirement.get("assertion").getAsString()).append("\n\n");
      requirement.getAsJsonArray("pdf_pages").forEach(page -> pages.add(page.getAsInt()));
    }
    if (!pages.isEmpty()) text.append(DATA.get("extraction_note").getAsString()).append("\n");
    for (int physical : pages) {
      JsonObject page = DATA.getAsJsonObject("pages").getAsJsonObject(Integer.toString(physical));
      text.append("\n── Printed page ").append(page.get("printed_page").getAsInt())
          .append(" / PDF page ").append(physical).append(" ──\n\n")
          .append(page.get("text").getAsString()).append("\n");
    }
    text.append("\nSource PDF SHA-256: ").append(DATA.get("source_sha256").getAsString());
    return text.toString();
  }

  private static String numbers(JsonObject value, String name) {
    return String.join(", ", value.getAsJsonArray(name).asList().stream().map(JsonElement::getAsString).toList());
  }
}
