package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MsgOfflineAlternativeTests {
  static final String TEXT = "Invoice total is 145.00 USD. Payment is due Friday.";
  @TempDir Path directory;

  static void fixture(Path path, String html) throws Exception {
    MsgBodySelectionTests.writeMsg(path, 3, false);
    try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(path)))) {
      var root = storage.getRoot();
      byte[] original;
      try (var input = root.createDocumentInputStream("__properties_version1.0")) { original = input.readAllBytes(); }
      var properties = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
      for (var entry : Map.of(0x1000001f, MsgBodySelectionTests.unicode(TEXT),
          0x10130102, html.getBytes(StandardCharsets.UTF_8)).entrySet()) {
        root.createOrUpdateDocument(String.format("__substg1.0_%08X", entry.getKey()), new ByteArrayInputStream(entry.getValue()));
        for (int offset = 32; offset + 16 <= original.length; offset += 16) {
          if (properties.getInt(offset) == entry.getKey()) properties.putInt(offset + 8, entry.getValue().length);
        }
      }
      root.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(original));
      try (var output = new ByteArrayOutputStream()) { storage.writeFilesystem(output); Files.write(path, output.toByteArray()); }
    }
  }

  @Test
  void unavailableHtmlImageUsesSuppliedPlainBodyAndRetainsOriginalMsg() throws Exception {
    Path source = directory.resolve("invoice.msg");
    fixture(source, "<html><body><div>&#xFEFF;&#x200B;&#x2060;&#160;&#8239;</div>"
        + "<img src=\"https://images.example.invalid/invoice.png\" alt=\"Invoice\"></body></html>");
    var record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    assertEquals("text/plain", record.bodyMime);
    assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("supplied plain-text alternative")));
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("invoice.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      assertTrue(new PDFTextStripper().getText(document).contains(TEXT));
      var files = AttachmentProfilePolicyTests.files(document);
      assertEquals(1, files.size());
      assertArrayEquals(Files.readAllBytes(source), AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
  }

  @Test
  void readableHtmlAndEmbeddedImagesKeepTheirNativeBody() throws Exception {
    String image = "<img alt=\"Invoice\" src=\"data:image/png;base64," + Base64.getEncoder().encodeToString(CidUrlTests.image()) + "\">";
    for (String html : List.of("<p>Native invoice details</p>", image)) {
      Path source = directory.resolve("native.msg");
      fixture(source, "<html><body><div>&#xFEFF;&#x200B;&#x2060;&#160;&#8239;</div>" + html + "</body></html>");
      var record = MsgReader.readMsg(source.toString(), "", List.of(), false);
      assertEquals("text/html", record.bodyMime);
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("native.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var document = Loader.loadPDF(output.pdfBytes)) {
        assertFalse(new PDFTextStripper().getText(document).contains(TEXT));
        if (html.startsWith("<p>")) assertTrue(new PDFTextStripper().getText(document).contains("Native invoice details"));
        else {
          boolean found = false;
          for (var page : document.getPages()) for (var name : page.getResources().getXObjectNames()) {
            if (page.getResources().getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject object
                && object.getWidth() == 2 && object.getHeight() == 2) found = true;
          }
          assertTrue(found);
        }
      }
      assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
    }
  }
  @Test
  void matchingNativeLocationKeepsItsImageBodyWhileUnrelatedPicturesStillUseText() throws Exception {
    String url = "https://stored.example.invalid/tinkerbell.jpg";
    for (boolean matching : List.of(true, false)) {
      Path source = directory.resolve("located-" + matching + ".msg");
      fixture(source, "<html><body><img src=\"" + url + "\" alt=\"Stored picture\"></body></html>");
      try (var storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(source)));
          var original = new POIFSFileSystem(MsgFilenameImageTests.SOURCE.toFile(), true)) {
        org.apache.poi.poifs.filesystem.EntryUtils.copyNodeRecursively(
            original.getRoot().getEntry("__attach_version1.0_#00000001"), storage.getRoot());
        var attachment = (org.apache.poi.poifs.filesystem.DirectoryNode)
            storage.getRoot().getEntry("__attach_version1.0_#00000001");
        byte[] location = MsgBodySelectionTests.unicode(matching ? url : "https://stored.example.invalid/unrelated.jpg");
        attachment.createDocument("__substg1.0_3713001F", new ByteArrayInputStream(location));
        byte[] originalProperties;
        try (var input = attachment.createDocumentInputStream("__properties_version1.0")) { originalProperties = input.readAllBytes(); }
        var table = ByteBuffer.allocate(originalProperties.length + 16).order(ByteOrder.LITTLE_ENDIAN);
        table.put(originalProperties).putInt(0x3713001f).putInt(6).putInt(location.length).putInt(0);
        attachment.createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(table.array()));
        byte[] root;
        try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) { root = input.readAllBytes(); }
        ByteBuffer.wrap(root).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 2).putInt(20, 1);
        storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(root));
        try (var output = new ByteArrayOutputStream()) { storage.writeFilesystem(output); Files.write(source, output.toByteArray()); }
      }
      var record = MsgReader.readMsg(source.toString(), "", List.of(), false);
      assertEquals(matching ? "text/html" : "text/plain", record.bodyMime);
      assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
      var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("located.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
      try (var document = Loader.loadPDF(output.pdfBytes)) {
        boolean picture = false;
        for (var page : document.getPages()) for (var name : page.getResources().getXObjectNames())
          if (page.getResources().getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject image
              && image.getWidth() == 500 && image.getHeight() == 953) picture = true;
        assertEquals(matching, picture);
        assertEquals(!matching, new PDFTextStripper().getText(document).contains(TEXT));
      }
    }
  }

}
