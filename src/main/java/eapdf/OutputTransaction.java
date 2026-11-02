package eapdf;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OutputTransaction implements AutoCloseable {
  static volatile OutputTransaction active;
  final Path directory;
  final Path workspace;
  final Map<Path, Entry> entries = new LinkedHashMap<>();

  record Entry(String destination, String staged, long size, String sha256) {}
  record Recovery(String destination, String prepared, String backup) {}
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
        directory.resolve(String.format("%06d", entries.size()) + ".asset").toString(), 0, ""));
    return Path.of(entry.staged());
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
      completed.add(new Entry(entry.destination(), entry.staged(), size, Common.digestHex(staged, "SHA-256")));
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
    if (entries.length > budget.setting("eapdf.maxMessages", "EAPDF_MAX_MESSAGES", ProcessingLimits.messages()) * 4) throw Common.fail("Too many output manifest entries");
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
    publish(List.of(entries), (source, target) -> Files.move(source, target, StandardCopyOption.REPLACE_EXISTING),
        manifest.resolveSibling("publication.json"));
  }

  interface MoveOperation {
    void move(Path source, Path target) throws IOException;
  }

  static void publish(List<Entry> entries, MoveOperation move) throws Exception {
    publish(entries, move, null);
  }

  static void publish(List<Entry> entries, MoveOperation move, Path journal) throws Exception {
    Map<Path, Path> prepared = new LinkedHashMap<>();
    Map<Path, Path> backups = new LinkedHashMap<>();
    List<Path> replaced = new ArrayList<>();
    boolean committed = false;
    boolean restored = true;
    try {
      ConversionProgress.begin("copying_outputs", entries.size(), "files");
      for (Entry entry : entries) {
        Path destination = Path.of(entry.destination()).toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Output destination is not a regular file: " + destination);
        }
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), ".eapdf-publish-", ".tmp");
        prepared.put(destination, temporary);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
          Path backup = Files.createTempFile(destination.getParent(), ".eapdf-backup-", ".tmp");
          Files.delete(backup);
          backups.put(destination, backup);
        }
        if (journal != null) {
          List<Recovery> pending = prepared.entrySet().stream().map(item -> new Recovery(item.getKey().toString(),
              item.getValue().toString(), backups.containsKey(item.getKey()) ? backups.get(item.getKey()).toString() : null)).toList();
          writeJournal(journal, new Journal(false, pending));
        }
        Files.copy(Path.of(entry.staged()), temporary, StandardCopyOption.REPLACE_EXISTING);
        try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
          channel.force(true);
        }
        ConversionProgress.advance("copying_outputs");
      }
      List<Recovery> recovery = prepared.entrySet().stream().map(entry -> new Recovery(entry.getKey().toString(),
          entry.getValue().toString(), backups.containsKey(entry.getKey()) ? backups.get(entry.getKey()).toString() : null)).toList();
      if (journal != null) writeJournal(journal, new Journal(false, recovery));
      ConversionProgress.begin("publishing", prepared.size(), "files");
      for (Path destination : prepared.keySet()) {
        if (backups.containsKey(destination)) Files.move(destination, backups.get(destination));
        replaced.add(destination);
        move.move(prepared.get(destination), destination);
        ConversionProgress.advance("publishing");
      }
      if (journal != null) writeJournal(journal, new Journal(true, recovery));
      committed = true;
    } finally {
      if (!committed) {
        java.util.Collections.reverse(replaced);
        for (Path destination : replaced) {
          try {
            Path backup = backups.get(destination);
            if (backup == null) Files.deleteIfExists(destination);
            else Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
          } catch (IOException error) {
            restored = false;
            System.err.println("error: Output rollback failed; retain recovery backup " + backups.get(destination) + ": " + error.getMessage());
          }
        }
      }
      for (Path temporary : prepared.values()) Files.deleteIfExists(temporary);
      if (committed || restored) for (Path backup : backups.values()) Files.deleteIfExists(backup);
      if (journal != null && (committed || restored)) Files.deleteIfExists(journal);
    }
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
      Path destination = Path.of(file.destination());
      Path prepared = Path.of(file.prepared());
      Path backup = file.backup() == null ? null : Path.of(file.backup());
      if (!journal.committed()) {
        if (backup != null && Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
          Files.move(backup, destination, StandardCopyOption.REPLACE_EXISTING);
        } else if (backup == null && !Files.exists(prepared, LinkOption.NOFOLLOW_LINKS)) {
          Files.deleteIfExists(destination);
        }
      }
      Files.deleteIfExists(prepared);
      if (backup != null) Files.deleteIfExists(backup);
    }
    Files.delete(path);
  }

  @Override public void close() {
    if (active == this) active = null;
  }
}
