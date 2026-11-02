package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InternationalHeaderTests {
  @TempDir Path tempDir;

  static byte[] message(String subject, String charset, String body) {
    return ("From: José <jose@example.org>\r\nTo: Zoë <zoe@example.org>\r\n"
        + "Subject: " + subject + "\r\nDate: Thu, 22 Oct 2026 12:00:00 +0000\r\n"
        + "Message-ID: <international@example.org>\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=" + charset + "\r\n\r\n" + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  static EmailRecord parse(byte[] raw) throws Exception {
    return EmlReader.readRfc822Bytes(raw, "international.eml", "international.eml", "", List.of(), false);
  }

  @Test
  void directUtf8HeadersRetainSubjectAndParticipantNames() throws Exception {
    byte[] raw = message("Résumé – réunion", "UTF-8", "Body");
    EmailRecord record = parse(raw);
    Assertions.assertEquals("Résumé – réunion", record.subject);
    Assertions.assertEquals("José <jose@example.org>", record.rawFrom);
    Assertions.assertEquals("José", record.fromAgents.get(0).name);
    Assertions.assertEquals("Zoë", record.toAgents.get(0).name);
    Assertions.assertArrayEquals(raw, record.sourceBytes);
  }

  @Test
  void nonUtf8BodyBytesDoNotPreventUtf8HeaderDecoding() throws Exception {
    byte[] headers = message("Résumé", "ISO-8859-1", "");
    byte[] raw = java.util.Arrays.copyOf(headers, headers.length + 1);
    raw[headers.length] = (byte) 0xe9;
    EmailRecord record = parse(raw);
    Assertions.assertEquals("Résumé", record.subject);
    Assertions.assertEquals("é", record.textBody);
    Assertions.assertArrayEquals(raw, record.sourceBytes);
  }

  @Test
  void legacyHeaderOctetsKeepTheirPreviousMappingWithoutReplacement() throws Exception {
    byte[] raw = ("From: sender@example.org\r\nSubject: Legacy é\r\n"
        + "Date: Thu, 22 Oct 2026 12:00:00 +0000\r\n\r\nBody")
        .getBytes(StandardCharsets.ISO_8859_1);
    EmailRecord record = parse(raw);
    Assertions.assertEquals("Legacy é", record.subject);
    Assertions.assertFalse(record.subject.contains("\ufffd"));
    Assertions.assertArrayEquals(raw, record.sourceBytes);
  }

  @Test
  void encodedWordsStillDecodeAlongsideDirectUtf8Names() throws Exception {
    EmailRecord record = parse(message("=?UTF-8?Q?R=C3=A9sum=C3=A9?=", "UTF-8", "Body"));
    Assertions.assertEquals("Résumé", record.subject);
  }

  @Test
  void mboxMessagesUseTheSameUtf8HeaderDecoder() throws Exception {
    Path mbox = tempDir.resolve("international.mbox");
    byte[] raw = message("Résumé", "UTF-8", "Body\r\n");
    var data = new java.io.ByteArrayOutputStream();
    data.write("From sender@example.org Thu Oct 22 12:00:00 2026\n".getBytes(StandardCharsets.US_ASCII));
    data.write(raw);
    Files.write(mbox, data.toByteArray());
    var records = MboxReader.readMbox(mbox.toString(), "", List.of(), false);
    Assertions.assertEquals(1, records.size());
    Assertions.assertEquals("Résumé", records.get(0).subject);
    Assertions.assertEquals("José", records.get(0).fromAgents.get(0).name);
  }
}
