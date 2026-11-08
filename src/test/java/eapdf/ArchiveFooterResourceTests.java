package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveFooterResourceTests {
  @TempDir Path directory;

  @Test
  void archiveHasNoBrandingAndPreservesEmailContent() throws Exception {
    byte[] shortRaw = WideEmailTableTests.message("600", 1);
    byte[] longRaw = WideEmailTableTests.message("900", 45);
    var shortMail = EmlReader.readRfc822Bytes(shortRaw, "short.eml", "short.eml", "", List.of(), false);
    var longMail = EmlReader.readRfc822Bytes(longRaw, "long.eml", "long.eml", "", List.of(), false);
    Path output = directory.resolve("archive.pdf");
    PdfMailWriter.writeEaPdfArchive(output, List.of(shortMail, longMail), TestSupport.fontPath(),
        TestSupport.iccPath(), false, directory, "m");
    try (var document = Loader.loadPDF(output.toFile())) {
      assertTrue(document.getNumberOfPages() > 2, "Long message spans several pages");
      var stripper = new PDFTextStripper();
      String text = stripper.getText(document);
      String label = "Email converted by ThinkBox";
      assertEquals(0, text.split(label, -1).length - 1);
      stripper.setEndPage(1);
      assertFalse(stripper.getText(document).contains(label));
      for (var page : document.getPages()) {
        for (var name : page.getResources().getXObjectNames()) {
          assertFalse(page.getResources().getXObject(name) instanceof PDImageXObject,
              "Text-only email must not embed raster artwork");
        }
      }
      assertTrue(text.contains("R45C6"));
      var sources = AttachmentProfilePolicyTests.files(document).values().stream()
          .map(file -> { try { return new String(AttachmentProfilePolicyTests.bytes(file), StandardCharsets.UTF_8); }
                         catch (Exception error) { throw new RuntimeException(error); } }).toList();
      assertTrue(sources.contains(new String(shortRaw, StandardCharsets.UTF_8)));
      assertTrue(sources.contains(new String(longRaw, StandardCharsets.UTF_8)));
    }
  }
}
