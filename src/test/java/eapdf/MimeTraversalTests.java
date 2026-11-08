package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

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
    assertEquals("Deep body",record.textBody.strip());assertSame(raw,record.sourceBytes);assertNull(record.mimePartScopes);
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
    EmlReader.collectRfc822AssociatedFiles(message,record.associatedFiles,record,false,new int[]{0},reads);reads.finish();
    assertEquals(1,body.opens);assertEquals(1,attachment.opens);assertEquals(1,ignored.opens);assertEquals(9,reads.decoded);
    reads.finish();assertEquals(1,ignored.opens);
  }
  @Test void ignoredLeavesStillEnforceAggregateDecodedByteLimit()throws Exception {
    String before=System.getProperty("eapdf.maxMessageBytes");System.setProperty("eapdf.maxMessageBytes","1024");
    try{
      Leaf a=new Leaf("application/octet-stream",new byte[800]),b=new Leaf("application/octet-stream",new byte[800]);
      var reads=new EmlReader.MimeReads(message(a,b));
      try(var input=reads.open(a,a.getInputStream())){assertEquals(800,input.readAllBytes().length);}
      assertThrows(RuntimeException.class,reads::finish);
    }finally{if(before==null)System.clearProperty("eapdf.maxMessageBytes");else System.setProperty("eapdf.maxMessageBytes",before);}
  }
  @Test void iterativeTraversalStillCancels()throws Exception {
    var message=message(new Leaf("text/plain",new byte[]{65}));Thread.currentThread().interrupt();
    try{assertThrows(RuntimeException.class,()->new EmlReader.MimeReads(message));}
    finally{Thread.interrupted();}
  }
}
