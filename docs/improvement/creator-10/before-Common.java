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
    return storedBody(record.textBody, record.textDataPath);
  }

  static String htmlBody(EmailRecord record) {
    return storedBody(record.htmlBody, record.htmlDataPath);
  }

  static String rtfBody(EmailRecord record) {
    return storedBody(record.rtfBody, record.rtfDataPath);
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

  static void warn(EmailRecord record, String message) {
    if (record != null) {
      if (record.conversionWarnings.contains(message)) return;
      record.conversionWarnings.add(message);
    } else {
      JobReport report = ACTIVE_JOB.get();
      PerFileLogCapture capture = ACTIVE_LOG_CAPTURE.get();
      String key = (capture == null ? "generated text" : capture.pdfOut.toString()) + " | " + message;
      if (report != null && !report.addWarning(key, message)) return;
    }
    System.err.println("warning: " + message);
    recordWarnings(record);
  }

  static void recordWarnings(EmailRecord record) {
    JobReport report = ACTIVE_JOB.get();
    if (report == null || record == null) return;
    for (String warning : record.conversionWarnings) {
      String identity = nz(record.sourcePath).length() > 240 || nz(record.subject).length() > 240 ? nz(record.mailGuid) + " | " : "";
      report.addWarning(preview(record.sourcePath, 240) + " | " + preview(record.subject, 240) + " | " + identity + warning, warning);
    }
  }

  static final class JobReport implements AutoCloseable {
    final JobReport previous = ACTIVE_JOB.get();
    final java.util.concurrent.ConcurrentMap<String, String> inputs = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.concurrent.ConcurrentMap<String, String> warnings = new java.util.concurrent.ConcurrentHashMap<>();
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

    synchronized boolean addWarning(String key, String warning) {
      if (warnings.containsKey(key)) return false;
      long size = (key.length() + warning.length()) * 2L + 128;
      if (metadataSize + warningSize + size > ProcessingLimits.metadataBytes()) throw fail("Export warning metadata exceeds the processing limit of " + ProcessingLimits.metadataBytes() + " bytes");
      warningSize += size;
      warnings.put(key, warning);
      return true;
    }

    synchronized void stage(EmailRecord record) throws Exception {
      if (record.payloadStaged) return;
      ProcessingLimits.checkCount(++stagedRecords);
      metadataSize += ProcessingLimits.metadataSize(record);
      if (metadataSize + warningSize > ProcessingLimits.metadataBytes()) throw fail("Export metadata exceeds the processing limit of " + ProcessingLimits.metadataBytes() + " bytes");
      if (payloads == null) payloads = new PayloadStore();
      payloads.stage(record);
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
      warnings.keySet().stream().sorted().forEach(warning -> System.out.println("warning summary: " + warning));
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
    OutputTransaction.write(destination, temporary -> {
      ProcessingLimits.checkCancelled();
      writer.write(temporary);
      PdfCreationValidator.validate(temporary, expected);
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

  static byte[] synthesizeEml(EmailRecord r) throws Exception {
    StringBuilder sb = new StringBuilder();
    String rawFrom = !nz(r.rawFrom).isBlank() ? nz(r.rawFrom) : nz(r.from);
    String rawTo = !nz(r.rawTo).isBlank() ? nz(r.rawTo) : nz(r.to);
    String rawCc = !nz(r.rawCc).isBlank() ? nz(r.rawCc) : nz(r.cc);
    String rawBcc = !nz(r.rawBcc).isBlank() ? nz(r.rawBcc) : nz(r.bcc);
    if (!rawFrom.isBlank()) sb.append("From: ").append(encodeAddressHeader(rawFrom)).append("\r\n");
    if (!rawTo.isBlank()) sb.append("To: ").append(encodeAddressHeader(rawTo)).append("\r\n");
    if (!rawCc.isBlank()) sb.append("Cc: ").append(encodeAddressHeader(rawCc)).append("\r\n");
    if (!rawBcc.isBlank()) sb.append("Bcc: ").append(encodeAddressHeader(rawBcc)).append("\r\n");
    if (!nz(r.subject).isBlank()) sb.append("Subject: ").append(MimeUtility.encodeText(r.subject, "UTF-8", null)).append("\r\n");
    if (!nz(r.rawDateHeader).isBlank()) sb.append("Date: ").append(nz(r.rawDateHeader)).append("\r\n");
    else if (!nz(r.date).isBlank()) sb.append("Date: ").append(nz(r.date)).append("\r\n");
    if (!nz(r.messageId).isBlank()) sb.append("Message-ID: ").append(nz(r.messageId)).append("\r\n");
    for (String value : r.inReplyTo) sb.append("In-Reply-To: ").append(value).append("\r\n");
    for (String value : r.references) sb.append("References: ").append(value).append("\r\n");
    sb.append("MIME-Version: 1.0\r\n");
    List<AssociatedFile> attachments = new ArrayList<>();
    List<AssociatedFile> inlineFiles = new ArrayList<>();
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null || !af.hasData()) continue;
      if ("Source".equals(nz(af.afRelationship))) continue;
      if (af.inline) inlineFiles.add(af);
      else attachments.add(af);
    }
    boolean hasHtml = !htmlBody(r).isBlank();
    boolean hasAttachments = !attachments.isEmpty();
    if (!hasHtml && !hasAttachments && inlineFiles.isEmpty()) {
      sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: 8bit\r\n");
      sb.append("\r\n");
      sb.append(textBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
      sb.append("\r\n");
      return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    String mixedBoundary = stableMimeBoundary("Mixed", r);
    String altBoundary = stableMimeBoundary("Alt", r);
    String relatedBoundary = stableMimeBoundary("Related", r);
    if (hasAttachments) {
      sb.append("Content-Type: multipart/mixed; boundary=\"").append(mixedBoundary).append("\"\r\n\r\n");
      if (inlineFiles.isEmpty()) {
        appendTextOrAltPart(sb, r, mixedBoundary, altBoundary, hasHtml);
      } else {
        sb.append("--").append(mixedBoundary).append("\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(relatedBoundary).append("\"\r\n\r\n");
        appendRelatedParts(sb, r, relatedBoundary, altBoundary, hasHtml, inlineFiles);
      }
      for (AssociatedFile af : attachments) appendAttachmentPart(sb, mixedBoundary, af);
      sb.append("--").append(mixedBoundary).append("--\r\n");
    } else if (!inlineFiles.isEmpty()) {
      sb.append("Content-Type: multipart/related; boundary=\"").append(relatedBoundary).append("\"\r\n\r\n");
      appendRelatedParts(sb, r, relatedBoundary, altBoundary, hasHtml, inlineFiles);
    } else {
      sb.append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n");
      appendTextAndHtml(sb, r, altBoundary, hasHtml);
      sb.append("--").append(altBoundary).append("--\r\n");
    }
    return sb.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String encodeAddressHeader(String rawHeader) throws Exception {
    if (rawHeader.chars().allMatch(character -> character < 128)) return rawHeader;
    InternetAddress[] addresses = InternetAddress.parseHeader(rawHeader, false);
    for (InternetAddress address : addresses) {
      if (address.getPersonal() != null) address.setPersonal(address.getPersonal(), "UTF-8");
    }
    return InternetAddress.toString(addresses);
  }

  static void appendRelatedParts(StringBuilder builder, EmailRecord record, String relatedBoundary, String altBoundary,
                                 boolean hasHtml, List<AssociatedFile> inlineFiles) throws Exception {
    appendTextOrAltPart(builder, record, relatedBoundary, altBoundary, hasHtml);
    for (AssociatedFile inlineFile : inlineFiles) appendAttachmentPart(builder, relatedBoundary, inlineFile);
    builder.append("--").append(relatedBoundary).append("--\r\n");
  }

  static String stableMimeBoundary(String prefix, EmailRecord r) {
    StringBuilder key = new StringBuilder();
    key.append(prefix).append('|');
    key.append(nz(r.messageId)).append('|');
    key.append(nz(r.subject)).append('|');
    key.append(nz(r.rawDateHeader)).append('|');
    key.append(nz(r.from)).append('|');
    key.append(nz(r.to)).append('|');
    key.append(nz(r.cc)).append('|');
    key.append(nz(r.bcc)).append('|');
    key.append(textBody(r)).append('|');
    key.append(htmlBody(r)).append('|');
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null || !af.hasData() || af.size() == 0) continue;
      if ("Source".equals(nz(af.afRelationship))) continue;
      key.append(nz(af.filename)).append(':').append(af.size()).append(':').append(nz(af.mime)).append('|');
    }
    int hash = key.toString().hashCode();
    return "=_" + prefix + "_" + Integer.toUnsignedString(hash, 16);
  }

  static void appendTextOrAltPart(StringBuilder sb, EmailRecord r, String mixedBoundary, String altBoundary, boolean hasHtml) {
    sb.append("--").append(mixedBoundary).append("\r\n");
    if (hasHtml) {
      sb.append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n");
      appendTextAndHtml(sb, r, altBoundary, true);
      sb.append("--").append(altBoundary).append("--\r\n");
    } else {
      sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
      sb.append(textBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
      sb.append("\r\n");
    }
  }

  static void appendTextAndHtml(StringBuilder sb, EmailRecord r, String altBoundary, boolean hasHtml) {
    sb.append("--").append(altBoundary).append("\r\n");
    sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
    sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
    sb.append(textBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
    sb.append("\r\n");
    if (hasHtml) {
      sb.append("--").append(altBoundary).append("\r\n");
      String rtf = rtfBody(r);
      if (!rtf.isBlank()) {
        sb.append("Content-Type: text/rtf; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(rtf.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        sb.append("--").append(altBoundary).append("\r\n");
      }
      sb.append("Content-Type: text/html; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
      sb.append(htmlBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
      sb.append("\r\n");
    }
  }

  static void appendAttachmentPart(StringBuilder sb, String mixedBoundary, AssociatedFile af) throws Exception {
    sb.append("--").append(mixedBoundary).append("\r\n");
    String originalContentType = sourceContentType(af);
    ContentType contentType = new ContentType(originalContentType.isBlank() ? "application/octet-stream" : originalContentType);
    if (contentType.getParameterList() == null) contentType.setParameterList(new ParameterList());
    ParameterList dispositionParameters = new ParameterList();
    if (!nz(af.filename).isBlank()) {
      dispositionParameters.set("filename", af.filename, "UTF-8");
    }
    sb.append("Content-Type: ").append(contentType).append("\r\n");
    sb.append("Content-Transfer-Encoding: base64\r\n");
    sb.append("Content-Disposition: ").append(new ContentDisposition(af.inline ? Part.INLINE : Part.ATTACHMENT, dispositionParameters)).append("\r\n");
    if (!nz(af.contentId).isBlank()) sb.append("Content-ID: <").append(normalizeCidKey(af.contentId)).append(">\r\n");
    sb.append("\r\n");
    sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(af.data()));
    sb.append("\r\n");
  }

  static String plainTextToHtml(String s) {
    String t = escapeHtml(nz(s));
    t = t.replace("\r\n", "\n").replace("\r", "\n");
    return "<div style=\"white-space: pre-wrap;\">" + t + "</div>";
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
  }
  try {
    java.time.OffsetDateTime.parse(nz(record.sentXmp));
  } catch (java.time.format.DateTimeParseException error) {
    throw fail("Cannot create EA-PDF without a valid sent date for email '" + nz(record.subject)
        + "'. Retain the original input and supply a verifiable source date; no sent date was invented.");
  }
  if (!nz(record.rawDateHeader).isBlank() && parseMessageDate(record.rawDateHeader) == null) {
    String warning = "Invalid source sent date retained as Raw-Sent; normalized Sent=" + record.sentXmp;
    if (!record.conversionWarnings.contains(warning)) warn(record, warning);
  }
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
  t = t.replaceAll("(?i)&#x0+;?", "");
  t = t.replaceAll("(?i)&#0+;?", "");
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
    t = t.replace("&nbsp;"," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&#39;","'");
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

  static Date parseMessageDate(String value) {
    String text = nz(value).trim();
    if (text.isBlank()) return null;
    try {
      return Date.from(java.time.OffsetDateTime.parse(text).toInstant());
    } catch (java.time.format.DateTimeParseException ignored) {}
    try {
      return Date.from(java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
    } catch (java.time.format.DateTimeParseException ignored) {}
    MailDateFormat format = new MailDateFormat();
    format.setLenient(false);
    java.text.ParsePosition position = new java.text.ParsePosition(0);
    Date parsed = format.parse(text, position);
    return position.getIndex() == text.length() ? parsed : null;
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
    return t.replace("%40", "@").replace("%2E", ".").replace("%2e", ".").replace("%3C", "<").replace("%3E", ">").trim();
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
    if (t.length() > 80) t = t.substring(0,80).trim();
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
      if (x instanceof InternetAddress ia) addAgent(out, nz(ia.getPersonal()), nz(ia.getAddress()));
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
    return e.toLowerCase(Locale.ROOT).startsWith("mailto:") ? e : "mailto:" + e;
  }

  static boolean smtpMailbox(String email) {
    String value = nz(email);
    if (value.regionMatches(true, 0, "mailto:", 0, 7)) value = value.substring(7);
    if (!value.contains("@") || value.contains("\r") || value.contains("\n")) return false;
    try {
      InternetAddress parsed = new InternetAddress(value, true);
      parsed.validate();
      return value.equals(parsed.getAddress());
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
        if (nz(af.checksumMd5).isBlank()) af.checksumMd5 = af.dataPath == null ? md5Hex(af.bytes) : digestHex(af.dataPath, "MD5");
        if (nz(af.checksumSha256).isBlank()) af.checksumSha256 = af.dataPath == null ? sha256Hex(af.bytes) : digestHex(af.dataPath, "SHA-256");
      }
      if (af.emailAttachment) count++;
    }
    r.attachmentCount = count;
    if (nz(r.mailGuid).isBlank()) {
      r.mailGuid = "uuid:" + UUID.randomUUID();
    }
  }

}
