package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgSentDateTests {
  static final Instant SENT = Instant.parse("2026-10-23T12:34:56Z");
  @TempDir Path directory;

  @Test
  void upstreamParserReturnsNullForAbsentDates() {
    var message = new org.simplejavamail.outlookmessageparser.model.OutlookMessage();
    assertNull(message.getDate());
    assertNull(message.getCreationDate());
    assertNull(message.getLastModificationDate());
    assertNull(MsgReader.sentDate(message));
  }

  static void fixture(Path source, boolean transport) throws Exception {
    MsgBodySelectionTests.writeMsg(source, 1, false);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(source)))) {
      if (!transport) storage.getRoot().getEntry("__substg1.0_007D001F").delete();
      byte[] original;
      try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) { original = input.readAllBytes(); }
      var properties = ByteBuffer.allocate(original.length + 16).order(ByteOrder.LITTLE_ENDIAN);
      properties.put(original);
      if (!transport) {
        for (int offset = 32; offset + 16 <= original.length; offset += 16) {
          if (properties.getInt(offset) == 0x007d001f) properties.putInt(offset, 0);
        }
      }
      long filetime = (SENT.toEpochMilli() + 11644473600000L) * 10000L;
      properties.position(original.length);
      properties.putInt(0x00390040).putInt(6).putLong(filetime);
      storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties.array()));
      try (var output = new ByteArrayOutputStream()) {
        storage.writeFilesystem(output);
        Files.write(source, output.toByteArray());
      }
    }
  }

  @Test
  void nativeSubmissionTimeCreatesTheCorrectDatedPdfWithoutTransportHeaders() throws Exception {
    Path source = directory.resolve("native-sent-date.msg");
    fixture(source, false);
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(SENT, Common.parseMessageDate(record.sentXmp).toInstant());
    assertEquals(SENT, Common.parseMessageDate(record.rawDateHeader).toInstant());
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("native-sent-date.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      assertTrue(new PDFTextStripper().getText(document).contains(record.rawDateHeader));
      assertTrue(TestSupport.metadataXml(document).contains(record.sentXmp));
      var files = AttachmentProfilePolicyTests.files(document);
      assertEquals(1, files.size());
      assertArrayEquals(Files.readAllBytes(source), AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
  }

  @Test
  void syntheticNativeSubmissionFractionSurvivesIntoXmpExactly() throws Exception {
    Path source = Path.of("src/test/resources/eapdf/outlook-regression/native-reply.msg");
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(Instant.parse("2019-10-08T14:07:48.3287079Z"), java.time.OffsetDateTime.parse(record.sentXmp).toInstant());
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("native-fraction.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      assertTrue(TestSupport.metadataXml(document).contains(record.sentXmp));
      var files = AttachmentProfilePolicyTests.files(document);
      assertArrayEquals(Files.readAllBytes(source), AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
  }

  @Test
  void suppliedTransportDateKeepsItsExistingPrecedenceOverTheNativeTimestamp() throws Exception {
    Path source = directory.resolve("transport-date.msg");
    fixture(source, true);
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(Instant.parse("2026-10-22T12:00:00Z"), Common.parseMessageDate(record.sentXmp).toInstant());
    assertEquals("Thu, 22 Oct 2026 12:00:00 +0000", record.rawDateHeader);
    assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
  }
}
