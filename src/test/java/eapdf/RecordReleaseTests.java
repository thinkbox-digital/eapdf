package eapdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecordReleaseTests {
  @TempDir Path directory;

  @Test void standaloneConversionReleasesPackedPayloadsBeforeTheJobCloses() throws Exception {
    Path input = TestSupport.fixturePath("sample.eml");
    try (var job = Common.beginJob()) {
      var sequence = new AtomicInteger();
      EmailToPDFA.processInput(input.toString(), directory.toString(), TestSupport.fontPath(),
          TestSupport.iccPath(), "", List.of(), false, directory.resolve("debug").toString(), sequence, false, false);
      assertEquals(1, sequence.get());
      assertEquals(0, job.metadataSize);
      try (var payloads = Files.list(job.payloads.directory)) {
        assertEquals(0, payloads.count());
      }
      try (var outputs = Files.list(directory)) {
        assertEquals(1, outputs.filter(output -> output.toString().endsWith(".pdf")).count());
      }
    }
  }

  @Test void cleanupFailureIsSuppressedOnTheOriginalConversionFailure() {
    var conversion = new IOException("conversion failed");
    var cleanup = new IOException("cleanup failed");
    var actual = assertThrows(IOException.class, () -> EmailToPDFA.withRecordRelease(
        () -> { throw conversion; }, () -> { throw cleanup; }));
    assertSame(conversion, actual);
    assertArrayEquals(new Throwable[]{cleanup}, actual.getSuppressed());
  }

  @Test void cleanupFailureStillFailsAnOtherwiseSuccessfulConversion() {
    var cleanup = new IOException("cleanup failed");
    assertSame(cleanup, assertThrows(IOException.class, () -> EmailToPDFA.withRecordRelease(
        () -> {}, () -> { throw cleanup; })));
  }

  @Test void cleanupRunsAfterSuccessAndAfterErrorsWithoutReplacingThem() throws Exception {
    int[] closed = {0};
    EmailToPDFA.withRecordRelease(() -> {}, () -> closed[0]++);
    assertEquals(1, closed[0]);
    var conversion = new AssertionError("conversion error");
    var cleanup = new IOException("cleanup failed");
    assertSame(conversion, assertThrows(AssertionError.class, () -> EmailToPDFA.withRecordRelease(
        () -> { throw conversion; }, () -> { closed[0]++; throw cleanup; })));
    assertEquals(2, closed[0]);
    assertArrayEquals(new Throwable[]{cleanup}, conversion.getSuppressed());
  }
}
