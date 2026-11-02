package eapdf;

import com.google.api.services.gmail.model.Message;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GmailResumeStore implements AutoCloseable {
  static final Gson JSON = new Gson();
  final Path directory;
  private final FileChannel channel;
  private final FileLock lock;
  private final Manifest manifest;

  static class Manifest {
    int version = 1;
    List<String> selected = new ArrayList<>();
    Map<String, Download> downloads = new LinkedHashMap<>();
    Map<String, List<Output>> completed = new LinkedHashMap<>();
  }

  static class Download {
    String checksum;
    List<String> labels;
  }

  static class Output {
    String path;
    long size;
    String checksum;
  }

  GmailResumeStore(Path root, String scope, List<String> selected) throws Exception {
    directory = root == null ? null : root.toAbsolutePath().normalize()
        .resolve(Common.sha256Hex(scope.getBytes(StandardCharsets.UTF_8)));
    if (directory == null) {
      channel = null;
      lock = null;
      manifest = new Manifest();
      manifest.selected.addAll(selected);
      return;
    }
    Files.createDirectories(directory);
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw Common.fail("Gmail resume storage must be a private directory: " + directory);
    }
    privatePermissions(directory, true);
    Path lockPath = directory.resolve("export.lock");
    channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    FileLock acquired;
    try {
      acquired = channel.tryLock();
      if (acquired == null) throw Common.fail("Gmail resume export is already running: " + directory);
    } catch (Exception exception) {
      channel.close();
      throw exception;
    }
    lock = acquired;
    try {
      privatePermissions(lockPath, false);
      Path checkpoint = directory.resolve("manifest.json");
      if (Files.exists(checkpoint, LinkOption.NOFOLLOW_LINKS)) {
        if (Files.isSymbolicLink(checkpoint)) throw new IOException("Gmail checkpoint cannot be a symbolic link");
        if (Files.size(checkpoint) > ProcessingLimits.metadataBytes()) throw Common.fail("Gmail checkpoint exceeds the metadata processing limit");
        manifest = JSON.fromJson(Files.readString(checkpoint), Manifest.class);
        if (manifest == null || manifest.version != 1 || manifest.selected == null
            || manifest.downloads == null || manifest.completed == null) throw new IOException("Invalid Gmail resume checkpoint");
        ProcessingLimits.checkCount(manifest.selected.size());
      } else {
        manifest = new Manifest();
        manifest.selected.addAll(selected);
        save();
      }
    } catch (Exception exception) {
      lock.release();
      channel.close();
      throw exception;
    }
    System.out.println("Gmail resume storage: " + directory + " (retained until explicitly deleted)");
  }

  List<String> selectedIds() {
    return List.copyOf(manifest.selected);
  }

  private Path messagePath(String id) throws Exception {
    return directory.resolve(Common.sha256Hex(id.getBytes(StandardCharsets.UTF_8)) + ".eml");
  }

  Message downloaded(String id) throws Exception {
    if (directory == null) return null;
    Download download = manifest.downloads.get(id);
    if (download == null) return null;
    Path path = messagePath(id);
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null;
    ProcessingLimits.checkMessage(Files.size(path), id);
    byte[] raw = Files.readAllBytes(path);
    if (!Common.sha256Hex(raw).equals(download.checksum)) {
      System.err.println("warning: Gmail staged message checksum mismatch; downloading again: " + id);
      return null;
    }
    return new Message().setId(id).setRaw(Base64.getUrlEncoder().withoutPadding().encodeToString(raw))
        .setLabelIds(download.labels == null ? List.of() : List.copyOf(download.labels));
  }

  void rememberDownload(String id, Message message) throws Exception {
    if (directory == null) return;
    ProcessingLimits.checkMessage((long)message.getRaw().length() * 3 / 4, id);
    byte[] raw = Base64.getUrlDecoder().decode(message.getRaw());
    Common.writeAtomically(messagePath(id), raw);
    privatePermissions(messagePath(id), false);
    Download download = new Download();
    download.checksum = Common.sha256Hex(raw);
    download.labels = message.getLabelIds() == null ? List.of() : List.copyOf(message.getLabelIds());
    manifest.downloads.put(id, download);
    save();
  }

  boolean completed(String id, Path outputDirectory) throws IOException {
    if (directory == null) return false;
    List<Output> outputs = manifest.completed.get(id);
    if (outputs == null || outputs.isEmpty()) return false;
    for (Output output : outputs) {
      Path path = Path.of(output.path).toAbsolutePath().normalize();
      if (!path.getParent().equals(outputDirectory.toAbsolutePath().normalize())
          || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != output.size
          || !Common.digestHex(path, "SHA-256").equals(output.checksum)) return false;
    }
    return true;
  }

  void rememberCompleted(String id, List<Path> paths) throws Exception {
    if (directory == null) return;
    List<Output> outputs = new ArrayList<>();
    for (Path path : paths) {
      Output output = new Output();
      output.path = path.toAbsolutePath().normalize().toString();
      Path readable = OutputTransaction.readable(path);
      output.size = Files.size(readable);
      output.checksum = Common.digestHex(readable, "SHA-256");
      outputs.add(output);
    }
    manifest.completed.put(id, outputs);
    save();
  }

  private void save() throws Exception {
    Path checkpoint = directory.resolve("manifest.json");
    byte[] json = JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8);
    if (json.length > ProcessingLimits.metadataBytes()) throw Common.fail("Gmail checkpoint exceeds the metadata processing limit");
    Common.writeAtomically(checkpoint, json);
    privatePermissions(checkpoint, false);
  }

  private static void privatePermissions(Path path, boolean directory) throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
  }

  @Override
  public void close() throws IOException {
    if (lock != null) {
      try {
        lock.release();
      } finally {
        channel.close();
      }
    }
  }
}
