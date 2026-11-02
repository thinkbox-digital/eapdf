package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgAttachmentEncodingTests {
  @TempDir Path directory;
  static final byte[] DATA = "Original invoice attachment bytes\r\n".getBytes(StandardCharsets.UTF_8);

  static void fixture(Path source, int codePage, String filename, boolean embedded) throws Exception {
    MsgTextEncodingTests.fixture(source, "Invoice details", codePage, false);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(source)))) {
      var attachment = storage.getRoot().createDirectory("__attach_version1.0_#00000000");
      byte[] name = (filename + "\0").getBytes(Charset.forName("windows-" + codePage));
      byte[] mime = MsgBodySelectionTests.unicode(embedded ? "application/vnd.ms-outlook" : "text/plain");
      attachment.createDocument("__substg1.0_3707001E", new ByteArrayInputStream(name));
      attachment.createDocument("__substg1.0_370E001F", new ByteArrayInputStream(mime));
      if (embedded) {
        var child = (DirectoryNode) attachment.createDirectory("__substg1.0_3701000D");
        Path childSource = source.resolveSibling("child.msg");
        MsgBodySelectionTests.writeMsg(childSource, 1, false);
        try (var original = new POIFSFileSystem(childSource.toFile(), true)) { EntryUtils.copyNodes(original.getRoot(), child); }
        byte[] originalProperties;
        try (var input = child.createDocumentInputStream("__properties_version1.0")) { originalProperties = input.readAllBytes(); }
        byte[] properties = new byte[originalProperties.length - 8];
        System.arraycopy(originalProperties, 0, properties, 0, 24);
        System.arraycopy(originalProperties, 32, properties, 24, originalProperties.length - 32);
        child.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
      } else {
        attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream(DATA));
      }
      var table = ByteBuffer.allocate(8 + 16 * 4).order(ByteOrder.LITTLE_ENDIAN);
      table.position(8);
      table.putInt(0x37050003).putInt(6).putInt(embedded ? 5 : 1).putInt(0);
      table.putInt(embedded ? 0x3701000d : 0x37010102).putInt(6).putInt(embedded ? 0 : DATA.length).putInt(0);
      table.putInt(0x3707001e).putInt(6).putInt(name.length).putInt(0);
      table.putInt(0x370e001f).putInt(6).putInt(mime.length).putInt(0);
      attachment.createDocument("__properties_version1.0", new ByteArrayInputStream(table.array()));
      byte[] root;
      try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) { root = input.readAllBytes(); }
      ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 1).putInt(20, 1);
      storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(root));
      try (var output = new ByteArrayOutputStream()) { storage.writeFilesystem(output); Files.write(source, output.toByteArray()); }
    }
  }

  @Test
  void declaredFilenameEncodingSurvivesIntoPdfNamesMetadataAndNativeAttachment() throws Exception {
    for (int codePage : new int[]{1251, 1252}) {
      String filename = codePage == 1251 ? "Смета.txt" : "Résumé — 25€.txt";
      Path source = directory.resolve("attachment-" + codePage + ".msg");
      fixture(source, codePage, filename, false);
      EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
      var attachment = record.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
      assertEquals(filename, attachment.filename);
      assertArrayEquals(DATA, attachment.data());
      assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("attachment-" + codePage + ".pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var document = Loader.loadPDF(output.pdfBytes)) {
        assertTrue(new PDFTextStripper().getText(document).contains(filename));
        assertTrue(TestSupport.metadataXml(document).contains(filename));
        boolean found = false;
        for (var page : document.getPages()) for (var annotation : page.getAnnotations()) {
          if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment item
              && item.getFile() instanceof org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification file
              && filename.equals(file.getFileUnicode())) {
            assertArrayEquals(DATA, AttachmentProfilePolicyTests.bytes(file));
            found = true;
          }
        }
        assertTrue(found);
      }
    }
  }

  @Test
  void attachedMessageFilenameUsesItsParentMessageEncoding() throws Exception {
    Path source = directory.resolve("nested.msg");
    fixture(source, 1251, "Вложенное.msg", true);
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    var attachment = record.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    assertEquals("Вложенное.msg", attachment.filename);
    Path child = directory.resolve("extracted.msg");
    Files.write(child, attachment.data());
    EmailRecord parsed = MsgReader.readMsg(child.toString(), "", List.of(), false);
    assertEquals("Original plain body", parsed.textBody);
    assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
  }
}
