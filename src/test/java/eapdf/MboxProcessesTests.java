package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.Loader;

class MboxProcessesTests {
  @TempDir Path directory;

  @Test void processCountRespectsMemoryCpuAndMessageCount() {
    long gib = ConversionMemory.GIB;
    assertEquals(409 * ConversionMemory.MIB, MboxProcesses.childHeap(4, 2*gib, 8*gib));
    assertEquals(256 * ConversionMemory.MIB, MboxProcesses.childHeap(4, 32*gib, 4*gib));
    assertEquals(4, MboxProcesses.workerCount(4, 1433, 2*gib, 8*gib, 8));
    assertEquals(1, MboxProcesses.workerCount(4, 1433, 512*ConversionMemory.MIB, 8*gib, 8));
    assertEquals(2, MboxProcesses.workerCount(4, 1433, 2*gib, 8*gib, 2));
    assertEquals(1, MboxProcesses.workerCount(4, 1, 2*gib, 8*gib, 8));
    assertEquals(1, MboxProcesses.workerCount(4, 1433, 2*gib, 512*ConversionMemory.MIB, 8));
  }

  @Test void realMboxPayloadsAndProvenanceSurviveProcessSerialization() throws Exception {
    try (var job = Common.beginJob()) {
      var records = MboxReader.readMbox("in/enron.mbox", "", List.of(), false);
      for (var original : records) {
        var copy = MboxProcesses.JSON.fromJson(MboxProcesses.JSON.toJson(original), EmailRecord.class);
        assertEquals(original.mboxProvenance, copy.mboxProvenance);
        assertEquals(original.sourcePath, copy.sourcePath);
        assertEquals(original.mailGuid, copy.mailGuid);
        assertEquals(original.diagnosticIdentity, copy.diagnosticIdentity);
        assertEquals(original.associatedFiles.size(), copy.associatedFiles.size());
        for (int i=0;i<original.associatedFiles.size();i++)
          assertArrayEquals(original.associatedFiles.get(i).data(), copy.associatedFiles.get(i).data());
        assertNotNull(copy.nativeLocationResources);
      }
    }
  }

  @Test void separateJvmsStageCompleteOriginalSourcesAndReceiptsWithoutPublishing() throws Exception {
    Path work = Files.createDirectory(directory.resolve("work"));
    Path out = Files.createDirectory(directory.resolve("out"));
    ConversionProgress.start("eapdf.EmailToPDFA", new String[]{"--out",out.toString(),"in/enron.mbox"},work);
    ConversionProgress.attach(work);
    try (var job = Common.beginJob(); var transaction = new OutputTransaction(work.resolve("outputs"), work)) {
      job.workerLimit = 2;
      var records = MboxReader.readMbox("in/enron.mbox", "", List.of(), false).subList(0,4);
      assertTrue(MboxProcesses.convert("in/enron.mbox", records, out.toString(),TestSupport.fontPath(),TestSupport.iccPath(),false,work.resolve("debug").toString(),false));
      assertEquals(4, transaction.entries.values().stream().filter(e->e.destination().endsWith(".pdf")).count());
      for (int i=0;i<4;i++) {
        int index=i+1;
        var entry=transaction.entries.values().stream().filter(e->e.destination().endsWith(String.format("_%06d.pdf",index))).findFirst().orElseThrow();
        assertFalse(Files.exists(Path.of(entry.destination())));
        try (var pdf=Loader.loadPDF(Path.of(entry.staged()).toFile())) {
          var sources=AttachmentProfilePolicyTests.files(pdf).values().stream().filter(f->"Source".equals(f.getCOSObject().getNameAsString(org.apache.pdfbox.cos.COSName.getPDFName("AFRelationship")))) .toList();
          assertEquals(1,sources.size());
          assertArrayEquals(records.get(i).associatedFiles.stream().filter(f->"Source".equals(f.afRelationship)).findFirst().orElseThrow().data(), AttachmentProfilePolicyTests.bytes(sources.get(0)));
        }
      }
      ConversionProgress.flush();
      var snapshot=com.google.gson.JsonParser.parseString(Files.readString(out.resolve("progress.json"))).getAsJsonObject();
      assertEquals(4,snapshot.get("emails_completed").getAsInt());
      assertEquals(4,snapshot.getAsJsonArray("creation_receipts").size());
      for(var receipt:snapshot.getAsJsonArray("creation_receipts")) assertEquals("PENDING",receipt.getAsJsonObject().get("publication").getAsString());
      try(var paths=Files.list(work)) { assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith("mbox-processes-"))); }
    } finally { ConversionProgress.detach(); }
  }

  @Test void childFailureDoesNotAdoptOrPublishAndCleansWorkers() throws Exception {
    Path work = Files.createDirectory(directory.resolve("failed-work"));
    Path out = Files.createDirectory(directory.resolve("failed-out"));
    try (var job = Common.beginJob(); var transaction = new OutputTransaction(work.resolve("outputs"), work)) {
      job.workerLimit=2;
      var records=MboxReader.readMbox("in/enron.mbox","",List.of(),false).subList(0,4);
      assertThrows(Exception.class,()->MboxProcesses.convert("in/enron.mbox",records,out.toString(),directory.resolve("missing.ttf").toString(),TestSupport.iccPath(),false,work.resolve("debug").toString(),false));
      assertTrue(transaction.entries.isEmpty());
      try(var paths=Files.walk(out)) { assertFalse(paths.anyMatch(p->p.toString().endsWith(".pdf"))); }
      try(var paths=Files.list(work)) { assertFalse(paths.anyMatch(p->p.getFileName().toString().startsWith("mbox-processes-"))); }
    }
  }
}
