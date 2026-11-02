package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OfflineImageAlternativeTests {
  static final String TEXT = "Your delivery is scheduled for Friday at 14:00. Reference 4921.";
  @TempDir Path directory;

  static byte[] message(boolean matching) throws Exception {
    String headers = "From: sender@example.org\r\nTo: recipient@example.org\r\nSubject: Delivery details\r\n"
        + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nMessage-ID: <offline-image@example.org>\r\n"
        + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=mixed\r\n\r\n";
    String alternatives = "--mixed\r\nContent-Type: multipart/alternative; boundary=choices\r\n\r\n"
        + EmailContentRegressionTests.section("choices", "text/plain; charset=UTF-8", "", TEXT)
        + EmailContentRegressionTests.section("choices", "text/html; charset=UTF-8", "",
            "<html><body><img src=\"https://images.example.invalid/delivery.png\" width=\"100\" alt=\"Delivery details\"></body></html>")
        + "--choices--\r\n";
    String image = EmailContentRegressionTests.section("mixed", "image/png",
        "Content-Disposition: attachment; filename=photo.png\r\n"
            + (matching ? "Content-Location: https://images.example.invalid/delivery.png\r\n" : "")
            + "Content-Transfer-Encoding: base64\r\n",
        Base64.getMimeEncoder().encodeToString(CidUrlTests.image()));
    return (headers + alternatives + image + "--mixed--\r\n").getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void unrelatedPhotoDoesNotHideTheSuppliedReadableMessage() throws Exception {
    byte[] raw = message(false);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "delivery.eml", "delivery.eml", "", List.of(), false);
    assertEquals("text/plain; charset=UTF-8", record.bodyMime);
    assertTrue(record.htmlBody.contains(TEXT));
    assertEquals(1, record.attachmentCount);
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("delivery.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      assertTrue(new PDFTextStripper().getText(document).contains(TEXT));
      var files = AttachmentProfilePolicyTests.files(document);
      assertEquals(1, files.size());
      assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
      boolean photo = false;
      for (var page : document.getPages()) {
        for (var annotation : page.getAnnotations()) {
          if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment attachment
              && attachment.getFile() instanceof org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification file
              && "photo.png".equals(file.getFileUnicode())) {
            assertArrayEquals(CidUrlTests.image(), AttachmentProfilePolicyTests.bytes(file));
            photo = true;
          }
        }
      }
      assertTrue(photo);
    }
  }

  @Test
  void matchingEmbeddedImageKeepsTheAuthoredHtml() throws Exception {
    byte[] raw = message(true);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "delivery.eml", "delivery.eml", "", List.of(), false);
    assertEquals("text/html; charset=UTF-8", record.bodyMime);
    assertFalse(record.htmlBody.contains(TEXT));
    assertArrayEquals(raw, record.sourceBytes);
    assertEquals(1, record.attachmentCount);
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("embedded.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      boolean image = false;
      for (var page : document.getPages()) {
        for (var name : page.getResources().getXObjectNames()) {
          if (page.getResources().getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject object
              && object.getWidth() == 2 && object.getHeight() == 2) image = true;
        }
      }
      assertTrue(image);
    }
  }
}
