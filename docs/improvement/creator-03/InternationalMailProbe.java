package eapdf;
import java.nio.charset.StandardCharsets;
import java.util.List;
public class InternationalMailProbe {
 public static void main(String[] args) throws Exception {
  String headers="From: José <jose@example.org>\r\nTo: Zoë <zoe@example.org>\r\nSubject: Résumé – réunion\r\nDate: Thu, 22 Oct 2026 12:00:00 +0000\r\nMessage-ID: <utf8@example.org>\r\nMIME-Version: 1.0\r\n";
  byte[] bytes=(headers+"Content-Type: text/plain; charset=UTF-8\r\n\r\nBody").getBytes(StandardCharsets.UTF_8);
  EmailRecord r=EmlReader.readRfc822Bytes(bytes,"utf8.eml","utf8.eml","",List.of(),false);
  System.out.println("subject="+r.subject+" rawFrom="+r.rawFrom+" sender="+r.fromAgents.get(0).name);
  String nested=headers+"Content-Type: multipart/mixed; boundary=m\r\n\r\n--m\r\nContent-Type: text/plain\r\n\r\nParent body\r\n--m\r\nContent-Type: message/global\r\n\r\n"+new String(bytes,StandardCharsets.UTF_8)+"\r\n--m--\r\n";
  EmailRecord g=EmlReader.readRfc822Bytes(nested.getBytes(StandardCharsets.UTF_8),"parent.eml","parent.eml","",List.of(),false);
  System.out.println("attached global count="+g.attachmentCount+" assets="+g.associatedFiles.size()+" body="+g.textBody);
  Thread.sleep(45000);
 }
}
