package eapdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AuditRepairTests {
  @TempDir Path directory;

  @Test void filenameBoundariesDoNotSplitSupplementaryCharacters() {
    for (String input : List.of("x".repeat(119) + "😀tail", "x".repeat(118) + "😀tail")) {
      for (String name : List.of(Common.safeFilename(input), Common.safeCollectionLabel(input, "Messages"))) {
        assertTrue(name.length() <= 120);
        assertEquals(name, new String(name.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
      }
    }
    assertEquals("Messages", Common.safeCollectionLabel("\0", "Messages"));
    assertFalse(Common.safeCollectionLabel("", "bad\0folder").contains("\0"));
  }

  @Test void encodedSubjectNulDoesNotReachOutputPathsOrAlterOriginalSource() throws Exception {
    byte[] source = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\n"
        + "Subject: =?UTF-8?Q?a=00b?=\r\n\r\nBody").getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(source, "sample.eml", "sample.eml", "", List.of(), false);
    assertTrue(record.subject.contains("\0"));
    String filename = EmailToPDFA.uniqueFileName(directory.toString(), "input", Common.safeFileStem(record.subject), 1, 1, false);
    assertDoesNotThrow(() -> Path.of(filename));
    assertDoesNotThrow(() -> Path.of(PdfCollectionSupport.collectionMemberFilename(record.subject, "s", 1)));
    assertFalse(Common.safeFileStem("a\u007fb").contains("\u007f"));
    assertArrayEquals(source, record.sourceBytes);
  }

  @Test void generatedPdfFilenamesKeepReadableUnicodeWithoutReplacementCharacters() throws Exception {
    byte[] source = ("Subject: =?UTF-8?Q?Caf=C3=A9=FF=FFnotes?=\r\n\r\nBody")
        .getBytes(StandardCharsets.US_ASCII);
    EmailRecord record = EmlReader.readRfc822Bytes(source, "sample.eml", "sample.eml", "", List.of(), false);

    assertEquals("Café\uFFFD\uFFFDnotes", record.subject);
    String inputStem = Common.safeFileStem("\uFFFD日本語\uFFFD");
    String subjectStem = Common.safeFileStem(record.subject);
    assertEquals("日本語__Café notes_000001.pdf",
        EmailToPDFA.uniqueFileName(directory.toString(), inputStem, subjectStem, 1, 1, false));
    assertFalse(PdfCollectionSupport.collectionMemberFilename(record.subject, "s", 1).contains("\uFFFD"));
    assertEquals("message", Common.safeFileStem("\uFFFD\uFFFD"));
    assertArrayEquals(source, record.sourceBytes);
  }

  @Test void malformedInlineImageDoesNotDiscardHtmlFormatting() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("Malformed inline image");
    record.htmlBody = "<p>Before <b>formatted</b></p><img alt='broken' src='data:image/png;base64,A'><p>After</p>";
    var built = PdfMailWriter.buildSingleMessagePdf(directory.resolve("message.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(built.pdfBytes)) {
      String text = new PDFTextStripper().getText(document);
      assertTrue(text.contains("Before")); assertTrue(text.contains("After"));
    }
    assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains("malformed inline data image")));
    assertTrue(record.conversionWarnings.stream().noneMatch(w -> w.contains("Reduced rendering")));
    assertFalse(MailRenderPolicy.isAllowedRenderResource("data:image/png,%Q0"));
    assertFalse(MailRenderPolicy.isAllowedRenderResource("data:image/png,%"));
  }

  @Test void inlineSizeLimitIsFatalAndCheckedBeforeMalformedBase64Decode() {
    String property = "eapdf.maxMessageBytes";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, "4");
      assertThrows(Common.UserFacingException.class,
          () -> MailRenderPolicy.decodeRenderImageUri("data:image/png;base64,AAAAAAAAAAAAA"));
      assertArrayEquals(new byte[4], MailRenderPolicy.decodeRenderImageUri("data:image/png;base64,AAAAAA=="));
      assertArrayEquals(new byte[4], MailRenderPolicy.decodeRenderImageUri("data:image/png,%00%00%00%00"));
    } finally { restoreProperty(property, previous); }
  }

  @Test void atomicReplacementPreservesPermissionsAndFailedWritePreservesOldBytes() throws Exception {
    Path output = directory.resolve("existing.pdf");
    Files.writeString(output, "original");
    boolean posix = Files.getFileStore(output).supportsFileAttributeView("posix");
    var permissions = PosixFilePermissions.fromString("r--r-----");
    if (posix) Files.setPosixFilePermissions(output, permissions);
    Common.writeAtomically(output, "replacement".getBytes(StandardCharsets.UTF_8));
    if (posix) assertEquals(permissions, Files.getPosixFilePermissions(output));
    IOException failure = new IOException("render failed");
    assertSame(failure, assertThrows(IOException.class, () -> Common.writeAtomically(output, temporary -> {
      Files.writeString(temporary, "incomplete"); throw failure;
    })));
    assertEquals("replacement", Files.readString(output));
    try (var files = Files.list(directory)) { assertEquals(List.of(output), files.toList()); }
  }

  @Test void publicationPreservesDestinationModeAndKeepsNewFilesPrivate() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(directory).supportsFileAttributeView("posix"));
    Path staged = directory.resolve("staged.pdf"), existing = directory.resolve("existing.pdf"), fresh = directory.resolve("fresh.pdf");
    Files.writeString(staged, "completed"); Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
    Files.writeString(existing, "previous");
    var mode = PosixFilePermissions.fromString("r--r-----"); Files.setPosixFilePermissions(existing, mode);
    String digest = Common.digestHex(staged, "SHA-256");
    var entries = List.of(new OutputTransaction.Entry(existing.toString(), staged.toString(), 9, digest, false),
        new OutputTransaction.Entry(fresh.toString(), staged.toString(), 9, digest, true));
    OutputTransaction.publish(entries, (source, target) -> Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
    assertEquals("completed", Files.readString(existing));
    assertEquals(mode, Files.getPosixFilePermissions(existing));
    assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(fresh));
  }

  @Test void cleanupFailureIsSuppressedBehindPrimaryFailure() throws Exception {
    Path[] temporaryPath = new Path[1];
    IOException primary = new IOException("primary render error");
    assertSame(primary, assertThrows(IOException.class, () -> Common.writeAtomically(directory.resolve("output.pdf"), temporary -> {
      temporaryPath[0] = temporary;
      Files.delete(temporary); Files.createDirectory(temporary); Files.writeString(temporary.resolve("child"), "retained");
      throw primary;
    })));
    assertEquals(1, primary.getSuppressed().length);
    Common.deleteTree(temporaryPath[0]);
  }

  @Test void embeddedChildSizeLimitHasAnActionableError() {
    assertEquals(Integer.MAX_VALUE, Common.checkedPayloadSize(Integer.MAX_VALUE));
    var error = assertThrows(Common.UserFacingException.class, () -> Common.checkedPayloadSize((long) Integer.MAX_VALUE + 1));
    assertTrue(error.getMessage().contains("supported size"));
  }

  @Test void mailboxMetadataDoesNotExposeLocalFilesystemPaths() throws Exception {
    for (String type : List.of("pst", "mbox")) {
      EmailRecord record = AttachmentProfilePolicyTests.record("Mailbox source");
      record.sourceType = type; record.sourcePath = "/private/work/source." + type;
      for (String profile : List.of("m", "mi", "c", "ci")) {
        String xmp = PdfMailWriter.buildXmpMetadata(List.of(record), profile);
        assertFalse(xmp.contains(record.sourcePath)); assertFalse(xmp.contains("/private/work"));
        assertTrue(xmp.contains("preprocessing:"));
      }
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) System.clearProperty(key); else System.setProperty(key, value);
  }
}
