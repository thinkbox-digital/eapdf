package eapdf;

import com.pff.PSTMessage;
import com.pff.PSTObject;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PstAuthorPreservationTests {
  @TempDir Path directory;

  @SuppressWarnings("unchecked")
  static void strings(PSTMessage message, Map<Integer, String> values) throws Exception {
    var field = PSTObject.class.getDeclaredField("items");
    field.setAccessible(true);
    Map<Integer, Object> items = (Map<Integer, Object>) field.get(message);
    var type = items.get(0x001a).getClass();
    var constructor = type.getDeclaredConstructor();
    constructor.setAccessible(true);
    for (var entry : values.entrySet()) {
      var item = constructor.newInstance();
      var data = type.getField("data"); data.setAccessible(true);
      data.set(item, entry.getValue().getBytes(StandardCharsets.UTF_16LE));
      var valueType = type.getField("entryValueType"); valueType.setAccessible(true); valueType.setInt(item, 0x1f);
      var external = type.getField("isExternalValueReference"); external.setAccessible(true); external.setBoolean(item, false);
      items.put(entry.getKey(), item);
    }
  }

  @Test
  void representedAuthorSurvivesNativePstReadingPdfMetadataAndExport() throws Exception {
    byte[] original = Files.readAllBytes(PstItemSelectionTests.SOURCE);
    try (var nativeItem = new PstItemSelectionTests.NativeItem()) {
      strings(nativeItem.message, Map.of(0x007d, "", 0x0042, "Alice Author", 0x0065, "alice@example.org",
          0x0c1a, "David Delegate", 0x0c1f, "david@example.org"));
      EmailRecord record = PstReader.readMessage(nativeItem.message, "Inbox", PstItemSelectionTests.SOURCE.toString(),
          "", List.of(), false, 0);
      assertEquals("Alice Author", record.fromAgents.get(0).name);
      assertEquals("alice@example.org", record.fromAgents.get(0).email);
      var exported = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
          new ByteArrayInputStream(Common.synthesizeEml(record)));
      assertEquals("alice@example.org", ((jakarta.mail.internet.InternetAddress) exported.getFrom()[0]).getAddress());
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("author.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var document = Loader.loadPDF(output.pdfBytes)) {
        assertTrue(new PDFTextStripper().getText(document).contains("Alice Author <alice@example.org>"));
        assertTrue(TestSupport.metadataXml(document).contains("mailto:alice@example.org"));
        assertFalse(TestSupport.metadataXml(document).contains("mailto:david@example.org"));
        var retained = EmlReader.readRfc822Bytes(AttachmentProfilePolicyTests.bytes(
            AttachmentProfilePolicyTests.files(document).values().iterator().next()), "export.eml", "export.eml", "", List.of(), false);
        assertEquals("alice@example.org", retained.fromAgents.get(0).email);
      }
    }
    assertArrayEquals(original, Files.readAllBytes(PstItemSelectionTests.SOURCE));
  }

  @Test
  void transportAuthorKeepsPriorityAndMissingRepresentedAuthorUsesTheActualSender() throws Exception {
    for (boolean transport : List.of(true, false)) {
      try (var nativeItem = new PstItemSelectionTests.NativeItem()) {
        strings(nativeItem.message, Map.of(0x007d, transport ? "From: Transport Author <transport@example.org>\r\n" : "",
            0x0042, transport ? "Alice Author" : "\0", 0x0065, transport ? "alice@example.org" : "\0",
            0x0c1a, "David Delegate", 0x0c1f, "david@example.org"));
        var record = PstReader.readMessage(nativeItem.message, "Inbox", PstItemSelectionTests.SOURCE.toString(),
            "", List.of(), false, 0);
        assertEquals(transport ? "transport@example.org" : "david@example.org", record.fromAgents.get(0).email);
        assertEquals(transport ? "Transport Author" : "David Delegate", record.fromAgents.get(0).name);
      }
    }
    try (var nativeItem = new PstItemSelectionTests.NativeItem()) {
      strings(nativeItem.message, Map.of(0x007d, "", 0x0042, "Partial Author", 0x0065, "",
          0x0c1a, "Stored Sender", 0x0c1f, "/O=EXCHANGE/CN=STORED-SENDER"));
      var record = PstReader.readMessage(nativeItem.message, "Inbox", PstItemSelectionTests.SOURCE.toString(),
          "", List.of(), false, 0);
      assertEquals("Stored Sender", record.fromAgents.get(0).name);
      assertEquals("/O=EXCHANGE/CN=STORED-SENDER", record.fromAgents.get(0).email);
      assertTrue(record.rawFrom.contains("/O=EXCHANGE/CN=STORED-SENDER"));
    }
  }
}
