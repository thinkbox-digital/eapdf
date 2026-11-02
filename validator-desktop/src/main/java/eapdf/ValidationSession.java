package eapdf;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.verapdf.pdfa.PDFAValidator;

/** Durable incomplete outcome and stage progress for the standalone worker. */
final class ValidationSession implements AutoCloseable {
    private static volatile ValidationSession current;
    private final Path input, output, progress;
    private final long started = System.nanoTime();
    private volatile String stage = "Starting validation", detail = "";
    private volatile boolean complete;
    private volatile JsonObject pdfa, iso32005;
    private byte[] reserve = new byte[512 * 1024];
    private final Thread shutdown;

    ValidationSession(Path input, Path output) throws IOException {
        this.input = input.toAbsolutePath(); this.output = output.toAbsolutePath();
        if (this.input.normalize().equals(this.output.normalize()) || (Files.exists(this.output) && Files.isSameFile(this.input, this.output))) throw new IOException("The report must not overwrite the input PDF.");
        progress = this.output.resolveSibling(this.output.getFileName() + ".progress.json");
        pdfa = new JsonObject(); pdfa.addProperty("status", "NOT_RUN");
        pdfa.addProperty("ran", false); pdfa.addProperty("compliant", false); pdfa.addProperty("profile_allowed", false);
        snapshot("Validation has not finished.", "RUNNING");
        shutdown = new Thread(() -> {
            if (!complete) failed("Validation was interrupted during " + stage + ".");
        }, "validation-outcome");
        Runtime.getRuntime().addShutdownHook(shutdown); current = this;
    }
    static void stage(String stage, Path file) {
        ValidationSession session = current;
        if (session == null) return;
        session.stage = stage; session.detail = file.getFileName().toString();
        session.updateProgress();
    }
    static void pdfaFinished(JsonObject result) {
        ValidationSession session = current;
        if (session == null) return;
        session.pdfa = result.deepCopy();
        try { session.snapshot("PDF/A checking has ended; EA-PDF checking is not complete.", "RUNNING"); }
        catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    static void isoFinished(JsonObject result) {
        ValidationSession session = current;
        if (session == null) return;
        session.iso32005 = result.deepCopy();
        try { session.snapshot("Upstream checking has ended; EA-PDF checking is not complete.", "RUNNING"); }
        catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }
    interface Monitor extends AutoCloseable { @Override void close(); }
    static Monitor monitor(PDFAValidator validator) {
        ValidationSession session = current;
        if (session == null) return () -> {};
        var timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "validation-progress"); thread.setDaemon(true); return thread;
        });
        timer.scheduleAtFixedRate(() -> {
            session.detail = validator.getValidationProgressString(); session.updateProgress();
        }, 0, 10, TimeUnit.SECONDS);
        return timer::shutdownNow;
    }
    private synchronized void updateProgress() {
        JsonObject value = new JsonObject();
        value.addProperty("stage", stage); value.addProperty("detail", detail);
        value.addProperty("elapsed_seconds", (System.nanoTime() - started) / 1_000_000_000);
        value.addProperty("updated", Instant.now().toString());
        try { writeAtomic(progress, value); }
        catch (IOException error) { System.err.println("Unable to write validation progress: " + error.getMessage()); }
        System.out.println("[validation] " + stage + " — " + detail);
    }
    private synchronized void snapshot(String reason, String state) throws IOException {
        JsonObject result = new JsonObject(), summary = new JsonObject(); JsonArray items = new JsonArray();
        String upstream = JavaValidation.text(pdfa, "status");
        String level = "PASS".equals(upstream) ? "PASS" : "FAIL".equals(upstream) ? "FAIL" : "WARN";
        summary.addProperty("file", input.toString()); summary.addProperty("status", "FAIL".equals(upstream) || iso32005 != null && "FAIL".equals(JavaValidation.text(iso32005, "status")) ? "FAIL" : "INCOMPLETE");
        summary.addProperty("pdfa_status", upstream); summary.addProperty("verification_spec", "");
        summary.addProperty("validation_scope", JavaValidation.SCOPE); summary.addProperty("java_port_complete", true);
        summary.addProperty("validation_target", "eapdf");
        summary.addProperty("rule_total", JavaValidation.RULES.size()); summary.addProperty("run_state", state);
        summary.addProperty("last_stage", stage);
        summary.addProperty("pass", "PASS".equals(level) ? 1 : 0);
        summary.addProperty("fail", "FAIL".equals(level) ? 1 : 0);
        summary.addProperty("warn", "WARN".equals(level) ? 2 : 1); summary.addProperty("na", 0);
        for (String code : List.of("PDFA_FIRST_PASS", "VALIDATION_INCOMPLETE")) {
            JsonObject item = new JsonObject(); item.addProperty("code", code);
            item.addProperty("level", "PDFA_FIRST_PASS".equals(code) ? level : "WARN");
            item.addProperty("message", "PDFA_FIRST_PASS".equals(code) ? "PDF/A result: " + upstream : reason);
            item.add("details", "PDFA_FIRST_PASS".equals(code) ? pdfa : JsonNull.INSTANCE); items.add(item);
        }
        if (iso32005 != null) {
            String status = JavaValidation.text(iso32005, "status");
            String isoLevel = "PASS".equals(status) ? "PASS" : "FAIL".equals(status) ? "FAIL" : "WARN";
            JsonObject item = new JsonObject(); item.addProperty("code", "ISO32005_UPSTREAM");
            item.addProperty("level", isoLevel); item.addProperty("message", "Selected upstream ISO 32005 result: " + status);
            item.add("details", iso32005); items.add(item);
            String counter = isoLevel.toLowerCase(java.util.Locale.ROOT);
            summary.addProperty(counter, summary.get(counter).getAsInt() + 1);
        }
        result.add("summary", summary); result.add("pdfa", pdfa); result.add("items", items);
        writeAtomic(output, result);
        JsonObject brief = new JsonObject(); brief.add("summary", summary); brief.add("pdfa", pdfa);
        java.util.List<String> failures = new java.util.ArrayList<>();
        if ("FAIL".equals(upstream)) failures.add("PDFA_FIRST_PASS");
        if (iso32005 != null && "FAIL".equals(JavaValidation.text(iso32005, "status"))) failures.add("ISO32005_UPSTREAM");
        brief.add("failure_codes", JavaValidation.JSON.toJsonTree(failures));
        JsonArray unavailable = new JsonArray(); unavailable.add(items.get(1)); brief.add("unavailable_examples", unavailable);
        writeAtomic(output.resolveSibling(output.getFileName() + ".summary.json"), brief);
    }
    void failed(String reason) {
        reserve = null;
        try { snapshot(reason, "INCOMPLETE"); }
        catch (Exception error) { System.err.println("Unable to write incomplete validation report: " + error); }
        stage = "Incomplete"; detail = reason; updateProgress(); complete = true;
    }
    void completed() { complete = true; stage = "Finished"; detail = "Validation report saved."; updateProgress(); }
    static void writeAtomic(Path output, JsonObject result) throws IOException {
        Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".validation-", ".json");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { JavaValidation.JSON.toJson(result, writer); }
            replace(temporary, output);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void replace(Path temporary, Path output) throws IOException {
        try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public void close() {
        current = null;
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        reserve = null;
    }
}
