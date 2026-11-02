package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgNameEncodingTests {
  @TempDir Path directory;
  static final String FROM = "Ирина Петрова";
  static final String TO = "Сергей Иванов";

  static void ansiName(DirectoryNode node, int id, String text, int headerSize) throws Exception {
    String prefix = String.format("__substg1.0_%04X", id);
    node.getEntry(prefix + "001F").delete();
    byte[] bytes = (text + "\0").getBytes(Charset.forName("windows-1251"));
    node.createDocument(prefix + "001E", new ByteArrayInputStream(bytes));
    byte[] original;
    try (var input = node.createDocumentInputStream("__properties_version1.0")) { original = input.readAllBytes(); }
    var properties = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
    for (int offset = headerSize; offset + 16 <= original.length; offset += 16) {
      if (properties.getInt(offset) == ((id << 16) | 0x001f)) {
        properties.putInt(offset, (id << 16) | 0x001e);
        properties.putInt(offset + 8, bytes.length);
      }
    }
    node.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(original));
  }

  static void fixture(Path source) throws Exception {
    MsgTextEncodingTests.fixture(source, "Обычное письмо", 1251, false);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(source)))) {
      ansiName(storage.getRoot(), 0x0042, FROM, 32);
      ansiName(storage.getRoot(), 0x0e04, TO, 32);
      ansiName((DirectoryNode) storage.getRoot().getEntry("__recip_version1.0_#00000000"), 0x3001, TO, 8);
      try (var output = new ByteArrayOutputStream()) {
        storage.writeFilesystem(output);
        Files.write(source, output.toByteArray());
      }
    }
  }

  @Test
  void nativeParticipantNamesUseTheirDeclaredEncodingInHeadersAndXmp() throws Exception {
    Path source = directory.resolve("participants.msg");
    fixture(source);
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(FROM, record.fromAgents.getFirst().name);
    assertEquals(TO, record.toAgents.getFirst().name);
    assertEquals("sender@example.org", record.fromAgents.getFirst().email);
    assertEquals("recipient@example.org", record.toAgents.getFirst().email);
    assertTrue(record.from.contains(FROM));
    assertTrue(record.to.contains(TO));
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("participants.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var pdf = Loader.loadPDF(output.pdfBytes)) {
      String text = new PDFTextStripper().getText(pdf);
      assertTrue(text.contains(FROM));
      assertTrue(text.contains(TO));
      String metadata = TestSupport.metadataXml(pdf);
      assertTrue(metadata.contains(FROM));
      assertTrue(metadata.contains(TO));
      var files = AttachmentProfilePolicyTests.files(pdf);
      assertEquals(1, files.size());
      assertArrayEquals(Files.readAllBytes(source), AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
  }

  @Test
  void displayOnlyRecipientNamesUseTheSameDeclaredEncoding() throws Exception {
    Path source = directory.resolve("display-only.msg");
    fixture(source);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(source)))) {
      var recipient = (DirectoryNode) storage.getRoot().getEntry("__recip_version1.0_#00000000");
      for (String name : List.of("__substg1.0_3001001E", "__substg1.0_3003001F", "__properties_version1.0")) recipient.getEntry(name).delete();
      recipient.delete();
      // No native rows or supplied transport To: display-name recovery must not invent a mailbox.
      byte[] headers = MsgBodySelectionTests.unicode("Date: Thu, 22 Oct 2026 12:00:00 +0000\r\n"
          + "Message-ID: <display-only@example.org>\r\n");
      storage.getRoot().createOrUpdateDocument("__substg1.0_007D001F", new ByteArrayInputStream(headers));
      byte[] properties;
      try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) { properties = input.readAllBytes(); }
      var table = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
      table.putInt(8, 0).putInt(16, 0);
      for (int offset = 32; offset + 16 <= properties.length; offset += 16) {
        if (table.getInt(offset) == 0x007d001f) table.putInt(offset + 8, headers.length);
      }
      storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
      try (var output = new ByteArrayOutputStream()) { storage.writeFilesystem(output); Files.write(source, output.toByteArray()); }
    }
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(TO, record.to);
    assertEquals(TO, record.rawTo);
    assertTrue(record.toAgents.isEmpty());
  }
}
