package eapdf;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Internal per-message JVM workers. Only the owning supervisor publishes outputs. */
public final class MboxProcesses {
  static final Gson JSON = new GsonBuilder().registerTypeHierarchyAdapter(Path.class, new com.google.gson.TypeAdapter<Path>() {
    public void write(JsonWriter out, Path value) throws IOException { out.value(value.toString()); }
    public Path read(JsonReader in) throws IOException { return Path.of(in.nextString()); }
  }.nullSafe()).create();
  record Item(int index, EmailRecord message) {}
  record Request(long owner, String ownerStart, String input, String output, String font, String icc,
                 boolean debug, String debugDirectory, boolean omitSource, List<Item> items,
                 boolean streaming, int globalOffset, boolean multiInput) {}
  record Report(Map<String, String> warnings, Map<String, ConversionDiagnostic> diagnostics, long pages, long pixels) {}
  record Worker(Path directory, Process process) {}

  static int workerCount(int requested, int messages, long heap, long available, int cpus) {
    long memory = Math.max(0, heap / (256 * ConversionMemory.MIB) - 1);
    // Native memory, heap and the coordinator must coexist with other applications.
    if (available >= 0) memory = Math.min(memory, available / (2 * (512 * ConversionMemory.MIB)));
    return (int) Math.max(1, Math.min(Math.min(requested, messages), Math.min(cpus, memory)));
  }

  static long childHeap(int count, long coordinatorHeap, long available) {
    long heap = coordinatorHeap / (count + 1);
    if (available >= 0) heap = Math.min(heap, available / (2L * count) - 256 * ConversionMemory.MIB);
    return Math.max(256 * ConversionMemory.MIB, heap / ConversionMemory.MIB * ConversionMemory.MIB);
  }

  static boolean convert(String input, List<EmailRecord> records, String output, String font, String icc,
                         boolean debug, String debugDirectory, boolean omitSource) throws Exception {
    Common.JobReport job = Common.ACTIVE_JOB.get();
    OutputTransaction transaction = OutputTransaction.active;
    if (job == null || transaction == null || transaction.workspace == null) return false;
    long available = ConversionMemory.availableBytes();
    int count = workerCount(job.workerLimit, records.size(), Runtime.getRuntime().maxMemory(),
        available, Runtime.getRuntime().availableProcessors());
    if (count <= 1) return false;
    long heap = childHeap(count, Runtime.getRuntime().maxMemory(), available);
    Path root = Files.createTempDirectory(transaction.workspace, "mbox-processes-");
    List<Worker> workers = new ArrayList<>();
    System.out.println("MBOX render processes: " + count + "; child heap: " + heap / ConversionMemory.MIB + " MiB each");
    try {
      ProcessHandle owner = ProcessHandle.current();
      for (int slot = 0; slot < count; slot++) {
        Path directory = Files.createDirectory(root.resolve("worker-" + slot));
        Path temporary = Files.createDirectory(directory.resolve("temporary"));
        List<Item> items = new ArrayList<>();
        for (int i = slot; i < records.size(); i += count) items.add(new Item(i + 1, records.get(i)));
        Request request = new Request(owner.pid(), owner.info().startInstant().orElseThrow().toString(), input,
            output, font, icc, debug, debugDirectory, omitSource, items, false, 0, false);
        Process process = startWorker(directory, temporary, heap, request);
        workers.add(new Worker(directory, process));
        System.out.println("MBOX render worker: " + process.pid() + "; messages: " + items.size());
      }
      long reported = 0;
      boolean running;
      do {
        ProcessingLimits.checkCancelled();
        running = false;
        long completed = 0;
        for (Worker worker : workers) {
          if (worker.process().isAlive()) running = true;
          else if (worker.process().exitValue() != 0)
            throw Common.fail("MBOX render worker " + worker.process().pid() + " failed (exit " + worker.process().exitValue() + "); no outputs published");
          Path progress = worker.directory().resolve("progress.json");
          if (Files.exists(progress)) {
            if (Files.size(progress) > ProcessingLimits.metadataBytes()) throw Common.fail("Worker progress exceeds the metadata budget");
            completed += JsonParser.parseString(Files.readString(progress)).getAsJsonObject().get("emails_completed").getAsLong();
          }
        }
        ConversionProgress.stage("rendering");
        while (reported < completed) { ConversionProgress.completed(); reported++; }
        if (running) TimeUnit.MILLISECONDS.sleep(250);
      } while (running);
      if (reported != records.size()) throw Common.fail("MBOX process results do not cover every message");
      for (Worker worker : workers) {
        Path directory = worker.directory();
        importWorker(directory, transaction, job);
      }
      return true;
    } finally {
      for (Worker worker : workers) ConversionSupervisor.stopTree(worker.process());
      Common.deleteTree(root);
    }
  }

  static Process startWorker(Path directory, Path temporary, long heap, Request request) throws Exception {
    try (var writer = Files.newBufferedWriter(directory.resolve("request.json"))) { JSON.toJson(request, writer); }
    if (Files.size(directory.resolve("request.json")) > ProcessingLimits.metadataBytes())
      throw Common.fail("Render worker request exceeds the metadata budget");
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    for (String argument : ManagementFactory.getRuntimeMXBean().getInputArguments())
      if (argument.startsWith("-D") && !argument.startsWith("-Djava.io.tmpdir=")) command.add(argument);
    Collections.addAll(command, "-Xmx" + heap, "-XX:MaxDirectMemorySize=128m", "-XX:MaxMetaspaceSize=256m",
        "-XX:ReservedCodeCacheSize=128m", "-XX:ActiveProcessorCount=1", "-XX:-CreateCoredumpOnCrash",
        "-XX:ErrorFile=" + temporary.resolve("hs_err_pid%p.log"), "-Djava.io.tmpdir=" + temporary,
        "-cp", System.getProperty("java.class.path"), MboxProcesses.class.getName(), directory.toString());
    return new ProcessBuilder(command).inheritIO().start();
  }

  static void importWorker(Path directory, OutputTransaction transaction, Common.JobReport job) throws Exception {
        transaction.adopt(directory.resolve("manifest.json"), directory.resolve("outputs"));
        ConversionProgress.importReceipts(directory.resolve("progress.json"));
        Report report;
        try (var reader = Files.newBufferedReader(directory.resolve("report.json"))) { report = JSON.fromJson(reader, Report.class); }
        // Shared with JobReport.stage/release and diagnostic accumulation.
        synchronized (job) {
          long size = JSON.toJson(report.warnings()).length() * 2L + JSON.toJson(report.diagnostics()).length() * 2L;
          if (size > ProcessingLimits.metadataBytes() - job.metadataSize - job.warningSize)
            throw Common.fail("Combined process diagnostics exceed the metadata budget");
          job.warningSize += size;
          job.warnings.putAll(report.warnings());
          job.diagnostics.putAll(report.diagnostics());
        }
        job.renderedPages.addAndGet(report.pages());
        job.imagePixels.addAndGet(report.pixels());
  }

  private static void renderItem(Request request, Item item) throws Exception {
    long started = System.nanoTime();
    EmailToPDFA.processRecord(request.input(), item.message(), request.output(), request.font(), request.icc(),
        request.debug(), request.debugDirectory(), item.index(), request.globalOffset() + item.index(), request.multiInput(), request.omitSource());
    System.out.println((request.streaming() ? "[pst-process]" : "[mbox-process]") + " completed index=" + item.index()
        + " pid=" + ProcessHandle.current().pid() + " milliseconds=" + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw Common.fail("Invalid MBOX worker invocation");
    Path directory = Path.of(args[0]).toAbsolutePath().normalize();
    Path requestPath = directory.resolve("request.json");
    if (Files.size(requestPath) > ProcessingLimits.metadataBytes()) throw Common.fail("MBOX worker request exceeds the metadata budget");
    Request request;
    try (var reader = Files.newBufferedReader(requestPath)) { request = JSON.fromJson(reader, Request.class); }
    var parent = ProcessHandle.current().parent().orElseThrow();
    if (request.owner() != parent.pid() || !ConversionSupervisor.sameProcess(request.owner(), request.ownerStart()))
      throw Common.fail("Invalid MBOX worker ownership");
    // Independent of rendering progress so an orphaned worker cannot keep running.
    Thread watchdog = new Thread(() -> {
      while (parent.isAlive()) {
        try { Thread.sleep(100); } catch (InterruptedException error) { return; }
      }
      Runtime.getRuntime().halt(2);
    }, "eapdf-mbox-parent-watchdog");
    watchdog.setDaemon(true); watchdog.start();
    ConversionProgress.start("eapdf.EmailToPDFA", new String[]{"--out", directory.toString(), request.input()}, directory);
    ConversionProgress.attach(directory);
    try (OutputTransaction transaction = new OutputTransaction(directory.resolve("outputs"), directory)) {
      try (Common.JobReport job = Common.beginJob()) {
        job.workerLimit = 1;
        if (!request.streaming()) ConversionProgress.total(request.items().size());
        EapdfConfig.get().requireCreator();
        PdfSigning.preflight();
        if (request.streaming()) {
          int completed = 0;
          Path task = directory.resolve("task.json");
          while (true) {
            ProcessingLimits.checkCancelled();
            if (Files.exists(task)) {
              if (Files.size(task) > ProcessingLimits.metadataBytes()) throw Common.fail("Render task exceeds the metadata budget");
              Item item;
              try (var reader = Files.newBufferedReader(task)) { item = JSON.fromJson(reader, Item.class); }
              ConversionProgress.discovered();
              renderItem(request, item);
              Files.delete(task);
              Common.writeAtomically(directory.resolve("done.json"), Integer.toString(item.index()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
              completed++;
            } else if (Files.exists(directory.resolve("finish"))) break;
            else TimeUnit.MILLISECONDS.sleep(10);
          }
          ConversionProgress.total(completed);
        } else for (Item item : request.items()) renderItem(request, item);
        try (var writer = Files.newBufferedWriter(directory.resolve("report.json"))) {
          JSON.toJson(new Report(job.warnings, job.diagnostics, job.renderedPages.get(), job.imagePixels.get()), writer);
        }
      }
      transaction.finish(directory.resolve("manifest.json"));
    } finally { ConversionProgress.flush(); ConversionProgress.detach(); }
  }
}
