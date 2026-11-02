package eapdf;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InternationalAttachmentTests {
  static byte[] parent(String type, byte[] child, boolean base64) {
    String body = "Content-Type: multipart/mixed; boundary=forwarded\r\n\r\n"
        + "--forwarded\r\nContent-Type: text/plain\r\n\r\nParent body\r\n"
        + "--forwarded\r\nContent-Type: " + type + "\r\n"
        + (base64 ? "Content-Transfer-Encoding: base64\r\n" : "") + "\r\n"
        + (base64 ? Base64.getMimeEncoder().encodeToString(child) : new String(child, StandardCharsets.UTF_8))
        + "\r\n--forwarded--\r\n";
    return PreservationRegressionTests.nestedEml("Forwarded parent", body);
  }

  static EmailRecord parse(byte[] raw) throws Exception {
    return EmlReader.readRfc822Bytes(raw, "parent.eml", "parent.eml", "", List.of(), false);
  }

  @Test
  void unnamedInternationalMessageIsPreservedAsOneAttachment() throws Exception {
    byte[] child = InternationalHeaderTests.message("Résumé", "UTF-8", "Attached body");
    byte[] raw = parent("message/global", child, false);
    EmailRecord record = parse(raw);
    Assertions.assertEquals(1, record.attachmentCount);
    AssociatedFile file = record.associatedFiles.stream().filter(item -> item.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals("message/global", file.mime);
    Assertions.assertTrue(file.filename.endsWith(".eml"));
    Assertions.assertArrayEquals(child, file.bytes);
    Assertions.assertArrayEquals(raw, record.sourceBytes);
    Assertions.assertEquals("Parent body", record.textBody.strip());
    Assertions.assertTrue(record.conversionWarnings.isEmpty());
    Assertions.assertEquals("Résumé", parse(file.bytes).subject);
  }

  @Test
  void encodedInternationalMessageRetainsItsDecodedNativeBytes() throws Exception {
    byte[] child = InternationalHeaderTests.message("Résumé", "UTF-8", "Attached body");
    EmailRecord record = parse(parent("message/global", child, true));
    Assertions.assertEquals(1, record.attachmentCount);
    AssociatedFile file = record.associatedFiles.stream().filter(item -> item.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals("message/global", file.rawContentType);
    Assertions.assertArrayEquals(child, file.bytes);
    Assertions.assertEquals(Common.sha256Hex(child), file.checksumSha256);
    Assertions.assertEquals("Parent body", record.textBody.strip());
  }

  @Test
  void encodedRfc822MessageAlsoRetainsItsDecodedNativeBytes() throws Exception {
    byte[] child = PreservationRegressionTests.nestedEml("Child", "Content-Type: text/plain\r\n\r\nAttached body");
    EmailRecord record = parse(parent("message/rfc822", child, true));
    Assertions.assertEquals(1, record.attachmentCount);
    Assertions.assertArrayEquals(child, record.associatedFiles.stream().filter(item -> item.emailAttachment)
        .findFirst().orElseThrow().bytes);
    Assertions.assertEquals("Parent body", record.textBody.strip());
  }

  @Test
  void unnamedRfc822AttachmentDoesNotBecomeAnUnsupportedParentBody() throws Exception {
    byte[] child = PreservationRegressionTests.nestedEml("Child", "Content-Type: text/plain\r\n\r\nAttached body");
    EmailRecord record = parse(parent("message/rfc822", child, false));
    Assertions.assertEquals(1, record.attachmentCount);
    Assertions.assertEquals("Parent body", record.textBody.strip());
    Assertions.assertFalse(record.conversionWarnings.stream().anyMatch(value -> value.contains("Unsupported")));
    Assertions.assertArrayEquals(child, record.associatedFiles.stream().filter(item -> item.emailAttachment)
        .findFirst().orElseThrow().bytes);
  }
}
