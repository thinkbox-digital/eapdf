package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.mail.Session;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AttachedMessageEncodingTests {
  @Test void realPstAttachedMessageIsReadableWithoutNonstandardBase64Decoding() throws Exception {
    var records = PstReader.readPst(TestSupport.fixturePath("attached-message.pst").toString(), "", List.of(), false);
    var parent = records.stream().filter(r -> "FW: First email".equals(r.subject)).findFirst().orElseThrow();
    var message = parse(Common.synthesizeEml(parent));
    var parts = (Multipart) message.getContent();
    var attachment = parts.getBodyPart(parts.getCount() - 1);
    assertArrayEquals(new String[]{"binary"}, attachment.getHeader("Content-Transfer-Encoding"));
    var child = (MimeMessage) attachment.getContent();
    assertEquals("First email", child.getSubject());
    var childParts = (Multipart) child.getContent();
    var document = childParts.getBodyPart(childParts.getCount() - 1);
    assertEquals("attachment.docx", document.getFileName());
    assertEquals("0c87a742c970907d3b08c73e7834768abadd00fe4f4995a7dd98a206d4c494c0",
        Common.sha256Hex(document.getInputStream().readAllBytes()));
  }

  @Test void encapsulatedNonUtf8BytesArePreservedExactly() throws Exception {
    byte[] child = "Subject: Child\r\nContent-Type: text/plain; charset=iso-8859-1\r\nContent-Transfer-Encoding: 8bit\r\n\r\nCafé\r\n".getBytes(StandardCharsets.ISO_8859_1);
    var record = new EmailRecord(); record.subject = "Parent";
    var file = new AssociatedFile(); file.filename = "child.eml"; file.mime = "message/rfc822";
    file.bytes = child; record.associatedFiles.add(file);
    var parts = (Multipart) parse(Common.synthesizeEml(record)).getContent();
    assertArrayEquals(child, parts.getBodyPart(parts.getCount() - 1).getInputStream().readAllBytes());
  }

  @Test void arbitraryAttachedMessageBytesSurviveEveryMultipartArrangement() throws Exception {
    for (boolean inline : List.of(false, true)) for (boolean html : List.of(false, true)) {
      for (byte[] payload : List.of(new byte[]{0, (byte) 255, '\r', '\n', (byte) 128},
          "Malformed header\n\nUnterminated body".getBytes(StandardCharsets.US_ASCII),
          "Subject: Child\r\n\r\nBody\r\n".getBytes(StandardCharsets.US_ASCII))) {
        var record = new EmailRecord(); record.textBody = "Parent";
        if (html) record.htmlBody = "<p>Parent</p>";
        var file = new AssociatedFile(); file.filename = "child.eml"; file.mime = "message/rfc822";
        file.inline = inline; file.bytes = payload; record.associatedFiles.add(file);
        byte[] encoded = Common.synthesizeEml(record);
        assertArrayEquals(encoded, Common.synthesizeEml(record));
        var message = parse(encoded);
        assertEquals("binary", message.getEncoding());
        var parts = (Multipart) message.getContent();
        var body = parts.getBodyPart(0);
        if (html) body = ((Multipart) body.getContent()).getBodyPart(0);
        assertEquals(record.textBody, body.getContent(), "Multipart framing must not add a body line");
        var attachment = parts.getBodyPart(parts.getCount() - 1);
        assertArrayEquals(new String[]{"binary"}, attachment.getHeader("Content-Transfer-Encoding"));
        assertArrayEquals(payload, attachment.getInputStream().readAllBytes());
      }
    }
  }

  @Test void reconstructionDoesNotInventMissingDatesOrIdentifiers() throws Exception {
    var record = new EmailRecord(); record.textBody = "Body";
    byte[] encoded = Common.synthesizeEml(record);
    assertArrayEquals(encoded, Common.synthesizeEml(record));
    var message = parse(encoded);
    assertNull(message.getHeader("Date"));
    assertNull(message.getHeader("Message-ID"));
    record.messageId = "<preserved@example.org>";
    assertEquals(record.messageId, parse(Common.synthesizeEml(record)).getMessageID());
  }

  @Test void compositeAttachmentsKeepTheirDeclaredTypeAndOpaquePayload() throws Exception {
    for (String type : List.of("message/delivery-status", "multipart/mixed; boundary=unavailable")) {
      var record = new EmailRecord(); record.textBody = "Parent";
      var file = new AssociatedFile(); file.filename = "original.bin"; file.mime = type;
      file.bytes = new byte[]{0, (byte) 255, '\r', '\n', 'x'};
      record.associatedFiles.add(file);
      var parts = (Multipart) parse(Common.synthesizeEml(record)).getContent();
      var attachment = parts.getBodyPart(1);
      assertTrue(attachment.isMimeType(type.split(";", 2)[0]));
      assertArrayEquals(new String[]{"base64"}, attachment.getHeader("Content-Transfer-Encoding"));
      // Mail's default reader ignores transfer encodings on composite types.
      try (var decoded = jakarta.mail.internet.MimeUtility.decode(
          ((jakarta.mail.internet.MimeBodyPart) attachment).getRawInputStream(), "base64")) {
        assertArrayEquals(file.bytes, decoded.readAllBytes());
      }
    }
  }

  private static MimeMessage parse(byte[] data) throws Exception {
    return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data));
  }
}
