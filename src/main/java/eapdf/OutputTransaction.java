package eapdf;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

final class OutputTransaction implements AutoCloseable {
  static volatile OutputTransaction active;
  final Path directory;
  final Path workspace;
  final Map<Path, Entry> entries = new LinkedHashMap<>();
  private final Set<Path> replacements = new java.util.HashSet<>();
  private final Map<Path, Entry> completed = new LinkedHashMap<>();
  private final Path ready;
  private long readySequence;
  private static final String LOCK_FILENAME = ".eapdf-publication.lock";
  private static final String PUBLICATION_DIRECTORY = ".eapdf-publication";

  record Entry(String destination, String staged, long size, String sha256, boolean createOnly) {
    Entry(String destination, String staged, long size, String sha256) { this(destination, staged, size, sha256, false); }
  }
  record Recovery(String destination, String prepared, String backup, String preparedIdentity) {}
  record Journal(boolean committed, List<Recovery> files, Batch batch) {
    Journal(boolean committed, List<Recovery> files) { this(committed, files, null); }
  }
  record Batch(String directory, boolean ready) {
    Path generation() { return Path.of(directory); }
    Path manifest() { return generation().resolve("manifest.json"); }
    Path preparedPointer() { return generation().resolve(".current.json.tmp"); }
    Path current() { return generation().getParent().resolve("current.json"); }
  }
  private record BatchFile(String path, long size, String sha256) {}
  private record BatchManifest(int schemaVersion, String generation, List<BatchFile> files) {}

  OutputTransaction(Path directory) throws IOException {
    this(directory, null);
  }

  OutputTransaction(Path directory, Path workspace) throws IOException {
    this.directory = directory.toAbsolutePath().normalize();
    this.workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
    Files.createDirectories(this.directory);
    Path queue = this.workspace == null ? null : this.workspace.resolve("ready");
    ready = queue != null && Files.isDirectory(queue, LinkOption.NOFOLLOW_LINKS) ? queue : null;
    if (active != null) throw new IllegalStateException("An output transaction is already active");
    active = this;
  }

  synchronized Path stage(Path destination) {
    Path output = destination.toAbsolutePath().normalize();
    if (output.startsWith(directory) || workspace != null && output.startsWith(workspace)) return output;
    Entry entry = entries.computeIfAbsent(output, key -> new Entry(key.toString(),
        directory.resolve(String.format("%06d", entries.size()) + ".asset").toString(), 0, "", !replacements.contains(key)));
    return Path.of(entry.staged());
  }

  static void allowReplacement(Path destination) {
    OutputTransaction transaction = active;
    if (transaction != null) synchronized (transaction) {
      transaction.replacements.add(destination.toAbsolutePath().normalize());
    }
  }

  static void protectArchiveInputs(Path destination, List<String> inputs, String font, String icc) throws IOException {
    for (String input : inputs) protectInput(destination, Path.of(input));
    protectConfiguredInputs(destination, font, icc);
  }

  static void protectConfiguredInputs(Path destination, String font, String icc) throws IOException {
    protectInput(destination, EapdfConfig.configurationPath());
    for (String asset : new String[]{font, icc, EapdfConfig.get().signing.pkcs12Path}) {
      if (asset != null && !asset.isBlank()) protectInput(destination, Path.of(asset));
    }
  }

  static void protectInput(Path destination, Path input) throws IOException {
    Path output = destination.toAbsolutePath().normalize();
    if (output.equals(input.toAbsolutePath().normalize())
        || Files.exists(output) && Files.exists(input) && Files.isSameFile(output, input)) {
      throw new IOException("The archive destination would replace an input file: " + input);
    }
  }

  synchronized void adopt(Path manifest, Path childDirectory) throws Exception {
    if (Files.size(manifest) > ProcessingLimits.metadataBytes()) throw Common.fail("Output manifest exceeds the metadata limit");
    Entry[] completed = new Gson().fromJson(Files.readString(manifest), Entry[].class);
    if (completed == null) throw new IOException("Missing child output manifest");
    for (Entry entry : completed) {
      Path source = Path.of(entry.staged()).toAbsolutePath().normalize();
      Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
      if (!source.startsWith(childDirectory.toAbsolutePath().normalize())
          || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || entries.containsKey(destination)
          || Files.size(source) != entry.size() || !Common.digestHex(source, "SHA-256").equals(entry.sha256()))
        throw new IOException("Invalid child output manifest");
      if (!entry.createOnly()) throw new IOException("Unexpected replacement in child output manifest");
      Path target = stage(destination);
      if (target.equals(destination)) throw new IOException("Child destination is inside working storage");
      Files.move(source, target);
    }
  }

  static Path readable(Path destination) {
    OutputTransaction transaction = active;
    if (transaction == null) return destination;
    synchronized (transaction) {
      Entry entry = transaction.entries.get(destination.toAbsolutePath().normalize());
      return entry == null ? destination : Path.of(entry.staged());
    }
  }

  static void write(Path destination, Common.AtomicFileWriter writer) throws Exception {
    OutputTransaction transaction = active;
    Path staged = transaction == null ? destination : transaction.stage(destination);
    Common.writeAtomically(staged, temporary -> {
      writer.write(temporary);
      ProcessingLimits.checkOutput(Files.size(temporary));
    });
  }

  boolean incremental() {
    return ready != null;
  }

  static void complete(Path destination) throws Exception {
    OutputTransaction transaction = active;
    if (transaction == null || !transaction.incremental()) return;
    synchronized (transaction) {
      Path output = destination.toAbsolutePath().normalize();
      Entry entry = transaction.entries.get(output);
      if (entry == null) return; // A collection child inside the worker workspace.
      Path staged = Path.of(entry.staged());
      transaction.queue(output, new Entry(entry.destination(), entry.staged(), Files.size(staged),
          Common.digestHex(staged, "SHA-256"), entry.createOnly()));
    }
  }

  private void queue(Path destination, Entry entry) throws Exception {
    if (completed.putIfAbsent(destination, entry) != null) throw new IOException("Duplicate completed output: " + destination);
    Path receipt = ready.resolve(Long.toString(readySequence++) + ".json");
    Common.writeAtomically(receipt, new Gson().toJson(entry).getBytes(StandardCharsets.UTF_8));
  }

  synchronized void adoptReady(Path child) throws Exception {
    if (!incremental()) return;
    try (var receipts = Files.newDirectoryStream(child.resolve("ready"), "*.json")) {
      for (Path receipt : receipts) {
        Entry entry = readReady(receipt);
        Path source = verify(entry, child.resolve("outputs"));
        Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
        if (entries.containsKey(destination)) throw new IOException("Duplicate output destination: " + destination);
        if (!entry.createOnly()) throw new IOException("Unexpected replacement in child output receipt");
        Path staged = stage(destination);
        if (staged.equals(destination)) throw new IOException("Child destination is inside working storage");
        Files.move(source, staged);
        queue(destination, new Entry(entry.destination(), staged.toString(), entry.size(), entry.sha256(), entry.createOnly()));
        Files.delete(receipt);
      }
    }
  }

  private static Entry readReady(Path receipt) throws IOException {
    if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) || Files.size(receipt) > ProcessingLimits.metadataBytes())
      throw new IOException("Invalid completed output receipt");
    Entry entry = new Gson().fromJson(Files.readString(receipt), Entry.class);
    if (entry == null) throw new IOException("Missing completed output receipt");
    return entry;
  }

  private static Path verify(Entry entry, Path directory) throws Exception {
    Path staged = Path.of(entry.staged()).toAbsolutePath().normalize();
    if (!staged.startsWith(directory.toAbsolutePath().normalize()) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
        || Files.size(staged) != entry.size() || !Common.digestHex(staged, "SHA-256").equals(entry.sha256()))
      throw new IOException("Invalid staged output manifest");
    return staged;
  }

  static final class LivePublication {
    private final Path workspace;
    private final PstJobBudget budget;
    private final BooleanSupplier cancelled;
    final Map<Path, Entry> published = new LinkedHashMap<>();
    private final long outputBudget;
    private long total;

    LivePublication(Path workspace, PstJobBudget budget, BooleanSupplier cancelled) {
      this.workspace = workspace;
      this.budget = budget;
      this.cancelled = cancelled;
      outputBudget = budget.setting("eapdf.maxOutputBytes", "EAPDF_MAX_OUTPUT_BYTES", 1024L * 1024 * 1024);
    }

    void drain(long monitoredTemporaryBytes) throws Exception {
      try (var receipts = Files.newDirectoryStream(workspace.resolve("ready"), "*.json")) {
        for (Path receipt : receipts) {
          checkCancelled(cancelled);
          Entry entry = readReady(receipt);
          verify(entry, workspace.resolve("outputs"));
          Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
          if (published.containsKey(destination)) throw new IOException("Duplicate output destination: " + destination);
          long nextTotal = Math.addExact(total, entry.size());
          if (nextTotal > outputBudget) throw Common.fail("Conversion exceeds the output byte limit");
          long temporaryBudget = budget.setting("eapdf.maxTempBytes", "EAPDF_MAX_TEMP_BYTES", 2L * 1024 * 1024 * 1024);
          long available = temporaryBudget - Math.max(nextTotal, monitoredTemporaryBytes) - entry.size();
          if (available < 0) {
            monitoredTemporaryBytes = ConversionSupervisor.checkTemporary(workspace, temporaryBudget,
                budget.setting("eapdf.maxTempFiles", "EAPDF_MAX_TEMP_FILES", 100000));
            available = temporaryBudget - Math.max(nextTotal, monitoredTemporaryBytes) - entry.size();
          }
          if (available < 0) throw Common.fail("Publication would exceed the temporary-storage budget");
          publish(List.of(entry), OutputTransaction::replaceOutput, workspace.resolve("publication.json"),
              available, cancelled, Set.of());
          published.put(destination, entry);
          total = nextTotal;
          Files.delete(receipt);
          System.out.println("Published PDF: " + destination);
        }
      }
    }
  }

  void finish(Path manifest) throws Exception {
    List<Entry> completed = new ArrayList<>();
    long total = 0;
    ConversionProgress.begin("hashing_outputs", entries.size(), "files");
    for (Entry entry : entries.values()) {
      Path staged = Path.of(entry.staged());
      Path destination = Path.of(entry.destination());
      Entry ready = this.completed.get(destination);
      if (ready == null) ready = new Entry(entry.destination(), entry.staged(), Files.size(staged),
          Common.digestHex(staged, "SHA-256"), entry.createOnly());
      total = Math.addExact(total, ready.size());
      ProcessingLimits.checkOutput(total);
      completed.add(ready);
      ConversionProgress.advance("hashing_outputs");
    }
    byte[] json = new Gson().toJson(completed).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (json.length > ProcessingLimits.metadataBytes()) throw Common.fail("Output manifest exceeds the metadata limit");
    Common.writeAtomically(manifest, json);
  }

  static Path publish(Path manifest, Path directory) throws Exception {
    return publish(manifest, directory, new PstJobBudget());
  }

  static Path publish(Path manifest, Path directory, PstJobBudget budget) throws Exception {
    return publish(manifest, directory, budget, () -> false);
  }

  static Path publish(Path manifest, Path directory, PstJobBudget budget, BooleanSupplier cancelled) throws Exception {
    return publish(manifest, directory, budget, cancelled, Map.of());
  }

  static Path publish(Path manifest, Path directory, PstJobBudget budget, BooleanSupplier cancelled,
                      Map<Path, Entry> published) throws Exception {
    long manifestLimit = budget.setting("eapdf.maxMetadataBytes", "EAPDF_MAX_METADATA_BYTES", ProcessingLimits.messageBytes());
    if (Files.size(manifest) > manifestLimit) throw Common.fail("Output manifest exceeds the metadata limit");
    Entry[] entries = new Gson().fromJson(Files.readString(manifest), Entry[].class);
    if (entries == null) throw new IOException("Missing output manifest entries");
    long total = 0, remaining = 0;
    var destinations = new java.util.HashSet<Path>();
    ConversionProgress.begin("verifying_outputs", entries.length, "files");
    for (Entry entry : entries) {
      checkCancelled(cancelled);
      Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
      if (!destinations.add(destination)) throw new IOException("Duplicate output destination: " + destination);
      Entry live = published.get(destination);
      if (live == null) {
        verify(entry, directory);
        remaining = Math.addExact(remaining, entry.size());
      } else if (!live.equals(entry)) throw new IOException("Final manifest differs from published output: " + destination);
      total = Math.addExact(total, entry.size());
      if (total > budget.setting("eapdf.maxOutputBytes", "EAPDF_MAX_OUTPUT_BYTES", 1024L * 1024 * 1024)) throw Common.fail("Conversion exceeds the output byte limit");
      ConversionProgress.advance("verifying_outputs");
    }
    long temporaryLimit = budget.setting("eapdf.maxTempBytes", "EAPDF_MAX_TEMP_BYTES", 2L * 1024 * 1024 * 1024);
    long currentTemporary = ConversionSupervisor.checkTemporary(directory.getParent(), temporaryLimit, budget.setting("eapdf.maxTempFiles", "EAPDF_MAX_TEMP_FILES", 100000));
    long available = temporaryLimit - currentTemporary;
    // A batch keeps its own bytes separate from the writable direct outputs.
    if (!destinations.containsAll(published.keySet())) throw new IOException("Final manifest omits published outputs");
    if (remaining > available || (entries.length > 1 && total > available - remaining))
      throw Common.fail("Publication would exceed the temporary-storage budget");
    available -= remaining;
    if (entries.length > 1) available -= total;
    return publish(List.of(entries), OutputTransaction::replaceOutput, manifest.resolveSibling("publication.json"),
        available, cancelled, published.keySet());
  }

  interface MoveOperation {
    void move(Path source, Path target) throws IOException;
  }

  static void publish(List<Entry> entries, MoveOperation move) throws Exception {
    publish(entries, move, null);
  }

  static Path publish(List<Entry> entries, MoveOperation move, Path journal) throws Exception {
    return publish(entries, move, journal, Long.MAX_VALUE, () -> false, Set.of());
  }

  private static synchronized Path publish(List<Entry> entries, MoveOperation move, Path journal,
                                           long backupBudget, BooleanSupplier cancelled, Set<Path> published) throws Exception {
    Map<Path, Entry> sources = new LinkedHashMap<>();
    Set<Path> committed = new java.util.HashSet<>();
    for (Entry entry : entries) {
      Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
      checkOutputDestination(destination);
      Files.createDirectories(destination.getParent());
      boolean alreadyPublished = published.contains(destination);
      destination = destination.getParent().toRealPath().resolve(destination.getFileName());
      checkOutputDestination(destination);
      if (alreadyPublished) committed.add(destination);
      if (sources.putIfAbsent(destination, entry) != null)
        throw new IOException("Duplicate output destination: " + destination);
    }
    try (var locks = new DestinationLocks(sources.keySet(), cancelled)) {
      return publishLocked(sources, move, journal, backupBudget, cancelled, committed);
    }
  }

  private static void checkOutputDestination(Path destination) throws IOException {
    if (LOCK_FILENAME.equalsIgnoreCase(destination.getFileName().toString()))
      throw new IOException("Output destination is reserved for publication locking: " + destination);
    for (Path part : destination) if (PUBLICATION_DIRECTORY.equalsIgnoreCase(part.toString()))
      throw new IOException("Output destination is inside reserved batch storage: " + destination);
  }

  private static Path publishLocked(Map<Path, Entry> sources, MoveOperation move, Path journal,
                                    long backupBudget, BooleanSupplier cancelled, Set<Path> published) throws Exception {
    checkCancelled(cancelled);
    List<Recovery> recovery = new ArrayList<>();
    for (Path destination : sources.keySet()) {
      if (published.contains(destination)) {
        recovery.add(new Recovery(destination.toString(), null, null, ""));
        continue;
      }
      boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
      if (exists && sources.get(destination).createOnly())
        throw new IOException("Another conversion already published the automatically named output: " + destination);
      if (exists && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Output destination is not a regular file: " + destination);
      }
      Path temporary = plannedFile(destination.getParent(), ".eapdf-publish-");
      Path backup = exists ? plannedFile(destination.getParent(), ".eapdf-backup-") : null;
      recovery.add(new Recovery(destination.toString(), temporary.toString(), backup == null ? null : backup.toString(), ""));
    }
    Batch batch = null;
    if (journal != null && sources.size() > 1) {
      Path parent = sources.keySet().stream().map(Path::getParent).min(Path::compareTo).orElseThrow();
      Path root = parent.resolve(PUBLICATION_DIRECTORY);
      if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid batch publication directory: " + root);
      batch = new Batch(root.resolve(UUID.randomUUID().toString()).toString(), false);
    }
    // Record planned names before creating any file outside the worker workspace.
    if (journal != null) writeJournal(journal, new Journal(false, recovery, batch));
    try {
      ConversionProgress.begin("copying_outputs", sources.size() - published.size(), "files");
      byte[] buffer = new byte[64 * 1024];
      for (int index = 0; index < recovery.size(); index++) {
        Recovery item = recovery.get(index);
        if (item.prepared() == null) continue;
        Path destination = Path.of(item.destination());
        Path temporary = Path.of(item.prepared());
        if (item.backup() != null) {
          Path backup = Path.of(item.backup());
          try { Files.createLink(backup, destination); }
          catch (IOException | UnsupportedOperationException error) {
            long size = Files.size(destination);
            if (size > backupBudget) throw new IOException("Output backup would exceed the temporary-storage budget", error);
            Files.copy(destination, backup, StandardCopyOption.COPY_ATTRIBUTES);
            backupBudget -= size;
          }
        }
        copyOutput(Path.of(sources.get(destination).staged()), temporary, destination, buffer, cancelled);
        recovery.set(index, new Recovery(item.destination(), item.prepared(), item.backup(), fileIdentity(temporary)));
        ConversionProgress.advance("copying_outputs");
      }
      if (batch != null) prepareBatch(batch, recovery, sources, buffer, cancelled);
      if (journal != null) writeJournal(journal, new Journal(false, recovery, batch));
      ConversionProgress.begin("publishing", recovery.size() - published.size(), "files");
      for (Recovery item : recovery) {
        if (item.prepared() == null) continue;
        checkCancelled(cancelled);
        Path destination = Path.of(item.destination());
        Path prepared = Path.of(item.prepared());
        if (sources.get(destination).createOnly()) {
          Files.createLink(destination, prepared);
        } else {
          move.move(prepared, destination);
        }
        ConversionProgress.advance("publishing");
      }
      checkCancelled(cancelled);
      if (batch != null) {
        Batch ready = new Batch(batch.directory(), true);
        writeJournal(journal, new Journal(false, recovery, ready));
        batch = ready;
        checkCancelled(cancelled);
        // This rename is the commit point. It consumes the private candidate.
        replaceOutput(batch.preparedPointer(), batch.current());
      } else if (journal != null) writeJournal(journal, new Journal(true, recovery));
    } catch (Exception | Error failure) {
      try { finishRecovery(new Journal(false, recovery, batch), journal); }
      catch (Exception cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
    finishRecovery(new Journal(true, recovery, batch), journal);
    return batch == null ? null : batch.manifest();
  }

  private static void prepareBatch(Batch batch, List<Recovery> files, Map<Path, Entry> sources,
                                   byte[] buffer, BooleanSupplier cancelled) throws Exception {
    Path generation = batch.generation();
    Path root = generation.getParent();
    Files.createDirectories(root);
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid batch publication directory: " + root);
    if (Files.exists(batch.current(), LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(batch.current(), LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Batch manifest destination is not a regular file: " + batch.current());
    var store = Files.getFileStore(root);
    if (store.supportsFileAttributeView("posix")) {
      Files.createDirectory(generation, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } else {
      Files.createDirectory(generation);
      if (store.supportsFileAttributeView("acl")) Common.makeOwnerOnly(generation);
    }

    List<BatchFile> batchFiles = new ArrayList<>();
    ConversionProgress.begin("copying_batch", files.size(), "files");
    for (int index = 0; index < files.size(); index++) {
      Recovery file = files.get(index);
      Path destination = Path.of(file.destination());
      String item = String.format(java.util.Locale.ROOT, "%06d", index);
      Path folder = Files.createDirectory(generation.resolve(item));
      Entry entry = sources.get(destination);
      Path payload = Path.of(file.prepared() == null ? entry.staged() : file.prepared());
      Path target = folder.resolve(destination.getFileName());
      copyOutput(payload, target, null, buffer, cancelled);
      if (file.prepared() == null && (Files.size(target) != entry.size()
          || !Common.digestHex(target, "SHA-256").equals(entry.sha256())))
        throw new IOException("Batch snapshot differs from published output: " + destination);
      batchFiles.add(new BatchFile(item + "/" + destination.getFileName(), entry.size(), entry.sha256()));
      ConversionProgress.advance("copying_batch");
    }
    byte[] manifest = new Gson().toJson(new BatchManifest(1, generation.getFileName().toString(), batchFiles))
        .getBytes(StandardCharsets.UTF_8);
    if (manifest.length > ProcessingLimits.metadataBytes()) throw Common.fail("Batch manifest exceeds the metadata limit");
    Common.writeAtomically(batch.manifest(), manifest);
    Common.writeAtomically(batch.preparedPointer(), manifest);
  }

  private static void copyOutput(Path source, Path target, Path permissionSource, byte[] buffer,
                                 BooleanSupplier cancelled) throws IOException {
    checkCancelled(cancelled);
    if (Files.getFileStore(target.getParent()).supportsFileAttributeView("posix")) {
      Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } else Files.createFile(target);
    try (var input = Files.newInputStream(source); var channel = FileChannel.open(target, StandardOpenOption.WRITE)) {
      var output = java.nio.channels.Channels.newOutputStream(channel);
      for (int count; (count = input.read(buffer)) != -1;) {
        checkCancelled(cancelled);
        output.write(buffer, 0, count);
      }
      if (permissionSource != null) Common.preserveOutputPermissions(permissionSource, target);
      channel.force(true);
    }
  }

  private static Path plannedFile(Path directory, String prefix) {
    return directory.resolve(prefix + UUID.randomUUID() + ".tmp");
  }

  private static void checkCancelled(BooleanSupplier cancelled) {
    ProcessingLimits.checkCancelled();
    if (cancelled.getAsBoolean()) throw Common.fail("Conversion cancelled during output publication");
  }

  private static void replaceOutput(Path source, Path destination) throws IOException {
    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  static String fileIdentity(Path path) throws IOException {
    var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Object key = attributes.fileKey();
    if (key == null) return null;
    return key + ":" + attributes.creationTime() + ":" + attributes.lastModifiedTime() + ":" + attributes.size();
  }

  static void writeJournal(Path path, Journal journal) throws Exception {
    Common.writeAtomically(path, new Gson().toJson(journal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static synchronized void recover(Path path) throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > ProcessingLimits.metadataBytes()) {
      throw new IOException("Invalid output recovery journal: " + path);
    }
    var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    var files = json.getAsJsonArray("files");
    if (files == null) throw new IOException("Invalid output recovery journal: " + path);
    Gson gson = new Gson();
    if (!files.isEmpty() && files.get(0).getAsJsonObject().has("workspace")) {
      recoverLegacy(gson.fromJson(json, LegacyOutputJournal.Journal.class), path);
      return;
    }
    Journal journal = gson.fromJson(json, Journal.class);
    try (var locks = new DestinationLocks(journal.files().stream().map(file -> Path.of(file.destination())).toList(), () -> false)) {
      finishRecovery(journal, path);
    }
  }

  private static void recoverLegacy(LegacyOutputJournal.Journal journal, Path path) throws Exception {
    List<Path> destinations = journal.files().stream().map(file -> Path.of(file.destination())).toList();
    try (var locks = new DestinationLocks(destinations, () -> false)) {
      Batch batch = journal.batch();
      if (batch != null) checkBatchRecovery(batch, destinations);
      boolean committed = publicationCommitted(journal.committed(), batch);
      LegacyOutputJournal.recoverFiles(journal, path, committed);
      if (batch != null && !committed) {
        if (batch.ready()) {
          var aborted = new LegacyOutputJournal.Journal(false, journal.files(), new Batch(batch.directory(), false));
          Common.writeAtomically(path, new Gson().toJson(aborted).getBytes(StandardCharsets.UTF_8));
        }
        Common.deleteTree(batch.generation());
      }
      Files.delete(path);
    }
  }

  private static boolean publicationCommitted(boolean committed, Batch batch) throws IOException {
    if (committed || batch == null || !batch.ready()) return committed;
    Path prepared = batch.preparedPointer();
    if (Files.notExists(prepared, LinkOption.NOFOLLOW_LINKS)) return true;
    if (!Files.isRegularFile(prepared, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Cannot establish batch publication state: " + batch.generation());
    }
    return false;
  }

  private static void finishRecovery(Journal journal, Path path) throws Exception {
    Batch batch = journal.batch();
    if (batch != null) {
      checkBatchRecovery(batch, journal.files().stream().map(file -> Path.of(file.destination())).toList());
    }
    boolean committed = publicationCommitted(journal.committed(), batch);
    for (Recovery file : journal.files().reversed()) {
      if (file.prepared() == null) continue;
      Path destination = Path.of(file.destination());
      Path prepared = Path.of(file.prepared());
      Path backup = file.backup() == null ? null : Path.of(file.backup());
      if (!committed) {
        boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        boolean linked = backup == null && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
            && Files.isRegularFile(prepared, LinkOption.NOFOLLOW_LINKS) && Files.isSameFile(destination, prepared);
        if (linked && (file.preparedIdentity() == null || file.preparedIdentity().equals(fileIdentity(destination)))) {
          Files.delete(destination);
        } else if (file.preparedIdentity() == null) {
          if (exists && !Files.exists(prepared, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Publication recovery cannot prove ownership of " + destination);
          if (!exists && backup != null && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) replaceOutput(backup, destination);
        } else if (file.preparedIdentity() != null && !file.preparedIdentity().isEmpty()
            && !Files.exists(prepared, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
            && file.preparedIdentity().equals(fileIdentity(destination))) {
          if (backup == null) Files.delete(destination);
          else replaceOutput(backup, destination);
        }
      }
      Files.deleteIfExists(prepared);
      if (backup != null) Files.deleteIfExists(backup);
    }
    if (batch != null && !committed) {
      // Deleting a ready candidate would otherwise look like a successful rename after a crash.
      if (batch.ready()) writeJournal(path, new Journal(false, journal.files(), new Batch(batch.directory(), false)));
      Common.deleteTree(batch.generation());
    }
    if (path != null) Files.deleteIfExists(path);
  }

  private static void checkBatchRecovery(Batch batch, List<Path> destinations) throws IOException {
    Path generation = batch.generation();
    Path root = generation.getParent();
    String name = generation.getFileName().toString();
    if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        || root == null || !PUBLICATION_DIRECTORY.equals(root.getFileName().toString())
        || destinations.stream().noneMatch(destination -> destination.getParent().equals(root.getParent()))
        || (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        || (Files.exists(generation, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(generation, LinkOption.NOFOLLOW_LINKS))
        || (batch.ready() && !Files.isRegularFile(batch.manifest(), LinkOption.NOFOLLOW_LINKS)))
      throw new IOException("Invalid batch publication recovery directory: " + generation);
  }

  private static final class DestinationLocks implements AutoCloseable {
    private final List<FileChannel> channels = new ArrayList<>();

    DestinationLocks(java.util.Collection<Path> destinations, BooleanSupplier cancelled) throws IOException {
      var parents = new java.util.TreeSet<Path>();
      for (Path destination : destinations) parents.add(destination.toAbsolutePath().getParent().toRealPath());
      try {
        for (Path parent : parents) {
          // Keep the lock file's inode stable when other JVMs are waiting.
          Path lock = parent.resolve(LOCK_FILENAME);
          FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
          channels.add(channel);
          while (channel.tryLock() == null) {
            checkCancelled(cancelled);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(25));
          }
        }
      } catch (IOException | RuntimeException failure) {
        try { close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
        throw failure;
      }
    }

    @Override public void close() throws IOException {
      IOException failure = null;
      for (FileChannel channel : channels.reversed()) {
        try { channel.close(); }
        catch (IOException error) {
          if (failure == null) failure = error;
          else failure.addSuppressed(error);
        }
      }
      if (failure != null) throw failure;
    }
  }

  @Override public void close() {
    if (active == this) active = null;
  }
}
