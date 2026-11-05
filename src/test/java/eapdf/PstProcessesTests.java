package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PstProcessesTests {
  @TempDir Path directory;
  static final String INPUT = "in/quality-attached-message.pst";

  @Test void nativePstPayloadsAndMetadataSurviveWorkerSerialization() throws Exception {
    try (var job=Common.beginJob()) {
      var records=PstReader.readPst(INPUT,"archive-test",List.of("archive@example.org"),false);
      assertEquals(7,records.size());
      for(var original:records) {
        var copy=MboxProcesses.JSON.fromJson(MboxProcesses.JSON.toJson(original),EmailRecord.class);
        assertEquals(MboxProcesses.JSON.toJsonTree(original),MboxProcesses.JSON.toJsonTree(copy));
        for(int i=0;i<original.associatedFiles.size();i++)
          assertArrayEquals(original.associatedFiles.get(i).data(),copy.associatedFiles.get(i).data());
        job.release(original);
      }
    }
  }

  @Test void streamingWorkersPreserveSourcesNumberingAndReleasePayloadsBeforePublication() throws Exception {
    Path work=Files.createDirectory(directory.resolve("work")),out=Files.createDirectory(directory.resolve("out"));
    ConversionProgress.start("eapdf.EmailToPDFA",new String[]{"--out",out.toString(),INPUT},work);
    ConversionProgress.attach(work);ConversionProgress.inputsSelected(1);
    try(var job=Common.beginJob();var transaction=new OutputTransaction(work.resolve("outputs"),work)) {
      job.workerLimit=2;AtomicInteger sequence=new AtomicInteger(41);
      assertTrue(PstProcesses.convert(INPUT,out.toString(),TestSupport.fontPath(),TestSupport.iccPath(),"",List.of(),false,work.resolve("debug").toString(),sequence,true,false));
      assertEquals(48,sequence.get());assertEquals(0,job.metadataSize);
      try(var paths=Files.list(job.payloads.directory)){assertEquals(0,paths.count(),"PST payloads must be released as workers finish");}
      var pdfs=transaction.entries.values().stream().filter(e->e.destination().endsWith(".pdf")).toList();assertEquals(7,pdfs.size());
      int sources=0;
      for(int i=42;i<=48;i++){
        String suffix=String.format("_%06d.pdf",i);
        var entry=pdfs.stream().filter(e->e.destination().endsWith(suffix)).findFirst().orElseThrow();
        assertFalse(Files.exists(Path.of(entry.destination())));
        try(var pdf=Loader.loadPDF(Path.of(entry.staged()).toFile())){
          var source=AttachmentProfilePolicyTests.files(pdf).values().stream().filter(f->"Source".equals(f.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))).toList();
          assertEquals(1,source.size());assertTrue(AttachmentProfilePolicyTests.bytes(source.get(0)).length>0);sources++;
        }
      }
      assertEquals(7,sources);ConversionProgress.flush();
      var progress=com.google.gson.JsonParser.parseString(Files.readString(out.resolve("progress.json"))).getAsJsonObject();
      assertEquals(7,progress.get("emails_completed").getAsInt());assertEquals(7,progress.get("emails_total").getAsInt());assertEquals(7,progress.getAsJsonArray("creation_receipts").size());
      for(var receipt:progress.getAsJsonArray("creation_receipts")){assertEquals("PENDING",receipt.getAsJsonObject().get("publication").getAsString());assertEquals("PASS",receipt.getAsJsonObject().get("creation_checks").getAsString());}
      try(var paths=Files.list(work)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith("pst-processes-")));}
    }finally{ConversionProgress.detach();}
  }

  @Test void childFailureAbortsAllPdfPublicationAndCleansProcessStorage() throws Exception {
    Path work=Files.createDirectory(directory.resolve("failed")),out=Files.createDirectory(directory.resolve("failed-out"));
    try(var job=Common.beginJob();var transaction=new OutputTransaction(work.resolve("outputs"),work)) {
      job.workerLimit=2;
      assertThrows(Exception.class,()->PstProcesses.convert(INPUT,out.toString(),directory.resolve("missing.ttf").toString(),TestSupport.iccPath(),"",List.of(),false,work.resolve("debug").toString(),new AtomicInteger(),false,false));
      assertTrue(transaction.entries.isEmpty());try(var paths=Files.walk(out)){assertFalse(paths.anyMatch(p->p.toString().endsWith(".pdf")));}
      try(var paths=Files.list(work)){assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith("pst-processes-")));}
    }
  }

  @Test void oneJobKeepsTheExistingInProcessPath() throws Exception {
    Path work=Files.createDirectory(directory.resolve("one"));
    try(var job=Common.beginJob();var transaction=new OutputTransaction(work.resolve("outputs"),work)){
      job.workerLimit=1;assertFalse(PstProcesses.convert(INPUT,directory.resolve("out").toString(),TestSupport.fontPath(),TestSupport.iccPath(),"",List.of(),false,work.toString(),new AtomicInteger(),false,false));
      assertNull(job.payloads);
    }
  }
}
