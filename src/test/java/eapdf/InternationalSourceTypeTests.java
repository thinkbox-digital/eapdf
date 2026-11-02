package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InternationalSourceTypeTests {
  static byte[] message(String subject, String newline, String body) {
    return ("From: sender@example.org" + newline + "To: recipient@example.org" + newline
        + "Subject: " + subject + newline + "Date: Thu, 22 Oct 2026 12:00:00 +0000" + newline
        + "Message-ID: <global-source@example.org>" + newline + "MIME-Version: 1.0" + newline
        + "Content-Type: text/plain; charset=UTF-8" + newline + newline + body)
        .getBytes(StandardCharsets.UTF_8);
  }

  static void check(byte[] raw, String expected) throws Exception {
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "original.eml", "original.eml", "", List.of(), false);
    Assertions.assertEquals(expected, record.sourceMime);
    AssociatedFile source = record.associatedFiles.stream().filter(f -> "Source".equals(f.afRelationship)).findFirst().orElseThrow();
    Assertions.assertEquals(expected, source.mime);
    Assertions.assertArrayEquals(raw, source.data());
    Assertions.assertArrayEquals(raw, record.sourceBytes);
  }

  @Test
  void directUtf8HeadersIdentifyAnInternationalOriginal() throws Exception {
    check(message("Résumé", "\r\n", "Body"), "message/global");
  }

  @Test
  void lfHeaderLinesAlsoIdentifyAnInternationalOriginal() throws Exception {
    check(message("Résumé", "\n", "Body"), "message/global");
  }

  @Test
  void asciiEncodedWordsRemainRfc822() throws Exception {
    check(message("=?UTF-8?Q?R=C3=A9sum=C3=A9?=", "\r\n", "Body"), "message/rfc822");
  }

  @Test
  void bodyOnlyUtf8DoesNotChangeTheOriginalType() throws Exception {
    check(message("ASCII", "\r\n", "Résumé"), "message/rfc822");
  }

  @Test
  void legacyInvalidUtf8HeadersKeepTheirExistingType() throws Exception {
    byte[] raw = new String(message("Résumé", "\r\n", "Body"), StandardCharsets.UTF_8).getBytes(StandardCharsets.ISO_8859_1);
    check(raw, "message/rfc822");
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    byte[] raw = message("Résumé", "\r\n", "International original body");
    Files.write(directory.resolve("original.eml"), raw);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "original.eml", "original.eml", "", List.of(), false);
    Files.writeString(directory.resolve("source-type.txt"), record.sourceMime);
    if (args.length > 1) Thread.sleep(45000);
  }
}
