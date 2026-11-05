package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Stream PST records into persistent JVMs, with at most one outstanding record per worker. */
final class PstProcesses {
  record Pending(int index, EmailRecord record, long started) {}

  static boolean convert(String input, String output, String font, String icc, String globalId,
                         List<String> archiveEmails, boolean debug, String debugDirectory, AtomicInteger sequence,
                         boolean multiInput, boolean omitSource) throws Exception {
    Common.JobReport job = Common.ACTIVE_JOB.get();
    OutputTransaction transaction = OutputTransaction.active;
    if (job == null || transaction == null || transaction.workspace == null) return false;
    long available = ConversionMemory.availableBytes();
    int count = MboxProcesses.workerCount(job.workerLimit, Integer.MAX_VALUE, Runtime.getRuntime().maxMemory(),
        available, Runtime.getRuntime().availableProcessors());
    if (count <= 1) return false;
    long heap = MboxProcesses.childHeap(count, Runtime.getRuntime().maxMemory(), available);
    Path root = Files.createTempDirectory(transaction.workspace, "pst-processes-");
    List<MboxProcesses.Worker> workers = new ArrayList<>();
    Map<MboxProcesses.Worker, Pending> pending = new LinkedHashMap<>();
    int[] submitted = {0};
    System.out.println("PST render processes: " + count + "; child heap: " + heap / ConversionMemory.MIB + " MiB each");
    try {
      ProcessHandle owner = ProcessHandle.current();
      int offset = sequence.get();
      for (int slot = 0; slot < count; slot++) {
        Path directory = Files.createDirectory(root.resolve("worker-" + slot));
        Path temporary = Files.createDirectory(directory.resolve("temporary"));
        var request = new MboxProcesses.Request(owner.pid(), owner.info().startInstant().orElseThrow().toString(), input,
            output, font, icc, debug, debugDirectory, omitSource, List.of(), true, offset, multiInput);
        Process process = MboxProcesses.startWorker(directory, temporary, heap, request);
        workers.add(new MboxProcesses.Worker(directory, process));
        System.out.println("PST render worker: " + process.pid());
      }
      PstReader.forEach(input, globalId, archiveEmails, debug, record -> {
        reap(workers, pending, job);
        while (pending.size() == workers.size()) {
          TimeUnit.MILLISECONDS.sleep(10);
          reap(workers, pending, job);
        }
        var worker = workers.stream().filter(w -> !pending.containsKey(w)).findFirst().orElseThrow();
        int index = ++submitted[0];
        sequence.incrementAndGet();
        var item = new MboxProcesses.Item(index, record);
        Path task = worker.directory().resolve("task.json");
        Common.writeAtomically(task, temporary -> {
          try (var writer = Files.newBufferedWriter(temporary)) { MboxProcesses.JSON.toJson(item, writer); }
          if (Files.size(temporary) > ProcessingLimits.metadataBytes()) throw Common.fail("PST render task exceeds the metadata budget");
        });
        pending.put(worker, new Pending(index, record, System.nanoTime()));
      });
      ConversionProgress.inputRead();
      while (!pending.isEmpty()) {
        reap(workers, pending, job);
        if (!pending.isEmpty()) TimeUnit.MILLISECONDS.sleep(10);
      }
      if (submitted[0] == 0) throw Common.fail("no messages found in input: " + input);
      for (var worker : workers) Files.createFile(worker.directory().resolve("finish"));
      boolean running;
      do {
        ProcessingLimits.checkCancelled();
        running = false;
        for (var worker : workers) {
          if (worker.process().isAlive()) running = true;
          else requireSuccess(worker);
        }
        if (running) TimeUnit.MILLISECONDS.sleep(25);
      } while (running);
      for (var worker : workers) MboxProcesses.importWorker(worker.directory(), transaction, job);
      System.out.println("input: " + input + " messages: " + submitted[0]);
      return true;
    } finally {
      for (var worker : workers) ConversionSupervisor.stopTree(worker.process());
      Common.deleteTree(root);
    }
  }

  static void requireSuccess(MboxProcesses.Worker worker) {
    if (worker.process().exitValue() != 0)
      throw Common.fail("PST render worker " + worker.process().pid() + " failed (exit " + worker.process().exitValue() + "); no outputs published");
  }

  static void reap(List<MboxProcesses.Worker> workers, Map<MboxProcesses.Worker, Pending> pending,
                   Common.JobReport job) throws Exception {
    ProcessingLimits.checkCancelled();
    for (var worker : workers) {
      if (!worker.process().isAlive()) {
        requireSuccess(worker);
        throw Common.fail("PST render worker stopped before input completion; no outputs published");
      }
      Pending task = pending.get(worker);
      if (task == null) continue;
      Path done = worker.directory().resolve("done.json");
      if (Files.exists(done)) {
        if (Files.size(done) > 32 || !Files.readString(done, StandardCharsets.UTF_8).equals(Integer.toString(task.index())))
          throw Common.fail("PST render worker returned an unexpected message index");
        Files.delete(done);
        job.release(task.record());
        pending.remove(worker);
        ConversionProgress.stage("rendering");
        ConversionProgress.completed();
      } else if (System.nanoTime() - task.started() > TimeUnit.SECONDS.toNanos(
          ProcessingLimits.setting("eapdf.workerTimeoutSeconds", "EAPDF_WORKER_TIMEOUT_SECONDS", 300))) {
        throw Common.fail("PST render worker exceeded the message processing time limit; no outputs published");
      }
    }
  }
}
