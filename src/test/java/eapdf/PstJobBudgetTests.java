package eapdf;
import java.nio.file.*;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class PstJobBudgetTests {
 @TempDir Path temp;
 @Test void largeMailboxAndMixedFolderReceiveDiskSizedOutputAllowance() throws Exception {
  Path folder = Files.createDirectory(temp.resolve("mixed"));
  Path mailbox = folder.resolve("mail.mbox");
  try(var channel=java.nio.channels.FileChannel.open(mailbox,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
   channel.position(128L*1024*1024-1);channel.write(ByteBuffer.wrap(new byte[]{0}));
  }
  for(Path input : java.util.List.of(mailbox, folder)) {
   var budget=PstJobBudget.forInput("eapdf.EmailToPDFA",new String[]{input.toString()},temp);
   long disk=Files.getFileStore(temp).getUsableSpace();
   long limit=budget.setting("eapdf.maxOutputBytes","EAPDF_MAX_OUTPUT_BYTES",0);
   assertTrue(limit > 0); assertTrue(limit <= disk/2);
   assertTrue(budget.setting("eapdf.jobTimeoutSeconds","EAPDF_JOB_TIMEOUT_SECONDS",0) >= 600);
  }
 }
 @Test void metadataAllowanceUsesTheEffectiveWorkerHeapAndHonorsItsOwnOverride() throws Exception {
  Path source=temp.resolve("archive.pst");long size=400L*1024*1024;
  try(var channel=java.nio.channels.FileChannel.open(source,StandardOpenOption.CREATE,StandardOpenOption.WRITE)) {
   channel.position(size-1);channel.write(ByteBuffer.wrap(new byte[]{0}));
  }
  String oldHeap=System.getProperty("eapdf.workerHeapBytes"),oldMetadata=System.getProperty("eapdf.maxMetadataBytes");
  try {
   System.clearProperty("eapdf.maxMetadataBytes");
   for(long heap:new long[]{4L*1024*1024*1024,512L*1024*1024}) {
    System.setProperty("eapdf.workerHeapBytes",Long.toString(heap));
    var budget=PstJobBudget.forInput("eapdf.EmailToPDFA",new String[]{source.toString()},temp);
    long expected=Math.min(ConversionMemory.workerHeap(heap)/8,size/2+32L*1024*1024);
    assertEquals(expected,budget.setting("eapdf.maxMetadataBytes","EAPDF_MAX_METADATA_BYTES",0));
    System.setProperty("eapdf.maxMetadataBytes","123456");
    assertEquals(123456,budget.setting("eapdf.maxMetadataBytes","EAPDF_MAX_METADATA_BYTES",0));
    System.clearProperty("eapdf.maxMetadataBytes");
   }
  } finally {
   if(oldHeap==null)System.clearProperty("eapdf.workerHeapBytes");else System.setProperty("eapdf.workerHeapBytes",oldHeap);
   if(oldMetadata==null)System.clearProperty("eapdf.maxMetadataBytes");else System.setProperty("eapdf.maxMetadataBytes",oldMetadata);
  }
 }
 @Test void smallLocalInputAndLimitedGmailUseConservativeHeapWithoutChangingExplicitOverrides() throws Exception {
  String previous=System.getProperty("eapdf.workerHeapBytes");
  Path source=Files.writeString(temp.resolve("mail.eml"),"Subject: budget test\n\nbody");
  try {
   System.clearProperty("eapdf.workerHeapBytes");
   for(String entry:java.util.List.of("eapdf.EmailToPDFA","eapdf.GmailDownloadToPdf")) {
    String[] args=entry.endsWith("EmailToPDFA")?new String[]{source.toString()}:new String[]{"--limit","200"};
    var budget=PstJobBudget.forInput(entry,args,temp);
    long heap=budget.setting("eapdf.workerHeapBytes","EAPDF_WORKER_HEAP_BYTES",0);
    assertTrue(heap>0&&heap<=2L*1024*1024*1024);
    System.setProperty("eapdf.workerHeapBytes","3221225472");
    assertEquals(3221225472L,PstJobBudget.forInput(entry,args,temp).setting("eapdf.workerHeapBytes","EAPDF_WORKER_HEAP_BYTES",0));
    System.clearProperty("eapdf.workerHeapBytes");
   }
   assertEquals(0,PstJobBudget.forInput("eapdf.GmailDownloadToPdf",new String[]{"--limit","201"},temp).setting("eapdf.workerHeapBytes","EAPDF_WORKER_HEAP_BYTES",0));
  } finally {
   if(previous==null)System.clearProperty("eapdf.workerHeapBytes");else System.setProperty("eapdf.workerHeapBytes",previous);
  }
 }
}
