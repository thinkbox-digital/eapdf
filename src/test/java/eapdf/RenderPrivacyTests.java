package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RenderPrivacyTests {
  @TempDir Path directory;

  @Test void debugMailFilesStayInAnOwnerOnlyDirectory() throws Exception {
    byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\n"
        + "From: sender@example.test\r\nTo: receiver@example.test\r\nSubject: Test subject\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n\r\nDiagnostic fixture body.\r\n")
        .getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "fixture.eml", "fixture.eml", "", List.of(), false);
    Path debug = directory.resolve("debug");
    PdfMailWriter.buildSingleMessagePdf(directory.resolve("message.pdf"), record, TestSupport.fontPath(),
        TestSupport.iccPath(), true, debug, 1, "s");
    try (var files = Files.list(debug)) {
      Path protectedFiles = files.findFirst().orElseThrow();
      assertTrue(Files.isDirectory(protectedFiles));
      Path body = protectedFiles.resolve("body.txt");
      if (Files.getFileStore(protectedFiles).supportsFileAttributeView("posix")) {
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(protectedFiles));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(body));
      }
      assertTrue(Files.readString(body).contains("Diagnostic fixture body."));
      assertTrue(Files.isRegularFile(protectedFiles.resolve("rendered.html")));
      assertFalse(Files.exists(protectedFiles.resolve("working.pdf")));
      assertFalse(Files.exists(protectedFiles.resolve("finalized.pdf")));
    }
  }
}
