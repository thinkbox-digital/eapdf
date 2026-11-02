package eapdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;

final class PayloadStore implements AutoCloseable {
  final Path directory;

  PayloadStore() throws IOException {
    directory = Files.createTempDirectory("emailtopdfa-payloads-");
  }

  synchronized Path write(byte[] bytes) throws IOException {
    Path path = Files.createTempFile(directory, "rendered-", ".pdf");
    Files.write(path, bytes);
    return path;
  }

  synchronized Path createPdf() throws IOException {
    return Files.createTempFile(directory, "rendered-", ".pdf");
  }

  synchronized void stage(EmailRecord record) throws Exception {
    if (record.payloadStaged) return;
    ProcessingLimits.checkBody(record.textBody, record.sourcePath);
    ProcessingLimits.checkBody(record.htmlBody, record.sourcePath);
    ProcessingLimits.checkBody(record.rtfBody, record.sourcePath);
    if (record.associatedFiles.stream().noneMatch(file -> "Source".equals(file.afRelationship))) {
      AssociatedFile source = new AssociatedFile();
      source.filename = Common.nz(record.sourceFilename).isBlank() ? "message.eml" : record.sourceFilename;
      source.mime = Common.nz(record.sourceMime).isBlank() ? "message/rfc822" : record.sourceMime;
      source.afRelationship = "Source";
      source.description = Common.sourceDescription(record);
      source.bytes = record.sourceBytes == null ? Common.synthesizeEml(record) : record.sourceBytes;
      source.ensureChecksums();
      record.associatedFiles.add(source);
    }
    Path packed = Files.createTempFile(directory, "message-", ".bin");
    IdentityHashMap<byte[], Long> stored = new IdentityHashMap<>();
    long offset = 0;
    try (var output = Files.newOutputStream(packed)) {
      for (AssociatedFile file : record.associatedFiles) {
        if (file.bytes == null) continue;
        Long position = stored.get(file.bytes);
        if (position == null) {
          position = offset; output.write(file.bytes); offset += file.bytes.length;
          stored.put(file.bytes, position);
        }
        file.storedSize = file.bytes.length;
        file.dataPath = packed; file.dataOffset = position; file.dataSlice = true;
        file.bytes = null;
      }
      byte[] text = Common.nz(record.textBody).getBytes(StandardCharsets.UTF_8);
      record.textDataPath = packed; record.textDataOffset = offset; record.textDataLength = text.length;
      output.write(text); offset += text.length;
      byte[] html = Common.nz(record.htmlBody).getBytes(StandardCharsets.UTF_8);
      record.htmlDataPath = packed; record.htmlDataOffset = offset; record.htmlDataLength = html.length;
      output.write(html); offset += html.length;
      byte[] rtf = Common.nz(record.rtfBody).getBytes(StandardCharsets.UTF_8);
      record.rtfDataPath = packed; record.rtfDataOffset = offset; record.rtfDataLength = rtf.length;
      output.write(rtf);
    }
    record.sourceBytes = null;
    record.textBody = null;
    record.htmlBody = null;
    record.rtfBody = null;
    record.payloadStaged = true;
  }

  static java.io.InputStream slice(Path path, long offset, long length) throws IOException {
    var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ);
    try {
      channel.position(offset);
      return new java.io.FilterInputStream(java.nio.channels.Channels.newInputStream(channel)) {
        long remaining = length;
        @Override public int read() throws IOException {
          if (remaining == 0) return -1;
          int value = super.read(); if (value >= 0) remaining--; return value;
        }
        @Override public int read(byte[] bytes, int start, int count) throws IOException {
          if (count == 0) return 0;
          if (remaining == 0) return -1;
          int n = in.read(bytes, start, (int)Math.min(count, remaining));
          if (n > 0) remaining -= n;
          return n;
        }
        @Override public long skip(long count) throws IOException {
          long skipped = in.skip(Math.min(Math.max(0, count), remaining)); remaining -= skipped; return skipped;
        }
      };
    } catch (Throwable error) { channel.close(); throw error; }
  }

  synchronized void release(EmailRecord record) throws IOException {
    var paths = new java.util.HashSet<Path>();
    for (var file : record.associatedFiles) if (file.dataPath != null) paths.add(file.dataPath);
    for (Path path : new Path[]{record.textDataPath, record.htmlDataPath, record.rtfDataPath}) if (path != null) paths.add(path);
    for (Path path : paths) if (path.getParent().equals(directory)) Files.deleteIfExists(path);
  }

  @Override
  public void close() throws IOException {
    Common.deleteTree(directory);
  }
}
