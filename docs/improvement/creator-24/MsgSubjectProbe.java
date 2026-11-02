package eapdf;
import java.nio.*;import java.nio.charset.*;import java.nio.file.*;import java.io.*;
import org.apache.poi.poifs.filesystem.*;
public class MsgSubjectProbe {
  public static void main(String[] args) throws Exception {
    Path dir=Path.of(args[0]);Files.createDirectories(dir);
    for(int cp:new int[]{1251,1252,65001}) {
      Path file=dir.resolve("subject-"+cp+".msg");Files.copy(Path.of(args[1]),file,StandardCopyOption.REPLACE_EXISTING);
      String text=cp==1251?"Привет — тема":"Résumé — subject";
      byte[] subject=(text+"\0").getBytes(Charset.forName(cp==65001?"UTF-8":"windows-"+cp));
      try(InputStream in=Files.newInputStream(file);POIFSFileSystem fs=new POIFSFileSystem(in)) {
        DirectoryNode root=fs.getRoot();byte[] original;
        try(InputStream props=root.createDocumentInputStream("__properties_version1.0")){original=props.readAllBytes();}
        byte[] properties=java.util.Arrays.copyOf(original,original.length+16);ByteBuffer values=ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);
        for(int offset=32;offset<original.length;offset+=16)if(values.getInt(offset)==0x0037001f){values.putInt(offset,0x0037001e);values.putInt(offset+8,subject.length);}
        values.position(original.length);values.putInt(0x3ffd0003).putInt(6).putInt(cp).putInt(0);
        root.getEntry("__properties_version1.0").delete();root.createDocument("__properties_version1.0",new ByteArrayInputStream(properties));
        root.getEntry("__substg1.0_0037001F").delete();root.createDocument("__substg1.0_0037001E",new ByteArrayInputStream(subject));
        try(OutputStream out=Files.newOutputStream(file)){fs.writeFilesystem(out);}
      }
    }
  }
}
