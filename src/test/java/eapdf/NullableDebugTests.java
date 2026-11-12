package eapdf;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NullableDebugTests {
  @Test
  void canonicalizationWithoutARecordKeepsDebugOutputAndTheSameHtml() {
    String html = "<body bgcolor='#ffffff'><p>Visible email</p><a href='javascript:alert(1)'>Link</a></body>";
    String expected = PdfMailWriter.canonicalizeEmailHtml(html, null, false);
    var output = new ByteArrayOutputStream();
    PrintStream previous = System.out;
    try (var capture = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(capture);
      assertEquals(expected, PdfMailWriter.canonicalizeEmailHtml(html, null, true));
    } finally {
      System.setOut(previous);
    }
    String log = output.toString(StandardCharsets.UTF_8);
    assertTrue(log.contains("[debug] canonicalized email html len="));
    assertTrue(log.contains("[debug] canonicalized email html preview="));
    assertTrue(expected.contains("Visible email"));
    assertFalse(expected.contains("javascript:"));
  }

  @Test
  void canonicalizationStillRecordsDebugMessagesOnTheOwningEmail() {
    var record = new EmailRecord();
    PdfMailWriter.canonicalizeEmailHtml("<p>Visible email</p>", record, true);
    assertTrue(record.debugLog.stream().anyMatch(s -> s.startsWith("canonicalized email html len=")));
    assertTrue(record.debugLog.stream().anyMatch(s -> s.startsWith("canonicalized email html preview=")));
  }
}
