package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgSenderAddressTests {
  @TempDir Path directory;

  static void fixture(Path path, Map<Integer, String> strings) throws Exception {
    MsgSentDateTests.fixture(path, false);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(path)))) {
      var root = storage.getRoot();
      byte[] original;
      try (var input = root.createDocumentInputStream("__properties_version1.0")) { original = input.readAllBytes(); }
      var old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
      var properties = ByteBuffer.allocate(original.length + strings.size() * 16).order(ByteOrder.LITTLE_ENDIAN);
      properties.put(original, 0, 32);
      for (int offset = 32; offset + 16 <= original.length; offset += 16) {
        if (!strings.containsKey(old.getInt(offset) >>> 16)) properties.put(original, offset, 16);
      }
      for (var entry : strings.entrySet()) {
        byte[] value = MsgBodySelectionTests.unicode(entry.getValue());
        root.createOrUpdateDocument(String.format("__substg1.0_%04X001F", entry.getKey()), new ByteArrayInputStream(value));
        properties.putInt(entry.getKey() << 16 | 0x001f).putInt(6).putInt(value.length).putInt(0);
      }
      root.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(
          java.util.Arrays.copyOf(properties.array(), properties.position())));
      try (var output = new ByteArrayOutputStream()) {
        storage.writeFilesystem(output);
        Files.write(path, output.toByteArray());
      }
    }
  }

  @Test
  void storedExchangeSmtpAddressSurvivesInHeadersMetadataAndOriginalSource() throws Exception {
    Path source = directory.resolve("exchange.msg");
    fixture(source, Map.of(0x0c1f, "/O=EXCHANGE/OU=ORG/CN=RECIPIENTS/CN=AUTHOR",
        0x0065, "/O=EXCHANGE/OU=ORG/CN=RECIPIENTS/CN=AUTHOR", 0x5d02, "author@example.org"));
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals("author@example.org", record.fromAgents.get(0).email);
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("exchange.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      assertTrue(new PDFTextStripper().getText(document).contains("author@example.org"));
      assertTrue(TestSupport.metadataXml(document).contains("mailto:author@example.org"));
      assertTrue(record.rawFrom.contains("author@example.org"));
      var files = AttachmentProfilePolicyTests.files(document);
      assertEquals(1, files.size());
      assertArrayEquals(Files.readAllBytes(source), AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
  }

  @Test
  void delegateAndAuthorAddressesAreNotConfusedAndParsedMailboxesKeepPriority() throws Exception {
    String delegate = "/O=EXCHANGE/CN=DELEGATE", author = "/O=EXCHANGE/CN=AUTHOR";
    Path source = directory.resolve("delegate.msg");
    fixture(source, Map.of(0x0c1f, delegate, 0x0065, author,
        0x5d01, "delegate@example.org", 0x5d02, "author@example.org"));
    assertEquals("author@example.org", MsgReader.readMsg(source.toString(), "", List.of(), false).fromAgents.get(0).email);
    fixture(source, Map.of(0x0c1f, delegate, 0x0065, author, 0x5d01, "delegate@example.org"));
    assertTrue(MsgReader.readMsg(source.toString(), "", List.of(), false).fromAgents.get(0).email.isBlank());
    fixture(source, Map.of(0x0c1f, delegate, 0x5d01, "delegate@example.org"));
    assertEquals("delegate@example.org", MsgReader.readMsg(source.toString(), "", List.of(), false).fromAgents.get(0).email);
    fixture(source, Map.of(0x0c1f, "known@example.org", 0x5d02, "other@example.org"));
    assertEquals("known@example.org", MsgReader.readMsg(source.toString(), "", List.of(), false).fromAgents.get(0).email);
  }

  @Test
  void suppliedNativeSenderNameIsRetainedWhenRepresentedNameIsAbsent() throws Exception {
    Path source = Path.of("in/EmailWithAttachments.msg");
    byte[] original = Files.readAllBytes(source);
    var record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals("Kees", record.fromAgents.get(0).name);
    assertEquals("peterpan@neverland.com", record.fromAgents.get(0).email);
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("sender.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      assertTrue(new PDFTextStripper().getText(document).contains("Kees <peterpan@neverland.com>"));
      assertTrue(TestSupport.metadataXml(document).contains("foaf:name=\"Kees\""));
      var files = AttachmentProfilePolicyTests.files(document);
      assertEquals(1, files.size());
      assertArrayEquals(original, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
    assertArrayEquals(original, Files.readAllBytes(source));
  }

  @Test
  void differentDelegateNameIsNotAssignedToRepresentedAuthor() throws Exception {
    Path source = directory.resolve("delegate-name.msg");
    fixture(source, Map.of(0x0042, "", 0x0c1a, "David Delegate", 0x0c1f, "david@example.org",
        0x0065, "alice@example.org"));
    var record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals("alice@example.org", record.fromAgents.get(0).email);
    assertTrue(record.fromAgents.get(0).name.isBlank());
  }
}
