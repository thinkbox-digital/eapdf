package eapdf;
import java.nio.*;import java.nio.charset.*;import java.nio.file.*;import java.io.*;
import org.apache.poi.poifs.filesystem.*;
public class UnicodeSubjectControl {
  public static void main(String[] args)throws Exception {
    Path file=Path.of(args[0]);Files.copy(Path.of(args[1]),file,StandardCopyOption.REPLACE_EXISTING);
    byte[] text=("Unicode Subject — Привет\0").getBytes(StandardCharsets.UTF_16LE);
    try(InputStream in=Files.newInputStream(file);POIFSFileSystem fs=new POIFSFileSystem(in)) {
      DirectoryNode root=fs.getRoot();byte[] props;try(InputStream p=root.createDocumentInputStream("__properties_version1.0")){props=p.readAllBytes();}
      ByteBuffer values=ByteBuffer.wrap(props).order(ByteOrder.LITTLE_ENDIAN);
      for(int offset=32;offset<props.length;offset+=16)if(values.getInt(offset)==0x0037001e){values.putInt(offset,0x0037001f);values.putInt(offset+8,text.length);}
      root.getEntry("__properties_version1.0").delete();root.createDocument("__properties_version1.0",new ByteArrayInputStream(props));
      root.getEntry("__substg1.0_0037001E").delete();root.createDocument("__substg1.0_0037001F",new ByteArrayInputStream(text));
      try(OutputStream out=Files.newOutputStream(file)){fs.writeFilesystem(out);}
    }
  }
}
