package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupervisedConversionTests {
  @TempDir Path directory;

  public static class InheritedLimitProbe {
    public static void main(String[] arguments) throws Exception {
      var command = ConversionSupervisor.nativeLimits(List.of("/bin/true"), new PstJobBudget());
      System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
    }
  }

  @Test void tighterInheritedFileLimitDoesNotPreventWorkerStartup() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/prlimit")));
    Process probe = new ProcessBuilder("/usr/bin/prlimit", "--fsize=67108864", "--",
        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), InheritedLimitProbe.class.getName())
        .redirectErrorStream(true).start();
    String diagnostic = new String(probe.getInputStream().readAllBytes());
    Assertions.assertTrue(probe.waitFor(10, TimeUnit.SECONDS));
    Assertions.assertEquals(0, probe.exitValue(), diagnostic);
  }

  @Test void earlyWorkerExitHasAnActionableError() throws Exception {
    Process child = new ProcessBuilder("/bin/false").start();
    child.waitFor();
    var error = Assertions.assertThrows(Common.UserFacingException.class, () -> ConversionSupervisor.workerStart(child));
    Assertions.assertTrue(error.getMessage().contains("exited before startup"), error.getMessage());
  }

  Process sleeper() throws Exception {
    return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-cp", System.getProperty("java.class.path"), SleepingWorker.class.getName()).start();
  }

  public static class SleepingWorker {
    public static void main(String[] arguments) throws Exception {
      Thread.sleep(60000);
    }
  }

  @Test void wholeJobDeadlineReapsEvenUncooperativeWorkerProcesses() throws Exception {
    Process process = sleeper();
    try {
      var error = Assertions.assertThrows(Common.UserFacingException.class,
          () -> ConversionSupervisor.monitor(process, directory, Duration.ofMillis(200), 100000));
      Assertions.assertTrue(error.getMessage().contains("whole-job"));
      Assertions.assertFalse(process.isAlive());
      Assertions.assertTrue(process.waitFor(1, TimeUnit.SECONDS));
    } finally {
      process.destroyForcibly();
    }
  }

  @Test void temporaryBudgetReapsTheWorkerWithoutTouchingExistingOutputs() throws Exception {
    Path protectedFile = directory.resolve("existing.pdf");
    Files.writeString(protectedFile, "original");
    Process process = sleeper();
    try {
      var error = Assertions.assertThrows(Common.UserFacingException.class,
          () -> ConversionSupervisor.monitor(process, directory, Duration.ofSeconds(10), 1));
      Assertions.assertTrue(error.getMessage().contains("temporary-storage"));
      Assertions.assertFalse(process.isAlive());
      Assertions.assertEquals("original", Files.readString(protectedFile));
    } finally {
      process.destroyForcibly();
    }
  }

  @Test void stagedPdfAndSidecarsAreInvisibleUntilSuccessfulPublication() throws Exception {
    Path destination = directory.resolve("output.pdf");
    Path sidecar = directory.resolve("output_premis.xml");
    Files.writeString(destination, "original PDF");
    Files.writeString(sidecar, "original XML");
    Path stage = directory.resolve("stage");
    Path manifest = directory.resolve("manifest.json");
    try (OutputTransaction transaction = new OutputTransaction(stage)) {
      OutputTransaction.write(destination, temporary -> Files.writeString(temporary, "replacement PDF"));
      OutputTransaction.write(sidecar, temporary -> Files.writeString(temporary, "replacement XML"));
      Assertions.assertEquals("original PDF", Files.readString(destination));
      Assertions.assertEquals("original XML", Files.readString(sidecar));
      Assertions.assertEquals("replacement PDF", Files.readString(OutputTransaction.readable(destination)));
      transaction.finish(manifest);
    }
    OutputTransaction.publish(manifest, stage);
    Assertions.assertEquals("replacement PDF", Files.readString(destination));
    Assertions.assertEquals("replacement XML", Files.readString(sidecar));
  }

  @Test void failedSecondPublicationRollsBackEveryExistingAndNewOutput() throws Exception {
    Path first = directory.resolve("first.pdf");
    Path second = directory.resolve("second.xml");
    Path third = directory.resolve("new.xlsx");
    Files.writeString(first, "first original");
    Files.writeString(second, "second original");
    Path payload = directory.resolve("payload");
    Files.writeString(payload, "replacement");
    AtomicInteger moves = new AtomicInteger();
    var entries = List.of(new OutputTransaction.Entry(first.toString(), payload.toString(), 11, ""),
        new OutputTransaction.Entry(third.toString(), payload.toString(), 11, ""),
        new OutputTransaction.Entry(second.toString(), payload.toString(), 11, ""));
    Assertions.assertThrows(java.io.IOException.class, () -> OutputTransaction.publish(entries, (source, target) -> {
      if (moves.incrementAndGet() == 3) throw new java.io.IOException("simulated disk exhaustion");
      Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }));
    Assertions.assertEquals("first original", Files.readString(first));
    Assertions.assertEquals("second original", Files.readString(second));
    Assertions.assertFalse(Files.exists(third));
    try (var paths = Files.list(directory)) {
      Assertions.assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith(".eapdf-")));
    }
  }

  @Test void outputManifestHasNoMessageCountCutoff() throws Exception {
    String previous = System.getProperty("eapdf.maxMessages");
    try {
      System.setProperty("eapdf.maxMessages", "1");
      for (int count : new int[]{4, 5}) {
        Path stage = directory.resolve("stage-" + count);
        Path manifest = directory.resolve("manifest-" + count + ".json");
        Path output = directory.resolve("output-" + count);
        try (OutputTransaction transaction = new OutputTransaction(stage)) {
          for (int index = 0; index < count; index++) {
            OutputTransaction.write(output.resolve("asset-" + index), temporary -> Files.writeString(temporary, "payload"));
          }
          transaction.finish(manifest);
        }
        OutputTransaction.publish(manifest, stage);
        try (var files = Files.list(output)) {
          Assertions.assertEquals(count, files.count());
        }
      }
    } finally {
      ProcessingLimitRepairTests.restore("eapdf.maxMessages", previous);
    }
  }

  @Test void outputLimitRejectsBeforeReplacingTheOriginal() throws Exception {
    Path output = directory.resolve("protected.pdf");
    Files.writeString(output, "original");
    String previous = System.getProperty("eapdf.maxOutputBytes");
    try {
      System.setProperty("eapdf.maxOutputBytes", "1");
      Assertions.assertThrows(Common.UserFacingException.class,
          () -> OutputTransaction.write(output, temporary -> Files.writeString(temporary, "oversized")));
      Assertions.assertEquals("original", Files.readString(output));
    } finally {
      ProcessingLimitRepairTests.restore("eapdf.maxOutputBytes", previous);
    }
  }

  @Test void pageAndAggregatePixelCountsDoNotCutOffContent() throws Exception {
    String pixels = System.getProperty("eapdf.maxTotalImagePixels");
    String pages = System.getProperty("eapdf.maxPages");
    try (var job = Common.beginJob()) {
      System.setProperty("eapdf.maxTotalImagePixels", "100");
      System.setProperty("eapdf.maxPages", "1");
      ProcessingLimits.accountImage(60);
      Assertions.assertDoesNotThrow(() -> ProcessingLimits.accountImage(60));
      Assertions.assertDoesNotThrow(() -> ProcessingLimits.checkPages(2));
    } finally {
      ProcessingLimitRepairTests.restore("eapdf.maxTotalImagePixels", pixels);
      ProcessingLimitRepairTests.restore("eapdf.maxPages", pages);
    }
  }

  @Test void nonSmtpAddressesStayRawAndAreNeverInventedAsFoafMailboxes() throws Exception {
    EmailRecord record = new SpecRepairTests().record("text/plain", "Body");
    record.rawFrom = "Exchange sender </o=Example/cn=recipient>";
    record.fromAgents = List.of(Common.agent("Exchange sender", "/o=Example/cn=recipient"));
    String metadata = PdfMailWriter.buildXmpMetadata(record, "s");
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(metadata.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String namespace = "http://www.pdfa.org/eapdf/ns/meta/";
    Assertions.assertEquals(0, document.getElementsByTagNameNS(namespace, "From").getLength());
    Assertions.assertEquals(record.rawFrom, document.getElementsByTagNameNS(namespace, "Raw-From").item(0).getTextContent());
    Assertions.assertTrue(Common.smtpMailbox("δοκιμή@παράδειγμα.δοκιμή"));
    Assertions.assertTrue(Common.smtpMailbox("alice@example.org"));
    Assertions.assertFalse(Common.smtpMailbox("/o=Example/cn=recipient"));
  }

  @Test void internalCollectionChildrenAreNotRedirectedAsPublishedOutputs() throws Exception {
    Path workspace = directory.resolve("worker");
    Path internal = workspace.resolve("temporary/child.pdf");
    Path published = directory.resolve("collection.pdf");
    Files.createDirectories(internal.getParent());
    try (OutputTransaction transaction = new OutputTransaction(workspace.resolve("outputs"), workspace)) {
      OutputTransaction.write(internal, temporary -> Files.writeString(temporary, "child PDF"));
      OutputTransaction.write(published, temporary -> Files.writeString(temporary, "collection PDF"));
      Assertions.assertEquals("child PDF", Files.readString(internal));
      Assertions.assertFalse(Files.exists(published));
      Assertions.assertEquals(1, transaction.entries.size());
    }
  }

  @Test void recoveryRestoresInterruptedPublicationButKeepsCommittedPublication() throws Exception {
    Path destination = directory.resolve("output.pdf");
    Path backup = directory.resolve("backup.pdf");
    Path prepared = directory.resolve("prepared.pdf");
    Path journal = directory.resolve("publication.json");
    var recovery = List.of(new OutputTransaction.Recovery(destination.toString(), prepared.toString(), backup.toString()));
    Files.writeString(destination, "new PDF");
    Files.writeString(backup, "original PDF");
    OutputTransaction.writeJournal(journal, new OutputTransaction.Journal(false, recovery));
    OutputTransaction.recover(journal);
    Assertions.assertEquals("original PDF", Files.readString(destination));
    Assertions.assertFalse(Files.exists(journal));
    Files.writeString(destination, "committed PDF");
    Files.writeString(backup, "original PDF");
    OutputTransaction.writeJournal(journal, new OutputTransaction.Journal(true, recovery));
    OutputTransaction.recover(journal);
    Assertions.assertEquals("committed PDF", Files.readString(destination));
    Assertions.assertFalse(Files.exists(backup));
    Files.writeString(prepared, "interrupted preparation");
    OutputTransaction.writeJournal(journal, new OutputTransaction.Journal(false, recovery));
    OutputTransaction.recover(journal);
    Assertions.assertEquals("committed PDF", Files.readString(destination));
    Assertions.assertFalse(Files.exists(prepared));
  }

  @Test void abandonedCleanupUsesProcessStartIdentityAndLeavesActiveWorkspacesAlone() throws Exception {
    Path abandoned = Files.createDirectory(directory.resolve("eapdf-worker-abandoned"));
    Path active = Files.createDirectory(directory.resolve("eapdf-worker-active"));
    var process = ProcessHandle.current();
    ConversionSupervisor.writeOwner(abandoned, new ConversionSupervisor.Owner(process.pid(), "not-the-current-process-start", 0, ""));
    ConversionSupervisor.writeOwner(active, new ConversionSupervisor.Owner(process.pid(), process.info().startInstant().orElseThrow().toString(), 0, ""));
    ConversionSupervisor.cleanAbandoned(directory);
    Assertions.assertFalse(Files.exists(abandoned));
    Assertions.assertTrue(Files.exists(active));
  }
  public static class ScratchSizeWorker {
    public static void main(String[] arguments) throws Exception {
      Path scratch = Path.of(arguments[0]);
      long length = Long.parseLong(arguments[1]);
      try (var file = java.nio.channels.FileChannel.open(scratch,
          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE)) {
        file.position(length - 1); file.write(java.nio.ByteBuffer.wrap(new byte[]{42}));
      }
      if (Files.size(scratch) != length) throw new AssertionError("Scratch length changed");
      try {
        ProcessingLimits.checkOutput(length);
        throw new AssertionError("Final output limit must still be enforced");
      } catch (Common.UserFacingException expected) {
        System.out.println("Scratch write succeeded; final output limit remains enforced.");
      }
    }
  }

  @Test void nativeScratchAllowanceCanExceedFinalOutputLimitBeyondFourGiB() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
    String oldTemp = System.getProperty("eapdf.maxTempBytes"), oldOutput = System.getProperty("eapdf.maxOutputBytes");
    long scratchSize = 4L * 1024 * 1024 * 1024 + 4096;
    try {
      System.setProperty("eapdf.maxTempBytes", Long.toString(5L * 1024 * 1024 * 1024));
      System.setProperty("eapdf.maxOutputBytes", Long.toString(1024L * 1024 * 1024));
      var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-Xmx128m", "-XX:ActiveProcessorCount=2", "-Deapdf.maxOutputBytes=1073741824",
          "-cp", System.getProperty("java.class.path"), ScratchSizeWorker.class.getName(),
          directory.resolve("scratch.bin").toString(), Long.toString(scratchSize));
      Path log = directory.resolve("native-limit.log");
      Process worker = new ProcessBuilder(ConversionSupervisor.nativeLimits(command)).redirectErrorStream(true).redirectOutput(log.toFile()).start();
      try {
        Assertions.assertTrue(worker.waitFor(20, TimeUnit.SECONDS));
        Assertions.assertEquals(0, worker.exitValue(), Files.readString(log));
        Assertions.assertEquals(scratchSize, Files.size(directory.resolve("scratch.bin")));
      } finally { worker.destroyForcibly(); }
    } finally {
      if(oldTemp == null)System.clearProperty("eapdf.maxTempBytes");else System.setProperty("eapdf.maxTempBytes",oldTemp);
      if(oldOutput == null)System.clearProperty("eapdf.maxOutputBytes");else System.setProperty("eapdf.maxOutputBytes",oldOutput);
    }
  }
}
