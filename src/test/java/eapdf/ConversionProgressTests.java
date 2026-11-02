package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class ConversionProgressTests {
 @TempDir Path directory;
 JsonObject read() throws Exception {return JsonParser.parseString(Files.readString(directory.resolve("out/progress.json"))).getAsJsonObject();}
 ConversionProgress begin(Path workspace) throws Exception {
  Files.createDirectories(workspace);
  return ConversionProgress.start("eapdf.EmailToPDFA",new String[]{"--out",directory.resolve("out").toString(),"input.eml"},workspace);
 }
 @Test void parallelCompletionKeepsExactTotalsAndDoesNotClaimPublicationEarly() throws Exception {
  Path workspace=directory.resolve("worker");var supervisor=begin(workspace);
  try {
   ConversionProgress.attach(workspace);ConversionProgress.inputsSelected(1);
   assertTrue(read().get("emails_total").isJsonNull());
   for(int i=0;i<128;i++)ConversionProgress.discovered();
   ConversionProgress.inputRead();
   var pool=Executors.newFixedThreadPool(4);
   try {
    var futures=new java.util.ArrayList<Future<?>>();
    for(int i=0;i<128;i++)futures.add(pool.submit(ConversionProgress::completed));
    for(var future:futures)future.get();
   } finally {pool.shutdownNow();}
   ConversionProgress.stage("saving");ConversionProgress.flush();
   assertEquals(128,read().get("emails_completed").getAsInt());
   assertEquals(128,read().get("emails_total").getAsInt());
   assertEquals("RUNNING",read().get("state").getAsString());
   assertEquals("saving",read().get("stage").getAsString());
   supervisor.finish("COMPLETED");assertEquals("COMPLETED",read().get("state").getAsString());
  } finally {ConversionProgress.detach();}
 }
 @Test void finalCountAndStageAreWrittenImmediatelyAndLongStagesRefresh() throws Exception {
  Path workspace=directory.resolve("worker");var supervisor=begin(workspace);
  try {
   ConversionProgress.attach(workspace);ConversionProgress.total(3);
   ConversionProgress.completed();ConversionProgress.completed();ConversionProgress.completed();
   assertEquals(3,read().get("emails_completed").getAsInt());
   ConversionProgress.stage("assembling");
   assertEquals("assembling",read().get("stage").getAsString());
   String updated=read().get("updated").getAsString();
   long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
   while(updated.equals(read().get("updated").getAsString()) && System.nanoTime()<until)Thread.sleep(25);
   assertNotEquals(updated,read().get("updated").getAsString());
   supervisor.finish("COMPLETED");ConversionProgress.flush();
   assertEquals("COMPLETED",read().get("state").getAsString());
  } finally {ConversionProgress.detach();}
 }
 @Test void failuresPreservePartialCountsAndOlderJobsCannotOverwriteNewerStatus() throws Exception {
  Path workspace=directory.resolve("old");var older=begin(workspace);
  try {
   ConversionProgress.attach(workspace);ConversionProgress.total(3);ConversionProgress.completed();ConversionProgress.flush();
   older.finish("FAILED");assertEquals(1,read().get("emails_completed").getAsInt());assertEquals(3,read().get("emails_total").getAsInt());
   begin(directory.resolve("new"));String owner=read().get("job_id").getAsString();
   ConversionProgress.completed();ConversionProgress.flush();older.finish("COMPLETED");
   assertEquals(owner,read().get("job_id").getAsString());assertEquals(0,read().get("emails_completed").getAsInt());
   assertEquals("RUNNING",read().get("state").getAsString());
  } finally {ConversionProgress.detach();}
 }
 @Test void stageCountsResetAndHeartbeatDoesNotInventCompletedWork() throws Exception {
  Path workspace=directory.resolve("worker");var supervisor=begin(workspace);
  try {
   ConversionProgress.attach(workspace); ConversionProgress.total(3);
   ConversionProgress.begin("tagging",3,"emails");
   ConversionProgress.advance("tagging"); ConversionProgress.advance("bookmarks"); ConversionProgress.flush();
   assertEquals(1,read().get("stage_completed").getAsInt());
   String last=read().get("last_progress").getAsString();
   ConversionProgress.flush(); assertEquals(last,read().get("last_progress").getAsString());
   ConversionProgress.advance("tagging"); ConversionProgress.advance("tagging");
   assertEquals(3,read().get("stage_completed").getAsInt());
   ConversionProgress.stage("checking");
   assertTrue(read().get("stage_total").isJsonNull());
   assertTrue(read().get("stage_completed").isJsonNull());
   ConversionProgress.begin("publishing",2,"files"); ConversionProgress.advance("publishing"); ConversionProgress.flush();
   supervisor.finish("FAILED");
   assertEquals(1,read().get("stage_completed").getAsInt());
   assertEquals("publishing",read().get("last_work_stage").getAsString());
  } finally {ConversionProgress.detach();}
 }
 @Test void savingReportsBytesAndPublicationHandoffPreservesEmailTotals() throws Exception {
  Path workspace=directory.resolve("worker");var supervisor=begin(workspace);
  try {
   ConversionProgress.attach(workspace); ConversionProgress.total(2);
   ConversionProgress.completed(); ConversionProgress.completed();
   Path pdf=directory.resolve("saving.pdf"); Files.write(pdf,new byte[1024]);
   ConversionProgress.saving(pdf);
   assertEquals(1024,read().get("stage_completed").getAsLong());
   assertTrue(read().get("stage_total").isJsonNull());
   Files.write(pdf,new byte[2048]); ConversionProgress.flush();
   assertEquals(2048,read().get("stage_completed").getAsLong());
   ConversionProgress.detach(); ConversionProgress.attach(workspace);
   ConversionProgress.begin("publishing",1,"files"); ConversionProgress.advance("publishing");
   assertEquals(2,read().get("emails_completed").getAsInt());
   assertEquals(2,read().get("emails_total").getAsInt());
   supervisor.finish("COMPLETED"); ConversionProgress.flush();
   assertEquals("COMPLETED",read().get("state").getAsString());
  } finally {ConversionProgress.detach();}
 }
 @Test void receiptsBindFinalBytesAndSurvivePublicationHandoffWithoutInventingValidation() throws Exception {
  Path workspace=directory.resolve("worker"); var supervisor=begin(workspace);
  Path staged=directory.resolve("staged.pdf"); Files.writeString(staged,"final signed bytes");
  try {
   ConversionProgress.attach(workspace);
   ConversionProgress.creationChecked(directory.resolve("out/mail.pdf"),staged,null);
   ConversionProgress.diagnostics(java.util.Map.of("message identity", "Reduced rendering: plain text"));
   var receipt=read().getAsJsonArray("creation_receipts").get(0).getAsJsonObject();
   assertEquals(Common.digestHex(staged,"SHA-256"),receipt.get("sha256").getAsString());
   assertEquals("PENDING",receipt.get("publication").getAsString());
   assertEquals("NOT_RUN",receipt.get("preservation_comparison").getAsString());
   ConversionProgress.detach(); ConversionProgress.attach(workspace); ConversionProgress.flush();
   supervisor.finish("COMPLETED");
   receipt=read().getAsJsonArray("creation_receipts").get(0).getAsJsonObject();
   assertEquals("PUBLISHED",receipt.get("publication").getAsString());
   assertEquals("NOT_RUN",receipt.get("pdfa_validation").getAsString());
   assertEquals("NOT_RUN",receipt.get("eapdf_validation").getAsString());
   assertEquals(1,read().getAsJsonObject("conversion_warnings").size());
  } finally {ConversionProgress.detach();}
 }
 @Test void failedPublicationNeverClaimsPublishedOutput() throws Exception {
  Path workspace=directory.resolve("worker"); var supervisor=begin(workspace);
  Path staged=directory.resolve("staged.pdf"); Files.writeString(staged,"bytes");
  try {
   ConversionProgress.attach(workspace);
   ConversionProgress.creationChecked(directory.resolve("out/mail.pdf"),staged,null);
   supervisor.finish("FAILED");
   assertEquals("NOT_CONFIRMED",read().getAsJsonArray("creation_receipts").get(0).getAsJsonObject().get("publication").getAsString());
  } finally {ConversionProgress.detach();}
 }
}
