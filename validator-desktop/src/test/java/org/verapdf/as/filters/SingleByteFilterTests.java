package org.verapdf.as.filters;

import java.io.IOException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.verapdf.as.io.ASMemoryInStream;
import static org.junit.jupiter.api.Assertions.*;

class SingleByteFilterTests {
  @Test void preservesUnsignedBytesZeroLengthReadsExceptionsAndEof() throws Exception {
    try (var filter = new ASInFilter(new ASMemoryInStream(new byte[0])) {
      int call;
      @Override public int read(byte[] buffer, int size) throws IOException {
        return switch (call++) {
          case 0 -> { buffer[0] = (byte) 255; yield 1; }
          case 1 -> 0;
          case 2 -> throw new IOException("read failed");
          case 3 -> { buffer[0] = 0; yield 1; }
          default -> -1;
        };
      }
    }) {
      assertEquals(255, filter.read()); assertEquals(0, filter.read());
      assertEquals("read failed", assertThrows(IOException.class, filter::read).getMessage());
      assertEquals(0, filter.read()); assertEquals(-1, filter.read());
    }
  }

  @Test void reentrantReadsKeepIndependentBuffers() throws Exception {
    try (var filter = new ASInFilter(new ASMemoryInStream(new byte[0])) {
      boolean nested;
      @Override public int read(byte[] buffer, int size) throws IOException {
        buffer[0] = (byte) (nested ? 29 : 11);
        if (!nested) {
          nested = true;
          try { assertEquals(29, read()); } finally { nested = false; }
        }
        return 1;
      }
    }) { assertEquals(11, filter.read()); assertEquals(11, filter.read()); }
  }

  @Test void concurrentReadsDoNotShareScratch() throws Exception {
    var barrier = new CyclicBarrier(2); var marker = new ThreadLocal<Integer>();
    var executor = Executors.newFixedThreadPool(2);
    try (var filter = new ASInFilter(new ASMemoryInStream(new byte[0])) {
      @Override public int read(byte[] buffer, int size) throws IOException {
        buffer[0] = marker.get().byteValue();
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (Exception error) { throw new IOException(error); }
        return 1;
      }
    }) {
      var first = executor.submit(() -> { marker.set(11); return filter.read(); });
      var second = executor.submit(() -> { marker.set(29); return filter.read(); });
      assertEquals(11, first.get(10, TimeUnit.SECONDS).intValue());
      assertEquals(29, second.get(10, TimeUnit.SECONDS).intValue());
    } finally { executor.shutdownNow(); }
  }
}
