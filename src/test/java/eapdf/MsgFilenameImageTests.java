package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgFilenameImageTests {
  static final Path SOURCE = Path.of("in/EmailWithAttachments.msg");
  @TempDir Path directory;

  @Test
  void suppliedOutlookPictureRendersFromStoredFileAndPreservesOriginal() throws Exception {
    var record = MsgReader.readMsg(SOURCE.toString(), "", List.of(), false);
    var picture = record.associatedFiles.stream().filter(f -> "tinkerbell.jpg".equals(f.filename)).findFirst().orElseThrow();
    assertSame(picture, PdfMailWriter.findLocationAsset("tinkerbell.jpg", record));
    assertNull(PdfMailWriter.findLocationAsset("file:///private/tinkerbell.jpg", record));
    assertArrayEquals(Files.readAllBytes(SOURCE), record.sourceBytes);
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("picture.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      boolean found = false;
      for (var name : document.getPage(0).getResources().getXObjectNames()) {
        if (document.getPage(0).getResources().getXObject(name) instanceof PDImageXObject image
            && image.getWidth() == 500 && image.getHeight() == 953) found = true;
      }
      assertTrue(found, "Stored picture is missing from its body page");
      assertArrayEquals(Files.readAllBytes(SOURCE), AttachmentProfilePolicyTests.bytes(
          AttachmentProfilePolicyTests.files(document).values().iterator().next()));
      for (var file : record.associatedFiles) if (file.emailAttachment) {
        boolean retained = false;
        for (var page : document.getPages()) for (var annotation : page.getAnnotations()) {
          if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment item
              && item.getFile() instanceof org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification embedded
              && file.filename.equals(embedded.getFileUnicode())) {
            assertArrayEquals(file.data(), AttachmentProfilePolicyTests.bytes(embedded));retained = true;
          }
        }
        assertTrue(retained);
      }
    }
  }

  @Test
  void ambiguousNativeImageNamesDoNotChooseAnArbitraryPicture() throws Exception {
    Path source = directory.resolve("duplicate.msg");
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(SOURCE)))) {
      var original = (DirectoryNode) storage.getRoot().getEntry("__attach_version1.0_#00000001");
      var duplicate = (DirectoryNode) storage.getRoot().createDirectory("__attach_version1.0_#00000002");
      EntryUtils.copyNodes(original, duplicate);
      byte[] data = CidUrlTests.image();
      duplicate.createOrUpdateDocument("__substg1.0_37010102", new ByteArrayInputStream(data));
      byte[] properties;
      try (var input = duplicate.createDocumentInputStream("__properties_version1.0")) { properties = input.readAllBytes(); }
      var table = ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
      for (int offset = 8; offset + 16 <= properties.length; offset += 16)
        if (table.getInt(offset) == 0x37010102) table.putInt(offset + 8, data.length);
      duplicate.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(properties));
      byte[] root;
      try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) { root = input.readAllBytes(); }
      ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 3).putInt(20, 3);
      storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(root));
      try (var output = new ByteArrayOutputStream()) { storage.writeFilesystem(output); Files.write(source, output.toByteArray()); }
    }
    var record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals(2, record.associatedFiles.stream().filter(f -> "tinkerbell.jpg".equals(f.filename)).count());
    assertNull(PdfMailWriter.findLocationAsset("tinkerbell.jpg", record));
    assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains("one image filename")));
  }
}
