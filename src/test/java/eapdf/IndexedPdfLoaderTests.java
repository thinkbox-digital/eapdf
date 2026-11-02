package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IndexedPdfLoaderTests {
  @TempDir Path directory;

  @Test void realArchiveAndSingleMessageMatchPdfboxTextMetadataAndEmbeddedBytes() throws Exception {
    for (String source : List.of("examples/pst/pst_quality-attached-message_1m.pdf", "examples/eml/eml_Agenda_and_timeline_slides_1s.pdf")) {
      Path path = Path.of(source);
      MetadataScanner.validate(path, false);
      byte[] before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
      try (var standard = Loader.loadPDF(path.toFile()); var indexed = IndexedPdfLoader.load(path)) {
        assertEquals(standard.getNumberOfPages(), indexed.getNumberOfPages());
        assertEquals(standard.getDocument().getXrefTable(), indexed.getDocument().getXrefTable());
        assertEquals(new PDFTextStripper().getText(standard), new PDFTextStripper().getText(indexed));
        assertArrayEquals(standard.getDocumentCatalog().getMetadata().toByteArray(), indexed.getDocumentCatalog().getMetadata().toByteArray());
        assertEquals(embeddedHashes(standard), embeddedHashes(indexed));
        PdfCreationValidator.validateXmpEncoding(indexed);
      }
      assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
  }

  private static Map<String,String> embeddedHashes(PDDocument document) throws Exception {
    Map<String,String> hashes = new TreeMap<>();
    var names = document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
    for (var entry : names.entrySet()) {
      var file = entry.getValue(); var stream = file.getEmbeddedFileUnicode();
      if (stream == null) stream = file.getEmbeddedFile();
      hashes.put(entry.getKey(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream.toByteArray())));
    }
    return hashes;
  }

  @Test void metadataTraversalStillRejectsUnreachableAndUntypedMetadata() throws Exception {
    Path orphan = directory.resolve("orphan-metadata.pdf");
    byte[] payload = "<xmp>café</xmp>".getBytes(StandardCharsets.UTF_16);
    var bytes = new java.io.ByteArrayOutputStream();
    bytes.write("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
    var offsets = new java.util.ArrayList<Integer>();
    for (String object : List.of("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>")) {
      offsets.add(bytes.size());
      bytes.write(((offsets.size()) + " 0 obj\n" + object + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
    }
    offsets.add(bytes.size());
    bytes.write(("4 0 obj\n<< /Type /Metadata /Subtype /XML /Length " + payload.length + " >>\nstream\n").getBytes(StandardCharsets.US_ASCII));
    bytes.write(payload); bytes.write("\nendstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));
    int xref = bytes.size();
    bytes.write("xref\n0 5\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
    for (int offset : offsets) bytes.write(String.format(Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
    bytes.write(("trailer\n<< /Root 1 0 R /Size 5 >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
    Files.write(orphan, bytes.toByteArray());
    assertThrows(java.io.IOException.class, () -> MetadataScanner.validate(orphan, false));
    try (var document = IndexedPdfLoader.load(orphan)) {
      assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateXmpEncoding(document));
    }
    Path saved = directory.resolve("invalid-metadata.pdf");
    try (var document = new PDDocument()) {
      var page = new org.apache.pdfbox.pdmodel.PDPage(); document.addPage(page);
      var metadata = new PDMetadata(document);
      metadata.importXMPMetadata("<xmp>café</xmp>".getBytes(StandardCharsets.UTF_16));
      metadata.getCOSObject().removeItem(COSName.TYPE); page.setMetadata(metadata);
      document.save(saved.toFile());
    }
    assertThrows(java.io.IOException.class, () -> MetadataScanner.validate(saved, false));
    try (var document = IndexedPdfLoader.load(saved)) {
      assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validateXmpEncoding(document));
    }
  }
}
