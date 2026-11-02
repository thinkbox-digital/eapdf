package eapdf;
import java.nio.*;import java.nio.charset.*;import java.nio.file.*;import java.io.*;import java.util.*;
import org.apache.poi.poifs.filesystem.*;
public class MsgLocationProbe {
  public static void main(String[] args) throws Exception {
    Path file=Path.of(args[0]);Files.copy(Path.of(args[1]),file,StandardCopyOption.REPLACE_EXISTING);
    byte[] html="<html><body><p>Native MSG location image</p><img src=\"http://example.invalid/native-image.png\" width=\"40\" height=\"40\" /></body></html>".getBytes(StandardCharsets.UTF_8);
    byte[] image=CidUrlTests.image();
    try(InputStream in=Files.newInputStream(file);POIFSFileSystem fs=new POIFSFileSystem(in)) {
      DirectoryNode root=fs.getRoot();byte[] properties;
      try(InputStream p=root.createDocumentInputStream("__properties_version1.0")){properties=p.readAllBytes();}
      ByteBuffer values=ByteBuffer.wrap(properties).order(ByteOrder.LITTLE_ENDIAN);values.putInt(12,1);values.putInt(20,1);
      for(int offset=32;offset<properties.length;offset+=16)if(values.getInt(offset)==0x10130102)values.putInt(offset+8,html.length);
      root.getEntry("__properties_version1.0").delete();root.createDocument("__properties_version1.0",new ByteArrayInputStream(properties));
      root.getEntry("__substg1.0_10130102").delete();root.createDocument("__substg1.0_10130102",new ByteArrayInputStream(html));
      DirectoryNode attachment=(DirectoryNode)root.createDirectory("__attach_version1.0_#00000000");
      Map<Integer,byte[]> streams=new LinkedHashMap<>();streams.put(0x37010102,image);
      streams.put(0x3707001f,("native.png\0").getBytes(StandardCharsets.UTF_16LE));streams.put(0x370e001f,("image/png\0").getBytes(StandardCharsets.UTF_16LE));
      streams.put(0x3713001f,("http://example.invalid/native-image.png\0").getBytes(StandardCharsets.UTF_16LE));
      ByteBuffer props=ByteBuffer.allocate(8+16*(streams.size()+2)).order(ByteOrder.LITTLE_ENDIAN);props.position(8);
      props.putInt(0x37050003).putInt(6).putInt(1).putInt(0);
      props.putInt(0x37140003).putInt(6).putInt(4).putInt(0);
      for(var e:streams.entrySet()){attachment.createDocument(String.format("__substg1.0_%08X",e.getKey()),new ByteArrayInputStream(e.getValue()));props.putInt(e.getKey()).putInt(6).putInt(e.getValue().length).putInt(0);}
      attachment.createDocument("__properties_version1.0",new ByteArrayInputStream(props.array()));
      try(OutputStream out=Files.newOutputStream(file)){fs.writeFilesystem(out);}
    }
  }
}
