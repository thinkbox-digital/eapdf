package eapdf;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import org.apache.poi.poifs.filesystem.*;
public class ReplyProbe {
 public static void main(String[] args) throws Exception {
  Path original=Path.of("in/email/MSGReader-master/2019.10.10a additional comments and questions for DEP.msg");Path changed=Path.of("/tmp/eapdf-creator-reply/Reply address.msg");
  Files.copy(original,changed,StandardCopyOption.REPLACE_EXISTING);
  try(POIFSFileSystem fs=new POIFSFileSystem(changed.toFile(),false)) {
   DirectoryNode root=fs.getRoot();String key=root.hasEntry("__substg1.0_007D001F")?"__substg1.0_007D001F":"__substg1.0_007D001E";var charset=key.endsWith("001F")?StandardCharsets.UTF_16LE:StandardCharsets.ISO_8859_1;String old="";
   if(root.hasEntry(key))try(var in=root.createDocumentInputStream(key)){old=new String(in.readAllBytes(),charset).replace("\0","");}
   byte[] replacement=("Reply-To: Project team <project-team@example.org>\r\n"+old+"\0").getBytes(charset);
   root.createOrUpdateDocument(key,new ByteArrayInputStream(replacement));
   try(var input=root.createDocumentInputStream("__properties_version1.0")){
    byte[] properties=input.readAllBytes();var buffer=java.nio.ByteBuffer.wrap(properties).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    int tag=key.endsWith("001F")?0x007d001f:0x007d001e;
    for(int offset=32;offset+16<=properties.length;offset+=16)if(buffer.getInt(offset)==tag)buffer.putInt(offset+8,replacement.length);
    root.createOrUpdateDocument("__properties_version1.0",new ByteArrayInputStream(properties));
   }
   fs.writeFilesystem();
  }
  EmailRecord msg=MsgReader.readMsg(changed.toString(),"",List.of(),false);if(!Common.nz(msg.rawReplyTo).contains("project-team@example.org"))throw new AssertionError("MSG header missing: "+msg.rawReplyTo);
  if(!new String(Common.synthesizeEml(msg),StandardCharsets.UTF_8).contains("Reply-To: Project team <project-team@example.org>"))throw new AssertionError("Export lost Reply-To");
  int total=0,supplied=0;
  for(String file:List.of("in/email/test.pst","src/test/resources/eapdf/email/attached-message.pst"))for(var r:PstReader.readPst(file,"",List.of(),false)) {
   total++;if(!Common.nz(r.rawReplyTo).isBlank()){
    supplied++;byte[] exported=Common.synthesizeEml(r);
    var parsed=new jakarta.mail.internet.MimeMessage(EmlReader.mimeSession(exported),new ByteArrayInputStream(exported));
    if(!Common.addrListLenient(r.rawReplyTo).equals(Common.addrListLenient(Common.header1(parsed,"Reply-To"))))throw new AssertionError("PST export changed Reply-To");
    if(supplied==1){Path pdf=Path.of("output/creator-review/c49-native/PST reply.pdf");Files.createDirectories(pdf.getParent());PdfMailWriter.writeEaPdfA3(pdf,r,Common.resolveFontPath(null),Common.resolveIccPath(null),false,Path.of("/tmp/eapdf-creator-reply"),1);Files.writeString(Path.of("/tmp/eapdf-creator-reply/pst-reply-address.txt"),Common.addrListLenient(r.rawReplyTo));}
   }
  }
  System.out.println("MSG supplied reply address read and exported; PST messages="+total+", supplied Reply-To="+supplied);
 }
}
