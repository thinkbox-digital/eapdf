package eapdf;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

final class OutputTransaction implements AutoCloseable {
  static volatile OutputTransaction active;
  final Path directory;
  final Path workspace;
  final Map<Path, Entry> entries = new LinkedHashMap<>();


  private final java.util.Set<Path> replacements = new java.util.HashSet<>();

  private static final String LOCK_FILENAME = ".eapdf-publication.lock";
  private static final String PUBLICATION_DIRECTORY = ".eapdf-publication";

  record Entry(String destination, String staged, long size, String sha256, boolean replace) {
    Entry(String destination, String staged, long size, String sha256) { this(destination, staged, size, sha256, false); }
  }
  record Recovery(String destination, String workspace, String sha256, String originalSha256) {}

  static void allowReplacement(Path destination) {
    OutputTransaction transaction = active;
    if (transaction != null) synchronized (transaction) {
      transaction.replacements.add(destination.toAbsolutePath().normalize());
    }
  }

  static void protectInput(Path destination, Path input) throws IOException {
    if (destination.toAbsolutePath().normalize().equals(input.toAbsolutePath().normalize())
        || Files.exists(destination) && Files.exists(input) && Files.isSameFile(destination, input)) {
      throw new IOException("The archive destination would replace an input file: " + input);
    }
  }
  static void protectConfiguredInputs(Path destination, String font, String icc) throws IOException {
    protectInput(destination, EapdfConfig.configurationPath());
    var signing = EapdfConfig.get().signing;
    for (String asset : new String[]{font, icc, signing.pkcs12Path}) {
      if (asset != null && !asset.isBlank()) protectInput(destination, Path.of(asset));
    }
  }

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
    if (active != null) throw new IllegalStateException("An output transaction is already active");
    active = this;
  }

  synchronized Path stage(Path destination) {
    Path output = destination.toAbsolutePath().normalize();
    if (output.startsWith(directory) || workspace != null && output.startsWith(workspace)) return output;
    Entry entry = entries.computeIfAbsent(output, key -> new Entry(key.toString(),
        directory.resolve(String.format("%06d", entries.size()) + ".asset").toString(), 0, "", replacements.contains(key)));
    return Path.of(entry.staged());
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
      if (entry.replace()) throw new IOException("Unexpected replacement in child output manifest");
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

  void finish(Path manifest) throws Exception {
    List<Entry> completed = new ArrayList<>();
    long total = 0;
    ConversionProgress.begin("hashing_outputs", entries.size(), "files");
    for (Entry entry : entries.values()) {
      Path staged = Path.of(entry.staged());
      long size = Files.size(staged);
      total = Math.addExact(total, size);
      ProcessingLimits.checkOutput(total);
      completed.add(new Entry(entry.destination(), entry.staged(), size, Common.digestHex(staged, "SHA-256"), entry.replace()));
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
    long manifestLimit = budget.setting("eapdf.maxMetadataBytes", "EAPDF_MAX_METADATA_BYTES", ProcessingLimits.messageBytes());
    if (Files.size(manifest) > manifestLimit) throw Common.fail("Output manifest exceeds the metadata limit");
    Entry[] entries = new Gson().fromJson(Files.readString(manifest), Entry[].class);
    if (entries == null) throw new IOException("Missing output manifest entries");
    // A collection can publish its PDF plus finding-aid PDF, spreadsheet and PREMIS sidecars.
    long total = 0;
    var destinations = new java.util.HashSet<Path>();
    ConversionProgress.begin("verifying_outputs", entries.length, "files");
    for (Entry entry : entries) {
      checkCancelled(cancelled);
      Path staged = Path.of(entry.staged()).toAbsolutePath().normalize();
      Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
      if (!staged.startsWith(directory.toAbsolutePath().normalize()) || !Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
          || !destinations.add(destination) || Files.size(staged) != entry.size()
          || !Common.digestHex(staged, "SHA-256").equals(entry.sha256())) throw new IOException("Invalid staged output manifest");
      total = Math.addExact(total, entry.size());
      if (total > budget.setting("eapdf.maxOutputBytes", "EAPDF_MAX_OUTPUT_BYTES", 1024L * 1024 * 1024)) throw Common.fail("Conversion exceeds the output byte limit");
      ConversionProgress.advance("verifying_outputs");
    }
    long temporaryLimit = budget.setting("eapdf.maxTempBytes", "EAPDF_MAX_TEMP_BYTES", 2L * 1024 * 1024 * 1024);
    long currentTemporary = ConversionSupervisor.checkTemporary(directory.getParent(), temporaryLimit, budget.setting("eapdf.maxTempFiles", "EAPDF_MAX_TEMP_FILES", 100000));
    long available = temporaryLimit - currentTemporary;
    if (total > available || (entries.length > 1 && total > available - total))
      throw Common.fail("Publication would exceed the temporary-storage budget");
    return publish(List.of(entries), (source, target) -> Files.createLink(target, source),
        manifest.resolveSibling("publication.json"), cancelled);
  }

  interface PublishOperation {
    void link(Path source, Path target) throws IOException;
  }

  static void publish(List<Entry> entries, PublishOperation publish) throws Exception {
    publish(entries, publish, null);
  }

  static Path publish(List<Entry> entries, PublishOperation publish, Path journal) throws Exception {
    return publish(entries, publish, journal, () -> false);
  }

  private static synchronized Path publish(List<Entry> entries, PublishOperation publish, Path journal,
                                           BooleanSupplier cancelled) throws Exception {
    Map<Path, Entry> destinations = new LinkedHashMap<>();
    for (Entry entry : entries) {
      Path target = Path.of(entry.destination()).toAbsolutePath().normalize();
      checkOutputDestination(target);
      Files.createDirectories(target.getParent());
      target = target.getParent().toRealPath().resolve(target.getFileName());
      checkOutputDestination(target);
      if (destinations.putIfAbsent(target, entry) != null) throw new IOException("Duplicate output destination: " + target);
    }
    try (var locks = new DestinationLocks(destinations.keySet(), cancelled)) {
      return publishLocked(destinations, publish, journal, cancelled);
    }
  }

  private static Path publishLocked(Map<Path, Entry> entries, PublishOperation publish, Path journal,
                                    BooleanSupplier cancelled) throws Exception {
    checkCancelled(cancelled);
    Batch batch = null;
    if (journal != null && entries.size() > 1) {
      Path parent = entries.keySet().stream().map(Path::getParent).min(Path::compareTo).orElseThrow();
      Path root = parent.resolve(PUBLICATION_DIRECTORY);
      if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        throw new IOException("Invalid batch publication directory: " + root);
      batch = new Batch(root.resolve(UUID.randomUUID().toString()).toString(), false);
    }
    List<Recovery> files = new ArrayList<>();
    int started = 0;
    boolean committed = false;
    byte[] buffer = new byte[64 * 1024];
    try {
      ConversionProgress.begin("copying_outputs", entries.size(), "files");
      for (var output : entries.entrySet()) {
        checkCancelled(cancelled);
        Path destination = output.getKey();
        Entry entry = output.getValue();
        String original = null;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          if (!entry.replace()) throw new java.nio.file.FileAlreadyExistsException(destination.toString());
          if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Output destination is not a regular file: " + destination);
          original = Common.digestHex(destination, "SHA-256");
        }
        Path workspace = destination.getParent().resolve(".eapdf-publication-" + UUID.randomUUID());
        Recovery file = new Recovery(destination.toString(), workspace.toString(), entry.sha256(), original);
        files.add(file);
        if (journal != null) writeJournal(journal, new Journal(false, files, batch));
        Files.createDirectory(workspace);
        Path prepared = workspace.resolve("prepared");
        copyOutput(Path.of(entry.staged()), prepared, destination, buffer, cancelled);
        if (!matches(prepared, file.sha256())) throw new IOException("Prepared output checksum mismatch: " + destination);
        ConversionProgress.advance("copying_outputs");
      }
      if (batch != null) prepareBatch(batch, files, entries, buffer, cancelled);
      ConversionProgress.begin("publishing", files.size(), "files");
      for (Recovery file : files) {
        checkCancelled(cancelled);
        Path destination = Path.of(file.destination());
        Path workspace = Path.of(file.workspace());
        Path original = workspace.resolve("original");
        Path prepared = workspace.resolve("prepared");
        started++;
        if (file.originalSha256() != null) {
          // Saving the original first lets a destination changed by another writer be put back safely.
          Files.move(destination, original);
          if (!matches(original, file.originalSha256())) {
            Files.createLink(destination, original);
            Files.delete(original);
            throw new IOException("Output destination changed during publication: " + destination);
          }
        }
        publish.link(prepared, destination);
        ConversionProgress.advance("publishing");
      }
      checkCancelled(cancelled);
      if (batch != null) {
        Batch ready = new Batch(batch.directory(), true);
        writeJournal(journal, new Journal(false, files, ready));
        batch = ready;
        checkCancelled(cancelled);
        // Consuming this candidate commits the complete generation in one rename.
        Files.move(batch.preparedPointer(), batch.current(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } else if (journal != null) writeJournal(journal, new Journal(true, files));
      committed = true;
    } finally {
      committed = publicationCommitted(committed, batch);
      boolean restored = true;
      if (!committed) {
        for (Recovery file : files.subList(0, started).reversed()) {
          try {
            rollback(file);
          } catch (IOException error) {
            restored = false;
            System.err.println("error: Output rollback needs attention. Recovery files retained in "
                + file.workspace() + ": " + error.getMessage());
          }
        }
      }
      if (committed || restored) {
        for (int index = 0; index < files.size(); index++) {
          Recovery file = files.get(index);
          if (!committed && index >= started) {
            // The live transaction created this directory and has never moved a destination into it.
            Path workspace = Path.of(file.workspace());
            Files.deleteIfExists(workspace.resolve("prepared"));
            Files.deleteIfExists(workspace);
          } else {
            cleanWorkspace(file);
          }
        }
        if (!committed) discardBatch(batch, files, journal);
        if (journal != null) Files.deleteIfExists(journal);
      }
    }
    return batch == null ? null : batch.manifest();
  }

  private static boolean publicationCommitted(boolean committed, Batch batch) throws IOException {
    if (committed || batch == null || !batch.ready()) return committed;
    if (Files.notExists(batch.preparedPointer(), LinkOption.NOFOLLOW_LINKS)) return true;
    if (!Files.isRegularFile(batch.preparedPointer(), LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Cannot establish batch publication state: " + batch.generation());
    return false;
  }

  private static void discardBatch(Batch batch, List<Recovery> files, Path journal) throws Exception {
    if (batch == null) return;
    checkBatchRecovery(batch, files);
    // Journal an abort before removing the ready candidate.
    if (batch.ready()) writeJournal(journal, new Journal(false, files, new Batch(batch.directory(), false)));
    Common.deleteTree(batch.generation());
  }

  private static void checkOutputDestination(Path destination) throws IOException {
    if (LOCK_FILENAME.equalsIgnoreCase(destination.getFileName().toString()))
      throw new IOException("Output destination is reserved for publication locking: " + destination);
    for (Path part : destination) if (PUBLICATION_DIRECTORY.equalsIgnoreCase(part.toString()))
      throw new IOException("Output destination is inside reserved batch storage: " + destination);
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
      copyOutput(Path.of(file.workspace()).resolve("prepared"), folder.resolve(destination.getFileName()), null, buffer, cancelled);
      Entry entry = sources.get(destination);
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

  private static void checkCancelled(BooleanSupplier cancelled) {
    ProcessingLimits.checkCancelled();
    if (cancelled.getAsBoolean()) throw Common.fail("Conversion cancelled during output publication");
  }

  private static boolean matches(Path path, String digest) throws IOException {
    return digest != null && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        && Common.digestHex(path, "SHA-256").equals(digest);
  }

  private static void rollback(Recovery file) throws IOException {
    Path destination = Path.of(file.destination());
    Path workspace = Path.of(file.workspace());
    Path prepared = workspace.resolve("prepared");
    Path original = workspace.resolve("original");
    Path displaced = workspace.resolve("displaced");

    if (!Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)
        && Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)
        && Files.isRegularFile(prepared, LinkOption.NOFOLLOW_LINKS)
        && Files.isSameFile(destination, prepared) && matches(destination, file.sha256())) {
      // Move first. Recheck the moved file before deleting anything in case a writer replaced the path.
      Files.move(destination, displaced);
    }
    if (Files.exists(displaced, LinkOption.NOFOLLOW_LINKS)) {
      if (!Files.isRegularFile(displaced, LinkOption.NOFOLLOW_LINKS) || !Files.exists(prepared)
          || !Files.isSameFile(displaced, prepared) || !matches(displaced, file.sha256())) {
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && Files.isRegularFile(displaced, LinkOption.NOFOLLOW_LINKS)) {
          Files.createLink(destination, displaced);
          Files.delete(displaced);
        }
        throw new IOException("Destination was changed by another writer: " + destination);
      }
      Files.delete(displaced);
    }
    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) {
      if (!matches(original, file.originalSha256()))
        throw new IOException("Recovery backup changed: " + original);
      if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
        if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS) || !Files.isSameFile(destination, original))
          throw new IOException("Destination was changed by another writer: " + destination);
      } else {
        Files.createLink(destination, original);
      }
    }
  }

  private static void cleanWorkspace(Recovery file) throws IOException {
    Path workspace = Path.of(file.workspace());
    Path prepared = workspace.resolve("prepared");
    Path original = workspace.resolve("original");
    if (Files.exists(original, LinkOption.NOFOLLOW_LINKS) && !matches(original, file.originalSha256()))
      throw new IOException("Recovery backup changed: " + original);
    if (Files.exists(prepared, LinkOption.NOFOLLOW_LINKS) && !matches(prepared, file.sha256()))
      throw new IOException("Prepared output is incomplete or has changed: " + prepared);
    Files.deleteIfExists(prepared);
    Files.deleteIfExists(original);
    Files.deleteIfExists(workspace);
  }

  static void writeJournal(Path path, Journal journal) throws Exception {
    Common.writeAtomically(path, new Gson().toJson(journal).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  static synchronized void recover(Path path) throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > ProcessingLimits.metadataBytes()) {
      throw new IOException("Invalid output recovery journal: " + path);
    }
    Journal journal = new Gson().fromJson(Files.readString(path), Journal.class);
    if (journal == null || journal.files() == null) throw new IOException("Invalid output recovery journal: " + path);
    if (journal.batch() != null) checkBatchRecovery(journal.batch(), journal.files());
    var destinations = journal.files().stream().map(file -> Path.of(file.destination())).toList();
    try (var locks = new DestinationLocks(destinations, () -> false)) {
      recoverLocked(path, journal);
    }
  }

  private static void recoverLocked(Path path, Journal journal) throws Exception {
    boolean committed = publicationCommitted(journal.committed(), journal.batch());
    for (Recovery file : journal.files()) {
      if (file.workspace() == null || file.destination() == null || file.sha256() == null
          || !file.sha256().matches("[0-9a-f]{64}"))
        throw new IOException("Unrecognized output recovery journal: " + path);
      Path destination = Path.of(file.destination()).toAbsolutePath().normalize();
      Path workspace = Path.of(file.workspace()).toAbsolutePath().normalize();
      if (!workspace.getParent().equals(destination.getParent())
          || !workspace.getFileName().toString().matches("\\.eapdf-publication-(?:[0-9]+|[0-9a-f-]{36})"))
        throw new IOException("Invalid output recovery workspace: " + workspace);
      if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) continue;
      if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
          || !workspace.equals(workspace.toRealPath()) || !Files.getOwner(workspace).equals(Files.getOwner(path)))
        throw new IOException("Invalid output recovery workspace: " + workspace);
      Recovery checked = new Recovery(destination.toString(), workspace.toString(), file.sha256(), file.originalSha256());
      if (!committed) rollback(checked);
      cleanWorkspace(checked);
    }
    if (!committed) discardBatch(journal.batch(), journal.files(), path);
    Files.delete(path);
  }

  private static void checkBatchRecovery(Batch batch, List<Recovery> files) throws IOException {
    Path generation = batch.generation();
    Path root = generation.getParent();
    String name = generation.getFileName().toString();
    if (!name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        || root == null || !PUBLICATION_DIRECTORY.equals(root.getFileName().toString())
        || files.stream().noneMatch(file -> Path.of(file.destination()).getParent().equals(root.getParent()))
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
