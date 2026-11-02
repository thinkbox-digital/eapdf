package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarationNamespaceTests {
  @TempDir Path tempDir;

  byte[] create() throws Exception {
    byte[] email = ("From: sender@example.org\r\nDate: Thu, 30 Sep 2010 13:24:56 GMT\r\n"
        + "Subject: Declaration namespace\r\nContent-Type: text/plain\r\n\r\nBody")
        .getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(email, "source.eml", "source.eml", "", List.of(), false);
    return PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("declaration.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
  }

  @Test void writerUsesPublishedNamespaceAndDistinctRegistryIdentifier() throws Exception {
    try (PDDocument document = Loader.loadPDF(create())) {
      String xmp = TestSupport.metadataXml(document);
      Assertions.assertTrue(xmp.contains("xmlns:pdfd=\"http://pdfa.org/declarations/\""));
      Assertions.assertTrue(xmp.contains("https://pdfa.org/declarations#iso32005"));
      PdfCreationValidator.validate(document);
    }
  }

  @Test void creationGateRejectsHttpsNamespaceSubstitution() throws Exception {
    try (PDDocument document = Loader.loadPDF(create())) {
      var metadata = document.getDocumentCatalog().getMetadata();
      String replaced = TestSupport.metadataXml(document)
          .replace("http://pdfa.org/declarations/", "https://pdfa.org/declarations/");
      metadata.importXMPMetadata(replaced.getBytes(StandardCharsets.UTF_8));
      Assertions.assertThrows(Exception.class, () -> PdfCreationValidator.validate(document));
    }
  }
}
