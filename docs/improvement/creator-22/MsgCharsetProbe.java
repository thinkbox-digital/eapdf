package eapdf;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.io.*;
import org.apache.poi.poifs.filesystem.*;
public class MsgCharsetProbe {
  public static void main(String[] args) throws Exception {
    Path dir=Path.of(args[0]);Files.createDirectories(dir);
    for(int cp:new int[]{1252,1251,65001}) {
      Path file=dir.resolve("charset-"+cp+".msg");MsgBodySelectionTests.writeMsg(file,3,false);
      String text=cp==1251?"Привет":"Résumé — café";
      Charset charset=Charset.forName(cp==65001?"UTF-8":"windows-"+cp);
      byte[] html=("<html><body><p>"+text+"</p></body></html>").getBytes(charset);
      byte[] plain=(text+"\0").getBytes(StandardCharsets.UTF_16LE);
      try(InputStream in=Files.newInputStream(file);POIFSFileSystem fs=new POIFSFileSystem(in)) {
        DirectoryNode root=fs.getRoot();byte[] prior;
        try(InputStream props=root.createDocumentInputStream("__properties_version1.0")){prior=props.readAllBytes();}
        byte[] properties=java.util.Arrays.copyOf(prior,prior.length+16);
        ByteBuffer values=ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset=32;offset<prior.length;offset+=16) {
          if(values.getInt(offset)==0x10130102)values.putInt(offset+8,html.length);
          if(values.getInt(offset)==0x1000001f)values.putInt(offset+8,plain.length);
        }
        values.position(prior.length);values.putInt(0x3fde0003).putInt(6).putInt(cp).putInt(0);
        root.getEntry("__properties_version1.0").delete();root.createDocument("__properties_version1.0",new ByteArrayInputStream(properties));
        root.getEntry("__substg1.0_10130102").delete();root.createDocument("__substg1.0_10130102",new ByteArrayInputStream(html));
        root.getEntry("__substg1.0_1000001F").delete();root.createDocument("__substg1.0_1000001F",new ByteArrayInputStream(plain));
        try(OutputStream out=Files.newOutputStream(file)){fs.writeFilesystem(out);}
      }
    }
    EmailRecord record=MsgReader.readMsg(dir.resolve("charset-1252.msg").toString(),"",java.util.List.of(),false);
    Files.writeString(dir.resolve("decoded.html"),record.htmlBody);
    if(args.length>1)Thread.sleep(45000);
  }
}
