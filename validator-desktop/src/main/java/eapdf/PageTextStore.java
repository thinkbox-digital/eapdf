package eapdf;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.io.IOUtils;

/** Exact extracted text on disk; only one requested page is materialized. */
final class PageTextStore implements AutoCloseable {
  private final Path path;
  private final RandomAccessFile file;
  private final Map<COSDictionary, Long> positions = new IdentityHashMap<>();
  private final byte[] buffer = new byte[8192];

  PageTextStore() throws IOException {
    path = IOUtils.createProtectedTempFile(Path.of(System.getProperty("java.io.tmpdir")), "eapdf-page-text-", ".bin");
    try { file = new RandomAccessFile(path.toFile(), "rw"); }
    catch (IOException error) { Files.deleteIfExists(path); throw error; }
  }

  void put(COSDictionary page, String text) throws IOException {
    long position = file.length(); file.seek(position); file.writeInt(text.length());
    for (int start = 0; start < text.length();) {
      int size = Math.min(buffer.length / 2, text.length() - start);
      for (int i = 0; i < size; i++) {
        char value = text.charAt(start + i);
        buffer[2 * i] = (byte) (value >>> 8); buffer[2 * i + 1] = (byte) value;
      }
      file.write(buffer, 0, size * 2); start += size;
    }
    positions.put(page, position);
  }

  String get(COSDictionary page) {
    Long position = positions.get(page);
    if (position == null) return "";
    try {
      file.seek(position); char[] text = new char[file.readInt()];
      for (int start = 0; start < text.length;) {
        int size = Math.min(buffer.length / 2, text.length - start); file.readFully(buffer, 0, size * 2);
        for (int i = 0; i < size; i++) text[start + i] = (char) ((buffer[2 * i] & 255) << 8 | buffer[2 * i + 1] & 255);
        start += size;
      }
      return new String(text);
    } catch (IOException error) { throw new UncheckedIOException(error); }
  }

  Set<COSDictionary> pages() { return positions.keySet(); }

  @Override public void close() throws IOException {
    positions.clear();
    try { file.close(); } finally { Files.deleteIfExists(path); }
  }
}
