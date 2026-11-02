package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.util.*;
import org.apache.pdfbox.io.RandomAccess;
import org.junit.jupiter.api.Test;

class CompactStreamCacheTests {
  @Test void interleavedStreamsSurviveEvictionRandomWritesViewsAndPageReuse() throws Exception {
    var cache = new CompactStreamCache();
    var path = cache.path;
    try (cache) {
      Random random = new Random(75431);
      List<RandomAccess> streams = new ArrayList<>();
      List<byte[]> expected = new ArrayList<>();
      for (int i = 0; i < 500; i++) {
        byte[] data = new byte[7000 + random.nextInt(5000)]; random.nextBytes(data);
        var stream = cache.createBuffer(); stream.write(data);
        streams.add(stream); expected.add(data);
      }
      for (int pass = 0; pass < 3; pass++) for (int i = 499; i >= 0; i--) {
        var stream = streams.get(i); byte[] data = expected.get(i);
        int offset = random.nextInt(data.length - 40); byte[] patch = new byte[40]; random.nextBytes(patch);
        stream.seek(offset); stream.write(patch); System.arraycopy(patch, 0, data, offset, patch.length);
        stream.seek(0); byte[] found = new byte[data.length]; stream.readFully(found); assertArrayEquals(data, found);
        assertEquals(-1, stream.read()); assertEquals(0, stream.read(new byte[0]));
        try (var view = stream.createView(4090, 50)) {
          byte[] portion = new byte[50]; view.readFully(portion);
          assertArrayEquals(Arrays.copyOfRange(data, 4090, 4140), portion);
        }
      }
      for (var stream : streams) stream.close();
      try (var reused = cache.createBuffer()) {
        reused.seek(9000); reused.write(123); reused.seek(0);
        byte[] hole = new byte[9001]; reused.readFully(hole);
        assertArrayEquals(new byte[9000], Arrays.copyOf(hole, 9000)); assertEquals(123, hole[9000]);
        reused.clear(); assertEquals(0, reused.length()); assertEquals(0, reused.getPosition());
        reused.write(new byte[]{1,2,3}); reused.seek(0); assertEquals(1, reused.read());
      }
    }
    assertFalse(Files.exists(path));
    assertThrows(java.io.IOException.class, cache::createBuffer);
  }
}
