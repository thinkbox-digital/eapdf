package eapdf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.apache.pdfbox.io.RandomAccess;
import org.apache.pdfbox.io.RandomAccessReadView;
import org.apache.pdfbox.io.RandomAccessStreamCache;

/** One bounded page cache for all PDF streams, rather than a resident page per stream. */
final class CompactStreamCache implements RandomAccessStreamCache {
  static final int PAGE_SIZE = 4096;
  static final int CACHE_PAGES = 128;
  final Path path;
  private final FileChannel file;
  private final LinkedHashMap<Integer, Page> cache = new LinkedHashMap<>(CACHE_PAGES, .75f, true);
  private final ArrayDeque<Integer> free = new ArrayDeque<>();
  private int allocated;
  private boolean closed;

  private static final class Page {
    final byte[] bytes = new byte[PAGE_SIZE];
    boolean dirty;
  }

  CompactStreamCache() throws IOException {
    path = Files.createTempFile("eapdf-streams-", ".bin");
    try { file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE); }
    catch (IOException error) { Files.deleteIfExists(path); throw error; }
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("PDF stream cache is closed");
  }

  private void flush(int id, Page page) throws IOException {
    if (!page.dirty) return;
    ByteBuffer buffer = ByteBuffer.wrap(page.bytes);
    long offset = (long) id * PAGE_SIZE;
    while (buffer.hasRemaining()) offset += file.write(buffer, offset);
    page.dirty = false;
  }

  private Page page(int id) throws IOException {
    checkOpen();
    Page page = cache.get(id);
    if (page != null) return page;
    if (cache.size() >= CACHE_PAGES) {
      var oldest = cache.entrySet().iterator();
      var entry = oldest.next();
      flush(entry.getKey(), entry.getValue()); oldest.remove();
    }
    page = new Page();
    ByteBuffer buffer = ByteBuffer.wrap(page.bytes);
    long offset = (long) id * PAGE_SIZE;
    while (buffer.hasRemaining()) {
      int read = file.read(buffer, offset);
      if (read < 0) break;
      offset += read;
    }
    cache.put(id, page);
    return page;
  }

  private int allocate() throws IOException {
    checkOpen();
    if (free.isEmpty() && allocated == Integer.MAX_VALUE) throw new IOException("PDF scratch address space exhausted");
    int id = free.isEmpty() ? allocated++ : free.removeFirst();
    Page page = page(id);
    Arrays.fill(page.bytes, (byte) 0); page.dirty = true;
    return id;
  }

  @Override public synchronized RandomAccess createBuffer() throws IOException {
    checkOpen(); return new Buffer();
  }

  @Override public synchronized void close() throws IOException {
    if (closed) return;
    closed = true; cache.clear(); free.clear();
    try { file.close(); } finally { Files.deleteIfExists(path); }
  }

  private final class Buffer implements RandomAccess {
    private int[] pages = new int[1];
    private int count;
    private long position, size;
    private boolean disposed;

    private void check() throws IOException {
      checkOpen();
      if (disposed) throw new IOException("PDF stream buffer is closed");
    }

    private int index(long offset) throws IOException {
      long index = offset / PAGE_SIZE;
      if (index >= Integer.MAX_VALUE) throw new IOException("PDF stream exceeds scratch address space");
      return (int) index;
    }

    private void ensure(long end) throws IOException {
      if (end < 0) throw new IOException("PDF stream size overflow");
      int needed = end == 0 ? 0 : index(end - 1) + 1;
      while (count < needed) {
        if (count == pages.length) pages = Arrays.copyOf(pages, Math.max(count + 1, count + count / 2));
        pages[count++] = allocate();
      }
    }

    @Override public int read() throws IOException {
      synchronized (CompactStreamCache.this) {
        check();
        if (position >= size) return -1;
        int value = page(pages[index(position)]).bytes[(int)(position % PAGE_SIZE)] & 255;
        position++; return value;
      }
    }

    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      synchronized (CompactStreamCache.this) {
        check(); if (length == 0) return 0;
        if (position >= size) return -1;
        int total = (int)Math.min(length, size - position), remaining = total;
        while (remaining > 0) {
          int within = (int)(position % PAGE_SIZE), n = Math.min(remaining, PAGE_SIZE - within);
          System.arraycopy(page(pages[index(position)]).bytes, within, bytes, offset, n);
          offset += n; position += n; remaining -= n;
        }
        return total;
      }
    }

    @Override public void write(int value) throws IOException {
      synchronized (CompactStreamCache.this) {
        check(); ensure(Math.addExact(position, 1));
        Page page = page(pages[index(position)]);
        page.bytes[(int)(position % PAGE_SIZE)] = (byte)value; page.dirty = true;
        size = Math.max(size, ++position);
      }
    }
    @Override public void write(byte[] bytes) throws IOException { write(bytes, 0, bytes.length); }
    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      synchronized (CompactStreamCache.this) {
        check(); if (length == 0) return;
        ensure(Math.addExact(position, length));
        while (length > 0) {
          int within = (int)(position % PAGE_SIZE), n = Math.min(length, PAGE_SIZE - within);
          Page page = page(pages[index(position)]);
          System.arraycopy(bytes, offset, page.bytes, within, n); page.dirty = true;
          offset += n; position += n; length -= n;
        }
        size = Math.max(size, position);
      }
    }
    @Override public long getPosition() throws IOException { synchronized (CompactStreamCache.this) { check(); return position; } }
    @Override public void seek(long offset) throws IOException {
      synchronized (CompactStreamCache.this) {
        check(); if (offset < 0) throw new IOException("Negative PDF stream position"); position = offset;
      }
    }
    @Override public long length() throws IOException { synchronized (CompactStreamCache.this) { check(); return size; } }
    @Override public boolean isClosed() { synchronized (CompactStreamCache.this) { return disposed || closed; } }
    @Override public boolean isEOF() throws IOException { synchronized (CompactStreamCache.this) { check(); return position >= size; } }
    @Override public RandomAccessReadView createView(long start, long length) throws IOException {
      synchronized (CompactStreamCache.this) {
        check();
        if (start < 0 || length < 0 || start > size || length > size - start) throw new IOException("Invalid PDF stream view");
        return new RandomAccessReadView(this, start, length);
      }
    }
    @Override public void clear() throws IOException {
      synchronized (CompactStreamCache.this) {
        check(); release();
      }
    }
    private void release() {
      for (int i = 0; i < count; i++) { cache.remove(pages[i]); free.addLast(pages[i]); }
      pages = new int[1]; count = 0; position = 0; size = 0;
    }
    @Override public void close() {
      synchronized (CompactStreamCache.this) {
        if (!disposed && !closed) release();
        disposed = true;
      }
    }
  }
}
