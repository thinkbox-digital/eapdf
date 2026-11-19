package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.verapdf.pd.PDDocument;
import static org.junit.jupiter.api.Assertions.*;

class TrailingDataTests {
  @TempDir Path temporary;

  @ParameterizedTest
  @ValueSource(ints = {0, 127, 128, 255, 256, 500})
  void countsAndValidatesTrailingDataWithLfAndCrLf(int count) throws Exception {
    Path sample = Path.of("..", "examples", "eml", "eml_Agenda_and_timeline_slides_1s.pdf");
    byte[] original = Files.readAllBytes(sample);
    int end = new String(original, StandardCharsets.ISO_8859_1).lastIndexOf("%%EOF") + 5;
    assertTrue(end > 5);
    for (String eol : new String[]{"\n", "\r\n"}) {
      byte[] bytes = Arrays.copyOf(original, end + eol.length() + count);
      System.arraycopy(eol.getBytes(StandardCharsets.US_ASCII), 0, bytes, end, eol.length());
      Path file = temporary.resolve("trailing-" + count + "-" + eol.length() + ".pdf");
      Files.write(file, bytes);
      var report = JavaValidation.pdfa(file, null);
      var document = new PDDocument(file.toString());
      try {
        assertAll(
            () -> assertEquals((long) count, document.getDocument().getPostEOFDataSize()),
            () -> assertEquals(count == 0 ? "PASS" : "FAIL", report.get("status").getAsString(), report.toString()));
      } finally { document.close(); }
      assertTrue(report.get("ran").getAsBoolean(), report.toString());
      if (count > 0) assertTrue(report.getAsJsonArray("findings").asList().stream()
          .anyMatch(item -> item.getAsJsonObject().get("clause").getAsString().equals("6.1.3")), report.toString());
    }
    assertArrayEquals(original, Files.readAllBytes(sample));
  }
}
