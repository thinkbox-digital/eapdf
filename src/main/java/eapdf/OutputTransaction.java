package eapdf;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OutputTransaction implements AutoCloseable {
  static volatile OutputTransaction active;
  final Path directory;
  final Path workspace;
  final Map<Path, Entry> entries = new LinkedHashMap<>();


  private final java.util.Set<Path> replacements = new java.util.HashSet<>();

  record Entry(String destination, String staged, long size, String sha256, boolean replace) {}
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

  record Journal(boolean committed, List<Recovery> files) {}

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

  static void publish(Path manifest, Path directory) throws Exception {
    publish(manifest, directory, new PstJobBudget());
  }

  static void publish(Path manifest, Path directory, PstJobBudget budget) throws Exception {
    long manifestLimit = budget.setting("eapdf.maxMetadataBytes", "EAPDF_MAX_METADATA_BYTES", ProcessingLimits.messageBytes());
    if (Files.size(manifest) > manifestLimit) throw Common.fail("Output manifest exceeds the metadata limit");
    Entry[] entries = new Gson().fromJson(Files.readString(manifest), Entry[].class);
    if (entries == null) throw new IOException("Missing output manifest entries");
    // A collection can publish its PDF plus finding-aid PDF, spreadsheet and PREMIS sidecars.
    long total = 0;
    var destinations = new java.util.HashSet<Path>();
    ConversionProgress.begin("verifying_outputs", entries.length, "files");
    for (Entry entry : entries) {
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
    if (total > temporaryLimit - currentTemporary) throw Common.fail("Publication would exceed the temporary-storage budget");
    publish(List.of(entries), (source, target) -> Files.createLink(target, source),
        manifest.resolveSibling("publication.json"));
  }

  interface PublishOperation {
    void link(Path source, Path target) throws IOException;
  }

  static void publish(List<Entry> entries, PublishOperation publish) throws Exception {
    publish(entries, publish, null);
  }

  static void publish(List<Entry> entries, PublishOperation publish, Path journal) throws Exception {
    List<Recovery> files = new ArrayList<>();
    int started = 0;
    var destinations = new java.util.HashSet<Path>();
    boolean committed = false;
    try {
      ConversionProgress.begin("copying_outputs", entries.size(), "files");
      for (Entry entry : entries) {
        Path requested = Path.of(entry.destination()).toAbsolutePath().normalize();
        Files.createDirectories(requested.getParent());
        Path destination = requested.getParent().toRealPath().resolve(requested.getFileName());
        if (!destinations.add(destination)) throw new IOException("Duplicate output destination: " + destination);
        String original = null;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          if (!entry.replace()) throw new java.nio.file.FileAlreadyExistsException(destination.toString());
          if (!Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Output destination is not a regular file: " + destination);
          original = Common.digestHex(destination, "SHA-256");
        }
        Path workspace = Files.createTempDirectory(destination.getParent(), ".eapdf-publication-");
        Recovery file = new Recovery(destination.toString(), workspace.toString(), entry.sha256(), original);
        files.add(file);
        if (journal != null) writeJournal(journal, new Journal(false, files));
        Path prepared = workspace.resolve("prepared");
        Files.copy(Path.of(entry.staged()), prepared);
        if (!matches(prepared, file.sha256())) throw new IOException("Prepared output checksum mismatch: " + destination);
        try (var channel = java.nio.channels.FileChannel.open(prepared, java.nio.file.StandardOpenOption.WRITE)) {
          Common.preserveOutputPermissions(destination, prepared);
          channel.force(true);
        }
        ConversionProgress.advance("copying_outputs");
      }
      ConversionProgress.begin("publishing", files.size(), "files");
      for (Recovery file : files) {
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
      if (journal != null) writeJournal(journal, new Journal(true, files));
      committed = true;
    } finally {
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
            Files.delete(workspace);
          } else {
            cleanWorkspace(file);
          }
        }
        if (journal != null) Files.deleteIfExists(journal);
      }
    }
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

  static void recover(Path path) throws Exception {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > ProcessingLimits.metadataBytes()) {
      throw new IOException("Invalid output recovery journal: " + path);
    }
    Journal journal = new Gson().fromJson(Files.readString(path), Journal.class);
    if (journal == null || journal.files() == null) throw new IOException("Invalid output recovery journal: " + path);
    for (Recovery file : journal.files()) {
      if (file.workspace() == null || file.destination() == null || file.sha256() == null
          || !file.sha256().matches("[0-9a-f]{64}"))
        throw new IOException("Unrecognized output recovery journal: " + path);
      Path destination = Path.of(file.destination()).toAbsolutePath().normalize();
      Path workspace = Path.of(file.workspace()).toAbsolutePath().normalize();
      if (!workspace.getParent().equals(destination.getParent())
          || !workspace.getFileName().toString().matches("\\.eapdf-publication-[0-9]+"))
        throw new IOException("Invalid output recovery workspace: " + workspace);
      if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) continue;
      if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
          || !workspace.equals(workspace.toRealPath()) || !Files.getOwner(workspace).equals(Files.getOwner(path)))
        throw new IOException("Invalid output recovery workspace: " + workspace);
      Recovery checked = new Recovery(destination.toString(), workspace.toString(), file.sha256(), file.originalSha256());
      if (!journal.committed()) rollback(checked);
      cleanWorkspace(checked);
    }
    Files.delete(path);
  }

  @Override public void close() {
    if (active == this) active = null;
  }
}
