package eapdf;

import java.nio.file.*;
import com.google.gson.*;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.flavours.PDFAFlavour;

/** Explicit PDF/UA-2 machine validation with the project's existing veraPDF JAR. */
public final class PdfUa2Probe {
  public static void main(String[] args) throws Exception {
    JavaValidation.initialise();
    var foundry = Foundries.defaultInstance();
    JsonObject out = new JsonObject();
    try (var parser = foundry.createParser(Path.of(args[0]).toFile(), PDFAFlavour.PDFUA_2);
         var validator = foundry.createValidator(PDFAFlavour.PDFUA_2, 100, false, true, false)) {
      var result = validator.validate(parser);
      out.addProperty("profile", result.getProfileDetails().getName());
      out.addProperty("flavour", result.getPDFAFlavour().getId());
      out.addProperty("job_end_status", result.getJobEndStatus().getValue());
      out.addProperty("compliant", result.isCompliant());
      out.addProperty("checks", result.getTotalAssertions());
      out.addProperty("failed_rules", result.getFailedChecks().size());
      out.addProperty("scope", "PDF/UA-2 machine checks; human accessibility review remains required.");
      var findings = new JsonArray();
      for (var assertion : result.getTestAssertions()) {
        if (assertion.getStatus() == org.verapdf.pdfa.results.TestAssertion.Status.PASSED) continue;
        var item = new JsonObject();
        item.addProperty("specification", assertion.getRuleId().getSpecification().toString());
        item.addProperty("clause", assertion.getRuleId().getClause());
        item.addProperty("test", assertion.getRuleId().getTestNumber());
        item.addProperty("message", assertion.getMessage());
        item.addProperty("error", assertion.getErrorMessage());
        item.addProperty("context", assertion.getLocationContext());
        findings.add(item);
      }
      out.add("findings", findings);
    }
    Files.writeString(Path.of(args[1]), JavaValidation.JSON.toJson(out)+"\n");
  }
}
