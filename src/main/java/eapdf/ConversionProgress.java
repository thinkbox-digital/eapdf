package eapdf;

import com.google.gson.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Live job status; this is deliberately separate from transactional PDF publication. */
final class ConversionProgress {
  private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
  private static volatile ConversionProgress active;
  private static java.util.concurrent.ScheduledExecutorService refresher;
  private final Path path;
  private final String jobId;
  private final String started;
  private long discovered, completed, inputs, inputsRead, lastWrite;
  private Long total;
  private String stage = "reading";
  private boolean warned;
  private long stageCompleted, lastLog;
  private Long stageTotal;
  private String stageUnit, stageStarted = Instant.now().toString(), lastProgress = stageStarted;
  private String loggedStage;
  private long loggedCompleted = -1;
  private Path savingFile;
  private JsonArray creationReceipts = new JsonArray();
  private long receiptBytes;
  private JsonObject conversionWarnings = new JsonObject();
  private JsonArray conversionDiagnostics = new JsonArray();

  private ConversionProgress(Path path, String jobId, String started) {
    this.path = path; this.jobId = jobId; this.started = started;
  }

  static ConversionProgress start(String entry, String[] arguments, Path workspace) throws Exception {
    Map<String, Object> options = switch (entry) {
      case "eapdf.EmailToPDFA" -> EmailToPDFA.parseArgs(arguments);
      case "eapdf.GmailDownloadToPdf" -> GmailDownloadToPdf.parseArgs(arguments);
      case "eapdf.MonthlyFilenameArchiveToPdf" -> MonthlyFilenameArchiveToPdf.parseArgs(arguments);
      default -> throw Common.fail("Unsupported progress entry point");
    };
    String fallback = entry.equals("eapdf.MonthlyFilenameArchiveToPdf")
        ? Path.of((String) options.getOrDefault("in", MonthlyFilenameArchiveToPdf.DEFAULT_INPUT_DIR)).resolve("_monthly_1m").toString() : "out";
    String option = entry.equals("eapdf.GmailDownloadToPdf") ? "out-pdf" : "out";
    Path path = Path.of((String) options.getOrDefault(option, fallback)).toAbsolutePath().normalize().resolve("progress.json");
    var progress = new ConversionProgress(path, UUID.randomUUID().toString(), Instant.now().toString());
    Common.writeAtomically(workspace.resolve("progress-target.json"), JSON.toJson(Map.of(
        "path", path.toString(), "job_id", progress.jobId, "started", progress.started)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    progress.write(false);
    System.out.println("Progress: " + path);
    return progress;
  }

  static void attach(Path workspace) throws Exception {
    Path target = workspace.resolve("progress-target.json");
    if (!Files.exists(target)) return;
    JsonObject owner = JsonParser.parseString(Files.readString(target)).getAsJsonObject();
    detach();
    active = new ConversionProgress(Path.of(owner.get("path").getAsString()), owner.get("job_id").getAsString(), owner.get("started").getAsString());
    // The supervisor attaches again for publication after the worker exits.
    JsonObject snapshot = JsonParser.parseString(Files.readString(active.path)).getAsJsonObject();
    if (active.jobId.equals(snapshot.get("job_id").getAsString())) {
      if (snapshot.has("creation_receipts")) {
        active.creationReceipts = snapshot.getAsJsonArray("creation_receipts").deepCopy();
        active.receiptBytes = active.creationReceipts.toString().length() * 2L;
      }
      if (snapshot.has("conversion_warnings")) active.conversionWarnings = snapshot.getAsJsonObject("conversion_warnings").deepCopy();
      if (snapshot.has("conversion_diagnostics")) active.conversionDiagnostics = snapshot.getAsJsonArray("conversion_diagnostics").deepCopy();
      active.completed = snapshot.get("emails_completed").getAsLong();
      active.discovered = snapshot.get("emails_discovered").getAsLong();
      active.total = snapshot.get("emails_total").isJsonNull() ? null : snapshot.get("emails_total").getAsLong();
    }
    refresher = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
      Thread thread = new Thread(task, "eapdf-progress"); thread.setDaemon(true); return thread;
    });
    refresher.scheduleWithFixedDelay(ConversionProgress::flush, 1, 1, java.util.concurrent.TimeUnit.SECONDS);
  }

  /** Records only checks actually performed by the creator, on final (possibly signed) bytes. */
  static void creationChecked(Path destination, Path bytes, PreservationInventory expected, boolean checked) throws Exception {
    var p = active;
    if (p == null) return;
    JsonObject receipt = new JsonObject();
    receipt.addProperty("file", destination.toAbsolutePath().normalize().toString());
    receipt.addProperty("sha256", Common.digestHex(bytes, "SHA-256"));
    receipt.addProperty("size_bytes", Files.size(bytes));
    receipt.addProperty("creation_checks", checked ? "PASS" : "SKIPPED");
    receipt.addProperty("preservation_comparison", !checked || expected == null ? "NOT_RUN" : "PASS");
    receipt.addProperty("preservation_scope", "Compared with normalized reader records; not independent proof of source completeness");
    receipt.addProperty("pdfa_validation", "NOT_RUN");
    receipt.addProperty("eapdf_validation", "NOT_RUN");
    receipt.addProperty("publication", "PENDING");
    if (expected != null) {
      receipt.addProperty("profile", expected.profile);
      receipt.addProperty("messages", expected.messages.size());
      receipt.addProperty("source_disposition", expected.sourceDisposition);
      receipt.addProperty("attachment_disposition", expected.attachmentDisposition);
      receipt.addProperty("source_problem_count", expected.messages.values().stream().mapToLong(m -> m.problems().size()).sum());
    }
    synchronized (p) {
      long size = receipt.toString().length() * 2L;
      if (size > ProcessingLimits.metadataBytes() - p.receiptBytes)
        throw Common.fail("Conversion receipts exceed the metadata budget");
      p.creationReceipts.add(receipt); p.receiptBytes += size;
      p.write(true);
    }
  }

  static void importReceipts(Path snapshot) throws Exception {
    var p = active;
    if (p == null) return;
    if (Files.size(snapshot) > ProcessingLimits.metadataBytes()) throw Common.fail("Worker progress exceeds the metadata budget");
    JsonArray receipts = JsonParser.parseString(Files.readString(snapshot)).getAsJsonObject().getAsJsonArray("creation_receipts");
    synchronized (p) {
      for (var receipt : receipts) {
        long size = receipt.toString().length() * 2L;
        if (size > ProcessingLimits.metadataBytes() - p.receiptBytes)
          throw Common.fail("Conversion receipts exceed the metadata budget");
        p.creationReceipts.add(receipt); p.receiptBytes += size;
      }
      p.emit(true);
    }
  }

  static void diagnostics(Map<String, String> warnings) {
    Map<String, ConversionDiagnostic> entries = new TreeMap<>();
    warnings.forEach((key, text) -> entries.put(key, new ConversionDiagnostic(
        ConversionDiagnostic.Code.REVIEW_REQUIRED, key, "", "", "", text)));
    diagnostics(warnings, entries);
  }

  static void diagnostics(Map<String, String> warnings, Map<String, ConversionDiagnostic> entries) {
    var p = active;
    if (p != null) synchronized (p) {
      p.conversionWarnings = new JsonObject();
      new TreeMap<>(warnings).forEach(p.conversionWarnings::addProperty);
      p.conversionDiagnostics = new JsonArray();
      new TreeMap<>(entries).values().forEach(entry -> p.conversionDiagnostics.add(entry.toJson()));
      p.emit(true);
    }
  }

  static void inputsSelected(long count) { var p = active; if (p != null) synchronized (p) { p.inputs = count; } }
  static void discovered() { var p = active; if (p != null) synchronized (p) { p.discovered++; p.changed(); p.emit(false); } }
  static void inputRead() {
    var p = active; if (p != null) synchronized (p) {
      p.inputsRead++;
      if (p.inputs > 0 && p.inputsRead == p.inputs) { p.total = p.discovered; p.emit(true); }
    }
  }
  static void total(long count) { var p = active; if (p != null) synchronized (p) { p.total = count; p.emit(true); } }
  static void completed() { var p = active; if (p != null) synchronized (p) { p.completed++; p.changed(); p.emit(p.total != null && p.completed == p.total); } }
  static void stage(String value) {
    var p = active; if (p != null) synchronized (p) {
      if (!p.stage.equals(value)) { p.setStage(value, null, null); p.emit(true); }
    }
  }
  static void begin(String value, long total, String unit) {
    var p = active; if (p != null) synchronized (p) {
      p.setStage(value, total, unit); p.emit(true);
    }
  }
  static void beginUnknown(String value, String unit) {
    var p = active; if (p != null) synchronized (p) {
      p.setStage(value, null, unit); p.emit(true);
    }
  }
  // Expected stage prevents nested message rendering from updating archive counters.
  static void advance(String expectedStage) {
    var p = active; if (p != null) synchronized (p) {
      if (!p.stage.equals(expectedStage) || p.stageUnit == null) return;
      p.stageCompleted++; p.changed();
      p.emit(p.stageTotal != null && p.stageCompleted == p.stageTotal);
    }
  }
  static void saving(Path file) {
    var p = active; if (p != null) synchronized (p) {
      p.setStage("saving", null, "bytes"); p.savingFile = file; p.emit(true);
    }
  }
  private void changed() { lastProgress = Instant.now().toString(); }
  private void setStage(String value, Long count, String unit) {
    stage = value; stageCompleted = 0; stageTotal = count; stageUnit = unit;
    stageStarted = Instant.now().toString(); savingFile = null; changed();
  }
  private static long secondsSince(String value) {
    return Math.max(0, java.time.Duration.between(Instant.parse(value), Instant.now()).getSeconds());
  }
  static void flush() { var p = active; if (p != null) synchronized (p) { p.emit(true); } }
  static void detach() {
    active = null;
    if (refresher != null) { refresher.shutdownNow(); refresher = null; }
  }

  private void emit(boolean force) {
    if (!force && System.nanoTime() - lastWrite < 1_000_000_000L) return;
    try { write(true); }
    catch (Exception error) {
      if (!warned) { warned = true; System.err.println("warning: Could not update conversion progress: " + path); }
    }
  }

  private void write(boolean checkOwner) throws Exception {
    if (checkOwner) {
      if (!ownsFile()) return;
      JsonObject existing = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      if (!"RUNNING".equals(existing.get("state").getAsString())) return;
    }
    if (savingFile != null && Files.exists(savingFile)) {
      long bytes = Files.size(savingFile);
      if (bytes != stageCompleted) { stageCompleted = bytes; changed(); }
    }
    long done = stage.equals("rendering") ? completed : stage.equals("reading") ? discovered : stageCompleted;
    Long count = stage.equals("rendering") ? total : stageTotal;
    String unit = stage.equals("rendering") || stage.equals("reading") ? "emails" : stageUnit;
    long elapsed = secondsSince(started), stageElapsed = secondsSince(stageStarted);
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("creation_receipts", creationReceipts);
    snapshot.put("conversion_warnings", conversionWarnings);
    snapshot.put("conversion_diagnostics", conversionDiagnostics);
    snapshot.put("job_id", jobId); snapshot.put("state", "RUNNING"); snapshot.put("stage", stage);
    snapshot.put("emails_completed", completed); snapshot.put("emails_total", total);
    snapshot.put("total_known", total != null); snapshot.put("emails_discovered", discovered);
    snapshot.put("stage_completed", unit == null ? null : done);
    snapshot.put("stage_total", count); snapshot.put("stage_unit", unit);
    snapshot.put("stage_started", stageStarted); snapshot.put("last_progress", lastProgress);
    snapshot.put("elapsed_seconds", elapsed); snapshot.put("stage_elapsed_seconds", stageElapsed);
    snapshot.put("process_id", ProcessHandle.current().pid());
    snapshot.put("process_cpu_seconds", ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toSeconds).orElse(null));
    snapshot.put("heap_used_bytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
    snapshot.put("heap_max_bytes", Runtime.getRuntime().maxMemory());
    snapshot.put("started", started); snapshot.put("updated", Instant.now().toString());
    Common.writeAtomically(path, JSON.toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    lastWrite = System.nanoTime();
    if (checkOwner && (!stage.equals(loggedStage) || lastWrite - lastLog >= 10_000_000_000L
        || count != null && done == count && done != loggedCompleted)) {
      String work = unit == null ? "" : " — " + done + (count == null ? "" : "/" + count) + " " + unit;
      System.out.println("[progress] " + stage.replace('_', ' ') + work + " — " + stageElapsed + "s in stage; " + elapsed + "s total; CPU " + snapshot.get("process_cpu_seconds") + "s");
      loggedStage = stage; loggedCompleted = done; lastLog = lastWrite;
    }
  }

  private boolean ownsFile() throws Exception {
    return Files.exists(path) && jobId.equals(JsonParser.parseString(Files.readString(path)).getAsJsonObject().get("job_id").getAsString());
  }

  void finish(String state) {
    try {
      if (!ownsFile()) return; // A newer job in this folder owns the displayed status.
      JsonObject snapshot = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
      if (snapshot.has("creation_receipts")) for (var item : snapshot.getAsJsonArray("creation_receipts"))
        item.getAsJsonObject().addProperty("publication", state.equals("COMPLETED") ? "PUBLISHED" : "NOT_CONFIRMED");
      snapshot.addProperty("last_work_stage", snapshot.get("stage").getAsString());
      snapshot.addProperty("elapsed_seconds", secondsSince(started));
      snapshot.addProperty("state", state);
      snapshot.addProperty("stage", state.equals("COMPLETED") ? "done" : state.toLowerCase(Locale.ROOT));
      snapshot.addProperty("updated", Instant.now().toString());
      if (state.equals("COMPLETED") && snapshot.get("emails_total").isJsonNull()) {
        snapshot.addProperty("emails_total", snapshot.get("emails_completed").getAsLong());
        snapshot.addProperty("total_known", true);
      }
      Common.writeAtomically(path, JSON.toJson(snapshot).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Exception error) { System.err.println("warning: Could not finalize conversion progress: " + path); }
  }
}
