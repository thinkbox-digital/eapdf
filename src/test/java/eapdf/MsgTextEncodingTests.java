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
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgTextEncodingTests {
  @TempDir Path directory;

  static void fixture(Path path, String text, int codePage, boolean unicode) throws Exception {
    MsgBodySelectionTests.writeMsg(path, 1, false);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(path)))) {
      String stream = unicode ? "__substg1.0_1000001F" : "__substg1.0_1000001E";
      byte[] body = unicode ? MsgBodySelectionTests.unicode(text)
          : (text + "\0").getBytes(Charset.forName("windows-" + codePage));
      storage.getRoot().getEntry("__substg1.0_1000001F").delete();
      storage.getRoot().createDocument(stream, new ByteArrayInputStream(body));
      byte[] original;
      try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) {
        original = input.readAllBytes();
      }
      ByteBuffer properties = ByteBuffer.allocate(original.length + 16).order(ByteOrder.LITTLE_ENDIAN);
      properties.put(original);
      for (int offset = 32; offset + 16 <= original.length; offset += 16) {
        if (properties.getInt(offset) == 0x1000001f) {
          properties.putInt(offset, unicode ? 0x1000001f : 0x1000001e);
          properties.putInt(offset + 8, body.length);
        }
      }
      properties.position(original.length);
      properties.putInt(0x3ffd0003).putInt(0).putInt(codePage).putInt(0);
      storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties.array()));
      try (var output = new ByteArrayOutputStream()) {
        storage.writeFilesystem(output);
        Files.write(path, output.toByteArray());
      }
    }
  }

  @Test
  void declaredAnsiBodyEncodingPreservesActualTextInThePdfAndExactOriginal() throws Exception {
    for (int codePage : new int[]{1251, 1252}) {
      String text = codePage == 1251 ? "Проверка письма: встреча в пятницу. Стоимость 25 €."
          : "Résumé de réunion — coût 25 €.";
      Path source = directory.resolve("body-" + codePage + ".msg");
      fixture(source, text, codePage, false);
      EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
      assertEquals(text, record.textBody);
      assertEquals("text/plain", record.bodyMime);
      assertEquals(text, org.jsoup.Jsoup.parse(record.htmlBody).text());
      byte[] pdf = PdfMailWriter.buildSingleMessagePdf(directory.resolve("body-" + codePage + ".pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
      try (var document = Loader.loadPDF(pdf)) {
        assertTrue(new PDFTextStripper().getText(document).contains(text));
        var sources = AttachmentProfilePolicyTests.files(document);
        assertEquals(1, sources.size());
        assertArrayEquals(Files.readAllBytes(source), AttachmentProfilePolicyTests.bytes(sources.values().iterator().next()));
      }
    }
  }

  @Test
  void unicodeBodyIsNotDecodedUsingTheAnsiCodePage() throws Exception {
    String text = "Réunion — проверка 25 €.";
    Path source = directory.resolve("unicode.msg");
    fixture(source, text, 1251, true);
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(text, record.textBody);
    assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
  }
}
