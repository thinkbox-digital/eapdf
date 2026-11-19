package eapdf;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import eapdf.ValidatorGui.Result;
import eapdf.ValidatorGui.Finding;

/** Single report reader shared by UI compatibility entry points. */
final class ValidationReportReader {
  private ValidationReportReader() {}
    static Result parse(String json) throws IOException {
      try {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject summary = root.getAsJsonObject("summary");
        JsonObject pdfa = root.getAsJsonObject("pdfa");
        List<Finding> findings = new ArrayList<>();
        for (JsonElement value : root.getAsJsonArray("items")) findings.add(finding(value.getAsJsonObject()));
        return checked(summary, pdfa, findings, json);
      } catch (RuntimeException error) {
        throw new IOException("The validator returned an unreadable report: " + error.getMessage(), error);
      }
    }

    static Result parse(Path report) throws IOException {
      ReportFindings findings = new ReportFindings(report);
      try (var reader = new com.google.gson.stream.JsonReader(Files.newBufferedReader(report))) {
        reader.setStrictness(com.google.gson.Strictness.STRICT);
        JsonObject summary = null, pdfa = null;
        boolean items = false;
        reader.beginObject();
        while (reader.hasNext()) switch (reader.nextName()) {
          case "summary" -> { if (summary != null) throw new IOException("Duplicate report summary"); summary = JsonParser.parseReader(reader).getAsJsonObject(); }
          case "pdfa" -> { if (pdfa != null) throw new IOException("Duplicate PDF/A result"); pdfa = JsonParser.parseReader(reader).getAsJsonObject(); }
          case "items" -> {
            if (items) throw new IOException("Duplicate findings"); items = true;
            reader.beginArray();
            while (reader.hasNext()) findings.append(JsonParser.parseReader(reader).getAsJsonObject());
            reader.endArray();
          }
          default -> reader.skipValue();
        }
        reader.endObject();
        if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) throw new IOException("Trailing report data");
        return checked(summary, pdfa, findings, "");
      } catch (IOException | RuntimeException error) {
        findings.close();
        throw new IOException("The validator returned an unreadable report: " + error.getMessage(), error);
      }
    }

    static Finding finding(JsonObject item) {
      String level = required(item, "level");
      if (!Set.of("PASS", "FAIL", "WARN", "N/A").contains(level)) throw new IllegalArgumentException("Unknown finding level: " + level);
      JsonElement details = item.get("details");
      String code = required(item, "code");
      return new Finding(level, code, required(item, "message"),
          details == null || details.isJsonNull() ? "" : JavaValidation.JSON.toJson(displayDetails(code, level, details)));
    }

    private static JsonElement displayDetails(String code, String level, JsonElement details) {
      if (!"COLLECTION_CHILD_VALIDATION".equals(code) || !details.isJsonObject()) return details;
      JsonObject source = details.getAsJsonObject();
      if (!(source.get("result") instanceof JsonObject result)) return details;
      JsonObject compact = new JsonObject(), child = new JsonObject();
      source.entrySet().forEach(entry -> {
        if (!"result".equals(entry.getKey())) compact.add(entry.getKey(), entry.getValue());
      });
      if (result.get("summary") instanceof JsonObject summary) {
        JsonObject selected = new JsonObject();
        for (String key : List.of("status", "verification_spec", "pdfa_status", "fail", "warn"))
          if (summary.has(key)) selected.add(key, summary.get(key));
        child.add("summary", selected);
      }
      JsonArray relevant = new JsonArray();
      if (result.get("items") instanceof JsonArray items) for (JsonElement value : items) {
        if (!(value instanceof JsonObject item)) continue;
        String itemLevel = optional(item, "level");
        if (!("FAIL".equals(itemLevel) || "WARN".equals(level) && "WARN".equals(itemLevel))) continue;
        JsonObject selected = new JsonObject();
        item.entrySet().forEach(entry -> selected.add(entry.getKey(), entry.getValue()));
        if (item.has("details")) selected.add("details", displayDetails(optional(item, "code"), itemLevel, item.get("details")));
        relevant.add(selected);
      }
      child.add("items", relevant);
      compact.add("result", child);
      return compact;
    }

    private static Result checked(JsonObject summary, JsonObject pdfa, List<Finding> findings, String json) {
        String status = required(summary, "status");
        if (!Set.of("PASS", "FAIL", "INCOMPLETE").contains(status)) throw new IllegalArgumentException("Unknown validation status: " + status);
        String pdfaStatus = required(pdfa, "status");
        if (!Set.of("PASS", "FAIL", "ERROR", "SKIP", "NOT_RUN").contains(pdfaStatus)) throw new IllegalArgumentException("Unknown PDF/A status: " + pdfaStatus);
        if (findings.isEmpty()) throw new IllegalArgumentException("The report has no findings");
        // A broken or truncated backend report must never appear as a successful check.
        if ("PASS".equals(status) && (!"PASS".equals(pdfaStatus)
            || !pdfa.get("ran").getAsBoolean() || !pdfa.get("compliant").getAsBoolean()
            || !pdfa.get("profile_allowed").getAsBoolean()
            || (findings instanceof ReportFindings stored ? stored.contradictsPass() : findings.stream().anyMatch(f -> "FAIL".equals(f.level())
                || ("WARN".equals(f.level()) && "VALIDATION_INCOMPLETE".equals(f.code())))))) {
          throw new IllegalArgumentException("The PASS result contradicts the report");
        }
        return new Result(status, pdfaStatus, optional(summary, "verification_spec"),
            required(summary, "validation_scope"), findings, json);
    }

    private static String required(JsonObject object, String key) {
      JsonElement value = object.get(key);
      if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("Missing report field: " + key);
      }
      return value.getAsString();
    }

    private static String optional(JsonObject object, String key) {
      return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }
}
