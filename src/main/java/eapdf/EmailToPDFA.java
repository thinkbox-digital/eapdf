package eapdf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

public class EmailToPDFA {
  static final byte[] OLE2_SIGNATURE = new byte[] {
      (byte)0xD0, (byte)0xCF, 0x11, (byte)0xE0, (byte)0xA1, (byte)0xB1, 0x1A, (byte)0xE1
  };

  public static void main(String[] args) throws Exception {
    if (ConversionSupervisor.run(EmailToPDFA.class, args)) return;
    try (Common.JobReport job = Common.beginJob()) {
      Map<String,Object> opt = parseArgs(args);
      EapdfConfig.get().requireCreator();
      PdfSigning.preflight();
      String in = (String)opt.get("in");
      if (in == null) throw Common.fail("missing input path");
      String outDir = (String)opt.getOrDefault("out", "out");
      String fontPath = Common.resolveFontPath((String)opt.get("font"));
      String iccPath = Common.resolveIccPath((String)opt.get("icc"));
      String globalId = (String)opt.get("global-id");
      List<String> archiveEmails = castStringList(opt.get("email"));
      boolean debug = Boolean.TRUE.equals(opt.get("debug"));
      boolean omitSource = Boolean.TRUE.equals(opt.get("omit-source"));
      String archiveProfile = (String)opt.get("archive-profile");
      String archiveOutput = (String)opt.get("archive-output");
      String debugDir = (String)opt.getOrDefault("debug-dir", Paths.get(outDir, "_debug").toString());
      int jobs = ((Number)opt.getOrDefault("jobs", Integer.valueOf(1))).intValue();
      job.workerLimit = jobs;

      Files.createDirectories(Paths.get(outDir));
      if (debug) Files.createDirectories(Paths.get(debugDir));

      List<String> inputs = collectInputs(in);
      ConversionProgress.inputsSelected(inputs.size());
      if (archiveProfile != null) {
        runArchive(inputs, outDir, archiveOutput, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, archiveProfile,
            Files.isDirectory(Paths.get(in)) ? Paths.get(in) : null);
        return;
      }
      boolean multiInput = inputs.size() > 1;
      if (jobs <= 1 || inputs.size() <= 1) {
        runSequential(inputs, outDir, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, multiInput, omitSource);
        return;
      }
      runParallel(inputs, outDir, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, jobs, multiInput, omitSource);
    } catch (Common.UserFacingException e) {
      Common.exitWithError(e.getMessage());
    }
  }

  static Map<String,Object> parseArgs(String[] args) {
    Common.validateCliArguments(args, Set.of("--out", "--font", "--icc", "--global-id", "-g", "--email", "-e", "--jobs", "-j", "--archive-profile", "--archive-output", "--debug-dir"),
        Set.of("--omit-source", "--debug"), true);
    Map<String,Object> m = new LinkedHashMap<>();
    List<String> rest = new ArrayList<>();
    List<String> emails = new ArrayList<>();
    for (int i=0;i<args.length;i++) {
      String a = args[i];
      if (a.equals("--out") && i+1<args.length) { m.put("out", args[++i]); continue; }
      if (a.equals("--font") && i+1<args.length) { m.put("font", args[++i]); continue; }
      if (a.equals("--icc") && i+1<args.length) { m.put("icc", args[++i]); continue; }
      if ((a.equals("--global-id") || a.equals("-g")) && i+1<args.length) { m.put("global-id", args[++i]); continue; }
      if ((a.equals("--email") || a.equals("-e")) && i+1<args.length) { emails.add(args[++i]); continue; }
      if ((a.equals("--jobs") || a.equals("-j")) && i+1<args.length) { m.put("jobs", Common.numericOption(a, args[++i], 1)); continue; }
      if (a.equals("--omit-source")) { m.put("omit-source", true); continue; }
      if (a.equals("--archive-profile") && i+1<args.length) { m.put("archive-profile", args[++i]); continue; }
      if (a.equals("--archive-output") && i+1<args.length) { m.put("archive-output", args[++i]); continue; }
      if (a.equals("--debug")) { m.put("debug", true); continue; }
      if (a.equals("--debug-dir") && i+1<args.length) { m.put("debug-dir", args[++i]); continue; }
      rest.add(a);
    }
    if (!rest.isEmpty()) m.put("in", rest.get(0));
    m.put("email", emails);
    Common.validateArchiveOptions(m);
    return m;
  }

  static List<String> castStringList(Object o) {
    if (o == null) return new ArrayList<>();
    if (o instanceof List<?> l) {
      List<String> out = new ArrayList<>();
      for (Object x : l) out.add(x == null ? "" : x.toString());
      return out;
    }
    return new ArrayList<>();
  }

  static List<String> collectInputs(String path) throws Exception {
    Path p = Paths.get(path);
    if (!Files.isDirectory(p)) {
      Common.inputState(path, "accepted");
      return List.of(path);
    }
    List<String> out = new ArrayList<>();
    try (var walk = Files.walk(p)) {
      List<Path> files = walk.filter(Files::isRegularFile).toList();
      ProcessingLimits.checkCount(files.size());
      files = files.stream().sorted().toList();
      for (Path file : files) {
        String candidate = file.toString();
        if (!isSupportedInput(candidate)) {
          Common.inputState(candidate, "skipped");
          System.out.println("skipped input: " + candidate + " (unsupported extension)");
          continue;
        }
        if (!shouldCollectInput(file)) {
          Common.inputState(candidate, "skipped");
          System.out.println("skipped input: " + candidate + " (not an Outlook MSG or RFC 822 email)");
          continue;
        }
        out.add(candidate);
        Common.inputState(candidate, "accepted");
      }
    }
    if (out.isEmpty()) throw Common.fail("no supported messages found under: " + path);
    System.out.println("inputs accepted: " + out.size());
    return out;
  }

  static boolean isSupportedInput(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    return p.endsWith(".pst") || p.endsWith(".msg") || p.endsWith(".eml") || p.endsWith(".mbox");
  }

  static boolean shouldCollectInput(Path path) {
    String p = path.toString().toLowerCase(Locale.ROOT);
    if (!p.endsWith(".msg")) return true;
    try {
      String kind = detectMsgInputKind(path);
      return "application/vnd.ms-outlook".equals(kind) || "message/rfc822".equals(kind);
    } catch (IOException ignored) {
      return true;
    }
  }

  static void runSequential(List<String> inputs, String outDir, String fontPath, String iccPath, String globalId,
                            List<String> archiveEmails, boolean debug, String debugDir, boolean multiInput,
                            boolean omitSource) throws Exception {
    AtomicInteger seq = new AtomicInteger();
    for (String input : inputs) {
      processInput(input, outDir, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, seq, multiInput, omitSource);
    }
    System.out.println("done. inputs: " + inputs.size() + " converted: " + seq.get());
  }

  static void runArchive(List<String> inputs, String outDir, String archiveOutput, String fontPath, String iccPath,
                         String globalId, List<String> archiveEmails, boolean debug, String debugDir,
                         String archiveProfile) throws Exception {
    runArchive(inputs, outDir, archiveOutput, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, archiveProfile, null);
  }

  static void runArchive(List<String> inputs, String outDir, String archiveOutput, String fontPath, String iccPath,
                         String globalId, List<String> archiveEmails, boolean debug, String debugDir,
                         String archiveProfile, Path inputRoot) throws Exception {
    if (!"m".equals(archiveProfile) && !"mi".equals(archiveProfile)
        && !"c".equals(archiveProfile) && !"ci".equals(archiveProfile)) {
      throw Common.fail("unsupported --archive-profile (expected m, mi, c, or ci): " + archiveProfile);
    }
    List<EmailRecord> all = new ArrayList<>();
    for (List<EmailRecord> recs : readManyForArchive(inputs, globalId, archiveEmails, debug)) {
      all.addAll(recs);
    }
    if (all.isEmpty()) throw Common.fail("no messages found for archive output");
    if (inputRoot != null) {
      Path base = inputRoot.toAbsolutePath().normalize();
      for (EmailRecord record : all) {
        if (!Common.nz(record.folderPath).isBlank() || !record.folderMemberships.isEmpty()) continue;
        if (!"eml".equals(record.sourceType) && !"msg".equals(record.sourceType)) continue;
        Path parent = Paths.get(record.sourcePath).toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.startsWith(base)) continue;
        String folder = base.relativize(parent).toString().replace(java.io.File.separatorChar, '/');
        if (!folder.isBlank()) {
          record.folderPath = folder;
          record.folderMemberships.add(folder);
        }
      }
    }
    boolean embedSource = "m".equals(archiveProfile);
    for (EmailRecord r : all) r.embedSourceMessage = embedSource;

    Path out;
    if (archiveOutput != null && !archiveOutput.isBlank()) {
      out = Paths.get(archiveOutput);
    } else {
      String inputStem = Common.safeFileStem(Paths.get(inputs.get(0)).getFileName().toString().replaceFirst("\\.[^.]+$", ""));
      if (inputs.size() > 1) inputStem = inputStem + "_archive";
      out = Common.availableArchivePath(Paths.get(outDir).resolve(inputStem + "_1" + archiveProfile + ".pdf"));
    }
    Path debugBase = Paths.get(debugDir, "archive_1" + archiveProfile);
    if (debug) Files.createDirectories(debugBase);
    if ("c".equals(archiveProfile) || "ci".equals(archiveProfile)) {
      PdfMailWriter.writeEaPdfCollection(out, all, fontPath, iccPath, debug, debugBase, archiveProfile);
    } else {
      PdfMailWriter.writeEaPdfArchive(out, all, fontPath, iccPath, debug, debugBase, archiveProfile);
    }
    System.out.println("wrote archive: " + out);
    for (String input : inputs) Common.inputState(input, "completed");
  }

  static List<List<EmailRecord>> readManyForArchive(List<String> inputs, String globalId, List<String> archiveEmails,
                                                    boolean debug) throws Exception {
    if (inputs.size() <= 1) {
      List<List<EmailRecord>> out = new ArrayList<>();
      for (String input : inputs) out.add(readAny(input, globalId, archiveEmails, debug));
      return out;
    }

    int workers = PdfMailWriter.renderParallelism(inputs.size());
    ExecutorService pool = Common.newWorkers(workers);
    try {
      Deque<Future<List<EmailRecord>>> futures = new ArrayDeque<>();
      List<List<EmailRecord>> out = new ArrayList<>();
      for (String input : inputs) {
        if (futures.size() >= workers) out.add(awaitRecords(futures.removeFirst()));
        futures.addLast(pool.submit(() -> readAny(input, globalId, archiveEmails, debug)));
      }
      while (!futures.isEmpty()) out.add(awaitRecords(futures.removeFirst()));
      return out;
    } finally {
      Common.stopWorkers(pool);
    }
  }

  static List<EmailRecord> awaitRecords(Future<List<EmailRecord>> records) throws Exception {
    try {
      return Common.awaitWorker(records);
    } catch (ExecutionException exception) {
      rethrowTaskFailure(exception);
      throw new AssertionError(exception);
    }
  }

  static void runParallel(List<String> inputs, String outDir, String fontPath, String iccPath, String globalId,
                          List<String> archiveEmails, boolean debug, String debugDir, int jobs, boolean multiInput,
                          boolean omitSource) throws Exception {
    int workers = Math.max(1, Math.min(jobs, PdfMailWriter.renderParallelism(inputs.size())));
    ExecutorService pool = Common.newWorkers(workers);
    AtomicInteger seq = new AtomicInteger();
    Deque<Future<?>> futures = new ArrayDeque<>();
    try {
      for (String input : inputs) {
        if (futures.size() >= workers) awaitConversion(futures.removeFirst());
        if (input.toLowerCase(Locale.ROOT).endsWith(".pst")) {
          while (!futures.isEmpty()) awaitConversion(futures.removeFirst());
          processInput(input, outDir, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, seq, multiInput, omitSource);
          continue;
        }
        Common.inputState(input, "started");
        List<EmailRecord> records = readAny(input, globalId, archiveEmails, debug);
        AtomicInteger inputSequence = new AtomicInteger(seq.getAndAdd(records.size()));
        futures.addLast(pool.submit(() -> {
          try {
            processRecords(input, records, outDir, fontPath, iccPath, debug, debugDir, inputSequence, multiInput, omitSource);
            Common.inputState(input, "completed");
          } catch (Exception error) {
            throw new CompletionException(error);
          }
        }));
      }
      while (!futures.isEmpty()) awaitConversion(futures.removeFirst());
      System.out.println("done. inputs: " + inputs.size() + " converted: " + seq.get());
    } finally {
      Common.stopWorkers(pool);
    }
  }

  static void awaitConversion(Future<?> conversion) throws Exception {
    try {
      Common.awaitWorker(conversion);
    } catch (ExecutionException error) {
      rethrowTaskFailure(error);
    }
  }

  static void processInput(String input, String outDir, String fontPath, String iccPath, String globalId,
                           List<String> archiveEmails, boolean debug, String debugDir, AtomicInteger seq,
                           boolean multiInput, boolean omitSource) throws Exception {
    Common.inputState(input, "started");
    if (input.toLowerCase(Locale.ROOT).endsWith(".pst")) {
      processPst(input, outDir, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, seq, multiInput, omitSource);
      Common.inputState(input, "completed");
      return;
    }
    List<EmailRecord> recs = readAny(input, globalId, archiveEmails, debug);
    processRecords(input, recs, outDir, fontPath, iccPath, debug, debugDir, seq, multiInput, omitSource);
    Common.inputState(input, "completed");
  }

  static void processPst(String input, String outDir, String fontPath, String iccPath, String globalId,
                         List<String> archiveEmails, boolean debug, String debugDir, AtomicInteger seq,
                         boolean multiInput, boolean omitSource) throws Exception {
    if (PstProcesses.convert(input, outDir, fontPath, iccPath, globalId, archiveEmails, debug, debugDir, seq, multiInput, omitSource)) return;
    int workers = PdfMailWriter.renderParallelism(Integer.MAX_VALUE);
    ExecutorService pool = Common.newWorkers(workers);
    Deque<Future<?>> pending = new ArrayDeque<>();
    int[] count = {0};
    Common.JobReport job = Common.ACTIVE_JOB.get();
    try {
      PstReader.forEach(input, globalId, archiveEmails, debug, record -> {
        if (pending.size() >= workers) awaitConversion(pending.removeFirst());
        int localIndex = ++count[0], globalIndex = seq.incrementAndGet();
        pending.addLast(pool.submit(() -> {
          try {
            processRecord(input, record, outDir, fontPath, iccPath, debug, debugDir, localIndex, globalIndex, multiInput, omitSource);
          } catch (Exception error) { throw new CompletionException(error); }
          finally {
            if (job != null) try { job.release(record); }
            catch (IOException error) { throw new CompletionException(error); }
          }
        }));
      });
      ConversionProgress.inputRead();
      while (!pending.isEmpty()) awaitConversion(pending.removeFirst());
      if (count[0] == 0) throw Common.fail("no messages found in input: " + input);
      System.out.println("input: " + input + " messages: " + count[0]);
    } finally { Common.stopWorkers(pool); }
  }

  static void processRecords(String input, List<EmailRecord> recs, String outDir, String fontPath, String iccPath,
                             boolean debug, String debugDir, AtomicInteger seq, boolean multiInput, boolean omitSource) throws Exception {
    if (recs.isEmpty()) throw Common.fail("no messages found in input: " + input);
    System.out.println("input: " + input + " messages: " + recs.size());
    String inputStem = Common.safeFileStem(Paths.get(input).getFileName().toString().replaceFirst("\\.[^.]+$", ""));
    if (!multiInput && input.toLowerCase(Locale.ROOT).endsWith(".mbox")
        && MboxProcesses.convert(input, recs, outDir, fontPath, iccPath, debug, debugDir, omitSource)) {
      seq.addAndGet(recs.size());
      return;
    }
    int localIndex = 0;
    for (EmailRecord r : recs) {
      localIndex++;
      int globalIndex = seq.incrementAndGet();
      processRecord(input, r, outDir, fontPath, iccPath, debug, debugDir, localIndex, globalIndex, multiInput, omitSource);
    }
  }

  static void processRecord(String input, EmailRecord r, String outDir, String fontPath, String iccPath,
                            boolean debug, String debugDir, int localIndex, int globalIndex, boolean multiInput, boolean omitSource) throws Exception {
    String inputStem = Common.safeFileStem(Paths.get(input).getFileName().toString().replaceFirst("\\.[^.]+$", ""));
    r.embedSourceMessage = !omitSource;
    String subjectStem = Common.safeFileStem((r.subject != null && !r.subject.isBlank()) ? r.subject : "message");
    String fn = uniqueFileName(outDir, inputStem, subjectStem, localIndex, globalIndex, multiInput);
    Path out = Paths.get(outDir).resolve(fn);
    Path debugBase = Paths.get(debugDir, inputStem + "_" + String.format("%06d", globalIndex));
    if (debug) Files.createDirectories(debugBase);
    PdfMailWriter.writeEaPdfA3(out, r, fontPath, iccPath, debug, debugBase, localIndex);
    System.out.println("wrote: " + out);
  }

  static synchronized String uniqueFileName(String outDir, String inputStem, String subjectStem, int localIndex,
                                            int globalIndex, boolean multiInput) {
    String base = inputStem + "__" + subjectStem + "_"
        + String.format("%06d", multiInput ? globalIndex : localIndex);
    String filename = base + ".pdf";
    int copy = 2;
    while (Files.exists(Paths.get(outDir, filename), LinkOption.NOFOLLOW_LINKS)
        || Files.exists(OutputTransaction.readable(Paths.get(outDir, filename)), LinkOption.NOFOLLOW_LINKS)) {
      String suffix = "_" + copy++;
      String stem = base;
      // Two international stems can already fill almost an entire filename.
      while (stem.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + suffix.length() + 4 > 255) {
        stem = stem.substring(0, stem.offsetByCodePoints(stem.length(), -1));
      }
      filename = stem + suffix + ".pdf";
    }
    return filename;
  }

  static List<EmailRecord> readAny(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    List<EmailRecord> records = readAnyInput(path, globalId, archiveEmails, debug);
    ConversionProgress.inputRead();
    return records;
  }

  private static List<EmailRecord> readAnyInput(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    Common.inputState(path, "started");
    String p = path.toLowerCase(Locale.ROOT);
    if (p.endsWith(".pst")) return PstReader.readPst(path, globalId, archiveEmails, debug);
    if (p.endsWith(".msg")) {
      String kind = detectMsgInputKind(Paths.get(path));
      if ("application/vnd.ms-outlook".equals(kind)) return List.of(Common.stageRecord(MsgReader.readMsg(path, globalId, archiveEmails, debug)));
      if ("message/rfc822".equals(kind)) return List.of(Common.stageRecord(EmlReader.readEml(path, globalId, archiveEmails, debug)));
      throw Common.fail("input has .msg extension but is not a valid Outlook MSG file"
          + (kind.isBlank() ? "" : " (detected " + kind + ")") + ": " + path);
    }
    if (p.endsWith(".eml")) return List.of(Common.stageRecord(EmlReader.readEml(path, globalId, archiveEmails, debug)));
    if (p.endsWith(".mbox")) return MboxReader.readMbox(path, globalId, archiveEmails, debug);
    throw Common.fail("unsupported input (expected .pst .msg .eml .mbox): " + path);
  }

  static void rethrowTaskFailure(ExecutionException e) throws Exception {
    Throwable cause = e;
    while ((cause instanceof ExecutionException || cause instanceof CompletionException) && cause.getCause() != null) {
      cause = cause.getCause();
    }
    if (cause instanceof Common.UserFacingException u) throw u;
    if (cause instanceof Exception ex) throw ex;
    if (cause instanceof Error err) throw err;
    throw new RuntimeException(cause);
  }

  static String detectMsgInputKind(Path path) throws IOException {
    byte[] head = readHead(path, 512);
    if (looksLikeOle2(head)) {
      try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
        if (Common.isNonEmailOutlookItem(MsgReader.storageString(storage.getRoot(), "001A"))) return "application/x-outlook-item";
      }
      return "application/vnd.ms-outlook";
    }
    String sniffed = Common.sniffMime(head);
    return Common.nz(sniffed);
  }

  static byte[] readHead(Path path, int maxBytes) throws IOException {
    byte[] head = new byte[Math.max(0, maxBytes)];
    try (InputStream in = Files.newInputStream(path)) {
      int off = 0;
      while (off < head.length) {
        int read = in.read(head, off, head.length - off);
        if (read < 0) break;
        off += read;
      }
      return off == head.length ? head : Arrays.copyOf(head, off);
    }
  }

  static boolean looksLikeOle2(byte[] bytes) {
    if (bytes == null || bytes.length < OLE2_SIGNATURE.length) return false;
    for (int i = 0; i < OLE2_SIGNATURE.length; i++) {
      if (bytes[i] != OLE2_SIGNATURE[i]) return false;
    }
    return true;
  }
}
