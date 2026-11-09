package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeUtility;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.cos.COSDictionary;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceFidelityTests {
  @Test void oneUnreadableOriginalDoesNotStopLaterSourceInspection() throws Exception {
    String previous = System.getProperty("VALIDATOR_MAX_STREAM_BYTES");
    try (var stream = new org.apache.pdfbox.cos.COSStream(); var report = new JavaValidation.Report("mixed sources")) {
      System.setProperty("VALIDATOR_MAX_STREAM_BYTES", "4");
      var badSpec = new COSDictionary(); var goodSpec = new COSDictionary();
      var bad = new EaPdfChecks.Embedded("", badSpec, stream, null, "large.msg", "application/vnd.ms-outlook", "", "Source", 8);
      byte[] wire = "From: a@example.org\r\nSubject: Later message\r\n\r\nBody".getBytes(StandardCharsets.US_ASCII);
      var good = new EaPdfChecks.Embedded("", goodSpec, stream, wire, "later.eml", "message/rfc822", "", "Source");
      var checks = new MetadataChecks(report, List.of(bad, good), List.of(badSpec, goodSpec), List.of(), List.of(), List.of(), "m");
      assertDoesNotThrow(checks::emails);
      assertTrue(checks.hasInspectableSources());
      assertTrue(report.items.asList().stream().map(value -> value.getAsJsonObject()).anyMatch(value ->
          "VALIDATION_INCOMPLETE".equals(value.get("code").getAsString()) && value.get("message").getAsString().contains("large.msg")));
    } finally {
      if (previous == null) System.clearProperty("VALIDATOR_MAX_STREAM_BYTES"); else System.setProperty("VALIDATOR_MAX_STREAM_BYTES", previous);
    }
  }
  @Test void utf7SourceHeadersDecodeWithoutTreatingPlusAsSpace() {
    assertEquals("Café + résumé", SourceHeaders.decoded("=?UTF-7?Q?Caf+AOk_+-_r+AOk-sum+AOk-?="));
    assertThrows(IllegalArgumentException.class, () -> SourceHeaders.decoded("=?UTF-7?Q?+A-?="));
  }

  @Test void legacyEmailYearsUseTheSpecifiedCentury() {
    for (String[] pair : new String[][] {{"03", "2003"}, {"49", "2049"}, {"50", "1950"}, {"68", "1968"}, {"99", "1999"}})
      assertEquals(java.time.Instant.parse(pair[1] + "-04-14T17:17:55Z"), SourceHeaders.instant("14 Apr " + pair[0] + " 13:17:55 -0400"));
  }

  @Test void joinedEncodedWordsCompareAsTheDecodedOriginalSubject() {
    assertEquals("Topic Re: \"Launch\" — update", SourceHeaders.decoded(
        "Topic =?utf-8?Q?Re=3A?==?utf-8?Q?_=22Launch=22?= =?utf-8?Q?_=E2=80=94_update?="));
    assertThrows(IllegalArgumentException.class, () -> SourceHeaders.decoded("=?utf-8?Q?bad=GG?==?utf-8?Q?_value?="));
  }

  @Test void anEncodedLiteralIsDecodedOnlyOnce() {
    assertEquals("=?UTF-8?Q?literal?= kept", SourceHeaders.decoded(
        "=?UTF-8?Q?=3D=3FUTF-8=3FQ=3Fliteral=3F=3D?==?UTF-8?Q?_kept?="));
  }

  @ParameterizedTest @ValueSource(strings = {"base64", "quoted-printable", "7bit"})
  void attachedMessageSizeAndChecksumUseDecodedWireBytes(String encoding) throws Exception {
    byte[] nested = ("From: author@example.org\r\nTo: reader@example.org\r\nSubject: Attached email\r\n"
        + "Message-ID: <attached@example.org>\r\n\r\nMeeting at 10:00.\r\n").getBytes(StandardCharsets.US_ASCII);
    var encoded = new ByteArrayOutputStream();
    try (var stream = MimeUtility.encode(encoded, encoding)) { stream.write(nested); }
    var wire = new ByteArrayOutputStream();
    wire.write(("Content-Type: message/rfc822; name=\"forwarded.eml\"\r\nContent-Disposition: attachment; filename=\"forwarded.eml\"\r\n"
        + "Content-Transfer-Encoding: " + encoding + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    wire.write(encoded.toByteArray());
    var part = new MimeBodyPart(new ByteArrayInputStream(wire.toByteArray()));
    var report = new JavaValidation.Report("attached email");
    var checks = new MetadataChecks(report, List.of(), List.of(), List.of(), List.of(), List.of(), "s");
    var inspect = MetadataChecks.class.getDeclaredMethod("inspectMime", jakarta.mail.Part.class, List.class, int.class);
    inspect.setAccessible(true);
    var inventory = new ArrayList<>(); inspect.invoke(checks, part, inventory, 0);
    assertEquals(1, inventory.size()); var attachment = inventory.getFirst();
    var size = attachment.getClass().getDeclaredMethod("size"); size.setAccessible(true);
    var checksum = attachment.getClass().getDeclaredMethod("md5"); checksum.setAccessible(true);
    assertEquals((long) nested.length, size.invoke(attachment));
    assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(nested)), checksum.invoke(attachment));
  }
}
