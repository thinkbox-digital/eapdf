package eapdf;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class ConversionSupervisor {
  private static volatile boolean worker;
  private static final List<String> ENTRIES = List.of("eapdf.EmailToPDFA", "eapdf.GmailDownloadToPdf", "eapdf.MonthlyFilenameArchiveToPdf");
  record Owner(long supervisor, String supervisorStart, long child, String childStart) {}

  static boolean run(Class<?> entry, String[] arguments) {
    if (worker) return false;
    try {
      int status = supervise(entry.getName(), arguments);
      if (status != 0) System.exit(status);
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      Common.exitWithError(Common.nz(error.getMessage()));
    }
    return true;
  }

  static int supervise(String entry, String[] arguments) throws Exception {
    if (!ENTRIES.contains(entry)) throw Common.fail("Unsupported supervised entry point");
    Path temporaryRoot = workspaceRoot(entry, arguments);
    cleanAbandoned(temporaryRoot);
    Path directory = Files.createTempDirectory(temporaryRoot, "eapdf-worker-");
    System.out.println("Conversion working storage: " + directory);
    Process process = null;
    Thread cleanup = null;
    Object publication = new Object();
    ConversionProgress progress = null;
    boolean successful = false;
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    try {
      Path temporary = Files.createDirectory(directory.resolve("temporary"));
      Path outputs = Files.createDirectory(directory.resolve("outputs"));
      Path manifest = directory.resolve("manifest.json");
      ProcessHandle supervisor = ProcessHandle.current();
      String supervisorStart = supervisor.info().startInstant().orElseThrow(
          () -> Common.fail("Cannot establish conversion supervisor ownership on this runtime")).toString();
      writeOwner(directory, new Owner(supervisor.pid(), supervisorStart, 0, ""));
      progress = ConversionProgress.start(entry, arguments, directory);
      ConversionProgress jobProgress = progress;
      PstJobBudget budget = PstJobBudget.forInput(entry, arguments, temporary);
      long heap = ConversionMemory.workerHeap(budget.setting("eapdf.workerHeapBytes", "EAPDF_WORKER_HEAP_BYTES",
          ConversionMemory.defaultHeap(512L * 1024 * 1024)));
      budget.useWorkerHeap(heap);
      System.out.println("Conversion memory allowance: " + heap / (1024 * 1024) + " MiB Java heap (chosen before startup).");
      List<String> command = new ArrayList<>();
      command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
      for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
        if (argument.startsWith("-D") && !argument.startsWith("-Djava.io.tmpdir=")) command.add(argument);
      }
      budget.addWorkerSettings(command);
      command.add("-Xmx" + heap);
      command.add("-XX:MaxDirectMemorySize=128m");
      command.add("-XX:MaxMetaspaceSize=256m");
      command.add("-XX:ReservedCodeCacheSize=128m");
      command.add("-XX:ActiveProcessorCount=" + Math.min(4, Runtime.getRuntime().availableProcessors()));
      command.add("-XX:-CreateCoredumpOnCrash");
      command.add("-XX:ErrorFile=" + temporary.resolve("hs_err_pid%p.log"));
      command.add("-Djava.io.tmpdir=" + temporary);
      command.add("-cp");
      command.add(System.getProperty("java.class.path"));
      command.add(ConversionSupervisor.class.getName());
      command.add(entry);
      command.add(directory.toString());
      command.addAll(Arrays.asList(arguments));
      process = new ProcessBuilder(nativeLimits(command, budget)).inheritIO().start();
      Process child = process;
      writeOwner(directory, new Owner(supervisor.pid(), supervisorStart, child.pid(), workerStart(child)));
      cleanup = new Thread(() -> {
        cancelled.set(true);
        stopTree(child);
        jobProgress.finish("CANCELLED");
        synchronized (publication) {
          try {
            OutputTransaction.recover(directory.resolve("publication.json"));
            Common.deleteTree(directory);
          } catch (Exception error) {
            System.err.println("warning: Could not clean cancelled conversion storage; retain recovery data: " + directory);
          }
        }
      }, "eapdf-worker-cleanup");
      Runtime.getRuntime().addShutdownHook(cleanup);
      monitor(process, directory, Duration.ofSeconds(budget.setting("eapdf.jobTimeoutSeconds", "EAPDF_JOB_TIMEOUT_SECONDS", 600)),
          budget.setting("eapdf.maxTempBytes", "EAPDF_MAX_TEMP_BYTES", 2L * 1024 * 1024 * 1024),
          budget.setting("eapdf.maxTempFiles", "EAPDF_MAX_TEMP_FILES", 100000));
      if (process.exitValue() != 0) return process.exitValue();
      synchronized (publication) {
        if (cancelled.get()) throw Common.fail("Conversion cancelled before publication");
        ConversionProgress.attach(directory);
        try {
          ConversionProgress.stage("publishing");
          OutputTransaction.publish(manifest, outputs, budget);
        } finally { ConversionProgress.flush(); ConversionProgress.detach(); }
      }
      successful = true;
      return 0;
    } finally {
      if (progress != null) progress.finish(successful ? "COMPLETED" : cancelled.get() ? "CANCELLED" : "FAILED");
      if (process != null) stopTree(process);
      if (cleanup != null) {
        try { Runtime.getRuntime().removeShutdownHook(cleanup); } catch (IllegalStateException ignored) {}
      }
      synchronized (publication) {
        OutputTransaction.recover(directory.resolve("publication.json"));
        Common.deleteTree(directory);
      }
    }
  }

  static Path workspaceRoot(String entry, String[] arguments) throws Exception {
    Path temporary = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
    if (!entry.equals("eapdf.EmailToPDFA") || !memoryBacked(temporary)) return temporary;
    var options = EmailToPDFA.parseArgs(arguments);
    String input = (String) options.get("in");
    if (input == null) return temporary;
    Path source = Path.of(input);
    if (PstJobBudget.inputBytes(source) < 128L * 1024 * 1024) return temporary;
    Path output = Path.of((String) options.getOrDefault("out", "out")).toAbsolutePath().normalize();
    Files.createDirectories(output);
    if (memoryBacked(output)) throw Common.fail("Large email conversion needs disk-backed working storage. Choose an output folder on disk or set java.io.tmpdir to a disk folder.");
    Path root = output.resolve(".eapdf-work");
    Files.createDirectories(root);
    return root;
  }

  private static boolean memoryBacked(Path path) throws java.io.IOException {
    String type = Files.getFileStore(path).type();
    return type.equalsIgnoreCase("tmpfs") || type.equalsIgnoreCase("ramfs");
  }

  static void writeOwner(Path directory, Owner owner) throws Exception {
    Common.writeAtomically(directory.resolve("owner.json"), new com.google.gson.Gson().toJson(owner).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static boolean sameProcess(long identifier, String started) {
    if (identifier <= 0 || started == null || started.isBlank()) return false;
    return ProcessHandle.of(identifier).filter(ProcessHandle::isAlive)
        .flatMap(handle -> handle.info().startInstant()).map(value -> value.toString().equals(started)).orElse(false);
  }

  static void cleanAbandoned(Path root) throws Exception {
    Path probe = Files.createTempFile(root, "eapdf-owner-", ".tmp");
    var currentOwner = Files.getOwner(probe);
    Files.delete(probe);
    try (var directories = Files.newDirectoryStream(root, "eapdf-worker-*")) {
      for (Path directory : directories) {
        Path marker = directory.resolve("owner.json");
        try {
          if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
              || !Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(marker) > 4096
              || !Files.getOwner(directory).equals(currentOwner)) continue;
          Owner owner = new com.google.gson.Gson().fromJson(Files.readString(marker), Owner.class);
          if (owner == null || sameProcess(owner.supervisor(), owner.supervisorStart())) continue;
          if (sameProcess(owner.child(), owner.childStart())) {
            var orphan = ProcessHandle.of(owner.child()).orElseThrow();
            orphan.destroyForcibly();
            orphan.onExit().get(5, TimeUnit.SECONDS);
          }
          OutputTransaction.recover(directory.resolve("publication.json"));
          Common.deleteTree(directory);
        } catch (java.io.IOException | RuntimeException error) {
          System.err.println("warning: Could not reclaim abandoned conversion storage: " + directory);
        }
      }
    }
  }

  static void requestStop(Process process) {
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) stopTree(process);
    } catch (InterruptedException error) {
      stopTree(process);
      Thread.currentThread().interrupt();
    }
  }

  static List<String> nativeLimits(List<String> command) {
    return nativeLimits(command, new PstJobBudget());
  }

  static List<String> nativeLimits(List<String> command, PstJobBudget budget) {
    if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux")) return command;
    Path limiter = Path.of("/usr/bin/prlimit");
    if (!Files.isExecutable(limiter)) throw Common.fail("Supervised Linux conversion requires /usr/bin/prlimit (util-linux)");
    List<String> limited = new ArrayList<>();
    limited.add(limiter.toString());
    limited.add("--as=" + inheritedLimit("Max address space", budget.setting("eapdf.workerAddressBytes", "EAPDF_WORKER_ADDRESS_BYTES", 4L * 1024 * 1024 * 1024)));
    // RLIMIT_FSIZE applies to scratch as well as outputs. A shared stream cache
    // can exceed the final PDF size; enforce final-output limits separately.
    limited.add("--fsize=" + inheritedLimit("Max file size", Math.max(
        budget.setting("eapdf.maxTempBytes", "EAPDF_MAX_TEMP_BYTES", 2L * 1024 * 1024 * 1024),
        Math.max(ProcessingLimits.messageBytes(), budget.setting("eapdf.maxOutputBytes", "EAPDF_MAX_OUTPUT_BYTES", 1024L * 1024 * 1024)))));
    // RLIMIT_CPU counts all threads, whereas the monitor bounds wall time.
    // Give parallel rendering/GC the same wall-time allowance as one thread.
    long wallSeconds = budget.setting("eapdf.jobTimeoutSeconds", "EAPDF_JOB_TIMEOUT_SECONDS", 600);
    limited.add("--cpu=" + inheritedLimit("Max cpu time", Math.multiplyExact(wallSeconds, Math.min(4, Runtime.getRuntime().availableProcessors()))));
    limited.add("--");
    limited.addAll(command);
    return limited;
  }

  // A sandboxed parent may already have a tighter hard limit. A child cannot
  // raise it; attempting to do so makes prlimit exit before Java starts.
  private static long inheritedLimit(String name, long requested) {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/self/limits"))) {
        if (!line.startsWith(name + " ")) continue;
        String[] values = line.substring(name.length()).trim().split("\\s+");
        return "unlimited".equals(values[1]) ? requested : Math.min(requested, Long.parseLong(values[1]));
      }
    } catch (java.io.IOException | NumberFormatException error) {
      throw Common.fail("Cannot read inherited process limits: " + error.getMessage());
    }
    throw Common.fail("Inherited process limit is unavailable: " + name);
  }

  static String workerStart(Process child) throws Exception {
    var start = child.info().startInstant();
    if (start.isPresent()) return start.get().toString();
    boolean exited = child.waitFor(100, TimeUnit.MILLISECONDS);
    throw Common.fail(exited
        ? "Conversion worker exited before startup completed (exit " + child.exitValue() + "). See the preceding worker error output."
        : "Cannot establish conversion worker ownership on this runtime");
  }

  static void monitor(Process process, Path directory, Duration timeout, long maxTemporaryBytes) throws Exception {
    monitor(process, directory, timeout, maxTemporaryBytes, ProcessingLimits.setting("eapdf.maxTempFiles", "EAPDF_MAX_TEMP_FILES", 100000));
  }

  static void monitor(Process process, Path directory, Duration timeout, long maxTemporaryBytes, long maxFiles) throws Exception {
    long started = System.nanoTime(), nextScan = started;
    try {
      while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
        if (System.nanoTime() - started > timeout.toNanos()) throw Common.fail("Conversion exceeded the whole-job time limit");
        if (System.nanoTime() >= nextScan) {
          checkTemporary(directory, maxTemporaryBytes, maxFiles);
          nextScan = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        }
      }
      checkTemporary(directory, maxTemporaryBytes, maxFiles);
    } catch (Exception error) {
      stopTree(process);
      throw error;
    }
  }

  static long checkTemporary(Path directory, long limit) throws Exception {
    return checkTemporary(directory, limit, ProcessingLimits.setting("eapdf.maxTempFiles", "EAPDF_MAX_TEMP_FILES", 100000));
  }

  static long checkTemporary(Path directory, long limit, long fileLimit) throws Exception {
    long[] total = {0};
    long[] files = {0};
    Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
      @Override public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes attributes) {
        if (++files[0] > fileLimit) throw Common.fail("Conversion exceeded the temporary-file count limit of " + fileLimit);
        if (attributes.isRegularFile()) total[0] = Math.addExact(total[0], attributes.size());
        if (total[0] > limit) throw Common.fail("Conversion exceeded the temporary-storage limit of " + limit + " bytes");
        return java.nio.file.FileVisitResult.CONTINUE;
      }
      @Override public java.nio.file.FileVisitResult visitFileFailed(Path path, java.io.IOException error) throws java.io.IOException {
        if (!(error instanceof java.nio.file.NoSuchFileException)) throw error;
        return java.nio.file.FileVisitResult.CONTINUE;
      }
    });
    return total[0];
  }

  static void stopTree(Process process) {
    boolean interrupted = Thread.interrupted();
    var descendants = process.descendants().toList();
    for (var child : descendants) child.destroyForcibly();
    if (process.isAlive()) process.destroyForcibly();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("Could not reap conversion worker");
      for (var child : descendants) {
        if (child.isAlive()) child.onExit().get(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException error) {
      interrupted = true;
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
      throw new IllegalStateException("Could not reap conversion descendants", error);
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  public static void main(String[] arguments) throws Exception {
    if (arguments.length < 2 || !ENTRIES.contains(arguments[0])) throw Common.fail("Invalid conversion worker invocation");
    worker = true;
    var parent = ProcessHandle.current().parent().orElseThrow();
    Path directory = Path.of(arguments[1]);
    Owner owner = new com.google.gson.Gson().fromJson(Files.readString(directory.resolve("owner.json")), Owner.class);
    if (owner == null || owner.supervisor() != parent.pid() || !sameProcess(owner.supervisor(), owner.supervisorStart())) {
      throw Common.fail("Invalid supervised-worker ownership");
    }
    // Intentional bounded polling: notice parent death even if conversion hangs.
    Thread watchdog = new Thread(() -> {
      while (parent.isAlive()) {
        try { Thread.sleep(100); } catch (InterruptedException error) { return; }
      }
      Runtime.getRuntime().halt(2);
    }, "eapdf-parent-watchdog");
    watchdog.setDaemon(true);
    watchdog.start();
    ConversionProgress.attach(directory);
    try (OutputTransaction transaction = new OutputTransaction(directory.resolve("outputs"), directory)) {
      String[] forwarded = Arrays.copyOfRange(arguments, 2, arguments.length);
      switch (arguments[0]) {
        case "eapdf.EmailToPDFA" -> EmailToPDFA.main(forwarded);
        case "eapdf.GmailDownloadToPdf" -> GmailDownloadToPdf.main(forwarded);
        case "eapdf.MonthlyFilenameArchiveToPdf" -> MonthlyFilenameArchiveToPdf.main(forwarded);
        default -> throw Common.fail("Unsupported conversion worker");
      }
      ConversionProgress.stage("publishing");
      ConversionProgress.flush();
      transaction.finish(directory.resolve("manifest.json"));
    } finally { ConversionProgress.flush(); ConversionProgress.detach(); }
  }
}
