package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PayloadFingerprintTests {
  @TempDir Path directory;

  static class StreamOnlyFile extends AssociatedFile {
    int opens, largestRead;
    boolean closed;
    @Override byte[] data() { throw new AssertionError("Fingerprinting must not materialize a payload"); }
    @Override InputStream openData() throws IOException {
      opens++;
      return new FilterInputStream(super.openData()) {
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
          largestRead = Math.max(largestRead, length);
          return super.read(buffer, offset, length);
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
      };
    }
  }

  StreamOnlyFile staged(byte[] payload) throws Exception {
    Path file = Files.createTempFile(directory, "payload-", ".bin");
    try (var output = Files.newOutputStream(file)) {
      output.write(new byte[]{91, 92, 93}); output.write(payload); output.write(new byte[]{94, 95});
    }
    StreamOnlyFile associated = new StreamOnlyFile();
    associated.filename = "native.bin"; associated.mime = "application/octet-stream";
    associated.afRelationship = "Source"; associated.dataPath = file;
    associated.dataSlice = true; associated.dataOffset = 3; associated.storedSize = payload.length;
    return associated;
  }

  @Test void bothFingerprintsUseOneBoundedPassOverOnlyThePackedSlice() throws Exception {
    byte[] payload = new byte[256 * 1024 + 7]; new java.util.Random(123).nextBytes(payload);
    var file = staged(payload); file.ensureChecksums();
    assertEquals(Common.md5Hex(payload), file.checksumMd5);
    assertEquals(Common.sha256Hex(payload), file.checksumSha256);
    assertEquals(1, file.opens); assertTrue(file.closed); assertTrue(file.largestRead <= 8192);
    Files.delete(file.dataPath);
    file.ensureChecksums(); // Completed fingerprints remain usable after normal payload release.
    assertEquals(1, file.opens);
  }

  @Test void existingFingerprintIsPreservedWhileMissingOneIsFilled() throws Exception {
    byte[] payload = {1, 2, 3}; var file = staged(payload);
    file.checksumMd5 = Common.md5Hex(payload); file.ensureChecksums();
    assertEquals(Common.md5Hex(payload), file.checksumMd5);
    assertEquals(Common.sha256Hex(payload), file.checksumSha256); assertEquals(1, file.opens);
  }

  @Test void truncatedSlicesNeverAcquirePartialFingerprintsOrFallbackTokens() throws Exception {
    var file = staged(new byte[]{1, 2, 3}); file.storedSize = 20;
    assertThrows(IOException.class, file::ensureChecksums);
    assertNull(file.checksumMd5); assertNull(file.checksumSha256); assertTrue(file.closed);
    assertThrows(UncheckedIOException.class, () -> PdfMailWriter.attachmentLaunchToken(file));
    assertNull(file.launchToken);
  }

  @Test void readFailureDoesNotPublishEitherFingerprintAndClosesStream() {
    boolean[] closed = {false};
    var file = new AssociatedFile() {
      @Override InputStream openData() {
        return new InputStream() {
          @Override public int read() throws IOException { throw new IOException("broken payload"); }
          @Override public void close() { closed[0] = true; }
        };
      }
    };
    file.bytes = new byte[]{1};
    assertThrows(IOException.class, file::ensureChecksums);
    assertNull(file.checksumMd5); assertNull(file.checksumSha256); assertTrue(closed[0]);
  }

  @Test void cancellationDoesNotReadOrCachePayload() throws Exception {
    var file = staged(new byte[]{1});
    try {
      Thread.currentThread().interrupt();
      assertThrows(Common.UserFacingException.class, file::ensureChecksums);
      assertEquals(0, file.opens); assertNull(file.checksumMd5); assertNull(file.checksumSha256);
    } finally { Thread.interrupted(); }
  }

  @Test void metadataArchivePlanningAndLaunchTokensReuseStreamedFingerprints() throws Exception {
    byte[] payload = {4, 5, 6}; var file = staged(payload); file.sourceSetMember = true; file.afRelationship = "Supplement";
    EmailRecord record = new EmailRecord(); record.sourceBytes = new byte[]{9}; record.associatedFiles.add(file);
    Common.finalizeRecordMetadata(record);
    var plans = PdfMailWriter.buildArchiveEmbeddedFilePlans(List.of(record));
    assertEquals(2, plans.size()); assertEquals(Common.md5Hex(payload), plans.stream().filter(p -> p.file == file).findFirst().orElseThrow().checksumMd5);
    assertTrue(PdfMailWriter.attachmentLaunchToken(file).endsWith(Common.sha256Hex(payload)));
    assertEquals(1, file.opens);
  }

  @Test void emptyPayloadHasRealFingerprintsButMissingPayloadFails() throws Exception {
    var file = staged(new byte[0]); file.ensureChecksums();
    assertEquals(Common.md5Hex(new byte[0]), file.checksumMd5);
    assertEquals(Common.sha256Hex(new byte[0]), file.checksumSha256);
    assertThrows(IOException.class, () -> new AssociatedFile().ensureChecksums());
  }

  @Test void repeatedInlineReferencesLoadPayloadOnceAndPreserveExactBytes() throws Exception {
    byte[] original = CidUrlTests.image();
    var file = new AssociatedFile() {
      int loads;
      @Override byte[] data() { assertEquals(1, ++loads, "Repeated inline payload read"); return super.data(); }
    };
    file.filename = "image.png"; file.mime = "image/png"; file.contentId = "image@example.org";
    file.inline = true; file.dataPath = directory.resolve("image.bin"); file.storedSize = original.length;
    Files.write(file.dataPath, original);
    var record = new EmailRecord();
    String html = PdfMailWriter.rewriteCidToFileUris("<img src=\"cid:image@example.org\"/><img src=\"cid:image@example.org\"/>",
        List.of(file), record, false, directory);
    assertFalse(html.contains("cid:")); assertEquals(1, record.renderAssets.size());
    assertArrayEquals(original, record.renderAssets.values().iterator().next());
    for (String uri : record.renderAssets.keySet()) assertArrayEquals(original, Files.readAllBytes(Path.of(java.net.URI.create(uri))));
  }
  @Test void payloadLargerThanWorkerHeapCanBeFingerprinted() throws Exception {
    Path payload = directory.resolve("larger-than-heap.bin");
    try (var file = new RandomAccessFile(payload.toFile(), "rw")) { file.setLength(64L * 1024 * 1024); }
    Path log = directory.resolve("small-heap.log");
    var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-Xmx32m", "-cp", System.getProperty("java.class.path"), getClass().getName(), payload.toString())
        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "Bounded fingerprint worker timed out");
      assertEquals(0, process.exitValue(), Files.readString(log));
      assertTrue(Files.readString(log).contains("streamed 67108864 bytes"));
    } finally { process.destroyForcibly(); process.waitFor(); }
  }

  public static void main(String[] args) throws Exception {
    var file = new StreamOnlyFile(); file.dataPath = Path.of(args[0]); file.storedSize = (int) Files.size(file.dataPath);
    file.ensureChecksums();
    if (!file.checksumSha256.equals(Common.digestHex(file.dataPath, "SHA-256"))
        || !file.checksumMd5.equals(Common.digestHex(file.dataPath, "MD5")) || file.opens != 1 || file.largestRead > 8192)
      throw new AssertionError("Incorrect bounded fingerprint");
    System.out.println("streamed " + file.storedSize + " bytes");
  }
}
