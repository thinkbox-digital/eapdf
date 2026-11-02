package eapdf;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ProcessingLimitRepairTests {
  @Test void nestedMimeAndExcessivePartCountsFailBeforeBodyTraversal() throws Exception {
    SpecRepairTests helpers = new SpecRepairTests();
    String previousDepth = System.getProperty("eapdf.maxMimeDepth");
    String previousParts = System.getProperty("eapdf.maxMimeParts");
    try {
      System.setProperty("eapdf.maxMimeDepth", "2");
      String body = "Content-Type: text/plain\r\n\r\nBody";
      for (int index = 0; index < 5; index++) body = "Content-Type: multipart/mixed; boundary=part" + index
          + "\r\n\r\n--part" + index + "\r\n" + body + "\r\n--part" + index + "--\r\n";
      String nested = body;
      Assertions.assertThrows(RuntimeException.class, () -> EmlReader.readRfc822Bytes(nested.getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "deep.eml", "deep.eml", "", java.util.List.of(), false));
      System.setProperty("eapdf.maxMimeParts", "2");
      Assertions.assertThrows(RuntimeException.class, () -> helpers.record("multipart/mixed; boundary=parts",
          "--parts\r\nContent-Type: text/plain\r\n\r\nOne\r\n--parts\r\nContent-Type: text/plain\r\n\r\nTwo\r\n--parts--\r\n"));
    } finally {
      restore("eapdf.maxMimeDepth", previousDepth);
      restore("eapdf.maxMimeParts", previousParts);
    }
  }

  @Test void imageDimensionsAreCheckedWithoutAllocatingAPixelBuffer() {
    byte[] pngHeader = java.util.HexFormat.of().parseHex("89504e470d0a1a0a0000000d494844520000001400000014080200000000000000");
    ByteBuffer.wrap(pngHeader).putInt(16, 100000).putInt(20, 100000);
    Assertions.assertThrows(RuntimeException.class, () -> PdfMailWriter.sniffImageSize(pngHeader));
    Assertions.assertThrows(RuntimeException.class, () -> PdfMailWriter.isAllowedRenderResource(
        "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(pngHeader)));
  }

  @Test void uncooperativeWorkersDoNotBlockShutdownForever() throws Exception {
    String previous = System.getProperty("eapdf.workerShutdownSeconds");
    var workers = Common.newWorkers(1);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      System.setProperty("eapdf.workerShutdownSeconds", "1");
      workers.submit(() -> {
        Assertions.assertTrue(Thread.currentThread().isDaemon());
        started.countDown();
        while (release.getCount() > 0) {
          try { release.await(); } catch (InterruptedException ignored) {}
        }
      });
      Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));
      long beginning = System.nanoTime();
      Common.stopWorkers(workers);
      Assertions.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - beginning) < 4000);
      Assertions.assertFalse(workers.isTerminated());
    } finally {
      release.countDown();
      workers.shutdownNow();
      workers.awaitTermination(5, TimeUnit.SECONDS);
      restore("eapdf.workerShutdownSeconds", previous);
    }
  }

  @Test void timedOutWorkersAreCancelled() throws Exception {
    String previous = System.getProperty("eapdf.workerTimeoutSeconds");
    var task = new java.util.concurrent.FutureTask<String>(() -> "not started");
    try {
      System.setProperty("eapdf.workerTimeoutSeconds", "1");
      Assertions.assertThrows(RuntimeException.class, () -> Common.awaitWorker(task));
      Assertions.assertTrue(task.isCancelled());
    } finally {
      restore("eapdf.workerTimeoutSeconds", previous);
    }
  }

  static void restore(String name, String previous) {
    if (previous == null) System.clearProperty(name);
    else System.setProperty(name, previous);
  }
}
