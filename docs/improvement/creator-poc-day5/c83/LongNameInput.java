import org.apache.poi.poifs.filesystem.*;
import java.nio.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
public class LongNameInput {
 public static void main(String[] args)throws Exception {
  String name="Weekly project review — timetable and responsibilities — "+"approved project notes — ".repeat(5)+"final decision.msg";
  try(POIFSFileSystem source=new POIFSFileSystem(Path.of(args[0]).toFile(),true);POIFSFileSystem output=new POIFSFileSystem()) {
   EntryUtils.copyNodes(source.getRoot(),output.getRoot());output.getRoot().setStorageClsid(source.getRoot().getStorageClsid());
   DirectoryNode attachment=(DirectoryNode)output.getRoot().getEntry("__attach_version1.0_#00000000");
   byte[] bytes=name.getBytes(StandardCharsets.UTF_16LE);attachment.createOrUpdateDocument("__substg1.0_3707001F",new ByteArrayInputStream(bytes));
   byte[] properties;try(InputStream input=attachment.createDocumentInputStream("__properties_version1.0")){properties=input.readAllBytes();}
   byte[] extended=java.util.Arrays.copyOf(properties,properties.length+16);ByteBuffer b=ByteBuffer.wrap(extended).order(ByteOrder.LITTLE_ENDIAN);b.position(properties.length);b.putInt(0x3707001f).putInt(6).putInt(bytes.length+2).putInt(0);attachment.createOrUpdateDocument("__properties_version1.0",new ByteArrayInputStream(extended));
   try(OutputStream file=Files.newOutputStream(Path.of(args[1]))){output.writeFilesystem(file);}
  }
  Files.writeString(Path.of(args[2]),name,StandardCharsets.UTF_8);
 }
}
