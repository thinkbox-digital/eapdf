package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MsgBodySelectionTests {
  @TempDir Path tempDir;

  static byte[] unicode(String value) {
    return (value + "\0").getBytes(StandardCharsets.UTF_16LE);
  }

  static void writeMsg(Path path, Integer nativeBody, boolean rtfInSync) throws Exception {
    Map<Integer, byte[]> streams = new LinkedHashMap<>();
    streams.put(0x0037001f, unicode("MSG body selection"));
    streams.put(0x0042001f, unicode("Sender"));
    streams.put(0x0c1f001f, unicode("sender@example.org"));
    streams.put(0x0e04001f, unicode("Recipient"));
    streams.put(0x007d001f, unicode("From: Sender <sender@example.org>\r\nTo: Recipient <recipient@example.org>\r\n"
        + "Date: Thu, 22 Oct 2026 12:00:00 +0000\r\nMessage-ID: <body-selection@example.org>\r\n"));
    String plain = nativeBody != null && nativeBody == 2 && rtfInSync ? "Converted RTF body" : "Original plain body";
    streams.put(0x1000001f, unicode(plain));
    streams.put(0x10130102, "<html><body><p>Native HTML body</p></body></html>".getBytes(StandardCharsets.UTF_8));
    String rtfText = rtfInSync ? plain : "Converted RTF body";
    byte[] rtf = ("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Arial;}}\\f0\\fs24 " + rtfText + "\\par}")
        .getBytes(StandardCharsets.US_ASCII);
    ByteBuffer storedRtf = ByteBuffer.allocate(16 + rtf.length).order(ByteOrder.LITTLE_ENDIAN);
    storedRtf.putInt(rtf.length + 12).putInt(rtf.length).putInt(0x414c454d).putInt(0).put(rtf);
    streams.put(0x10090102, storedRtf.array());
    try (POIFSFileSystem storage = new POIFSFileSystem()) {
      ByteBuffer properties = ByteBuffer.allocate(32 + 16 * (streams.size() + (nativeBody == null ? 1 : 2)))
          .order(ByteOrder.LITTLE_ENDIAN);
      properties.putLong(0).putInt(1).putInt(0).putInt(1).putInt(0);
      properties.position(32);
      for (var entry : streams.entrySet()) {
        storage.getRoot().createDocument(String.format("__substg1.0_%08X", entry.getKey()),
            new ByteArrayInputStream(entry.getValue()));
        properties.putInt(entry.getKey()).putInt(6).putInt(entry.getValue().length).putInt(0);
      }
      properties.putInt(0x0e1f000b).putInt(6).putLong(rtfInSync ? 1 : 0);
      if (nativeBody != null) properties.putInt(0x10160003).putInt(6).putInt(nativeBody).putInt(0);
      storage.getRoot().createDocument("__properties_version1.0", new ByteArrayInputStream(properties.array()));
      var recipient = storage.getRoot().createDirectory("__recip_version1.0_#00000000");
      byte[] display = unicode("Recipient");
      byte[] address = unicode("recipient@example.org");
      recipient.createDocument("__substg1.0_3001001F", new ByteArrayInputStream(display));
      recipient.createDocument("__substg1.0_3003001F", new ByteArrayInputStream(address));
      ByteBuffer recipientProperties = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
      recipientProperties.position(8);
      recipientProperties.putInt(0x3001001f).putInt(6).putInt(display.length).putInt(0);
      recipientProperties.putInt(0x3003001f).putInt(6).putInt(address.length).putInt(0);
      recipientProperties.putInt(0x0c150003).putInt(6).putInt(1).putInt(0);
      recipient.createDocument("__properties_version1.0", new ByteArrayInputStream(recipientProperties.array()));
      try (var output = Files.newOutputStream(path)) {
        storage.writeFilesystem(output);
      }
    }
  }

  EmailRecord record(Integer nativeBody, boolean inSync) throws Exception {
    Path path = tempDir.resolve("body.msg");
    writeMsg(path, nativeBody, inSync);
    EmailRecord record = MsgReader.readMsg(path.toString(), "", List.of(), false);
    Assertions.assertArrayEquals(Files.readAllBytes(path), record.sourceBytes);
    return record;
  }

  @Test
  void declaredHtmlBodyDoesNotRenderTheConvertedRtfAlternative() throws Exception {
    EmailRecord record = record(3, true);
    Assertions.assertEquals("text/html", record.bodyMime);
    Assertions.assertEquals("Native HTML body", Jsoup.parse(record.htmlBody).text());
  }

  @Test
  void declaredPlainBodyDoesNotRenderAnHtmlOrRtfAlternative() throws Exception {
    EmailRecord record = record(1, true);
    Assertions.assertEquals("text/plain", record.bodyMime);
    Assertions.assertEquals("Original plain body", Jsoup.parse(record.htmlBody).text());
  }

  @Test
  void declaredRtfBodyKeepsItsConvertedRenderingAndOriginalRtf() throws Exception {
    EmailRecord record = record(2, false);
    Assertions.assertEquals("text/rtf", record.bodyMime);
    Assertions.assertTrue(Jsoup.parse(record.htmlBody).text().contains("Converted RTF body"));
    Assertions.assertTrue(record.rtfBody.contains("Converted RTF body"));
  }

  @Test
  void absentNativeBodyUsesHtmlWhenRtfIsNotInSync() throws Exception {
    EmailRecord record = record(null, false);
    Assertions.assertEquals("text/html", record.bodyMime);
    Assertions.assertEquals("Native HTML body", Jsoup.parse(record.htmlBody).text());
  }

  @Test
  void absentNativeBodyKeepsRtfWhenMarkedInSync() throws Exception {
    EmailRecord record = record(null, true);
    Assertions.assertEquals("text/rtf", record.bodyMime);
    Assertions.assertTrue(Jsoup.parse(record.htmlBody).text().contains("Original plain body"));
    Assertions.assertTrue(record.rtfBody.contains("Original plain body"));
  }

  @Test
  void zeroPaddedPropertyStreamKeepsAnOrdinaryMessageAndItsAttachments() throws Exception {
    Path source = Path.of("in/EmailWithAttachments.msg");
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    Assertions.assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
    Assertions.assertEquals(2, record.attachmentCount);
    Assertions.assertEquals("text/html", record.bodyMime);
    Assertions.assertFalse(Jsoup.parse(record.htmlBody).text().isBlank());
    Assertions.assertTrue(record.associatedFiles.stream().filter(file -> file.emailAttachment)
        .allMatch(file -> file.hasData() && file.size() > 0));
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    for (int format : new int[]{1, 2, 3}) {
      Path path = directory.resolve("native-" + format + ".msg");
      writeMsg(path, format, true);
      EmailRecord record = MsgReader.readMsg(path.toString(), "", List.of(), false);
      System.out.println("native=" + format + " selected=" + record.bodyMime + " text=" + Jsoup.parse(record.htmlBody).text());
    }
    if (args.length > 1) Thread.sleep(45000);
  }
}
