package eapdf;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecordReleaseTests {
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
