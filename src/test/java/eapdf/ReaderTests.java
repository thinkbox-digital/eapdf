package eapdf;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ReaderTests {

  @Test
  void gmailSelectionIsNotRestrictedToAnEmbeddedParticipantAddress() {
    Assertions.assertNull(GmailDownloadToPdf.buildMessageQuery(false));
    Assertions.assertEquals("is:unread", GmailDownloadToPdf.buildMessageQuery(true));
    var options = GmailDownloadToPdf.parseArgs(new String[] {"--email", "owner@example.org"});
    Assertions.assertEquals(List.of("owner@example.org"), options.get("email"));
  }

  @Test
  void readEmlParsesKeyFieldsAndAssociatedFiles() throws Exception {
    var emlPath = TestSupport.firstInputWithExtension(".eml");
    EmailRecord record = EmlReader.readEml(
        emlPath.toString(),
        "",
        List.of(),
        false);

    Assertions.assertEquals("eml", record.sourceType);
    Assertions.assertNotNull(record.subject);
    Assertions.assertFalse(record.subject.isBlank());
    Assertions.assertNotNull(record.from);
    Assertions.assertFalse(record.from.isBlank());
    Assertions.assertNotNull(record.to);
    Assertions.assertFalse(record.to.isBlank());
    Assertions.assertTrue(
        (record.textBody != null && !record.textBody.isBlank())
            || (record.htmlBody != null && !record.htmlBody.isBlank()));
    Assertions.assertEquals(emlPath.getFileName().toString(), record.sourceFilename);
    Assertions.assertTrue(
        record.associatedFiles.stream()
            .anyMatch(
                af ->
                    "Source".equals(af.afRelationship)
                        && emlPath.getFileName().toString().equals(af.filename)));
  }

  @Test
  void mboxReaderParsesExpectedCountAndSourceType() throws Exception {
    var mboxPath = TestSupport.firstInputWithExtension(".mbox");
    List<EmailRecord> records = MboxReader.readMbox(
        mboxPath.toString(),
        "",
        List.of(),
        false);

    Assertions.assertFalse(records.isEmpty());
    Assertions.assertTrue(records.stream().allMatch(r -> "mbox".equals(r.sourceType)));
    Assertions.assertTrue(records.get(0).sourceFilename.endsWith("_000001.eml"));
    Assertions.assertTrue(records.stream().allMatch(r -> r.subject != null));
  }

  @Test
  void normalizeMessageBytesUnescapesBodyFromLines() {
    String message = ""
        + "Subject: Example\r\n"
        + "\r\n"
        + ">From escaped body line\r\n"
        + "Normal line\r\n";
    byte[] bytes = MboxReader.normalizeMessageBytes(message);
    String normalized = new String(bytes, StandardCharsets.ISO_8859_1);
    Assertions.assertTrue(normalized.contains("\r\nFrom escaped body line\r\n"));
    Assertions.assertFalse(normalized.contains("\r\n>From escaped body line\r\n"));
  }
}
