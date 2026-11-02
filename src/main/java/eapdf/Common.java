package eapdf;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

class Common {
  static synchronized Path availableArchivePath(Path requested) {
    Path candidate = requested;
    String filename = requested.getFileName().toString();
    String stem = filename.substring(0, filename.length() - 4);
    int copy = 2;
    while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
        || Files.exists(OutputTransaction.readable(candidate), LinkOption.NOFOLLOW_LINKS)) {
      candidate = requested.resolveSibling(stem + "_" + copy++ + ".pdf");
    }
    return candidate;
  }

  static String digestHex(Path path, String algorithm) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance(algorithm);
      try (InputStream input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalArgumentException(exception);
    }
  }
  static final Map<String, Path> BUNDLED_ASSETS = new HashMap<>();
  static final Object LOG_CAPTURE_LOCK = new Object();
  static volatile boolean LOG_CAPTURE_INSTALLED = false;
  static volatile String PROJECT_VERSION = null;
  static final InheritableThreadLocal<PerFileLogCapture> ACTIVE_LOG_CAPTURE = new InheritableThreadLocal<>();
  static final InheritableThreadLocal<JobReport> ACTIVE_JOB = new InheritableThreadLocal<>();

  static JobReport beginJob() {
    return new JobReport();
  }

  static EmailRecord stageRecord(EmailRecord record) throws Exception {
    JobReport report = ACTIVE_JOB.get();
    if (report != null) report.stage(record);
    return record;
  }

  static void stopWorkers(java.util.concurrent.ExecutorService workers) {
    boolean interrupted = Thread.interrupted();
    workers.shutdownNow();
    long started = System.nanoTime();
    long limit = java.util.concurrent.TimeUnit.SECONDS.toNanos(ProcessingLimits.setting(
        "eapdf.workerShutdownSeconds", "EAPDF_WORKER_SHUTDOWN_SECONDS", 5));
    while (!workers.isTerminated()) {
      long remaining = limit - (System.nanoTime() - started);
      if (remaining <= 0) {
        System.err.println("warning: Email workers did not stop within the shutdown limit; abandon this process before another conversion.");
        break;
      }
      try {
        workers.awaitTermination(Math.min(remaining, java.util.concurrent.TimeUnit.SECONDS.toNanos(1)), java.util.concurrent.TimeUnit.NANOSECONDS);
      } catch (InterruptedException exception) {
        interrupted = true;
      }
    }
    if (interrupted) Thread.currentThread().interrupt();
  }

  static java.util.concurrent.ExecutorService newWorkers(int count) {
    var sequence = new java.util.concurrent.atomic.AtomicInteger();
    return java.util.concurrent.Executors.newFixedThreadPool(count, task -> {
      Thread worker = new Thread(task, "eapdf-worker-" + sequence.incrementAndGet());
      worker.setDaemon(true);
      return worker;
    });
  }

  static <Result> Result awaitWorker(java.util.concurrent.Future<Result> future) throws Exception {
    try {
      return future.get(ProcessingLimits.setting("eapdf.workerTimeoutSeconds", "EAPDF_WORKER_TIMEOUT_SECONDS", 300),
          java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException error) {
      future.cancel(true);
      throw Common.fail("Email worker exceeded the processing time limit");
    }
  }

  static String textBody(EmailRecord record) {
    return record.payloadStaged ? storedBodySlice(record.textDataPath, record.textDataOffset, record.textDataLength) : storedBody(record.textBody, record.textDataPath);
  }

  static String htmlBody(EmailRecord record) {
    return record.payloadStaged ? storedBodySlice(record.htmlDataPath, record.htmlDataOffset, record.htmlDataLength) : storedBody(record.htmlBody, record.htmlDataPath);
  }

  static String rtfBody(EmailRecord record) {
    return record.payloadStaged ? storedBodySlice(record.rtfDataPath, record.rtfDataOffset, record.rtfDataLength) : storedBody(record.rtfBody, record.rtfDataPath);
  }

  static String storedBodySlice(Path path, long offset, int length) {
    if (path == null) return "";
    try (var input = PayloadStore.slice(path, offset, length)) {
      byte[] bytes = input.readAllBytes();
      if (bytes.length != length) throw new IOException("Truncated staged email body");
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
  }

  static String storedBody(String value, Path path) {
    if (value != null || path == null) return nz(value);
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  static void inputState(String input, String state) {
    JobReport report = ACTIVE_JOB.get();
    if (report != null) report.inputs.put(input, state);
  }

  static void sourceProblem(EmailRecord record, String message) {
    if (!record.sourceProblems.contains(message)) record.sourceProblems.add(message);
    warn(record, ConversionDiagnostic.Code.SOURCE_INCOMPLETE, "INCOMPLETE SOURCE: " + message);
  }

  static void warn(EmailRecord record, String message) {
    warn(record, ConversionDiagnostic.Code.REVIEW_REQUIRED, message);
  }

  static void warn(EmailRecord record, ConversionDiagnostic.Code code, String message) {
    ConversionDiagnostics.warn(record, code, message);
  }

  static void recordWarnings(EmailRecord record) {
    ConversionDiagnostics.recordWarnings(record);
  }

  static final class JobReport implements AutoCloseable {
    final JobReport previous = ACTIVE_JOB.get();
    final java.util.concurrent.ConcurrentMap<String, String> inputs = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentMap<String, String> warnings = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentMap<String, ConversionDiagnostic> diagnostics = new java.util.concurrent.ConcurrentHashMap<>();
    PayloadStore payloads;
    long metadataSize;
    long warningSize;
    int stagedRecords;
    final java.util.concurrent.atomic.AtomicLong imagePixels = new java.util.concurrent.atomic.AtomicLong();
    final java.util.concurrent.atomic.AtomicLong renderedPages = new java.util.concurrent.atomic.AtomicLong();
    int workerLimit = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

    JobReport() {
      ACTIVE_JOB.set(this);
    }

    synchronized boolean addWarning(String key, String warning, ConversionDiagnostic diagnostic, String diagnosticKey) {
      boolean fresh = !warnings.containsKey(key);
      ConversionDiagnostic previous = diagnostics.get(diagnosticKey);
      long size = fresh ? (key.length() + warning.length()) * 2L + 128 : 0;
      size += (diagnostic.toJson().toString().length()
          - (previous == null ? 0 : previous.toJson().toString().length())) * 2L;
      if (previous == null) size += diagnosticKey.length() * 2L + 128;
      if (metadataSize + warningSize + size > ProcessingLimits.metadataBytes()) throw fail("Export warning metadata exceeds the processing limit of " + ProcessingLimits.metadataBytes() + " bytes");
      warningSize += size;
      warnings.put(key, warning);
      diagnostics.put(diagnosticKey, diagnostic);
      return fresh;
    }

    synchronized void stage(EmailRecord record) throws Exception {
      if (record.payloadStaged) return;
      ProcessingLimits.checkCount(++stagedRecords);
      record.stagedMetadataSize = ProcessingLimits.metadataSize(record);
      metadataSize += record.stagedMetadataSize;
      if (metadataSize + warningSize > ProcessingLimits.metadataBytes()) throw fail("Export metadata exceeds the processing limit of " + ProcessingLimits.metadataBytes() + " bytes");
      if (payloads == null) payloads = new PayloadStore();
      payloads.stage(record);
      ConversionProgress.discovered();
    }

    synchronized void release(EmailRecord record) throws IOException {
      if (record.stagedMetadataSize == 0) return;
      if (payloads != null) payloads.release(record);
      metadataSize -= record.stagedMetadataSize;
      record.stagedMetadataSize = 0;
    }

    @Override
    public void close() {
      long skipped = inputs.values().stream().filter("skipped"::equals).count();
      long completed = inputs.values().stream().filter("completed"::equals).count();
      long failed = inputs.values().stream().filter("started"::equals).count();
      long pending = inputs.values().stream().filter("accepted"::equals).count();
      System.out.println("input summary: accepted: " + (inputs.size() - skipped) + " skipped: " + skipped
          + " completed: " + completed + " failed: " + failed + " not processed: " + pending);
      System.out.println("conversion warnings: " + warnings.size());
      long sourceProblems = warnings.values().stream().filter(w -> w.startsWith("INCOMPLETE SOURCE:")).count();
      if (sourceProblems > 0) System.out.println("INCOMPLETE SOURCE: " + sourceProblems
          + " reported problems. Created PDFs retain available content; this is not a complete-conformance result.");
      warnings.keySet().stream().sorted().forEach(warning -> System.out.println("warning summary: " + warning));
      ConversionProgress.diagnostics(warnings, diagnostics);
      if (payloads != null) {
        try {
          payloads.close();
        } catch (IOException exception) {
          System.err.println("warning: could not remove temporary payload storage: " + exception.getMessage());
        }
      }
      if (previous == null) ACTIVE_JOB.remove();
      else ACTIVE_JOB.set(previous);
    }
  }

  static final class UserFacingException extends RuntimeException {
    UserFacingException(String message) {
      super(message);
    }
  }

  static UserFacingException fail(String s) {
    return new UserFacingException(s);
  }

  static void validateCliArguments(String[] arguments, Set<String> valueOptions, Set<String> flags, boolean allowInput) {
    int inputs = 0;
    for (int index = 0; index < arguments.length; index++) {
      String argument = arguments[index];
      if (flags.contains(argument)) continue;
      if (valueOptions.contains(argument)) {
        if (index + 1 == arguments.length || arguments[index + 1].startsWith("--")
            || valueOptions.contains(arguments[index + 1]) || flags.contains(arguments[index + 1])) {
          throw fail("missing value for " + argument);
        }
        index++;
        continue;
      }
      if (argument.startsWith("-")) throw fail("unknown option: " + argument);
      if (!allowInput || ++inputs > 1) throw fail("unexpected argument: " + argument);
    }
  }

  static int numericOption(String option, String value, int minimum) {
    try {
      int parsed = Integer.parseInt(value);
      if (parsed >= minimum) return parsed;
    } catch (NumberFormatException ignored) {}
    throw fail(option + " must be an integer of at least " + minimum + ": " + value);
  }

  static void validateArchiveOptions(Map<String, Object> options) {
    Object profile = options.get("archive-profile");
    if (profile != null && !Set.of("m", "mi", "c", "ci").contains(profile)) {
      throw fail("unsupported --archive-profile (expected m, mi, c, or ci): " + profile);
    }
    if (options.containsKey("archive-output") && profile == null) throw fail("--archive-output requires --archive-profile");
  }

  static void die(String s) {
    throw fail(s);
  }

  interface AtomicFileWriter {
    void write(Path temporary) throws Exception;
  }

  static void writeAtomically(Path destination, AtomicFileWriter writer) throws Exception {
    Path output = destination.toAbsolutePath();
    Files.createDirectories(output.getParent());
    Path temporary = Files.createTempFile(output.getParent(), "." + output.getFileName() + ".", ".tmp");
    try {
      writer.write(temporary);
      try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException unsupported) {
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static void writeAtomically(Path destination, byte[] bytes) throws Exception {
    writeAtomically(destination, temporary -> Files.write(temporary, bytes));
  }

  static void writePdfAtomically(Path destination, AtomicFileWriter writer) throws Exception {
    writePdfAtomically(destination, writer, null);
  }

  static void writePdfAtomically(Path destination, AtomicFileWriter writer, PreservationInventory expected) throws Exception {
    writePdfAtomically(destination, writer, expected, false);
  }

  static void writeOuterPdfAtomically(Path destination, AtomicFileWriter writer, PreservationInventory expected) throws Exception {
    PdfSigning.preflight();
    writePdfAtomically(destination, writer, expected, true);
  }

  private static void writePdfAtomically(Path destination, AtomicFileWriter writer,
      PreservationInventory expected, boolean outermost) throws Exception {
    OutputTransaction.write(destination, temporary -> {
      ProcessingLimits.checkCancelled();
      writer.write(temporary);
      if (outermost) ConversionProgress.stage("checking");
      PdfCreationValidator.validate(temporary, expected, outermost);
      if (outermost && EapdfConfig.get().signing.enabled) {
        ConversionProgress.stage("signing");
        PdfSigning.signConfigured(temporary);
        ConversionProgress.stage("checking_signature_output");
        PdfCreationValidator.validate(temporary, expected, outermost);
      }
      if (outermost) ConversionProgress.creationChecked(destination, temporary, expected);
      ProcessingLimits.checkCancelled();
    });
  }

  static void exitWithError(String s) {
    System.out.println("error: " + s);
    System.exit(2);
  }

  static void dbg(EmailRecord r, String s) {
    r.debugLog.add(s);
    System.out.println("[debug] " + s);
  }

  static PerFileLogCapture beginPerFileLog(Path pdfOut) {
    ensureLogCaptureInstalled();
    return new PerFileLogCapture(pdfOut);
  }

  static void ensureLogCaptureInstalled() {
    if (LOG_CAPTURE_INSTALLED) return;
    synchronized (LOG_CAPTURE_LOCK) {
      if (LOG_CAPTURE_INSTALLED) return;
      PrintStream originalOut = System.out;
      PrintStream originalErr = System.err;
      System.setOut(new PrintStream(new TeeLoggingOutputStream(originalOut), true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(new TeeLoggingOutputStream(originalErr), true, StandardCharsets.UTF_8));
      LOG_CAPTURE_INSTALLED = true;
    }
  }

  static final class PerFileLogCapture implements AutoCloseable {
    final Path pdfOut;
    final Path logPath;
    final StringBuilder pendingLine = new StringBuilder();
    final Date generatedAt = new Date();
    final Map<String, Integer> warningCounts = new LinkedHashMap<>();
    final Map<String, Integer> errorCounts = new LinkedHashMap<>();
    final Map<String, BlacklistedAssetSummary> blacklistedAssets = new LinkedHashMap<>();

    PerFileLogCapture(Path pdfOut) {
      this.pdfOut = pdfOut;
      Path parent = pdfOut.getParent() == null ? Paths.get("out") : pdfOut.getParent();
      Path logsDir = parent.resolve("logs");
      String filename = pdfOut.getFileName() == null ? "output.log" : pdfOut.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + ".log";
      this.logPath = logsDir.resolve(filename);
      ACTIVE_LOG_CAPTURE.set(this);
    }

    void append(String text) {
      if (text == null || text.isEmpty()) return;
      synchronized (this) {
        pendingLine.append(text);
        int newline;
        while ((newline = indexOfLineBreak(pendingLine)) >= 0) {
          String line = pendingLine.substring(0, newline);
          int consume = newline;
          if (newline < pendingLine.length() && pendingLine.charAt(newline) == '\r') consume++;
          if (consume < pendingLine.length() && pendingLine.charAt(consume) == '\n') consume++;
          else if (newline < pendingLine.length() && pendingLine.charAt(newline) == '\n') consume++;
          pendingLine.delete(0, consume);
          recordLine(line);
        }
      }
    }

    void recordBlacklistedRemoteAsset(String url, String reason, EmailRecord record) {
      String assetUrl = nz(url).trim();
      if (assetUrl.isBlank()) return;
      synchronized (this) {
        String subject = summarizeValue(record == null ? null : record.subject);
        String sourcePath = summarizeValue(record == null ? null : record.sourcePath);
        String key = assetUrl + "\u0000" + subject + "\u0000" + sourcePath;
        BlacklistedAssetSummary summary = blacklistedAssets.computeIfAbsent(key,
            ignored -> new BlacklistedAssetSummary(assetUrl, subject, sourcePath));
        summary.encounters++;
        String trimmedReason = nz(reason).trim();
        if (!trimmedReason.isBlank() && shouldReplaceBlacklistReason(summary.reason, trimmedReason)) {
          summary.reason = trimmedReason;
        }
      }
    }

    @Override
    public void close() {
      ACTIVE_LOG_CAPTURE.remove();
      try {
        Files.createDirectories(logPath.getParent());
        synchronized (this) {
          if (pendingLine.length() > 0) recordLine(pendingLine.toString());
          Files.writeString(logPath, renderSummary(), StandardCharsets.UTF_8);
        }
      } catch (IOException ignored) {
      }
    }

    static int indexOfLineBreak(StringBuilder sb) {
      for (int i = 0; i < sb.length(); i++) {
        char ch = sb.charAt(i);
        if (ch == '\n' || ch == '\r') return i;
      }
      return -1;
    }

    static boolean shouldSkipLogLine(String line) {
      String value = line == null ? "" : line.trim();
      if (value.isEmpty()) return false;
      if (value.startsWith("com.openhtmltopdf.") && value.contains(" INFO::")) return true;
      if (value.startsWith("com.openhtmltopdf.css-parse") && value.contains(" WARNING::")) return true;
      return value.startsWith("ERROR StatusLogger Log4j2 could not find a logging implementation.");
    }

    void recordLine(String line) {
      String value = line == null ? "" : line.trim();
      if (value.isEmpty() || shouldSkipLogLine(value)) return;
      LogSummaryEntry entry = summarizeLogLine(value);
      if (entry == null) return;
      Map<String, Integer> target = entry.error ? errorCounts : warningCounts;
      target.merge(entry.message, 1, Integer::sum);
    }

    String renderSummary() {
      int warningTotal = totalCount(warningCounts);
      int errorTotal = totalCount(errorCounts);
      int blacklistedTotal = 0;
      for (BlacklistedAssetSummary summary : blacklistedAssets.values()) blacklistedTotal += summary.encounters;

      StringBuilder out = new StringBuilder();
      out.append("Output file: ").append(pdfOut).append(System.lineSeparator());
      out.append("Generated: ").append(generatedAt).append(System.lineSeparator()).append(System.lineSeparator());
      out.append("Warnings recorded: ").append(warningTotal).append(System.lineSeparator());
      out.append("Errors recorded: ").append(errorTotal).append(System.lineSeparator());
      out.append("Blacklisted remote asset encounters: ").append(blacklistedTotal).append(System.lineSeparator());
      out.append("Distinct blacklisted remote assets: ").append(blacklistedAssets.size()).append(System.lineSeparator());

      if (warningTotal == 0 && errorTotal == 0 && blacklistedAssets.isEmpty()) {
        out.append(System.lineSeparator()).append("No warnings or errors recorded.").append(System.lineSeparator());
        return out.toString();
      }

      if (!warningCounts.isEmpty()) appendCountSection(out, "Warning Summary", warningCounts);
      if (!errorCounts.isEmpty()) appendCountSection(out, "Error Summary", errorCounts);
      if (!blacklistedAssets.isEmpty()) appendBlacklistedAssetSection(out);
      return out.toString();
    }

    void appendBlacklistedAssetSection(StringBuilder out) {
      out.append(System.lineSeparator()).append("Blacklisted Remote Assets").append(System.lineSeparator());
      for (BlacklistedAssetSummary summary : blacklistedAssets.values()) {
        out.append("- ").append(summary.url).append(" (encounters: ").append(summary.encounters).append(")").append(System.lineSeparator());
        if (!summary.reason.isBlank()) out.append("  reason: ").append(summary.reason).append(System.lineSeparator());
        if (!summary.subject.isBlank()) out.append("  subject: ").append(summary.subject).append(System.lineSeparator());
        if (!summary.sourcePath.isBlank()) out.append("  source: ").append(summary.sourcePath).append(System.lineSeparator());
      }
    }

    static void appendCountSection(StringBuilder out, String title, Map<String, Integer> counts) {
      out.append(System.lineSeparator()).append(title).append(System.lineSeparator());
      for (Map.Entry<String, Integer> entry : counts.entrySet()) {
        out.append("- ").append(entry.getValue()).append("x ").append(entry.getKey()).append(System.lineSeparator());
      }
    }

    static int totalCount(Map<String, Integer> counts) {
      int total = 0;
      for (int value : counts.values()) total += value;
      return total;
    }

    static LogSummaryEntry summarizeLogLine(String line) {
      String value = nz(line).trim();
      if (value.isEmpty() || value.startsWith("[debug]")) return null;

      String extracted = extractSeverityMessage(value, "SEVERE::");
      if (extracted != null) return new LogSummaryEntry(true, extracted);

      extracted = extractSeverityMessage(value, "ERROR::");
      if (extracted != null) return new LogSummaryEntry(true, extracted);

      extracted = extractSeverityMessage(value, "SEVERE:");
      if (extracted != null) return new LogSummaryEntry(true, extracted);

      extracted = extractSeverityMessage(value, "ERROR:");
      if (extracted != null) return new LogSummaryEntry(true, extracted);

      extracted = extractSeverityMessage(value, "WARNING::");
      if (extracted != null) return new LogSummaryEntry(false, extracted);

      extracted = extractSeverityMessage(value, "WARN::");
      if (extracted != null) return new LogSummaryEntry(false, extracted);

      extracted = extractSeverityMessage(value, "WARNING:");
      if (extracted != null) return new LogSummaryEntry(false, extracted);

      extracted = extractSeverityMessage(value, "WARN:");
      if (extracted != null) return new LogSummaryEntry(false, extracted);

      String upper = value.toUpperCase(Locale.ROOT);
      if (upper.startsWith("ERROR ")) return new LogSummaryEntry(true, summarizeValue(value.substring("ERROR ".length())));
      if (upper.startsWith("WARN ")) return new LogSummaryEntry(false, summarizeValue(value.substring("WARN ".length())));
      if (upper.startsWith("EXCEPTION IN THREAD")) return new LogSummaryEntry(true, summarizeValue(value));
      return null;
    }

    static String extractSeverityMessage(String line, String token) {
      String upper = line.toUpperCase(Locale.ROOT);
      String upperToken = token.toUpperCase(Locale.ROOT);
      int idx = upper.indexOf(upperToken);
      if (idx < 0) return null;
      return summarizeValue(line.substring(idx + token.length()));
    }

    static String summarizeValue(String value) {
      String out = nz(value).replaceAll("\\s+", " ").trim();
      return out.length() > 240 ? out.substring(0, 237) + "..." : out;
    }

    static boolean shouldReplaceBlacklistReason(String existing, String replacement) {
      String current = nz(existing).trim();
      String incoming = nz(replacement).trim();
      if (incoming.isBlank()) return false;
      if (current.isBlank()) return true;
      return "previously blacklisted".equalsIgnoreCase(current) && !"previously blacklisted".equalsIgnoreCase(incoming);
    }
  }

  static final class LogSummaryEntry {
    final boolean error;
    final String message;

    LogSummaryEntry(boolean error, String message) {
      this.error = error;
      this.message = message;
    }
  }

  static final class BlacklistedAssetSummary {
    final String url;
    final String subject;
    final String sourcePath;
    String reason = "";
    int encounters = 0;

    BlacklistedAssetSummary(String url, String subject, String sourcePath) {
      this.url = url;
      this.subject = subject;
      this.sourcePath = sourcePath;
    }
  }

  static void recordBlacklistedRemoteAsset(String url, String reason, EmailRecord record) {
    PerFileLogCapture capture = ACTIVE_LOG_CAPTURE.get();
    if (capture != null) capture.recordBlacklistedRemoteAsset(url, reason, record);
  }

  static final class TeeLoggingOutputStream extends OutputStream {
    final PrintStream delegate;

    TeeLoggingOutputStream(PrintStream delegate) {
      this.delegate = delegate;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      PerFileLogCapture capture = ACTIVE_LOG_CAPTURE.get();
      if (capture != null) capture.append(new String(new byte[]{(byte)b}, StandardCharsets.UTF_8));
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      PerFileLogCapture capture = ACTIVE_LOG_CAPTURE.get();
      if (capture != null && b != null && len > 0) {
        capture.append(new String(b, off, len, StandardCharsets.UTF_8));
      }
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }
  }

  static String md5Hex(byte[] b) throws Exception {
    MessageDigest md = MessageDigest.getInstance("MD5");
    byte[] d = md.digest(b);
    char[] hex = "0123456789abcdef".toCharArray();
    char[] out = new char[d.length*2];
    for (int i=0;i<d.length;i++) {
      int v = d[i] & 0xff;
      out[i*2] = hex[v>>>4];
      out[i*2+1] = hex[v & 0x0f];
    }
    return new String(out);
  }

  static String sha256Hex(byte[] b) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] d = md.digest(b);
    char[] hex = "0123456789abcdef".toCharArray();
    char[] out = new char[d.length*2];
    for (int i=0;i<d.length;i++) {
      int v = d[i] & 0xff;
      out[i*2] = hex[v>>>4];
      out[i*2+1] = hex[v & 0x0f];
    }
    return new String(out);
  }

  static byte[] synthesizeEml(EmailRecord record) throws Exception {
    return MimeMessageWriter.synthesizeEml(record);
  }

  static String encodeAddressHeader(String header) throws Exception {
    return MimeMessageWriter.encodeAddressHeader(header);
  }

  static String plainTextToHtml(String s) {
    String t = nz(s).replace("\r\n", "\n").replace("\r", "\n");
    t = escapeHtml(expandTabs(t, new int[1]));
    return "<div class=\"ea-plain-text\" style=\"white-space: pre-wrap;\">" + t + "</div>";
  }

  static String expandTabs(String text, int[] column) {
    StringBuilder out = new StringBuilder(text.length());
    for (int offset = 0; offset < text.length();) {
      int point = text.codePointAt(offset);
      offset += Character.charCount(point);
      if (point == '\t') {
        int spaces = 8 - column[0] % 8;
        out.append(" ".repeat(spaces));
        column[0] += spaces;
      } else {
        out.appendCodePoint(point);
        column[0] = point == '\n' || point == '\r' ? 0 : column[0] + 1;
      }
    }
    return out.toString();
  }

  static String safeFilename(String s){
  String t = nz(s);
  t = stripInvalidXml(t);
  t = t.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+","_");
  t = t.replaceAll("\\s+"," ").trim();
  if(t.isEmpty()) t="message";
  if(t.length()>120) t=t.substring(0,120);
  return t;
}

static String normalizeAttachmentFilename(String filename) {
  return nz(filename).replace("\0", "");
}

static String portablePdfFilename(String filename) {
  String value = normalizeAttachmentFilename(filename).replace('\\', '/');
  value = value.substring(value.lastIndexOf('/') + 1).replace("..", "_");
  value = value.replaceFirst("^[A-Za-z]:", "").replaceFirst("^~", "_");
  value = value.replaceAll("[\\p{Cntrl}]", "_").trim();
  return value.isBlank() || value.equals(".") ? "attachment" : value;
}

static String sourceContentType(AssociatedFile file) {
  return nz(file.rawContentType).isBlank() ? nz(file.mime) : file.rawContentType;
}

static String sourceDerivation(EmailRecord record) {
  if ("pst".equals(record.sourceType)) return "PST preprocessing: exported the selected message to RFC 822 from "
      + nz(record.sourcePath) + "; the original PST container and unexported storage properties are not embedded.";
  if ("mbox".equals(record.sourceType)) {
    String description = "MBOX preprocessing: extracted an RFC 822 message and reversed mailbox quoting from "
        + nz(record.sourcePath) + "; the original mailbox container and envelope are not embedded.";
    if (record.mboxProvenance != null) {
      var provenance = record.mboxProvenance;
      var entry = provenance.entry();
      description += " MBOX inventory: container SHA-256=" + provenance.containerSha256() + "; bytes=" + provenance.containerSize()
          + "; requested format=" + provenance.requestedFormat() + "; entry=" + entry.ordinal() + "/" + provenance.entryCount()
          + "; envelope byte=" + entry.envelopeOffset() + "; raw message byte=" + entry.messageOffset() + "; raw size=" + entry.rawSize()
          + "; raw SHA-256=" + entry.rawSha256() + "; exported size=" + entry.exportedSize() + "; exported SHA-256=" + entry.exportedSha256()
          + "; padding bytes=" + entry.paddingSize() + "; framing=" + entry.framing() + "; quoting=" + entry.quoting() + ".";
    }
    return description;
  }
  return "";
}

static String sourceDescription(EmailRecord record) {
  String derivation = sourceDerivation(record);
  return derivation.isBlank() ? "Source email" : "Exported RFC 822 conversion source. " + derivation;
}

static String collectionFilename(String filename) {
  String value = portablePdfFilename(filename).replaceAll("[\\\\/:*\"<>|]", "_");
  if (value.length() > 200) {
    int dot = value.lastIndexOf('.');
    String extension = dot > 0 && value.length() - dot <= 20 ? value.substring(dot) : "";
    value = value.substring(0, 200 - extension.length()) + extension;
  }
  return value.replaceAll("\\.+$", "_");
}

static String filenameCaseKey(String filename) {
  return filename.toLowerCase(Locale.ROOT).toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
}

static boolean sameContentType(String first, String second) {
  try {
    var expected = new jakarta.mail.internet.ContentType(first);
    var actual = new jakarta.mail.internet.ContentType(second);
    if (!expected.getBaseType().equalsIgnoreCase(actual.getBaseType())) return false;
    var expectedParameters = expected.getParameterList();
    var actualParameters = actual.getParameterList();
    if ((expectedParameters == null ? 0 : expectedParameters.size()) != (actualParameters == null ? 0 : actualParameters.size())) return false;
    if (expectedParameters != null) {
      var names = expectedParameters.getNames();
      while (names.hasMoreElements()) {
        String name = names.nextElement();
        String value = actualParameters.get(name);
        if (value == null || !(name.equalsIgnoreCase("charset")
            ? value.equalsIgnoreCase(expectedParameters.get(name)) : value.equals(expectedParameters.get(name)))) return false;
      }
    }
    return true;
  } catch (jakarta.mail.internet.ParseException error) {
    return false;
  }
}

static void requireCreationDate(EmailRecord record) {
  if (nz(record.sentXmp).isBlank()) {
    Date parsed = parseMessageDate(record.rawDateHeader);
    if (parsed == null) parsed = parseMessageDate(record.date);
    if (parsed != null) record.sentXmp = xmpDate(parsed);
    else {
      String local = sourceLocalDate(record.rawDateHeader);
      if (!local.isEmpty()) {
        record.sentXmp = local;
        record.date = nz(record.rawDateHeader);
      }
    }
  }
  if (!validSentDate(record.sentXmp)) {
    record.sentXmp = "";
    sourceProblem(record, "A verifiable sent date is missing from the source. No sent date was invented.");
    return;
  }
  if (sourceDateNeedsRaw(record.rawDateHeader)) {
    String warning = "Invalid source sent date retained as Raw-Sent; normalized Sent=" + record.sentXmp;
    if (!record.conversionWarnings.contains(warning)) warn(record, warning);
  }
}

static boolean validSentDate(String value) {
  if (!nz(value).matches("(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})?")) return false;
  try { java.time.OffsetDateTime.parse(nz(value)); return true; }
  catch (java.time.format.DateTimeParseException ignored) {}
  try { java.time.LocalDateTime.parse(nz(value)); return true; }
  catch (java.time.format.DateTimeParseException ignored) { return false; }
}

static String sourceLocalDate(String value) {
  // Some real mailing-list exports use ctime without a zone. Preserve the
  // supplied wall-clock time in XMP, whose dateTime permits an omitted zone.
  // Do not assign UTC, the machine's timezone, or a Received timestamp.
  try {
    var format = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.ENGLISH)
        .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    var local = java.time.LocalDateTime.parse(nz(value).strip().replaceAll("\\s+", " "), format);
    return local.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss"));
  } catch (java.time.format.DateTimeParseException ignored) { return ""; }
}

static String normalizeHtml(String s) {
  String t = nz(s);
  t = t.replaceAll("(?is)<!--.*?-->", "");
  return stripInvalidXml(t);
}

static String normalizeBody(String s) {
  return stripInvalidXml(s).replace("\r\n","\n").replace("\r","\n");
}

static String escapeHtml(String s) {
  return stripInvalidXml(nz(s)).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
}

static String stripInvalidXml(String s) {
  String t = nz(s);
  // Only remove complete zero references, not the zero prefix of valid values
  // such as &#039; (apostrophe) or &#x00A3; (pound sign).
  t = t.replaceAll("(?i)&#x0+(?![0-9a-f]);?", "");
  t = t.replaceAll("(?i)&#0+(?![0-9]);?", "");
  StringBuilder out = new StringBuilder(t.length());
  for (int i = 0; i < t.length();) {
    int cp = t.codePointAt(i);
    i += Character.charCount(cp);
    if (cp == 0x9 || cp == 0xA || cp == 0xD || (cp >= 0x20 && cp <= 0xD7FF) || (cp >= 0xE000 && cp <= 0xFFFD) || (cp >= 0x10000 && cp <= 0x10FFFF)) out.appendCodePoint(cp);
  }
  return out.toString();
}

  static String stripHtml(String html) {
    if (html == null) return "";
    String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
    t = t.replaceAll("(?is)<br\\s*/?>", "\n");
    t = t.replaceAll("(?is)</p\\s*>", "\n\n");
    t = t.replaceAll("(?is)</div\\s*>", "\n");
    t = t.replaceAll("(?is)</tr\\s*>", "\n");
    t = t.replaceAll("(?is)</td\\s*>", " ");
    t = t.replaceAll("(?is)<[^>]+>", " ");
    t = org.jsoup.parser.Parser.unescapeEntities(stripInvalidXml(t), false).replace('\u00A0', ' ');
    t = t.replaceAll("[ \\t\\x0B\\f]+"," ");
    t = t.replaceAll(" *\n *","\n");
    return t.trim();
  }

  static String readToString(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    for (int n; (n=in.read(buf))!=-1;) b.write(buf,0,n);
    return b.toString(StandardCharsets.UTF_8);
  }

  static byte[] readAllBytes(InputStream in) throws IOException {
    return ProcessingLimits.read(in, "email attachment");
  }

  static synchronized Path bundledAssetPath(String resourceName, String suffix) {
    Path cached = BUNDLED_ASSETS.get(resourceName);
    if (cached != null && Files.exists(cached)) return cached;
    try (InputStream in = Common.class.getClassLoader().getResourceAsStream(resourceName)) {
      if (in == null) return null;
      String safeSuffix = (suffix == null || suffix.isBlank()) ? ".bin" : suffix;
      Path tmp = Files.createTempFile("emailtopdfa-", safeSuffix);
      Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
      tmp.toFile().deleteOnExit();
      BUNDLED_ASSETS.put(resourceName, tmp);
      return tmp;
    } catch (IOException e) {
      return null;
    }
  }

  static String resolveFontPath(String fontPath) {
    if (fontPath != null && !fontPath.isBlank()) return fontPath;
    Path assetFont = resolveExistingPath("assets/LiberationSans-Regular.ttf");
    if (assetFont != null) return assetFont.toString();
    Path bundled = bundledAssetPath("assets/DejaVuSans.ttf", ".ttf");
    if (bundled != null) return bundled.toString();
    throw fail("missing --font /path/to/font.ttf and no bundled font found");
  }

  static String resolveIccPath(String iccPath) {
    if (iccPath != null && !iccPath.isBlank()) return iccPath;
    Path bundled = bundledAssetPath("assets/sRGB.icc", ".icc");
    if (bundled != null) return bundled.toString();
    throw fail("missing --icc /path/to/sRGB.icc and no bundled ICC profile found");
  }

  static String bundledLogoUri() {
    Path bundled = bundledAssetPath("assets/thinkbox_logo.jpg", ".jpg");
    return bundled == null ? "" : bundled.toUri().toString();
  }

  static String bundledFooterUri() {
    Path optimized = bundledAssetPath("assets/thinkbox_footer_optimized.png", ".png");
    if (optimized != null) return optimized.toUri().toString();
    Path bundled = bundledAssetPath("assets/thinkbox_footer.png", ".png");
    return bundled == null ? "" : bundled.toUri().toString();
  }

  static Path resolveExistingPath(String... candidates) {
    if (candidates == null) return null;
    for (String candidate : candidates) {
      if (candidate == null || candidate.isBlank()) continue;
      try {
        Path path = Paths.get(candidate);
        if (Files.exists(path)) return path;
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  static String resolveCredentialsPath(String credentialsPath) {
    if (credentialsPath != null && !credentialsPath.isBlank()) return credentialsPath;
    Path resolved = resolveExistingPath("config/credentials.json", "credentials.json");
    return resolved == null ? "config/credentials.json" : resolved.toString();
  }

  static String projectVersion() {
    String cached = PROJECT_VERSION;
    if (cached != null) return cached;
    synchronized (Common.class) {
      if (PROJECT_VERSION != null) return PROJECT_VERSION;
      try (InputStream resource = Common.class.getResourceAsStream("/eapdf-build.properties")) {
        if (resource != null) {
          Properties metadata = new Properties();
          metadata.load(resource);
          String version = metadata.getProperty("version", "").trim();
          if (!version.isBlank() && !version.contains("${")) {
            PROJECT_VERSION = version;
            return PROJECT_VERSION;
          }
        }
      } catch (IOException ignored) {}
      try {
        Path versionFile = resolveExistingPath("VERSION");
        if (versionFile != null) {
          String value = Files.readString(versionFile, StandardCharsets.UTF_8).trim();
          if (!value.isBlank()) {
            PROJECT_VERSION = value;
            return PROJECT_VERSION;
          }
        }
      } catch (Exception ignored) {
      }
      PROJECT_VERSION = "dev";
      return PROJECT_VERSION;
    }
  }

  static String productLabel() {
    return "EmailToPDFA " + projectVersion();
  }

  static String header1(MimeMessage mm, String name) throws MessagingException {
    String[] h = mm.getHeader(name);
    if (h == null || h.length == 0) return "";
    return nz(h[0]);
  }

  static String header1(Part p, String name) throws MessagingException {
    String[] h = p.getHeader(name);
    if (h == null || h.length == 0) return "";
    return nz(h[0]);
  }

  static String addrList(Address[] a) {
    if (a == null || a.length == 0) return "";
    List<String> out = new ArrayList<>();
    for (Address x : a) {
      if (x instanceof InternetAddress ia) out.add(joinAddr(nz(ia.getPersonal()), nz(ia.getAddress())));
      else out.add(nz(x.toString()));
    }
    return String.join(", ", out);
  }

  static Address[] parseAddressesLenient(String headerValue) {
    String raw = nz(headerValue).trim();
    if (raw.isBlank()) return new Address[0];
    try {
      return InternetAddress.parseHeader(raw, false);
    } catch (AddressException e) {
      try {
        return InternetAddress.parse(raw, false);
      } catch (AddressException ignored) {
        return new Address[0];
      }
    }
  }

  static String addrListLenient(String headerValue) {
    return addrList(parseAddressesLenient(headerValue));
  }

  static String joinAddr(String name, String email) {
    name = nz(name).trim();
    email = nz(email).trim();
    if (!name.isEmpty() && !email.isEmpty()) return name + " <" + email + ">";
    if (!email.isEmpty()) return email;
    return name;
  }

  static String fmtDate(Date d) {
    if (d == null) return "";
    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z").format(d);
  }

  static String rfc822Date(Date d) {
    if (d == null) return "";
    SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    return f.format(d);
  }

  static String xmpDate(Date d) {
    if (d == null) return "";
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX");
    return f.format(d);
  }

  static List<String> headerValues(Part part, String name) throws MessagingException {
    String[] values = part.getHeader(name);
    return values == null ? List.of() : Arrays.asList(values);
  }

  private static final java.util.regex.Pattern SOURCE_EMAIL_DATE = java.util.regex.Pattern.compile(
      "(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),?\\s+)?[0-9]{1,2}\\s+"
      + "(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+(?:[0-9]{4}|[0-9]{2})\\s+"
      + "[0-9]{2}:[0-9]{2}(?::[0-9]{2})?\\s+(?:[+-][0-9]{4}|UT|UTC|GMT|EST|EDT|CST|CDT|MST|MDT|PST|PDT)",
      java.util.regex.Pattern.CASE_INSENSITIVE);

  static boolean sourceDateNeedsRaw(String value) {
    String text = nz(value).strip();
    if (text.isEmpty()) return false;
    if (parseMessageDate(text) == null) return true;
    for (int depth = 0; depth < 32; depth++) {
      String reduced = text.replaceAll("\\([^()]*\\)", " ");
      if (reduced.equals(text)) break;
      text = reduced;
    }
    text = text.strip().replaceAll("\\s+", " ");
    // Recovering a date with a permissive mail parser does not make its original spelling valid.
    return !validSentDate(text) && !SOURCE_EMAIL_DATE.matcher(text).matches();
  }

  static Date parseMessageDate(String value) {
    String text = nz(value).trim();
    if (text.isBlank()) return null;
    try {
      return Date.from(java.time.OffsetDateTime.parse(text).toInstant());
    } catch (java.time.format.DateTimeParseException ignored) {}
    // RFC 5322 section 4.3 defines the century for legacy two-digit email years.
    var shortYear = java.util.regex.Pattern.compile("(?i)\\b(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+([0-9]{2})(?=\\s+[0-9]{1,2}:)").matcher(text);
    if (shortYear.find()) {
      int year = Integer.parseInt(shortYear.group(1));
      text = text.substring(0, shortYear.start(1)) + (year + (year < 50 ? 2000 : 1900)) + text.substring(shortYear.end(1));
    }
    try {
      return Date.from(java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
          .withResolverStyle(java.time.format.ResolverStyle.STRICT)).toInstant());
    } catch (java.time.format.DateTimeParseException ignored) {}
    MailDateFormat format = new MailDateFormat();
    format.setLenient(false);
    java.text.ParsePosition position = new java.text.ParsePosition(0);
    Date parsed = format.parse(text, position);
    if (parsed == null || position.getIndex() == text.length()) return parsed;
    // MailDateFormat stops after the zone; RFC 5322 permits trailing CFWS.
    // Accept only whitespace and balanced comments, never unparsed date text.
    try {
      HeaderTokenizer trailing = new HeaderTokenizer(text.substring(position.getIndex()), HeaderTokenizer.RFC822);
      return trailing.next().getType() == HeaderTokenizer.Token.EOF ? parsed : null;
    } catch (jakarta.mail.internet.ParseException invalidComment) {
      return null;
    }
  }

  static java.time.Instant messageInstant(EmailRecord record) {
    if (record != null) {
      for (String value : List.of(nz(record.sentXmp), nz(record.rawDateHeader), nz(record.date))) {
        Date parsed = parseMessageDate(value);
        if (parsed != null) return parsed.toInstant();
      }
    }
    return java.time.Instant.MAX;
  }

  static String decodeMimeWord(String s) {
    try { return s == null ? "" : MimeUtility.decodeText(s); }
    catch (Exception e) { return nz(s); }
  }

  static String baseMimeType(String s) {
    String t = nz(s).trim().toLowerCase(Locale.ROOT);
    int i = t.indexOf(';');
    return i >= 0 ? t.substring(0, i).trim() : t;
  }

  static boolean isNonEmailOutlookItem(String itemClass) {
    String value = stripInvalidXml(nz(itemClass)).trim().toLowerCase(Locale.ROOT);
    for (String nonEmail : List.of("ipm.contact", "ipm.distlist", "ipm.appointment", "ipm.task", "ipm.stickynote", "ipm.activity")) {
      if (value.equals(nonEmail) || value.startsWith(nonEmail + ".")) return true;
    }
    return false;
  }

  static String detectMimeType(String filename, String declaredMime, byte[] bytes) {
    String m = nz(declaredMime).trim().toLowerCase(Locale.ROOT);
    if (m.equals("image/png") || m.equals("image/jpeg") || m.equals("image/gif") || m.equals("image/webp") || m.equals("image/bmp") || m.equals("image/svg+xml") || m.equals("message/rfc822") || m.equals("application/vnd.ms-outlook")) return m;
    if (m.equals("application/octet-stream") || m.equals("application/octetstream") || m.isBlank()) {
      String sniffed = sniffMime(bytes);
      if (!sniffed.isBlank()) return sniffed;
      String byName = mimeFromFilename(filename);
      if (!byName.isBlank()) return byName;
      return "application/octet-stream";
    }
    return m;
  }

  static String sniffMime(byte[] b) {
    if (b == null || b.length < 4) return "";
    if (b.length >= 8 &&
      (b[0] & 0xff) == 0x89 && b[1] == 0x50 && b[2] == 0x4e && b[3] == 0x47 &&
      b[4] == 0x0d && b[5] == 0x0a && b[6] == 0x1a && b[7] == 0x0a) return "image/png";
    if ((b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) return "image/jpeg";
    if (b.length >= 6) {
      String s6 = new String(b, 0, 6, StandardCharsets.ISO_8859_1);
      if ("GIF87a".equals(s6) || "GIF89a".equals(s6)) return "image/gif";
    }
    if (b.length >= 12) {
      String riff = new String(b, 0, 4, StandardCharsets.ISO_8859_1);
      String webp = new String(b, 8, 4, StandardCharsets.ISO_8859_1);
      if ("RIFF".equals(riff) && "WEBP".equals(webp)) return "image/webp";
    }
    if (b.length >= 2 && b[0] == 'B' && b[1] == 'M') return "image/bmp";
    if (b.length >= 5) {
      String s = new String(b, 0, Math.min(b.length, 256), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
      if (s.contains("<svg")) return "image/svg+xml";
      if (s.contains("mime-version:") || s.contains("message-id:") || s.contains("subject:")) return "message/rfc822";
    }
    return "";
  }

  static String mimeFromFilename(String filename) {
    String f = nz(filename).trim().toLowerCase(Locale.ROOT);
    if (f.endsWith(".png")) return "image/png";
    if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
    if (f.endsWith(".gif")) return "image/gif";
    if (f.endsWith(".webp")) return "image/webp";
    if (f.endsWith(".bmp")) return "image/bmp";
    if (f.endsWith(".svg")) return "image/svg+xml";
    if (f.endsWith(".pdf")) return "application/pdf";
    if (f.endsWith(".txt")) return "text/plain";
    if (f.endsWith(".html") || f.endsWith(".htm")) return "text/html";
    if (f.endsWith(".eml")) return "message/rfc822";
    if (f.endsWith(".msg")) return "application/vnd.ms-outlook";
    if (f.endsWith(".mbox")) return "application/mbox";
    return "";
  }

  static String normalizeCidKey(String s) {
    String t = nz(s).trim();
    if (t.regionMatches(true, 0, "cid:", 0, 4)) t = t.substring(4).trim();
    if (t.startsWith("<") && t.endsWith(">") && t.length() > 2) t = t.substring(1, t.length() - 1).trim();
    return t;
  }

  static String cidUrlKey(String s) {
    String key = normalizeCidKey(s);
    try {
      // A CID URL is not form data: a literal plus remains a plus.
      return normalizeCidKey(java.net.URLDecoder.decode(key.replace("+", "%2B"), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException malformedEscape) {
      return key;
    }
  }

  static String defaultExtensionName(String mime, String stem) {
    String m = nz(mime).toLowerCase(Locale.ROOT);
    if (m.equals("image/png")) return stem + ".png";
    if (m.equals("image/jpeg")) return stem + ".jpg";
    if (m.equals("image/gif")) return stem + ".gif";
    if (m.equals("image/webp")) return stem + ".webp";
    if (m.equals("image/bmp")) return stem + ".bmp";
    if (m.equals("image/svg+xml")) return stem + ".svg";
    if (m.equals("application/pdf")) return stem + ".pdf";
    if (m.equals("text/plain")) return stem + ".txt";
    if (m.equals("text/html")) return stem + ".html";
    if (m.equals("message/rfc822")) return stem + ".eml";
    if (m.equals("application/vnd.ms-outlook")) return stem + ".msg";
    return stem + ".bin";
  }

  static String extensionForMime(String mime, String filename) {
    String byName = mimeFromFilename(filename);
    if (!byName.isBlank()) {
      if (byName.equals("image/png")) return ".png";
      if (byName.equals("image/jpeg")) return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".jpeg") ? ".jpeg" : ".jpg";
      if (byName.equals("image/gif")) return ".gif";
      if (byName.equals("image/webp")) return ".webp";
      if (byName.equals("image/bmp")) return ".bmp";
      if (byName.equals("image/svg+xml")) return ".svg";
      if (byName.equals("application/pdf")) return ".pdf";
      if (byName.equals("text/plain")) return ".txt";
      if (byName.equals("text/html")) return ".html";
      if (byName.equals("message/rfc822")) return ".eml";
      if (byName.equals("application/vnd.ms-outlook")) return ".msg";
    }
    String m = nz(mime).toLowerCase(Locale.ROOT);
    if (m.equals("image/png")) return ".png";
    if (m.equals("image/jpeg")) return ".jpg";
    if (m.equals("image/gif")) return ".gif";
    if (m.equals("image/webp")) return ".webp";
    if (m.equals("image/bmp")) return ".bmp";
    if (m.equals("image/svg+xml")) return ".svg";
    if (m.equals("application/pdf")) return ".pdf";
    if (m.equals("text/plain")) return ".txt";
    if (m.equals("text/html")) return ".html";
    if (m.equals("message/rfc822")) return ".eml";
    if (m.equals("application/vnd.ms-outlook")) return ".msg";
    return ".bin";
  }

  static void deleteTree(Path dir) throws IOException {
    if (dir == null || !Files.exists(dir)) return;
    try (var walk = Files.walk(dir)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> {
        try { Files.deleteIfExists(p); } catch (IOException e) { throw new UncheckedIOException(e); }
      });
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }

  static String preview(String s, int n) {
    String t = nz(s).replace("\r", " ").replace("\n", " ");
    if (t.length() <= n) return t;
    return t.substring(0, n);
  }

  static String nz(String s) { return s == null ? "" : s; }

  static String safeFileStem(String s) {
    String t = nz(s).trim();
    if (t.isEmpty()) t = "message";
    t = t.replaceAll("[\\\\/:*?\"<>|]+", "_");
    t = t.replaceAll("\\s+", " ").trim();
    // Bulk names combine two stems and a sequence suffix. Bound UTF-8 bytes
    // as well as characters so ordinary international names fit a filesystem
    // component, and never split a supplementary Unicode character.
    int end = 0;
    int bytes = 0;
    while (end < t.length()) {
      int point = t.codePointAt(end);
      int characters = Character.charCount(point);
      int encodedBytes = point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4;
      if (end + characters > 80 || bytes + encodedBytes > 120) break;
      end += characters;
      bytes += encodedBytes;
    }
    t = t.substring(0, end).trim();
    if (t.isEmpty()) t = "message";
    return t;
  }

  static String safeCollectionLabel(String s, String fallback) {
    String label = nz(s).trim();
    if (label.isEmpty()) label = nz(fallback).trim();
    if (label.isEmpty()) label = "Collection";
    label = label.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]+", "_");
    label = label.replaceAll("\\s+", " ").trim();
    while (label.endsWith(".")) label = label.substring(0, label.length() - 1).trim();
    if (label.length() > 120) label = label.substring(0, 120).trim();
    while (label.endsWith(".")) label = label.substring(0, label.length() - 1).trim();
    if (label.isEmpty()) label = nz(fallback).trim();
    if (label.isEmpty()) label = "Collection";
    return label;
  }

  static String xmpDateNowUtc() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }

  static String xml(String s) {
    return stripInvalidXml(nz(s)).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
  }

  static String xmlAttr(String s) {
    return stripInvalidXml(nz(s)).replace("&","&amp;").replace("<","&lt;").replace("\"","&quot;");
  }

  static MailAgent agent(String name, String email) {
    MailAgent a = new MailAgent();
    a.name = nz(name).trim();
    a.email = nz(email).trim();
    return a;
  }

  static void addAgent(List<MailAgent> out, String name, String email) {
    MailAgent a = agent(name, email);
    if (a.name.isBlank() && a.email.isBlank()) return;
    out.add(a);
  }

  static List<MailAgent> internetAddressList(Address[] addrs) {
    List<MailAgent> out = new ArrayList<>();
    if (addrs == null) return out;
    for (Address x : addrs) {
      if (x instanceof InternetAddress ia && ia.isGroup()) {
        try {
          InternetAddress[] members = ia.getGroup(false);
          if (members != null) for (InternetAddress member : members) addAgent(out, nz(member.getPersonal()), nz(member.getAddress()));
        } catch (AddressException malformedGroup) {
          addAgent(out, nz(ia.getPersonal()), nz(ia.getAddress()));
        }
      } else if (x instanceof InternetAddress ia) addAgent(out, nz(ia.getPersonal()), nz(ia.getAddress()));
      else addAgent(out, "", nz(x == null ? "" : x.toString()));
    }
    return out;
  }

  static List<MailAgent> internetAddressListLenient(String headerValue) {
    return internetAddressList(parseAddressesLenient(headerValue));
  }

  static String joinAgents(List<MailAgent> agents) {
    if (agents == null || agents.isEmpty()) return "";
    List<String> out = new ArrayList<>();
    for (MailAgent a : agents) out.add(joinAddr(nz(a.name), nz(a.email)));
    return String.join(", ", out);
  }

  static String displayAddressHeader(String value, List<MailAgent> agents) {
    String original = nz(value);
    if (agents == null || agents.isEmpty() || !original.equals(joinAgents(agents))) return original;
    List<String> display = new ArrayList<>();
    for (MailAgent agent : agents) {
      String name = nz(agent.name).trim();
      String email = nz(agent.email).trim();
      display.add(!email.isBlank() && name.equals(email) ? email : joinAddr(name, email));
    }
    return String.join(", ", display);
  }

  static String rfc822AddrList(List<MailAgent> agents) {
    if (agents == null || agents.isEmpty()) return "";
    List<String> out = new ArrayList<>();
    for (MailAgent a : agents) {
      try {
        InternetAddress ia = new InternetAddress(nz(a.email));
        if (!nz(a.name).isBlank()) ia.setPersonal(nz(a.name), StandardCharsets.UTF_8.name());
        out.add(ia.toUnicodeString());
      } catch (Exception e) {
        out.add(joinAddr(nz(a.name), nz(a.email)));
      }
    }
    return String.join(", ", out);
  }

  static String mailto(String email) {
    String e = nz(email).trim();
    if (e.isBlank()) return "";
    if (e.regionMatches(true, 0, "mailto:", 0, 7)) return e;
    // RFC 6068 section 2: encode raw mailbox octets once. A literal percent
    // sign (including one followed by hex digits) belongs to the address.
    StringBuilder uri = new StringBuilder("mailto:");
    for (byte octet : e.getBytes(StandardCharsets.UTF_8)) {
      int c = octet & 255;
      if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
          || "-._~!$'()*+:@".indexOf(c) >= 0) uri.append((char) c);
      else uri.append('%').append("0123456789ABCDEF".charAt(c >>> 4)).append("0123456789ABCDEF".charAt(c & 15));
    }
    return uri.toString();
  }

  static boolean smtpMailbox(String email) {
    String value = nz(email);
    if (value.regionMatches(true, 0, "mailto:", 0, 7)) value = value.substring(7);
    if (!value.contains("@") || value.contains("\r") || value.contains("\n")) return false;
    try {
      InternetAddress parsed = new InternetAddress(value, true);
      parsed.validate();
      return !parsed.isGroup() && value.equals(parsed.getAddress());
    } catch (jakarta.mail.internet.AddressException error) {
      return false;
    }
  }

  static String contentSetSubtype(EmailRecord r) {
    if (!nz(r.bodyMime).isBlank()) return r.bodyMime;
    return !htmlBody(r).isBlank() ? "text/html" : "text/plain";
  }

  static void finalizeRecordMetadata(EmailRecord r) throws Exception {
    if (r == null) return;
    for (String header : new String[]{r.subject, r.rawFrom, r.rawTo, r.rawCc, r.rawBcc, r.rawReplyTo, r.rawDateHeader, r.messageId}) {
      if (!nz(header).equals(stripInvalidXml(header))) {
        sourceProblem(r, "The original email has unsupported header characters. They are omitted from the displayed headers and metadata.");
        break;
      }
    }
    r.messageId = stripInvalidXml(nz(r.messageId)).trim();
    r.archiveGlobalId = stripInvalidXml(nz(r.archiveGlobalId)).trim();
    r.mailGuid = stripInvalidXml(nz(r.mailGuid)).trim();
    if (nz(r.from).isBlank()) r.from = joinAgents(r.fromAgents);
    if (nz(r.to).isBlank()) r.to = joinAgents(r.toAgents);
    if (nz(r.cc).isBlank()) r.cc = joinAgents(r.ccAgents);
    if (nz(r.bcc).isBlank()) r.bcc = joinAgents(r.bccAgents);
    if (nz(r.rawFrom).isBlank()) r.rawFrom = nz(r.from);
    if (nz(r.rawTo).isBlank()) r.rawTo = nz(r.to);
    if (nz(r.rawCc).isBlank()) r.rawCc = nz(r.cc);
    if (nz(r.rawBcc).isBlank()) r.rawBcc = nz(r.bcc);
    if (nz(r.contentSetDescription).isBlank()) r.contentSetDescription = "Message body";
    int count = 0;
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null) continue;
      af.filename = normalizeAttachmentFilename(af.filename);
      if (af.sourceSetMember && af.hasData()) {
        af.ensureChecksums();
      }
      if (af.emailAttachment) count++;
    }
    r.attachmentCount = count;
    if (nz(r.mailGuid).isBlank()) {
      r.mailGuid = "uuid:" + UUID.randomUUID();
    }
  }

}
