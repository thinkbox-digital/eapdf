package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversionDiagnosticTests {
  @TempDir Path directory;

  @Test void equalWarningsKeepDistinctMessagesAndRefreshFinalIdentity() throws Exception {
    try (var job = Common.beginJob()) {
      var first = new EmailRecord(); first.sourcePath = "mailbox.pst"; first.subject = "Same subject";
      var second = new EmailRecord(); second.sourcePath = first.sourcePath; second.subject = first.subject;
      Common.warn(first, ConversionDiagnostic.Code.TEXT_RECOVERED, "Recovered text");
      Common.warn(second, ConversionDiagnostic.Code.TEXT_RECOVERED, "Recovered text");
      first.messageId = "<first@example.org>"; first.mailGuid = "first-guid";
      second.messageId = "<second@example.org>"; second.mailGuid = "second-guid";
      Common.recordWarnings(first); Common.recordWarnings(first); Common.recordWarnings(second);
      assertEquals(2, job.diagnostics.size());
      assertEquals(java.util.Set.of(first.messageId, second.messageId), job.diagnostics.values().stream()
          .map(ConversionDiagnostic::messageId).collect(java.util.stream.Collectors.toSet()));
      assertEquals(1, first.conversionWarnings.size());
    }
  }

  @Test void diagnosticsSurviveWorkerPublicationHandoff() throws Exception {
    Path work = directory.resolve("workspace"); Files.createDirectories(work);
    var supervisor = ConversionProgress.start("eapdf.EmailToPDFA",
        new String[]{"--out", directory.resolve("out").toString(), "input.eml"}, work);
    try {
      ConversionProgress.attach(work);
      try (var job = Common.beginJob()) {
        var record = new EmailRecord(); record.sourcePath = "input.eml"; record.messageId = "<id@example.org>";
        Common.warn(record, ConversionDiagnostic.Code.DATE_PRECISION_REDUCED, "Native date lost .446 seconds");
      }
      ConversionProgress.detach(); ConversionProgress.attach(work); supervisor.finish("COMPLETED");
      var json = JsonParser.parseString(Files.readString(directory.resolve("out/progress.json"))).getAsJsonObject();
      var entry = json.getAsJsonArray("conversion_diagnostics").get(0).getAsJsonObject();
      assertEquals("DATE_PRECISION_REDUCED", entry.get("code").getAsString());
      assertEquals("<id@example.org>", entry.get("message_id").getAsString());
      assertTrue(entry.get("effect").getAsString().contains("fractional seconds are lost"));
      assertFalse(entry.get("suggested_action").getAsString().isBlank());
      assertEquals(1, json.getAsJsonObject("conversion_warnings").size());
    } finally { ConversionProgress.detach(); }
  }

  @Test void malformedTextHasTypedRecoveryWithoutLosingOriginalBytes() throws Exception {
    var record = TextDecodeWarningTests.parse("text/plain; charset=UTF-8", new byte[]{'a', (byte)0xff});
    assertEquals(ConversionDiagnostic.Code.TEXT_RECOVERED, record.diagnosticCodes.get(record.conversionWarnings.get(0)));
  }

  @Test void sourceProblemsRemainExplicitAndDoNotClaimPreservation() {
    var record = new EmailRecord(); Common.sourceProblem(record, "Missing attachment bytes");
    assertEquals(ConversionDiagnostic.Code.SOURCE_INCOMPLETE, record.diagnosticCodes.get(record.conversionWarnings.get(0)));
    assertEquals(java.util.List.of("Missing attachment bytes"), record.sourceProblems);
  }

  @Test void structuredEvidenceCannotBypassMetadataBudget() {
    String before = System.getProperty("eapdf.maxMetadataBytes");
    try (var job = Common.beginJob()) {
      System.setProperty("eapdf.maxMetadataBytes", "1024");
      var record = new EmailRecord(); record.subject = "x".repeat(2000);
      assertThrows(Common.UserFacingException.class, () -> Common.warn(record, "Review needed"));
      assertTrue(job.diagnostics.isEmpty());
    } finally { if (before == null) System.clearProperty("eapdf.maxMetadataBytes"); else System.setProperty("eapdf.maxMetadataBytes", before); }
  }
}
