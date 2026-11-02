package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveFooterResourceTests {
  @TempDir Path directory;

  @Test
  void archiveSharesFooterImageOnlyOnEachMessagesLastPage() throws Exception {
    byte[] shortRaw = WideEmailTableTests.message("600", 1);
    byte[] longRaw = WideEmailTableTests.message("900", 45);
    var shortMail = EmlReader.readRfc822Bytes(shortRaw, "short.eml", "short.eml", "", List.of(), false);
    var longMail = EmlReader.readRfc822Bytes(longRaw, "long.eml", "long.eml", "", List.of(), false);
    Path output = directory.resolve("archive.pdf");
    PdfMailWriter.writeEaPdfArchive(output, List.of(shortMail, longMail), TestSupport.fontPath(),
        TestSupport.iccPath(), false, directory, "m");
    try (var document = Loader.loadPDF(output.toFile())) {
      COSDictionary sharedImage = null;
      int footerPages = 0;
      int firstFooter = -1;
      for (int index = 0; index < document.getNumberOfPages(); index++) {
        var page = document.getPage(index);
        for (var name : page.getResources().getXObjectNames()) {
          if (page.getResources().getXObject(name) instanceof PDImageXObject image) {
            if (sharedImage == null) sharedImage = image.getCOSObject();
            else assertSame(sharedImage, image.getCOSObject(), "Footer must share one image object");
            footerPages++;
            if (firstFooter < 0) firstFooter = index;
          }
        }
      }
      assertEquals(2, footerPages);
      assertTrue(firstFooter < document.getNumberOfPages() - 2, "Long message spans several pages");
      assertTrue(document.getPage(document.getNumberOfPages() - 1).getResources().getXObjectNames().iterator().hasNext());
      String text = new PDFTextStripper().getText(document);
      assertTrue(text.contains("R45C6"));
      var sources = AttachmentProfilePolicyTests.files(document).values().stream()
          .map(file -> { try { return new String(AttachmentProfilePolicyTests.bytes(file), StandardCharsets.UTF_8); }
                         catch (Exception error) { throw new RuntimeException(error); } }).toList();
      assertTrue(sources.contains(new String(shortRaw, StandardCharsets.UTF_8)));
      assertTrue(sources.contains(new String(longRaw, StandardCharsets.UTF_8)));
    }
  }
}
