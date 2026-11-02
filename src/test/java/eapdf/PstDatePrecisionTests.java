package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

class PstDatePrecisionTests {
  @Test void realNativePrecisionLossIsReportedWithoutInventingPrecisionInRfc822() throws Exception {
    var records = PstReader.readPst(TestSupport.fixturePath("attached-message.pst").toString(), "", List.of(), false);
    var record = records.stream().filter(r -> "FW: First email".equals(r.subject)).findFirst().orElseThrow();
    // Independently established with libpff; this is not a value copied from creator output.
    assertEquals(Instant.parse("2020-11-26T22:18:29Z"), Common.parseMessageDate(record.sentXmp).toInstant());
    assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains("2020-11-26T22:18:29.446Z") && w.contains("Reduced date precision")));
  }

  @Test void validTransportDateDoesNotReportUnusedNativePrecision() {
    var record = new EmailRecord(); record.rawDateHeader = "Thu, 26 Nov 2020 22:18:29 +0000";
    PstReader.populatePstSentDate(record, Date.from(Instant.parse("2020-11-26T22:18:29.446Z")), null);
    assertTrue(record.conversionWarnings.isEmpty());
  }
}
