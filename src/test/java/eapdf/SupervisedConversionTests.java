package eapdf;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
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

  public static class ParentWatchdogProbe {
    public static void main(String[] arguments) throws Exception {
      ConversionSupervisor.watchParent(ProcessHandle.of(Long.parseLong(arguments[0])).orElseThrow(),
          "eapdf-test-parent-watchdog");
      System.out.write('R');
      System.out.flush();
      new java.util.concurrent.CountDownLatch(1).await();
    }
  }

  @Test void parentExitHaltsAWorkerEvenWhenItsMainThreadIsBlocked() throws Exception {
    Process parent = sleeper();
    Process worker = null;
    try {
      worker = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
          "-cp", System.getProperty("java.class.path"), ParentWatchdogProbe.class.getName(), Long.toString(parent.pid()))
          .redirectError(directory.resolve("watchdog.log").toFile()).start();
      Process probe = worker;
      var ready = new java.util.concurrent.FutureTask<>(() -> probe.getInputStream().read());
      Thread.ofVirtual().start(ready);
      Assertions.assertEquals((int) 'R', ready.get(10, TimeUnit.SECONDS).intValue());
      Assertions.assertTrue(worker.isAlive());
      ConversionSupervisor.stopTree(parent);
      Assertions.assertTrue(worker.waitFor(10, TimeUnit.SECONDS));
      Assertions.assertEquals(2, worker.exitValue());
    } finally {
      ConversionSupervisor.stopTree(parent);
      if (worker != null) ConversionSupervisor.stopTree(worker);
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
      OutputTransaction.allowReplacement(destination);
      OutputTransaction.allowReplacement(sidecar);
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
    String digest = Common.digestHex(payload, "SHA-256");
    var entries = List.of(new OutputTransaction.Entry(first.toString(), payload.toString(), 11, digest, true),
        new OutputTransaction.Entry(third.toString(), payload.toString(), 11, digest, false),
        new OutputTransaction.Entry(second.toString(), payload.toString(), 11, digest, true));
    Assertions.assertThrows(java.io.IOException.class, () -> OutputTransaction.publish(entries, (source, target) -> {
      if (moves.incrementAndGet() == 3) throw new java.io.IOException("simulated disk exhaustion");
      Files.createLink(target, source);
    }));
    Assertions.assertEquals("first original", Files.readString(first));
    Assertions.assertEquals("second original", Files.readString(second));
    Assertions.assertFalse(Files.exists(third));
    try (var paths = Files.list(directory)) {
      Assertions.assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  private List<OutputTransaction.Entry> batchEntries(Path output, String version, String prefix) throws IOException {
    Path stage = Files.createDirectory(directory.resolve(version + "-stage"));
    List<OutputTransaction.Entry> entries = new ArrayList<>();
    for (String name : List.of("first.pdf", "second.pdf")) {
      Path source = Files.writeString(stage.resolve(name), version + " " + name);
      entries.add(new OutputTransaction.Entry(output.resolve(prefix + name).toString(), source.toString(),
          Files.size(source), Common.digestHex(source, "SHA-256"), true));
    }
    return entries;
  }

  private static void replaceBatchFile(Path source, Path target) throws IOException {
    Files.createLink(target, source);
  }

  private static void assertBatchFiles(Path manifest, String version, String prefix) throws IOException {
    var snapshot = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
    Assertions.assertEquals(1, snapshot.get("schemaVersion").getAsInt());
    Assertions.assertEquals(manifest.getParent().getFileName().toString(), snapshot.get("generation").getAsString());
    var files = snapshot.getAsJsonArray("files");
    Assertions.assertEquals(2, files.size());
    for (int index = 0; index < files.size(); index++) {
      String name = index == 0 ? "first.pdf" : "second.pdf";
      var file = files.get(index).getAsJsonObject();
      String relative = file.get("path").getAsString();
      Assertions.assertEquals("00000" + index + "/" + prefix + name, relative);
      Path payload = manifest.getParent().resolve(relative);
      Assertions.assertEquals(version + " " + name, Files.readString(payload));
      Assertions.assertEquals(Files.size(payload), file.get("size").getAsLong());
      Assertions.assertEquals(Common.digestHex(payload, "SHA-256"), file.get("sha256").getAsString());
    }
  }

  @Test void batchReadersStayOnTheirGenerationWhileFlatPathsAreReplaced() throws Exception {
    Path output = Files.createDirectory(directory.resolve("out")).toRealPath();
    Path current = output.resolve(".eapdf-publication/current.json");
    Path old = OutputTransaction.publish(batchEntries(output, "old", ""), SupervisedConversionTests::replaceBatchFile,
        directory.resolve("old.journal"));
    String oldPointer = Files.readString(old);
    Assertions.assertEquals(oldPointer, Files.readString(current));
    Files.writeString(output.resolve("first.pdf"), "edited legacy flat file");
    assertBatchFiles(old, "old", "");

    Path updated = OutputTransaction.publish(batchEntries(output, "new", ""), (source, target) -> {
      replaceBatchFile(source, target);
      Assertions.assertEquals(oldPointer, Files.readString(current));
      assertBatchFiles(old, "old", "");
    }, directory.resolve("new.journal"));

    Assertions.assertNotEquals(old, updated);
    Assertions.assertEquals(Files.readString(updated), Files.readString(current));
    assertBatchFiles(updated, "new", "");
    assertBatchFiles(old, "old", "");
  }

  @Test void failedBatchLeavesCurrentAndRemovesTheUnpublishedGeneration() throws Exception {
    Path output = Files.createDirectory(directory.resolve("out")).toRealPath();
    Path current = output.resolve(".eapdf-publication/current.json");
    Path old = OutputTransaction.publish(batchEntries(output, "old", ""), SupervisedConversionTests::replaceBatchFile,
        directory.resolve("old.journal"));
    Path failedJournal = directory.resolve("failed.journal");
    AtomicInteger moves = new AtomicInteger();
    var entries = batchEntries(output, "new", "");

    Assertions.assertThrows(IOException.class, () -> OutputTransaction.publish(entries, (source, target) -> {
      if (moves.incrementAndGet() == 2) throw new IOException("simulated publication failure");
      replaceBatchFile(source, target);
    }, failedJournal));

    Assertions.assertEquals(Files.readString(old), Files.readString(current));
    Assertions.assertEquals("old first.pdf", Files.readString(output.resolve("first.pdf")));
    Assertions.assertEquals("old second.pdf", Files.readString(output.resolve("second.pdf")));
    Assertions.assertFalse(Files.exists(failedJournal));
    try (var paths = Files.list(current.getParent().toRealPath())) {
      Assertions.assertEquals(List.of(old.getParent()), paths.filter(Files::isDirectory).toList());
    }
  }

  @Test void recoveryKeepsAPreviouslyPublishedGenerationAfterCurrentAdvances() throws Exception {
    Path output = Files.createDirectory(directory.resolve("out")).toRealPath();
    Path current = output.resolve(".eapdf-publication/current.json");
    Path old = OutputTransaction.publish(batchEntries(output, "old", ""), SupervisedConversionTests::replaceBatchFile,
        directory.resolve("old.journal"));
    Path latest = OutputTransaction.publish(batchEntries(output, "more", "other-"), SupervisedConversionTests::replaceBatchFile,
        directory.resolve("latest.journal"));
    Path destination = output.resolve("first.pdf");
    Path workspace = Files.createTempDirectory(output, ".eapdf-publication-");
    Path original = Files.writeString(workspace.resolve("original"), "original before old batch");
    var entry = new OutputTransaction.Recovery(destination.toString(), workspace.toString(),
        Common.digestHex(destination, "SHA-256"), Common.digestHex(original, "SHA-256"));
    Path recoveryJournal = directory.resolve("interrupted.journal");
    // Simulate a crash after the atomic move consumed the ready pointer, before journal cleanup.
    Assertions.assertFalse(Files.exists(old.resolveSibling(".current.json.tmp")));
    OutputTransaction.writeJournal(recoveryJournal,
        new OutputTransaction.Journal(false, List.of(entry), new OutputTransaction.Batch(old.getParent().toString(), true)));

    OutputTransaction.recover(recoveryJournal);

    Assertions.assertEquals(Files.readString(latest), Files.readString(current));
    Assertions.assertEquals("old first.pdf", Files.readString(destination));
    assertBatchFiles(old, "old", "");
    assertBatchFiles(latest, "more", "other-");
    Assertions.assertFalse(Files.exists(workspace));
    Assertions.assertFalse(Files.exists(recoveryJournal));
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
          Assertions.assertEquals(count, files.filter(Files::isRegularFile)
              .filter(path -> !path.getFileName().toString().equals(".eapdf-publication.lock")).count());
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

  @Test void recoveryRestoresOriginalButPreservesAnInterveningDestination() throws Exception {
    Path destination = directory.resolve("output.pdf");
    Path publication = Files.createTempDirectory(directory.toRealPath(), ".eapdf-publication-");
    Path original = Files.writeString(publication.resolve("original"), "original PDF");
    Path prepared = Files.writeString(publication.resolve("prepared"), "new PDF");
    String oldDigest = Common.digestHex(original, "SHA-256");
    String newDigest = Common.digestHex(prepared, "SHA-256");
    var recovery = List.of(new OutputTransaction.Recovery(destination.toString(), publication.toString(), newDigest, oldDigest));
    Path journal = directory.resolve("publication.json");
    Files.createLink(destination, prepared);
    OutputTransaction.writeJournal(journal, new OutputTransaction.Journal(false, recovery));
    OutputTransaction.recover(journal);
    Assertions.assertEquals("original PDF", Files.readString(destination));
    Assertions.assertFalse(Files.exists(journal));

    Files.createDirectory(publication);
    Files.writeString(original, "original PDF");
    Files.writeString(prepared, "new PDF");
    Files.writeString(destination, "user replacement");
    OutputTransaction.writeJournal(journal, new OutputTransaction.Journal(false, recovery));
    Assertions.assertThrows(java.io.IOException.class, () -> OutputTransaction.recover(journal));
    Assertions.assertEquals("user replacement", Files.readString(destination));
    Assertions.assertEquals("original PDF", Files.readString(original));
    Assertions.assertTrue(Files.exists(journal));
  }

  @Test void explicitArchiveCannotReplaceInputOrHardLink() throws Exception {
    Path input = Files.copy(Path.of("in/sample.eml"), directory.resolve("source.eml"));
    Path alias = Files.createLink(directory.resolve("archive.pdf"), input);
    String original = Common.digestHex(input, "SHA-256");
    Path parentAlias = Files.createSymbolicLink(directory.resolve("alias"), directory.toRealPath());
    Path relative = Path.of("").toAbsolutePath().relativize(input.toAbsolutePath());
    for (Path output : List.of(input, alias, relative, parentAlias.resolve(input.getFileName()))) {
      var error = Assertions.assertThrows(java.io.IOException.class, () -> ConversionSupervisor.supervise(
          "eapdf.EmailToPDFA", new String[]{"--archive-profile", "m", "--archive-output", output.toString(),
              "--out", directory.resolve("out").toString(), input.toString()}));
      Assertions.assertTrue(error.getMessage().contains("would replace an input file"));
      Assertions.assertEquals(original, Common.digestHex(input, "SHA-256"));
    }
    Assertions.assertFalse(Files.exists(directory.resolve("out")));
  }

  @Test void automaticPublicationNeverReplacesACompetingDestination() throws Exception {
    Path destination = directory.resolve("output.pdf");
    Path staged = Files.writeString(directory.resolve("staged"), "new archive");
    String digest = Common.digestHex(staged, "SHA-256");
    var entry = new OutputTransaction.Entry(destination.toString(), staged.toString(), Files.size(staged), digest, false);
    Assertions.assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> OutputTransaction.publish(List.of(entry), (source, target) -> {
      Files.writeString(target, "other conversion result");
      Files.createLink(target, source);
    }));
    Assertions.assertEquals("other conversion result", Files.readString(destination));
  }

  @Test void automaticallyNamedOutputCannotReplaceAConcurrentPublication() throws Exception {
    Path destination = directory.resolve("selected.pdf");
    Path stage = directory.resolve("stage");
    Path manifest = directory.resolve("manifest.json");
    try (var transaction = new OutputTransaction(stage)) {
      Assertions.assertEquals(destination, Common.availableArchivePath(destination));
      OutputTransaction.write(destination, temporary -> Files.writeString(temporary, "replacement"));
      transaction.finish(manifest);
    }
    Files.writeString(destination, "first publisher");
    Assertions.assertThrows(java.io.IOException.class, () -> OutputTransaction.publish(manifest, stage));
    Assertions.assertEquals("first publisher", Files.readString(destination));
  }

  public static class PublicationProbe {
    public static void main(String[] args) throws Exception {
      Path destination = Path.of(args[0]);
      Path payload = Path.of(args[1]);
      System.out.println("Starting");
      var entry = new OutputTransaction.Entry(destination.toString(), payload.toString(), Files.size(payload),
          Common.digestHex(payload, "SHA-256"), true);
      OutputTransaction.publish(List.of(entry), (source, target) -> {
        Files.createLink(target, source);
        System.out.println("Published");
        if (Boolean.parseBoolean(args[3])) new java.util.concurrent.Semaphore(0).acquireUninterruptibly();
      }, Path.of(args[2]));
    }
  }

  @Test void separateJvmWaitsForPublisherAndSurvivesStaleRecovery() throws Exception {
    var children = new java.util.ArrayList<Process>();
    var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Path destination = directory.resolve("shared.pdf");
    Files.writeString(destination, "original");
    try (var reader = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        for (String name : List.of("first", "second")) {
          Path payload = Files.writeString(directory.resolve(name + ".payload"), name);
          Process child = new ProcessBuilder(javaExecutable, "-cp", System.getProperty("java.class.path"), PublicationProbe.class.getName(),
              destination.toString(), payload.toString(), directory.resolve(name + ".journal").toString(),
              Boolean.toString(name.equals("first"))).redirectError(directory.resolve(name + ".log").toFile()).start();
          children.add(child);
          var stdout = child.inputReader();
          Assertions.assertEquals("Starting", reader.submit(stdout::readLine).get(15, TimeUnit.SECONDS));
          if (name.equals("first")) Assertions.assertEquals("Published", reader.submit(stdout::readLine).get(15, TimeUnit.SECONDS));
        }
        Assertions.assertFalse(children.get(1).waitFor(250, TimeUnit.MILLISECONDS));
        Assertions.assertEquals("first", Files.readString(destination));
        Assertions.assertTrue(children.get(0).destroyForcibly().waitFor(10, TimeUnit.SECONDS));
        Assertions.assertTrue(children.get(1).waitFor(30, TimeUnit.SECONDS),
            "Second publisher did not complete after the lock holder exited");
        Assertions.assertEquals(0, children.get(1).exitValue());
        Assertions.assertThrows(IOException.class, () -> OutputTransaction.recover(directory.resolve("first.journal")));
        Assertions.assertEquals("second", Files.readString(destination));
      } finally {
        for (Process child : children) if (child.isAlive()) child.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
      }
    }
  }

  @Test void abandonedCleanupUsesProcessStartIdentityAndLeavesActiveWorkspacesAlone() throws Exception {
    Path abandoned = Files.createDirectory(directory.resolve("eapdf-worker-abandoned"));
    Path active = Files.createDirectory(directory.resolve("eapdf-worker-active"));
    var process = ProcessHandle.current();
    ConversionSupervisor.writeOwner(abandoned, new ConversionSupervisor.Owner(process.pid(), "2000-01-01T00:00:00Z", 0, "", abandoned.getFileName().toString()));
    ConversionSupervisor.writeOwner(active, new ConversionSupervisor.Owner(process.pid(), process.info().startInstant().orElseThrow().toString(), 0, "", active.getFileName().toString()));
    Path unknown = Files.createDirectory(directory.resolve("eapdf-worker-unknown"));
    Path notes = Files.writeString(unknown.resolve("operator-notes.txt"), "preserve");
    Files.writeString(unknown.resolve("owner.json"), "{}");
    ConversionSupervisor.cleanAbandoned(directory);
    Assertions.assertEquals("preserve", Files.readString(notes));
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
