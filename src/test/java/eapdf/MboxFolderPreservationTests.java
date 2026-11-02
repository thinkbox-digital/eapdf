package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MboxFolderPreservationTests {
  @TempDir Path directory;
  static final String LABELS = "Inbox, Projets/Résumé, 工作";

  @Test
  void mailboxFolderNamesSurviveUtf8AndEncodedHeadersWithExactEmailBytes() throws Exception {
    String encoded = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(LABELS.getBytes(StandardCharsets.UTF_8)) + "?=";
    List<byte[]> originals = new ArrayList<>();
    StringBuilder mailbox = new StringBuilder();
    for (String labels : List.of(LABELS, encoded, "Inbox, Project")) {
      String message = "From: author@example.org\r\nTo: recipient@example.org\r\nSubject: Project record\r\n"
          + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nX-Gmail-Labels: " + labels
          + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nProject body.\r\n";
      originals.add(message.getBytes(StandardCharsets.UTF_8));
      mailbox.append("From author@example.org Fri Oct 23 12:00:00 2026\r\n").append(message);
    }
    Path input = directory.resolve("Projects.mbox");
    byte[] nativeBytes = mailbox.toString().getBytes(StandardCharsets.UTF_8);
    Files.write(input, nativeBytes);
    var records = MboxReader.readMbox(input.toString(), "", List.of(), false);
    assertEquals(3, records.size());
    for (int index = 0; index < 3; index++) {
      assertEquals(index < 2 ? LABELS : "Inbox, Project", records.get(index).folderPath);
      assertArrayEquals(originals.get(index), records.get(index).sourceBytes);
    }
    Path output = directory.resolve("Projects.pdf");
    PdfMailWriter.writeEaPdfArchive(output, records, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, "m");
    try (var document = Loader.loadPDF(output.toFile())) {
      assertTrue(new PDFTextStripper().getText(document).contains(LABELS));
      var sources = AttachmentProfilePolicyTests.files(document).values().stream()
          .filter(file -> "Source".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship"))))
          .toList();
      assertEquals(3, sources.size());
      for (byte[] original : originals) assertTrue(sources.stream().anyMatch(file -> {
        try { return java.util.Arrays.equals(original, AttachmentProfilePolicyTests.bytes(file)); }
        catch (Exception error) { throw new IllegalStateException(error); }
      }));
    }
    assertArrayEquals(nativeBytes, Files.readAllBytes(input));
  }
}
