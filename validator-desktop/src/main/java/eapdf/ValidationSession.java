package eapdf;

import com.google.gson.*;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.verapdf.pdfa.PDFAValidator;

/** Durable incomplete outcome and stage progress for the standalone worker. */
final class ValidationSession implements AutoCloseable {
    private static volatile ValidationSession current;
    static final String RUN_ID_PROPERTY = "eapdf.validator.runId";
    private final Path input;
    private final ValidationOutputs outputs;
    private final List<Path> protectedInputs;
    private final String runId;
    private final long started = System.nanoTime();
    private volatile String stage = "Starting validation", detail = "";
    private volatile Work work;
    private volatile boolean complete;
    private volatile JsonObject pdfa, iso32005;
    private byte[] reserve = new byte[512 * 1024];
    private final Thread shutdown;
    private final ScheduledExecutorService heartbeat;
    private PDFAValidator upstreamValidator;

    ValidationSession(Path input, Path output) throws IOException {
        this(input, output, java.util.UUID.randomUUID().toString(), false);
    }

    ValidationSession(Path input, Path output, String runId, boolean recoverUpstream) throws IOException {
        this(input, output, runId, recoverUpstream, null);
    }

    ValidationSession(Path input, Path output, String runId, boolean recoverUpstream, Path stagedSummary) throws IOException {
        this.input = input.toAbsolutePath();
        this.outputs = ValidationOutputs.of(output);
        this.runId = runId;
        outputs.protectInput(input);
        protectedInputs = ValidationOutputs.protectedInputs(this.input);
        pdfa = new JsonObject(); pdfa.addProperty("status", "NOT_RUN");
        pdfa.addProperty("ran", false); pdfa.addProperty("compliant", false); pdfa.addProperty("profile_allowed", false);
        if (recoverUpstream) {
            Path checkpoint = stagedSummary != null && Files.isRegularFile(stagedSummary) ? stagedSummary : outputs.summary();
            if (Files.isRegularFile(checkpoint)) try (var reader = Files.newBufferedReader(checkpoint)) {
                JsonObject prior = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject summary = prior.getAsJsonObject("summary");
                if (this.input.toString().equals(JavaValidation.text(summary, "file"))
                    && runId.equals(JavaValidation.text(summary, "run_id"))) {
                    if (prior.has("pdfa")) pdfa = prior.getAsJsonObject("pdfa").deepCopy();
                    if (prior.has("iso32005")) iso32005 = prior.getAsJsonObject("iso32005").deepCopy();
                    if (summary.has("last_stage")) stage = summary.get("last_stage").getAsString();
                }
            } catch (IOException | RuntimeException error) {
                System.err.println("Unable to recover prior upstream validation outcome: " + error.getMessage());
            }
        }
        snapshot("Validation has not finished.", "RUNNING");
        shutdown = new Thread(() -> {
            if (!complete) failed("Validation was interrupted during " + stage + ".");
        }, "validation-outcome");
        Runtime.getRuntime().addShutdownHook(shutdown); current = this;
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "validation-progress");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(this::heartbeat, 5, 5, TimeUnit.SECONDS);
    }
    static void stage(String stage, Path file) {
        ValidationSession session = current;
        if (session == null) {
            if (Boolean.getBoolean("eapdf.validator.childWorker")) System.out.println("[validation] " + stage + " — " + file.getFileName());
            return;
        }
        synchronized (session) {
            session.stage = stage; session.detail = file.getFileName().toString(); session.work = null;
            session.updateProgress();
        }
    }
    private record Work(long completed, long total, String unit, long encoded, long decoded, String relationship) {}

    static CountedProgress count(String stage, Path file, String unit, long total) {
        ValidationSession session = current;
        var counter = new CountedProgress(session, stage, unit, total);
        if (session == null) stage(stage, file);
        else synchronized (session) {
            session.stage = stage;
            counter.finish(0);
        }
        return counter;
    }

    static final class CountedProgress {
        private final ValidationSession session;
        private final String stage, unit;
        private final long total;
        private long lastUpdate;

        private CountedProgress(ValidationSession session, String stage, String unit, long total) {
            this.session = session;
            this.stage = stage;
            this.unit = unit;
            this.total = total;
        }
        void advance(long completed) { advance(completed, -1, -1, null); }
        void finish(long completed) { finish(completed, -1, -1, null); }
        void advance(long completed, long encoded, long decoded, String relationship) {
            if (session != null && System.nanoTime() - lastUpdate >= TimeUnit.SECONDS.toNanos(1))
                finish(completed, encoded, decoded, relationship);
        }
        void finish(long completed, long encoded, long decoded, String relationship) {
            if (session == null) return;
            synchronized (session) {
                if (!stage.equals(session.stage)) return;
                lastUpdate = System.nanoTime();
                session.work = new Work(completed, total, unit, encoded, decoded, relationship);
                session.detail = completed + (total >= 0 ? "/" + total : "") + " " + unit
                        + (decoded >= 0 ? ", " + decoded + " decoded bytes" : "");
                session.updateProgress();
            }
        }
    }
    static void collectionProgress(int completed, int total) {
        ValidationSession session = current;
        if (session == null) return;
        synchronized (session) {
            session.stage = "Checking embedded email PDFs";
            session.work = null;
            session.detail = completed + " of " + total + " collection members finished; four processes";
            session.updateProgress();
        }
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
        synchronized (session) {
            session.upstreamValidator = validator;
            session.detail = validator.getValidationProgressString();
            session.updateProgress();
        }
        return () -> {
            synchronized (session) {
                session.upstreamValidator = null;
            }
        };
    }
    private synchronized void heartbeat() {
        if (complete) return;
        if (upstreamValidator != null) detail = upstreamValidator.getValidationProgressString();
        updateProgress(upstreamValidator != null);
    }
    private void updateProgress() { updateProgress(true); }
    private synchronized void updateProgress(boolean log) {
        JsonObject value = new JsonObject();
        String progressDetail = detail;
        if (upstreamValidator != null) {
            var stats = upstreamValidator.getValidationProgressStats();
            value.addProperty("upstream_checks", (Number) stats.get("checks"));
            value.addProperty("upstream_failed_checks", (Number) stats.get("failedChecks"));
            value.addProperty("upstream_processed_objects", (Number) stats.get("processedObjects"));
            value.addProperty("upstream_pending_objects", (Number) stats.get("objectsToBeProcessed"));
            if (stats.get("modelActivity") instanceof String activity) {
                value.addProperty("upstream_activity", activity);
                progressDetail += " " + activity + ".";
            }
        }
        value.addProperty("stage", stage); value.addProperty("detail", progressDetail);
        value.addProperty("elapsed_seconds", (System.nanoTime() - started) / 1_000_000_000);
        value.addProperty("updated", Instant.now().toString());
        value.addProperty("validation_target", "eapdf");
        value.addProperty("report", outputs.report().toString());
        value.addProperty("run_id", runId);
        Work counter = work;
        if (counter != null) {
            value.addProperty("stage_completed", counter.completed());
            if (counter.total() >= 0) value.addProperty("stage_total", counter.total());
            value.addProperty("stage_unit", counter.unit());
            if (counter.encoded() >= 0) value.addProperty("stage_encoded_bytes", counter.encoded());
            if (counter.decoded() >= 0) value.addProperty("stage_decoded_bytes", counter.decoded());
            if (counter.relationship() != null) value.addProperty("stage_relationship", counter.relationship());
        }
        Runtime runtime = Runtime.getRuntime();
        value.addProperty("process_id", ProcessHandle.current().pid());
        value.addProperty("heap_used_bytes", runtime.totalMemory() - runtime.freeMemory());
        value.addProperty("heap_max_bytes", runtime.maxMemory());
        long rss = residentBytes();
        if (rss >= 0) value.addProperty("rss_bytes", rss);
        long gcCount = 0, gcMillis = 0;
        for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcCount += Math.max(0, collector.getCollectionCount());
            gcMillis += Math.max(0, collector.getCollectionTime());
        }
        value.addProperty("gc_count", gcCount);
        value.addProperty("gc_milliseconds", gcMillis);
        try { write(outputs.progress(), value); }
        catch (IOException error) { System.err.println("Unable to write validation progress: " + error.getMessage()); }
        if (log) System.out.println("[validation] " + stage + " — " + progressDetail);
    }
    private static long residentBytes() {
        try (var status = Files.newBufferedReader(Path.of("/proc/self/status"))) {
            for (String line; (line = status.readLine()) != null;) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.split("\\s+")[1]) * 1024L;
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            // This operating system may not expose process RSS through procfs.
        }
        return -1;
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
        summary.addProperty("run_id", runId);
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
        JsonObject brief = new JsonObject(); brief.add("summary", summary); brief.add("pdfa", pdfa);
        brief.addProperty("report", outputs.report().toString());
        if (iso32005 != null) brief.add("iso32005", iso32005);
        java.util.List<String> failures = new java.util.ArrayList<>();
        if ("FAIL".equals(upstream)) failures.add("PDFA_FIRST_PASS");
        if (iso32005 != null && "FAIL".equals(JavaValidation.text(iso32005, "status"))) failures.add("ISO32005_UPSTREAM");
        brief.add("failure_codes", JavaValidation.JSON.toJsonTree(failures));
        JsonArray unavailable = new JsonArray(); unavailable.add(items.get(1)); brief.add("unavailable_examples", unavailable);
        write(outputs.summary(), brief);
        write(outputs.report(), result);
    }
    synchronized void failed(String reason) {
        if (complete) return;
        reserve = null;
        try { snapshot(reason, "INCOMPLETE"); }
        catch (Exception error) { System.err.println("Unable to write incomplete validation report: " + error); }
        stage = "Incomplete"; detail = reason; work = null; updateProgress(); complete = true;
    }
    void memoryFailure(Error error) {
        reserve = null;
        String kind = error instanceof StackOverflowError ? "exceeded the Java stack" : "ran out of memory";
        StackTraceElement[] trace = error.getStackTrace();
        String location = trace.length == 0 ? "" : " at " + trace[0];
        failed("Validation " + kind + " during " + stage + " (" + error + location + ").");
        error.printStackTrace(System.err);
    }
    JsonObject completedSummary(JsonObject summary) {
        summary.addProperty("run_id", runId);
        summary.addProperty("run_state", "COMPLETE");
        return summary;
    }

    void replaceOutput(Path temporary, Path output) throws IOException {
        ValidationOutputs.protect(output, protectedInputs);
        replace(temporary, output);
    }

    void write(Path output, JsonObject result) throws IOException {
        if (!output.equals(outputs.report())) outputs.verifyPreviousSidecars();
        writeAtomic(output, result, protectedInputs);
    }

    synchronized void completed() { complete = true; stage = "Finished"; detail = "Validation report saved."; work = null; updateProgress(); }
    static void writeAtomic(Path output, JsonObject result) throws IOException {
        writeAtomic(output, result, List.of());
    }
    static void writeAtomic(Path output, JsonObject result, List<Path> protectedInputs) throws IOException {
        output = ValidationOutputs.entry(output);
        ValidationOutputs.protect(output, protectedInputs);
        Path temporary = Files.createTempFile(output.toAbsolutePath().getParent(), ".validation-", ".json");
        try {
            try (var writer = Files.newBufferedWriter(temporary)) { JavaValidation.JSON.toJson(result, writer); }
            ValidationOutputs.protect(output, protectedInputs);
            replace(temporary, output);
        } finally { Files.deleteIfExists(temporary); }
    }
    static void replace(Path temporary, Path output) throws IOException {
        try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException error) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
    }
    @Override public void close() {
        heartbeat.shutdownNow();
        current = null;
        try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { }
        reserve = null;
    }
}
