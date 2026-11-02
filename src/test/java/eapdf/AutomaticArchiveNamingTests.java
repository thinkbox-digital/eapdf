package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticArchiveNamingTests {
  @TempDir Path directory;

  List<String> inputs(String label) throws Exception {
    Path folder = directory.resolve(label);
    Files.createDirectories(folder);
    for (int index = 1; index <= 2; index++) {
      Files.writeString(folder.resolve("mail" + index + ".eml"), "From: author@example.org\r\nTo: recipient@example.org\r\n"
          + "Subject: " + label + " project message " + index + "\r\nDate: Fri, 23 Oct 2026 12:00:00 +0000\r\n"
          + "Message-ID: <" + label + "-" + index + "@example.org>\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n"
          + label + " project record " + index);
    }
    return List.of(folder.resolve("mail1.eml").toString(), folder.resolve("mail2.eml").toString());
  }

  void run(List<String> inputs, Path output, String explicit, String profile) throws Exception {
    Files.createDirectories(output);
    EmailToPDFA.runArchive(inputs, output.toString(), explicit, TestSupport.fontPath(), TestSupport.iccPath(),
        "", List.of(), false, directory.toString(), profile);
  }

  @Test
  void individualEmailsRetainTheirInputFilenameEvenForOneMailbox() throws Exception {
    String first = EmailToPDFA.uniqueFileName(directory.toString(), "auriza", "Meeting notes", 1, 9, false);
    assertEquals("auriza__Meeting notes_000001.pdf", first);
    Files.writeString(directory.resolve(first), "keep existing file");
    assertEquals("auriza__Meeting notes_000001_2.pdf",
        EmailToPDFA.uniqueFileName(directory.toString(), "auriza", "Meeting notes", 1, 9, false));
    assertEquals("keep existing file", Files.readString(directory.resolve(first)));
    assertEquals("auriza__Meeting notes_000009.pdf",
        EmailToPDFA.uniqueFileName(directory.toString(), "auriza", "Meeting notes", 1, 9, true));
  }

  @Test
  void automaticArchivesAndCollectionsKeepEarlierPdfsAndSidecars() throws Exception {
    List<String> first = inputs("First"), second = inputs("Second");
    for (String profile : List.of("m", "c")) {
      Path output = directory.resolve("out-" + profile);
      run(first, output, null, profile);
      Map<Path, byte[]> original = new java.util.LinkedHashMap<>();
      try (var paths = Files.list(output)) {
        for (Path path : paths.filter(Files::isRegularFile).toList()) original.put(path, Files.readAllBytes(path));
      }
      run(second, output, null, profile);
      for (var file : original.entrySet()) assertArrayEquals(file.getValue(), Files.readAllBytes(file.getKey()));
      Path firstPdf = output.resolve("mail1_archive_1" + profile + ".pdf");
      Path secondPdf = output.resolve("mail1_archive_1" + profile + "_2.pdf");
      assertTrue(Files.isRegularFile(firstPdf));
      assertTrue(Files.isRegularFile(secondPdf));
      if (profile.equals("m")) try (var firstDocument = Loader.loadPDF(firstPdf.toFile());
                                  var secondDocument = Loader.loadPDF(secondPdf.toFile())) {
        assertTrue(new PDFTextStripper().getText(firstDocument).contains("First project message 1"));
        assertTrue(new PDFTextStripper().getText(secondDocument).contains("Second project message 1"));
      }
    }
  }

  @Test
  void explicitlyRequestedArchivePathRetainsReplacementBehavior() throws Exception {
    Path output = directory.resolve("explicit");
    Path archive = output.resolve("selected.pdf");
    run(inputs("First"), output, archive.toString(), "m");
    run(inputs("Second"), output, archive.toString(), "m");
    try (var document = Loader.loadPDF(archive.toFile())) {
      String text = new PDFTextStripper().getText(document);
      assertTrue(text.contains("Second project message 1"));
      assertFalse(text.contains("First project message 1"));
    }
    assertFalse(Files.exists(output.resolve("selected_2.pdf")));
  }
}
