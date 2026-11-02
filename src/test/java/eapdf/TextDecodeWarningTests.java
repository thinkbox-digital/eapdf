package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class TextDecodeWarningTests {
  static byte[] message(String type, byte[] body) throws Exception {
    byte[] header = ("From: sender@example.org\r\nTo: recipient@example.org\r\n"
        + "Subject: Text decoding\r\nDate: Thu, 22 Oct 2026 12:00:00 +0000\r\n"
        + "Message-ID: <decode@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: " + type + "\r\n\r\n")
        .getBytes(StandardCharsets.US_ASCII);
    byte[] raw = java.util.Arrays.copyOf(header, header.length + body.length);
    System.arraycopy(body, 0, raw, header.length, body.length);
    return raw;
  }

  static EmailRecord parse(String type, byte[] body) throws Exception {
    byte[] raw = message(type, body);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "decode.eml", "decode.eml", "", List.of(), false);
    Assertions.assertArrayEquals(raw, record.sourceBytes);
    return record;
  }

  @Test
  void malformedUtf8RetainsRecoveryAndReportsReplacement() throws Exception {
    EmailRecord record = parse("text/plain; charset=UTF-8", new byte[]{'a', (byte) 0xc3, '(', 'b'});
    Assertions.assertEquals("a\ufffd(b", record.textBody);
    Assertions.assertEquals(1, record.conversionWarnings.size());
    Assertions.assertTrue(record.conversionWarnings.get(0).contains("UTF-8"));
  }

  @Test
  void undeclaredHighBytesKeepTheExistingCharsetMapping() throws Exception {
    EmailRecord record = parse("text/plain", new byte[]{'a', (byte) 0xe9});
    Assertions.assertEquals("a\u00e9", record.textBody);
    Assertions.assertTrue(record.conversionWarnings.isEmpty());
  }

  @Test
  void malformedHtmlAlsoReportsRecovery() throws Exception {
    EmailRecord record = parse("text/html; charset=UTF-8", new byte[]{'<', 'p', '>', (byte) 0xff, '<', '/', 'p', '>'});
    Assertions.assertTrue(record.htmlBody.contains("\ufffd"));
    Assertions.assertEquals(1, record.conversionWarnings.size());
  }

  @Test
  void literalReplacementCharacterDoesNotImplyDecodingLoss() throws Exception {
    EmailRecord record = parse("text/plain; charset=UTF-8", "Valid \ufffd character".getBytes(StandardCharsets.UTF_8));
    Assertions.assertEquals("Valid \ufffd character", record.textBody);
    Assertions.assertTrue(record.conversionWarnings.isEmpty());
  }

  @Test
  void validLegacyCharsetDoesNotWarn() throws Exception {
    EmailRecord record = parse("text/plain; charset=ISO-8859-1", new byte[]{'c', 'a', 'f', (byte) 0xe9});
    Assertions.assertEquals("caf\u00e9", record.textBody);
    Assertions.assertTrue(record.conversionWarnings.isEmpty());
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    byte[] raw = message("text/plain; charset=UTF-8", new byte[]{'a', (byte) 0xc3, '(', 'b'});
    Files.write(directory.resolve("decode.eml"), raw);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "decode.eml", "decode.eml", "", List.of(), false);
    Files.writeString(directory.resolve("body.txt"), record.textBody);
    Files.writeString(directory.resolve("warnings.txt"), String.join("\n", record.conversionWarnings));
    if (args.length > 1) Thread.sleep(45000);
  }
}
