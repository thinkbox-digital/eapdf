package eapdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

final class LegacyOutputJournal {
  record Recovery(String destination, String workspace, String sha256, String originalSha256) {}
  record Journal(boolean committed, List<Recovery> files, OutputTransaction.Batch batch) {}

  static void recoverFiles(Journal journal, Path path, boolean committed) throws IOException {
    for (Recovery file : journal.files()) {
      if (file == null || file.destination() == null || file.workspace() == null
          || file.sha256() == null || !file.sha256().matches("[0-9a-f]{64}")) {
        throw new IOException("Unrecognized output recovery journal: " + path);
      }
      Path destination = Path.of(file.destination()).toAbsolutePath().normalize();
      Path workspace = Path.of(file.workspace()).toAbsolutePath().normalize();
      if (workspace.getParent() == null || !Objects.equals(workspace.getParent(), destination.getParent())
          || !workspace.getFileName().toString().matches("\\.eapdf-publication-(?:[0-9]+|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})")) {
        throw new IOException("Invalid output recovery workspace: " + workspace);
      }
      if (!Files.exists(workspace, LinkOption.NOFOLLOW_LINKS)) continue;
      if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
          || !workspace.equals(workspace.toRealPath()) || !Files.getOwner(workspace).equals(Files.getOwner(path))) {
        throw new IOException("Invalid output recovery workspace: " + workspace);
      }
      Recovery checked = new Recovery(destination.toString(), workspace.toString(), file.sha256(), file.originalSha256());
      if (!committed) rollback(checked);
      cleanWorkspace(checked);
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
      if (!Files.isRegularFile(displaced, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(prepared, LinkOption.NOFOLLOW_LINKS)
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
}
