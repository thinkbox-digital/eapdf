package eapdf;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.gf.model.impl.containers.StaticContainers;
import org.verapdf.pdfa.flavours.PDFAFlavour;
import static org.junit.jupiter.api.Assertions.*;

class ValidationResourceTests {
    @TempDir Path temporary;
    @Test void indexedBodyMatchesPreserveConflictingAndDuplicateIdentities() {
        var identities = new ArrayList<AttachmentChecks.Identity>();
        for (String guid : List.of("", "a", "b")) for (String id : List.of("", "<x@EXAMPLE.org>", "<x@example.org>", "y@example.org"))
            identities.add(new AttachmentChecks.Identity(guid, id));
        var bodies = new ArrayList<AttachmentChecks.Body>();
        for (int repeat = 0; repeat < 2; repeat++) for (var id : identities)
            bodies.add(new AttachmentChecks.Body(new org.apache.pdfbox.cos.COSDictionary(), List.of(), id));
        var index = new AttachmentChecks.BodyIndex(bodies);
        for (var query : identities) {
            var expected = bodies.stream().filter(body -> query.matches(body.identity())).toList();
            var actual = index.matching(query);
            assertEquals(expected.size(), actual.size());
            for (int i = 0; i < expected.size(); i++) assertSame(expected.get(i), actual.get(i));
        }
    }
    @Test void upstreamValidationReleasesDocumentAndModelCaches() {
        Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf").toAbsolutePath().normalize();
        var result = JavaValidation.pdfa(sample, null);
        assertEquals("PASS", result.get("status").getAsString());
        assertNull(org.verapdf.tools.StaticResources.getDocument(), "Closed parser must not retain the archive");
        assertTrue(StaticContainers.getCachedGlyphs().isEmpty());
        var fonts = org.verapdf.tools.StaticResources.getCachedFonts();
        assertTrue(fonts == null || fonts.isEmpty(), "The calling thread must not retain upstream fonts");
    }
    @Test @SuppressWarnings("unchecked")
    void completedUpstreamPassesDoNotRetainRuleContextsOnTheCallingThread() throws Exception {
        var field = org.verapdf.pdfa.validation.validators.JavaScriptEvaluator.class.getDeclaredField("context");
        field.setAccessible(true);
        var context = (ThreadLocal<Object>) field.get(null);
        Object previous = context.get();
        context.remove();
        try {
            Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf").toAbsolutePath().normalize();
            assertEquals("PASS", JavaValidation.pdfa(sample, null).get("status").getAsString());
            assertNull(context.get(), "A closed upstream pass must not retain its rule context for the EA-PDF stage");
            assertEquals("PASS", JavaValidation.iso(sample).get("status").getAsString());
            assertNull(context.get(), "The ISO pass must release its thread-local rule context too");
        } finally {
            if (previous == null) context.remove(); else context.set(previous);
        }
    }
    @Test void streamedReportRetainsAllFindingsAndMatchesInMemorySummary() throws Exception {
        Path output = temporary.resolve("report.json");
        try (var memory = new JavaValidation.Report("input.pdf"); var disk = new JavaValidation.Report("input.pdf", true)) {
            JsonObject pdfa = JsonParser.parseString("{\"status\":\"PASS\"}").getAsJsonObject();
            memory.pdfa = pdfa; disk.pdfa = pdfa;
            for (int i = 0; i < 3000; i++) for (var report : List.of(memory, disk))
                report.add(i % 100 == 0 ? "FAIL" : "PASS", "EMBEDDED_STREAM", "Preserved payload", Map.of("file", "email-" + i));
            disk.write(output);
            assertEquals(memory.result(), JsonParser.parseString(Files.readString(output)));
            assertTrue(disk.items.isEmpty(), "The CLI must not retain the complete findings tree");
            var brief = JsonParser.parseString(Files.readString(output.resolveSibling("report.json.summary.json"))).getAsJsonObject();
            assertEquals(30, brief.getAsJsonObject("summary").get("fail").getAsInt());
            assertEquals("EMBEDDED_STREAM", brief.getAsJsonArray("failure_codes").get(0).getAsString());
        }
    }
    @Test void desktopKeepsAllOccurrencesOnDiskAndSavesTheUnchangedReport() throws Exception {
        Path output = temporary.resolve("desktop.json"), saved = temporary.resolve("saved.json");
        try (var report = new JavaValidation.Report("input.pdf", true)) {
            report.pdfa = JsonParser.parseString("{\"status\":\"PASS\",\"ran\":true,\"compliant\":true,\"profile_allowed\":true}").getAsJsonObject();
            for (int i = 0; i < 12000; i++) report.add("PASS", "EMBEDDED_STREAM", "Preserved payload", Map.of("file", "email-" + i));
            report.add("FAIL", "EMBEDDED_STREAM", "Missing payload", Map.of("file", "last-email"));
            report.write(output);
        }
        var result = ValidatorGui.Result.parse(output);
        try {
            assertInstanceOf(ReportFindings.class, result.findings());
            assertEquals(12001, result.findings().size());
            assertEquals("FAIL", result.status());
            var groups = ValidatorGui.groupFindings(result.findings());
            assertEquals(2, groups.size());
            assertEquals(12000, groups.getFirst().count());
            assertTrue(groups.getFirst().detailText().contains("Save the report"));
            assertTrue(groups.getLast().detailText().contains("last-email"));
            result.save(saved); assertEquals(-1, Files.mismatch(output, saved));
        } finally { result.close(); }
        // A false PASS at the end of a large stream is still rejected.
        String content = Files.readString(output).replace("\"status\": \"FAIL\"", "\"status\": \"PASS\"");
        Files.writeString(output, content);
        assertThrows(java.io.IOException.class, () -> ValidatorGui.Result.parse(output));
    }
    @Test void storedMimeSlicesKeepHeadersAndMultipartBodiesAfterTheirInputCloses() throws Exception {
        byte[] first = ("Subject: first\r\nContent-Type: multipart/mixed; boundary=b\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nbody\r\n--b\r\nContent-Type: application/octet-stream\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename=data.bin\r\n\r\nAAECAw==\r\n--b--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        byte[] second = "Subject: second\r\n\r\nseparate".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var a = new EaPdfChecks.Embedded("", new org.apache.pdfbox.cos.COSDictionary(), null, first, "a.eml", "message/rfc822", "", "Source");
        var b = new EaPdfChecks.Embedded("", new org.apache.pdfbox.cos.COSDictionary(), null, second, "b.eml", "message/rfc822", "", "Source");
        try (var store = new MimeSourceStore(List.of(a, b))) {
            jakarta.mail.internet.MimeMessage message;
            try (var input = store.open(a)) { message = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new Properties()), input); }
            assertEquals("first", message.getSubject());
            var parts = (jakarta.mail.Multipart)message.getContent();
            assertEquals("body", parts.getBodyPart(0).getContent());
            assertArrayEquals(new byte[]{0,1,2,3}, parts.getBodyPart(1).getInputStream().readAllBytes());
            try (var input = store.open(b)) {
                input.mark(100); assertEquals('S', input.read()); input.reset();
                assertArrayEquals(second, input.readAllBytes()); assertEquals(-1, input.read());
            }
        }
    }
    @Test void engineFailureIsIncompleteAndKeepsTheCompletedPdfaOutcome() throws Exception {
        Path output = temporary.resolve("stopped.json");
        try (var session = new ValidationSession(temporary.resolve("email.pdf"), output, "stopped", false)) {
            ValidationSession.pdfaFinished(JsonParser.parseString("{\"status\":\"PASS\",\"ran\":true,\"compliant\":true,\"profile_allowed\":true}").getAsJsonObject());
            session.failed("Stopped during EA-PDF checking.");
        }
        var parsed = ValidatorGui.Result.parse(Files.readString(output));
        assertEquals("INCOMPLETE", parsed.status()); assertEquals("PASS", parsed.pdfaStatus());
        assertTrue(parsed.findings().stream().anyMatch(f -> f.message().contains("Stopped during")));
        try (var report = new JavaValidation.Report("input.pdf")) {
            report.pdfa = JsonParser.parseString("{\"status\":\"ERROR\"}").getAsJsonObject();
            assertEquals("INCOMPLETE", report.summary().get("status").getAsString());
        }
    }
    @Test void supervisorFailureKeepsUpstreamCheckpointsButANewRunResetsThem() throws Exception {
        Path input = Files.writeString(temporary.resolve("email.pdf"), "fixture"), output = temporary.resolve("checkpoint.json");
        try (var session = new ValidationSession(input, output, "worker", false)) {
            ValidationSession.pdfaFinished(JsonParser.parseString("{\"status\":\"PASS\"}").getAsJsonObject());
            ValidationSession.isoFinished(JsonParser.parseString("{\"status\":\"FAIL\"}").getAsJsonObject());
            session.failed("Worker stopped.");
        }
        try (var session = new ValidationSession(input, output, "worker", true)) { session.failed("Supervisor observed worker termination."); }
        var recovered = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals("PASS", recovered.getAsJsonObject("pdfa").get("status").getAsString());
        assertEquals("FAIL", recovered.getAsJsonObject("summary").get("status").getAsString());
        try (var session = new ValidationSession(input, output, "new", true)) { session.failed("Starting a separate run."); }
        var fresh = JsonParser.parseString(Files.readString(output)).getAsJsonObject();
        assertEquals("NOT_RUN", fresh.getAsJsonObject("pdfa").get("status").getAsString());
    }
    @Test void reportPathsCannotReplaceTheInput() throws Exception {
        Path output = temporary.resolve("report.json");
        for (String suffix : List.of("", ".progress.json", ".summary.json")) {
            Path input = Files.writeString(output.resolveSibling(output.getFileName() + suffix), "original PDF");
            assertThrows(java.io.IOException.class, () -> new ValidationSession(input, output));
            assertEquals("original PDF", Files.readString(input));
            Files.delete(input);
            assertFalse(Files.exists(output));
        }
    }
    @Test void overlappingReportSetsAreLockedAcrossJvms() throws Exception {
        var seconds = java.util.concurrent.TimeUnit.SECONDS;
        Path input = temporary.resolve("input.pdf");
        Path report = temporary.resolve("report.json");
        Path overlapping = temporary.resolve("report.json.summary.json");
        Path log = temporary.resolve("lock.log");
        try (var lock = new ValidationRuntime.ReportSet(input, report, seconds.toNanos(2))) {
            var probe = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ReportLockProbe.class.getName(), input.toString(), overlapping.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                assertTrue(probe.waitFor(15, seconds));
                assertEquals(2, probe.exitValue(), Files.readString(log));
                assertTrue(Files.readString(log).contains("Another validation"));
            } finally { if (probe.isAlive()) probe.destroyForcibly().waitFor(5, seconds); }
        }
        try (var lock = new ValidationRuntime.ReportSet(input, overlapping, seconds.toNanos(2))) {
            assertEquals(overlapping, lock.report);
        }
    }
    public static final class ReportLockProbe {
        public static void main(String[] arguments) {
            try (var lock = new ValidationRuntime.ReportSet(Path.of(arguments[0]), Path.of(arguments[1]),
                     java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(300))) {
                System.out.println("Report acquired");
            } catch (Exception error) {
                System.err.println(error.getMessage());
                System.exit(2);
            }
        }
    }
    @Test void cliWorkerPublishesItsReportAndSidecars() throws Exception {
        Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf").toAbsolutePath().normalize();
        Path report = temporary.resolve("cli-report.json");
        assertEquals(0, ValidationWorker.run(sample, report));
        var full = JsonParser.parseString(Files.readString(report)).getAsJsonObject();
        var summary = JsonParser.parseString(Files.readString(temporary.resolve("cli-report.json.summary.json"))).getAsJsonObject();
        assertEquals("PASS", JavaValidation.text(full.getAsJsonObject("summary"), "status"));
        assertEquals(full.get("summary"), summary.get("summary"));
        assertEquals(report.toString(), summary.get("report").getAsString());
        Path progress = temporary.resolve("cli-report.json.progress.json");
        assertTrue(Files.isRegularFile(progress));
        assertEquals(report.toString(), JsonParser.parseString(Files.readString(progress))
            .getAsJsonObject().get("report").getAsString());
        assertEquals(0, ValidationWorker.run(sample, report));
        var subsequent = JsonParser.parseString(Files.readString(report)).getAsJsonObject().getAsJsonObject("summary");
        assertNotEquals(full.getAsJsonObject("summary").get("run_id"), subsequent.get("run_id"));
    }
    @Test void runningWorkerCanPublishProgressButNotItsVerdict() throws Exception {
        Path staged = Files.createDirectory(temporary.resolve("worker")).resolve("report.json");
        Path stagedProgress = staged.resolveSibling("report.json.progress.json");
        String verdict = "{\"summary\":{\"status\":\"PASS\",\"validation_target\":\"eapdf\",\"run_id\":\"worker\"},\"pdfa\":{},\"failure_codes\":[]}";
        Files.writeString(staged, verdict);
        Files.writeString(staged.resolveSibling("report.json.summary.json"), verdict);
        Files.writeString(stagedProgress, "{\"stage\":\"Checking EA-PDF\",\"validation_target\":\"eapdf\",\"run_id\":\"worker\"}");
        Path report = temporary.resolve("report.json");
        Path summary = temporary.resolve("report.json.summary.json");
        Path progress = temporary.resolve("report.json.progress.json");
        try (var reports = new ValidationRuntime.ReportSet(temporary.resolve("input.pdf"), report,
                 java.util.concurrent.TimeUnit.SECONDS.toNanos(2))) {
            reports.mirror(staged, false);
            assertFalse(Files.exists(report));
            assertFalse(Files.exists(summary));
            assertTrue(Files.readString(progress).contains("Checking EA-PDF"));
            Files.writeString(stagedProgress, "{\"stage\":\"Finished\",\"validation_target\":\"eapdf\",\"run_id\":\"worker\"}");
            reports.mirror(staged, false);
            assertTrue(Files.readString(progress).contains("Checking EA-PDF"));
            reports.mirror(staged, true);
            assertEquals(verdict, Files.readString(report));
            var brief = JsonParser.parseString(Files.readString(summary)).getAsJsonObject();
            assertEquals(report.toString(), brief.remove("report").getAsString());
            assertEquals(JsonParser.parseString(verdict), brief);
            assertTrue(Files.readString(progress).contains("Finished"));
        }
    }
    @Test void childBudgetsAreCheckedBeforeAdmissionAndSupportOrdinaryLargeCollections() throws Exception {
        var children = new JavaValidation.Children();
        assertTrue(children.accepts(300L * 1024 * 1024, 0));
        for (int i = 0; i < 300; i++) assertTrue(children.accepts(1, 0));
        long before = children.bytes; int count = children.count;
        assertFalse(children.accepts(Long.MAX_VALUE, 0));
        assertEquals(before, children.bytes); assertEquals(count, children.count);
        assertFalse(children.accepts(1, 1000));
    }
    @Test void childParsersConsumeTheSameJobAllowance() throws Exception {
        String oldBytes = System.getProperty("VALIDATOR_MAX_CHILD_BYTES");
        String oldCount = System.getProperty("VALIDATOR_MAX_CHILDREN");
        try {
            System.setProperty("VALIDATOR_MAX_CHILD_BYTES", "20");
            System.setProperty("VALIDATOR_MAX_CHILDREN", "3");
            Path ledger = Files.write(temporary.resolve("children.bin"), new byte[Integer.BYTES + Long.BYTES]);
            var first = new JavaValidation.Children(); first.sharedBudget = ledger;
            var second = new JavaValidation.Children(); second.sharedBudget = ledger;
            assertTrue(first.accepts(12, 0));
            assertFalse(second.accepts(9, 1));
            assertTrue(second.accepts(8, 1));
            assertTrue(second.accepts(0, 1));
            assertFalse(first.accepts(0, 0));
        } finally {
            if (oldBytes == null) System.clearProperty("VALIDATOR_MAX_CHILD_BYTES");
            else System.setProperty("VALIDATOR_MAX_CHILD_BYTES", oldBytes);
            if (oldCount == null) System.clearProperty("VALIDATOR_MAX_CHILDREN");
            else System.setProperty("VALIDATOR_MAX_CHILDREN", oldCount);
        }
    }
    @Test void glyphInventoryIsNotEvictedForPdfa1() {
        StaticContainers.clearAllContainers();
        try {
            StaticContainers.setFlavour(List.of(PDFAFlavour.PDFA_1_A));
            var original = StaticContainers.getCachedGlyphs(); BoundedGlyphCache.install();
            assertSame(original, StaticContainers.getCachedGlyphs());
            StaticContainers.setFlavour(List.of(PDFAFlavour.PDFA_4_F)); BoundedGlyphCache.install();
            assertInstanceOf(BoundedGlyphCache.class, StaticContainers.getCachedGlyphs());
        } finally { StaticContainers.clearAllContainers(); }
    }
}
