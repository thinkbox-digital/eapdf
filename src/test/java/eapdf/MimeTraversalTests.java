package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MimeTraversalTests {
  static byte[] nested(int depth) {
    StringBuilder value=new StringBuilder("From: sender@example.org\r\nSubject: Deep MIME\r\nMIME-Version: 1.0\r\n");
    for(int i=0;i<depth;i++)value.append("Content-Type: multipart/mixed; boundary=b").append(i).append("\r\n\r\n--b").append(i).append("\r\n");
    value.append("Content-Type: text/plain; charset=UTF-8\r\n\r\nDeep body\r\n");
    value.append("--b").append(depth-1).append("\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=deep.bin\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n");
    for(int i=depth-1;i>=0;i--)value.append("--b").append(i).append("--\r\n");
    return value.toString().getBytes(StandardCharsets.US_ASCII);
  }

  @Test void deeplyNestedMessagePreservesBodyAndAttachmentWithoutRecursiveWalks()throws Exception {
    byte[] raw=nested(3000);
    var record=EmlReader.readRfc822Bytes(raw,"deep.eml","deep.eml","",List.of(),false);
    assertEquals("Deep body",record.textBody.strip());assertSame(raw,record.sourceBytes);
    assertEquals(1,record.attachmentCount);
    var attachment=record.associatedFiles.stream().filter(file->file.emailAttachment).findFirst().orElseThrow();
    assertArrayEquals(new byte[]{1,2,3},attachment.bytes);assertEquals(Common.sha256Hex(attachment.bytes),attachment.checksumSha256);
    try(var input=new jakarta.mail.util.SharedByteArrayInputStream(raw)){
      var message=new MimeMessage(EmlReader.mimeSession(raw),input);
      PrintStream previous=System.out;
      try(var discarded=new PrintStream(OutputStream.nullOutputStream())){System.setOut(discarded);EmlReader.dumpMimeStructure(message,record,"");}
      finally{System.setOut(previous);}
    }
  }

  @Test
  void malformedAttachmentParametersKeepAttachmentSeparateAndPreserveOriginal() throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        Subject: Separate attachment
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary=parts

        --parts
        Content-Type: text/plain

        Visible message body
        --parts
        Content-Type: text/html; name="note.html" extra="value"
        Content-Disposition: attachment; filename="note.html" extra="value"
        Content-Transfer-Encoding: quoted-printable

        <p>Attached=20note</p>
        --parts--
        """.getBytes(StandardCharsets.US_ASCII);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "attachment.eml", "attachment.eml", "", List.of(), false);

    assertEquals("Visible message body", record.textBody.strip());
    assertEquals("text/plain", record.bodyMime);
    assertFalse(record.htmlBody.contains("Attached note"));
    assertEquals(1, record.attachmentCount);
    AssociatedFile attachment = record.associatedFiles.stream()
        .filter(file -> file.emailAttachment).findFirst().orElseThrow();
    assertEquals("part_001.html", attachment.filename);
    assertEquals("text/html", Common.sourceContentType(attachment));
    assertArrayEquals("<p>Attached note</p>".getBytes(StandardCharsets.US_ASCII), attachment.data());
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertArrayEquals(raw, record.sourceBytes);
    AssociatedFile source = record.associatedFiles.stream()
        .filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow();
    assertArrayEquals(raw, source.data());
  }

  @Test
  void malformedTextBodyParametersKeepReadableUtf8AndPreserveOriginal() throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        Subject: Body with a malformed parameter
        MIME-Version: 1.0
        Content-Type: text/html; name="note.html" extra="value"

        <p>Café</p>
        """.getBytes(StandardCharsets.UTF_8);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "body.eml", "body.eml", "", List.of(), false);

    assertEquals("Café", record.textBody.strip());
    assertEquals("text/html", record.bodyMime);
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertArrayEquals(raw, record.sourceBytes);
  }

  @Test
  void staleMultipartBoundaryRecoversCompleteAlternativesAndPreservesOriginal() throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        Subject: Available alternatives
        MIME-Version: 1.0
        Content-Type: multipart/alternative; boundary=stale-boundary

        --actual-boundary
        Content-Type: text/plain

        Readable plain alternative
        --actual-boundary
        Content-Type: text/html

        <p>Readable HTML alternative</p>
        --actual-boundary--
        """.getBytes(StandardCharsets.US_ASCII);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "stale.eml", "stale.eml", "", List.of(), false);

    assertEquals("Readable plain alternative", record.textBody.strip());
    assertEquals("text/html", record.bodyMime);
    assertTrue(record.htmlBody.contains("Readable HTML alternative"));
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertOriginalSource(raw, record);
  }

  @Test
  void missingRootMultipartBoundaryReportsUnavailableBodyAndPreservesOriginal() throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        Subject: Missing opening delimiter
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary=missing-boundary

        Unparsed multipart data
        """.getBytes(StandardCharsets.US_ASCII);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "missing.eml", "missing.eml", "", List.of(), true);

    assertFalse(record.textBody.isBlank());
    assertFalse(record.textBody.contains("Unparsed multipart data"));
    assertFalse(record.htmlBody.contains("Unparsed multipart data"));
    assertEquals(0, record.attachmentCount);
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertOriginalSource(raw, record);
  }

  @Test
  void unavailableNestedMultipartRetainsReadableSiblingAndAttachment() throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        Subject: Readable siblings
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary=outside

        --outside
        Content-Type: multipart/alternative; boundary=missing-boundary

        Unparsed nested data
        --outside
        Content-Type: text/plain

        Readable sibling
        --outside
        Content-Type: application/octet-stream
        Content-Disposition: attachment; filename=sample.bin
        Content-Transfer-Encoding: base64

        AQID
        --outside--
        """.getBytes(StandardCharsets.US_ASCII);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "nested.eml", "nested.eml", "", List.of(), false);

    assertEquals("Readable sibling", record.textBody.strip());
    assertTrue(record.htmlBody.contains("Readable sibling"));
    assertFalse(record.htmlBody.contains("Unparsed nested data"));
    assertEquals(1, record.attachmentCount);
    AssociatedFile attachment = record.associatedFiles.stream()
        .filter(file -> file.emailAttachment).findFirst().orElseThrow();
    assertEquals("sample.bin", attachment.filename);
    assertArrayEquals(new byte[] {1, 2, 3}, attachment.data());
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertOriginalSource(raw, record);
  }

  private static void assertOriginalSource(byte[] raw, EmailRecord record) throws IOException {
    assertArrayEquals(raw, record.sourceBytes);
    AssociatedFile source = record.associatedFiles.stream()
        .filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow();
    assertArrayEquals(raw, source.data());
  }

  @Test
  void truncatedTextBodyPreservesReadableAlternativeAndAttachment() throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary=outer

        --outer
        Content-Type: multipart/alternative; boundary=body

        --body
        Content-Type: text/plain

        Readable alternative
        --body
        Content-Type: text/html
        Content-Transfer-Encoding: base64

        PGI+c2
        --body--
        --outer
        Content-Type: application/octet-stream
        Content-Disposition: attachment; filename=sample.bin
        Content-Transfer-Encoding: base64

        AQID
        --outer--
        """.getBytes(StandardCharsets.US_ASCII);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "body.eml", "body.eml", "", List.of(), false);

    assertEquals("Readable alternative", record.textBody.strip());
    assertTrue(record.htmlBody.contains("Readable alternative"));
    assertEquals(1, record.attachmentCount);
    AssociatedFile attachment = record.associatedFiles.stream()
        .filter(file -> file.emailAttachment).findFirst().orElseThrow();
    assertArrayEquals(new byte[] {1, 2, 3}, attachment.data());
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertOriginalSource(raw, record);
  }

  @ParameterizedTest
  @ValueSource(strings = {"base64", "05-bit"})
  void unreadableOnlyBodyShowsUnavailableNoticeAndPreservesOriginal(String encoding) throws Exception {
    byte[] raw = """
        From: sender@example.org
        To: recipient@example.org
        Date: Fri, 16 Oct 2026 12:00:00 +0000
        MIME-Version: 1.0
        Content-Type: text/plain
        Content-Transfer-Encoding: %s

        YWJjZA
        """.formatted(encoding).getBytes(StandardCharsets.US_ASCII);

    EmailRecord record = EmlReader.readRfc822Bytes(raw, "body.eml", "body.eml", "", List.of(), false);

    assertTrue(record.textBody.contains("MIME message body could not be read"));
    assertTrue(record.diagnosticCodes.containsValue(ConversionDiagnostic.Code.SOURCE_INCOMPLETE));
    assertOriginalSource(raw, record);
  }

  static final class Leaf extends MimeBodyPart {
    final byte[] bytes;int opens;
    Leaf(String type,byte[] bytes)throws Exception{this.bytes=bytes;setHeader("Content-Type",type);}
    @Override public InputStream getInputStream(){opens++;return new ByteArrayInputStream(bytes);}
  }
  static MimeMessage message(Leaf...parts)throws Exception {
    var multipart=new MimeMultipart("mixed");for(var part:parts)multipart.addBodyPart(part);
    var message=new MimeMessage(Session.getInstance(new Properties()));message.setContent(multipart);message.setHeader("Content-Type",multipart.getContentType());return message;
  }
  @Test void extractedLeavesDecodeOnceAndIgnoredLeavesAreStillCounted()throws Exception {
    var body=new Leaf("text/plain","Body".getBytes(StandardCharsets.US_ASCII));
    var attachment=new Leaf("application/octet-stream",new byte[]{1,2,3});attachment.setHeader("Content-Disposition","attachment; filename=sample.bin");
    var ignored=new Leaf("application/octet-stream",new byte[]{4,5});
    var message=message(body,attachment,ignored);var reads=new EmlReader.MimeReads(message);var record=new EmailRecord();
    assertTrue(EmlReader.extractBodyChoice(message,record,false,reads).text.startsWith("Body\nUnsupported email body"));
    EmlReader.collectRfc822AssociatedFiles(message,record.associatedFiles,record,false,new int[]{0},reads);reads.finish(record);
    assertEquals(1,body.opens);assertEquals(1,attachment.opens);assertEquals(1,ignored.opens);assertEquals(9,reads.decoded);
    reads.finish(record);assertEquals(1,ignored.opens);
  }
  @Test void ignoredLeavesStillEnforceAggregateDecodedByteLimit()throws Exception {
    String before=System.getProperty("eapdf.maxMessageBytes");System.setProperty("eapdf.maxMessageBytes","1024");
    try{
      Leaf a=new Leaf("application/octet-stream",new byte[800]),b=new Leaf("application/octet-stream",new byte[800]);
      var reads=new EmlReader.MimeReads(message(a,b));
      try(var input=reads.open(a,a.getInputStream())){assertEquals(800,input.readAllBytes().length);}
      assertThrows(RuntimeException.class, () -> reads.finish(new EmailRecord()));
    }finally{if(before==null)System.clearProperty("eapdf.maxMessageBytes");else System.setProperty("eapdf.maxMessageBytes",before);}
  }

  @Test void mimePartAdmissionUsesMetadataBudget() {
    String previous = System.getProperty("eapdf.maxMetadataBytes");
    try {
      System.setProperty("eapdf.maxMetadataBytes", "2048");
      var error = assertThrows(Common.UserFacingException.class,
          () -> EmlReader.readRfc822Bytes(nested(1), "sample.eml", "sample.eml", "", List.of(), false));
      assertTrue(error.getMessage().contains("MIME structure"));
    } finally {
      ProcessingLimitRepairTests.restore("eapdf.maxMetadataBytes", previous);
    }
  }

  @Test void iterativeTraversalStillCancels()throws Exception {
    var message=message(new Leaf("text/plain",new byte[]{65}));Thread.currentThread().interrupt();
    try{assertThrows(RuntimeException.class,()->new EmlReader.MimeReads(message));}
    finally{Thread.interrupted();}
  }
}
