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
          () -> ConversionSupervisor.monitor(process, directory, Duration.ZERO, 1));
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

  @Test void completedPdfAppearsBeforeOtherFilesAndBatchSnapshot() throws Exception {
    Path workspace = Files.createDirectory(directory.resolve("worker"));
    Files.createDirectory(workspace.resolve("ready"));
    Path output = Files.createDirectory(directory.resolve("out"));
    Path first = output.resolve("first.pdf"), second = output.resolve("second.pdf");
    Path stage = workspace.resolve("outputs"), manifest = workspace.resolve("manifest.json");
    Path current = output.resolve(".eapdf-publication/current.json");
    var budget = new PstJobBudget();
    var publication = new OutputTransaction.LivePublication(workspace, budget, () -> false);
    try (var transaction = new OutputTransaction(stage, workspace)) {
      OutputTransaction.write(first, temporary -> Files.writeString(temporary, "new first.pdf"));
      OutputTransaction.write(second, temporary -> Files.writeString(temporary, "new second.pdf"));
      publication.drain(0);
      Assertions.assertFalse(Files.exists(first));

      OutputTransaction.complete(first);
      publication.drain(0);
      Assertions.assertEquals("new first.pdf", Files.readString(first));
      Assertions.assertFalse(Files.exists(second));
      Assertions.assertFalse(Files.exists(current));

      Files.writeString(first, "external edit");
      OutputTransaction.complete(second);
      publication.drain(0);
      transaction.finish(manifest);
    }

    Path batch = OutputTransaction.publish(manifest, stage, budget, () -> false, publication.published);
    Assertions.assertEquals("external edit", Files.readString(first));
    Assertions.assertEquals("new second.pdf", Files.readString(second));
    assertBatchFiles(batch, "new", "");
    Assertions.assertEquals(Files.readString(batch), Files.readString(current));
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
      Assertions.assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  private List<OutputTransaction.Entry> batchEntries(Path output, String version, String prefix) throws IOException {
    Path stage = Files.createDirectory(directory.resolve(version + "-stage"));
    List<OutputTransaction.Entry> entries = new ArrayList<>();
    for (String name : List.of("first.pdf", "second.pdf")) {
      Path source = Files.writeString(stage.resolve(name), version + " " + name);
      entries.add(new OutputTransaction.Entry(output.resolve(prefix + name).toString(), source.toString(),
          Files.size(source), Common.digestHex(source, "SHA-256")));
    }
    return entries;
  }

  private static void replaceBatchFile(Path source, Path target) throws IOException {
    Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
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
    Path backup = Files.writeString(output.resolve(".eapdf-backup-recovery.tmp"), "original before old batch");
    Path recoveryJournal = directory.resolve("interrupted.journal");
    var entry = new OutputTransaction.Recovery(destination.toString(), output.resolve(".eapdf-publish-recovery.tmp").toString(),
        backup.toString(), OutputTransaction.fileIdentity(destination));
    // Simulate a crash after the atomic move consumed the ready pointer, before journal cleanup.
    Assertions.assertFalse(Files.exists(old.resolveSibling(".current.json.tmp")));
    OutputTransaction.writeJournal(recoveryJournal,
        new OutputTransaction.Journal(false, List.of(entry), new OutputTransaction.Batch(old.getParent().toString(), true)));

    OutputTransaction.recover(recoveryJournal);

    Assertions.assertEquals(Files.readString(latest), Files.readString(current));
    Assertions.assertEquals("old first.pdf", Files.readString(destination));
    assertBatchFiles(old, "old", "");
    assertBatchFiles(latest, "more", "other-");
    Assertions.assertFalse(Files.exists(backup));
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

  @Test void recoveryRestoresInterruptedPublicationButKeepsCommittedPublication() throws Exception {
    Path destination = directory.resolve("output.pdf");
    Path backup = directory.resolve("backup.pdf");
    Path prepared = directory.resolve("prepared.pdf");
    Path journal = directory.resolve("publication.json");
    Files.writeString(destination, "new PDF");
    var recovery = List.of(new OutputTransaction.Recovery(destination.toString(), prepared.toString(), backup.toString(),
        OutputTransaction.fileIdentity(destination)));
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

  @Test void stalePublicationCannotRollBackANewerOutput() throws Exception {
    Path destination = directory.resolve("output.pdf");
    Path prepared = directory.resolve("prepared.pdf");
    Path backup = directory.resolve("backup.pdf");
    Path journal = directory.resolve("publication.json");
    Files.writeString(destination, "previous replacement");
    Files.writeString(backup, "previous original");
    var recovery = new OutputTransaction.Recovery(destination.toString(), prepared.toString(), backup.toString(),
        OutputTransaction.fileIdentity(destination));
    OutputTransaction.writeJournal(journal, new OutputTransaction.Journal(false, List.of(recovery)));
    Files.writeString(prepared, "new committed output");
    Files.move(prepared, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    OutputTransaction.recover(journal);
    Assertions.assertEquals("new committed output", Files.readString(destination));
    Assertions.assertFalse(Files.exists(backup));
  }

  @Test void archiveDestinationCannotAliasSourceOrConfiguration() throws Exception {
    Path source = Files.writeString(directory.resolve("source.eml"), "Subject: Source\r\n\r\nBody");
    Path alias = Files.createLink(directory.resolve("alias.pdf"), source);
    Path config = EapdfConfig.configurationPath();
    for (Path destination : List.of(source, alias, config)) {
      byte[] original = Files.readAllBytes(destination);
      String[] args = {"--archive-profile", "c", "--archive-output", destination.toString(), source.toString()};
      var error = Assertions.assertThrows(IOException.class,
          () -> ConversionSupervisor.supervise(EmailToPDFA.class.getName(), args));
      Assertions.assertTrue(error.getMessage().contains("would replace an input file"), error.getMessage());
      Assertions.assertThrows(IOException.class, () -> EmailToPDFA.runArchive(List.of(source.toString()),
          directory.toString(), destination.toString(), null, null, "", List.of(), false, directory.toString(), "c"));
      Assertions.assertArrayEquals(original, Files.readAllBytes(destination));
    }
  }

  @Test void recoversPublishedBeta5JournalWithOrWithoutBatchCommit() throws Exception {
    for (boolean committed : new boolean[]{false, true}) {
      Path output = Files.createDirectory(directory.toRealPath().resolve(committed ? "committed" : "interrupted"));
      Path workspace = Files.createDirectory(output.resolve(".eapdf-publication-" + java.util.UUID.randomUUID()));
      Path prepared = Files.writeString(workspace.resolve("prepared"), "new output");
      Path original = Files.writeString(workspace.resolve("original"), "original output");
      Path destination = Files.createLink(output.resolve("output.pdf"), prepared);
      Path batchRoot = Files.createDirectory(output.resolve(".eapdf-publication"));
      Path generation = Files.createDirectory(batchRoot.resolve(java.util.UUID.randomUUID().toString()));
      Path candidate = Files.writeString(generation.resolve(".current.json.tmp"), "{}");
      Files.writeString(generation.resolve("manifest.json"), "{}");
      if (committed) Files.move(candidate, batchRoot.resolve("current.json"));

      Path journal = output.resolve("publication.json");
      var legacyFile = new LegacyOutputJournal.Recovery(destination.toString(), workspace.toString(),
          Common.digestHex(prepared, "SHA-256"), Common.digestHex(original, "SHA-256"));
      var legacyBatch = new OutputTransaction.Batch(generation.toString(), true);
      Files.writeString(journal, new com.google.gson.Gson().toJson(
          new LegacyOutputJournal.Journal(false, List.of(legacyFile), legacyBatch)));

      OutputTransaction.recover(journal);

      Assertions.assertEquals(committed ? "new output" : "original output", Files.readString(destination));
      Assertions.assertEquals(committed, Files.exists(generation));
      Assertions.assertEquals(committed, Files.exists(batchRoot.resolve("current.json")));
      Assertions.assertFalse(Files.exists(workspace));
      Assertions.assertFalse(Files.exists(journal));
    }
  }

  @Test void generatedOutputCannotOverwriteFileAppearingDuringPublication() throws Exception {
    Path created = directory.resolve("created.pdf");
    Path competitor = directory.resolve("competitor.pdf");
    Path staged = Files.writeString(directory.resolve("staged.pdf"), "new PDF");
    var outputs = List.of(new OutputTransaction.Entry(created.toString(), staged.toString(), 7, "", false),
        new OutputTransaction.Entry(competitor.toString(), staged.toString(), 7, "", true));
    Assertions.assertThrows(java.nio.file.FileAlreadyExistsException.class,
        () -> OutputTransaction.publish(outputs, (prepared, target) -> {
          Files.move(prepared, target);
          Files.writeString(competitor, "other writer");
        }));
    Assertions.assertFalse(Files.exists(created));
    Assertions.assertEquals("other writer", Files.readString(competitor));
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
      var entry = new OutputTransaction.Entry(destination.toString(), payload.toString(), Files.size(payload), "");
      OutputTransaction.publish(List.of(entry), (source, target) -> {
        Files.move(source, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
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
        OutputTransaction.recover(directory.resolve("first.journal"));
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
    ConversionSupervisor.writeOwner(abandoned, new ConversionSupervisor.Owner(process.pid(), process.info().startInstant().orElseThrow().minusSeconds(1).toString(), 0, ""));
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
