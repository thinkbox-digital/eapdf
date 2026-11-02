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

  private static MimeMessage parse(byte[] data) throws Exception {
    return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(data));
  }
}
