package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class SharedMimePayloadTests {
 @Test void extractedPayloadsSurviveMimeClosureAndStaging()throws Exception{
  Path input=Path.of("in/Agenda and timeline slides.eml");byte[] raw=Files.readAllBytes(input);
  var record=EmlReader.readRfc822Bytes(raw,input.toString(),input.getFileName().toString(),"",List.of(),false);
  assertSame(raw,record.sourceBytes);assertNull(record.mimePartScopes);
  var hashes=new ArrayList<String>();for(var file:record.associatedFiles){hashes.add(Common.sha256Hex(file.data()));assertEquals(hashes.get(hashes.size()-1),file.checksumSha256);}
  assertTrue(record.associatedFiles.stream().anyMatch(f->f.emailAttachment));
  try(var store=new PayloadStore()){
   store.stage(record);assertNull(record.sourceBytes);
   for(int i=0;i<hashes.size();i++){var file=record.associatedFiles.get(i);assertNull(file.bytes);assertEquals(hashes.get(i),Common.sha256Hex(file.data()));}
   assertEquals(Common.sha256Hex(raw),hashes.get(0));store.release(record);
  }
 }
}
