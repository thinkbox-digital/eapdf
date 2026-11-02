package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SubjectDecodingTests {
  @TempDir Path directory;

  static byte[] message(String subject) {
    return ("From: author@example.org\r\nTo: reader@example.org\r\nSubject: " + subject
        + "\r\nDate: Fri, 23 Oct 2026 12:00:00 +0000\r\nMessage-ID: <subject-review@example.org>"
        + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nThe meeting starts at 10:00.")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test void adjacentEncodedWordsDecodeOnceInMetadataAndVisibleSubject() throws Exception {
    byte[] raw = message("Topic =?utf-8?Q?Re=3A?==?utf-8?Q?_=22Launch=22?=\r\n =?utf-8?Q?_=E2=80=94_update?=");
    var record = EmlReader.readRfc822Bytes(raw, "subject.eml", "subject.eml", "", List.of(), false);
    assertEquals("Topic Re: \"Launch\" — update", record.subject);
    var bytes = PdfMailWriter.buildSingleMessagePdf(directory.resolve("subject.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      String text = new PDFTextStripper().getText(pdf);
      assertTrue(text.contains(record.subject));
      assertFalse(text.contains("=?utf-8?"));
      assertTrue(text.contains("The meeting starts at 10:00."));
      assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(
          AttachmentProfilePolicyTests.files(pdf).values().iterator().next()));
    }
  }

  @Test void encodedLiteralIsNotDecodedAgainWhenWordsAreAdjacent() throws Exception {
    byte[] raw = message("=?UTF-8?Q?=3D=3FUTF-8=3FQ=3Fliteral=3F=3D?==?UTF-8?Q?_kept?=");
    var record = EmlReader.readRfc822Bytes(raw, "literal.eml", "literal.eml", "", List.of(), false);
    assertEquals("=?UTF-8?Q?literal?= kept", record.subject);
  }

  @Test void ordinaryAndProperlySeparatedSubjectsKeepExistingInterpretation() throws Exception {
    for (String subject : List.of("Ordinary project review", "Literal punctuation ?==? stays",
        "Topic =?UTF-8?Q?caf=C3=A9?=\r\n =?UTF-8?Q?_review?=")) {
      byte[] raw = message(subject);
      var parsed = new jakarta.mail.internet.MimeMessage(EmlReader.mimeSession(raw), new java.io.ByteArrayInputStream(raw));
      assertEquals(parsed.getSubject(), EmlReader.decodedSubject(parsed));
    }
  }
}
