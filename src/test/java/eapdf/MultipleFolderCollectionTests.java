package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MultipleFolderCollectionTests {
  @TempDir Path directory;

  static List<EmailRecord> records() throws Exception {
    List<EmailRecord> records = new ArrayList<>();
    for (String id : List.of("A", "B", "C")) {
      String boundary = "mixed-" + id;
      String raw = "From: author@example.org\r\nTo: recipient@example.org\r\nSubject: Project update\r\n"
          + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nMessage-ID: <folders-" + id + "@example.org>\r\n"
          + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=" + boundary + "\r\n\r\n"
          + EmailContentRegressionTests.section(boundary, "text/plain; charset=UTF-8", "", "Body " + id)
          + EmailContentRegressionTests.section(boundary, "text/plain", "Content-Disposition: attachment; filename=native.txt\r\n"
              + "Content-Transfer-Encoding: base64\r\n", Base64.getEncoder().encodeToString(("Payload " + id).getBytes(StandardCharsets.UTF_8)))
          + "--" + boundary + "--\r\n";
      var record = EmlReader.readRfc822Bytes(raw.getBytes(StandardCharsets.UTF_8), id + ".eml", id + ".eml", "", List.of(), false);
      record.folderMemberships.addAll(id.equals("A") ? List.of("Inbox", "Projects")
          : id.equals("B") ? List.of("Inbox", "Archive") : List.of("Projects"));
      record.folderPath = String.join(", ", record.folderMemberships);
      records.add(record);
    }
    return records;
  }

  @Test
  void indexedNamesPreserveExistingCollisionAndSuffixRules() {
    var reference = new LinkedHashMap<String, Boolean>();
    var index = new java.util.HashSet<String>();
    for (String name : List.of("<1>Mail.pdf", "<1>MAIL.PDF", "<2>Mail.pdf", "<1>Mail_2.pdf",
        "Résumé.pdf", "RÉSUMÉ.PDF", "straße.txt", "STRASSE.TXT", "../sample.pdf", "sample.pdf")) {
      String expected = PdfMailWriter.uniqueAttachmentKey(reference, name);
      assertEquals(expected, PdfMailWriter.reserveAttachmentKey(index, name));
      reference.put(expected, true);
    }
  }

  @Test
  void overlappingFoldersShareOneChildPerEmailForRetainedAndIsolatedCollections() throws Exception {
    {
      for (String profile : List.of("c", "ci")) {
        var records = records();
        Path output = directory.resolve("folders_1" + profile + ".pdf");
        PdfMailWriter.writeEaPdfCollection(output, records, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
        try (var document = Loader.loadPDF(output.toFile())) {
          var files = AttachmentProfilePolicyTests.files(document);
          var placements = new IdentityHashMap<COSDictionary, Integer>();
          for (var entry : files.entrySet()) if (entry.getKey().startsWith("<")) placements.merge(entry.getValue().getCOSObject(), 1, Integer::sum);
          assertEquals(3, placements.size());
          assertEquals(5, placements.values().stream().mapToInt(Integer::intValue).sum());
          assertEquals(List.of("Archive", "Inbox", "Projects"), TestSupport.folderNames(document));
          var identifiers = new java.util.HashSet<String>();
          for (var file : files.values()) {
            if (!placements.containsKey(file.getCOSObject())) continue;
            placements.remove(file.getCOSObject());
            try (var child = Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))) {
              var xml = TestSupport.metadataXml(child);
              for (var record : records) if (xml.contains(record.mailGuid)) assertTrue(identifiers.add(record.mailGuid));
            }
          }
          assertEquals(3, identifiers.size());
        }
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);

      }
    }
  }

  @Test
  void duplicatePlacementAndIndependentDuplicateChildStillFailWithoutReplacingOutput() throws Exception {
    var records = records();
    var expected = PreservationInventory.capture(records, "c");
    Path original = directory.resolve("valid.pdf");
    PdfMailWriter.writeEaPdfCollection(original, records, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, "c");
    Path destination = directory.resolve("protected.pdf");
    byte[] previous = "Existing complete output".getBytes(StandardCharsets.UTF_8);
    for (boolean sameFolder : List.of(true, false)) {
      Files.write(destination, previous);
      try (var document = Loader.loadPDF(original.toFile())) {
        var tree = document.getDocumentCatalog().getNames().getEmbeddedFiles();
        var names = new LinkedHashMap<>(tree.getNames());
        var entry = names.entrySet().stream().filter(item -> item.getKey().startsWith("<1>")).findFirst().orElseThrow();
        String filename = entry.getKey().substring(3);
        if (sameFolder) names.put("<01>" + filename, entry.getValue());
        else {
          var copy = new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification(new COSDictionary(entry.getValue().getCOSObject()));
          names.put("<3>" + filename, copy);
          ((org.apache.pdfbox.cos.COSArray) document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF"))).add(copy.getCOSObject());
        }
        tree.setNames(names);
        Exception failure = assertThrows(Exception.class,
            () -> Common.writePdfAtomically(destination, temporary -> document.save(temporary.toFile()), expected));
        assertTrue(failure.getMessage().contains(sameFolder ? "same folder" : "exactly once"), failure.getMessage());
      }
      assertArrayEquals(previous, Files.readAllBytes(destination));
    }
  }
}
