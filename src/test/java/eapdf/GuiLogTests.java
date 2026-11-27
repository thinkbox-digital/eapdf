package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiLogTests {
 @TempDir Path directory;

 @Test void progressOmitsTimingAndKeepsStageAndEmailCounts() {
  var progress = GuiLog.Progress.parse(
      "[progress] checking embedded files — 2/5 files — CPU 32s — emails completed 18/100");
  assertEquals("checking embedded files — 2/5 files", progress.description());
  assertEquals("checking embedded files", progress.stage());
  assertEquals("18", progress.completed());
  assertEquals("100", progress.total());
  assertEquals(new GuiLog.Progress("checking", "checking", "18", "?"), GuiLog.Progress.parse(
      "[progress] checking — 0s in stage; 32s total; CPU nulls; emails completed 18/?"));
  assertEquals("saving — 2048 bytes (total unknown)", GuiLog.Progress.parse(
      "[progress] saving — 2048 bytes (total unknown) — CPU 32s — emails completed 18/100").description());
  assertEquals("checking attachment annotations — 2/5 attachments — message 12", GuiLog.Progress.parse(
      "[progress] checking attachment annotations — 2/5 attachments — message 12 — CPU 32s — emails completed 18/100").description());
 }

 @Test void rapidStagesKeepTheJobStatusAndLongStagesShowTheirLatestCount() {
  long second = TimeUnit.SECONDS.toNanos(1);
  var status = new GuiLog.ProgressStatus();
  status.accept(GuiLog.Progress.parse("[progress] rendering — emails completed 2/100"), 0);
  status.accept(GuiLog.Progress.parse("[progress] checking — emails completed 3/100"), second / 2);
  assertEquals("Converting", status.text("Converting", second));
  status.accept(GuiLog.Progress.parse(
      "[progress] checking — 1/4 files — emails completed 3/100"), second);
  assertEquals("checking — 1/4 files", status.text("Converting", 3 * second / 2));
  assertEquals("3", status.latest().completed());
  status.clear();
  assertEquals("Converting", status.text("Converting", 2 * second));
 }

 @Test void completeLogIsSavedWhileDisplayAndBatchSizesRemainBounded()throws Exception{
  String input=("warning: café 日本語 " + "x".repeat(80)+System.lineSeparator()).repeat(20_000);
  Path log=directory.resolve("conversion.log");var area=new JTextArea();int[] batches={0};
  try(var saved=Files.newBufferedWriter(log,StandardCharsets.UTF_8)){
   GuiLog.copy(new BufferedReader(new StringReader(input)),saved,lines->{
    assertTrue(lines.size()<=GuiLog.BATCH_LINES);batches[0]++;
    String chunk=String.join(System.lineSeparator(),lines)+System.lineSeparator();
    SwingUtilities.invokeAndWait(()->{GuiLog.append(area,chunk,GuiLog.DISPLAY_CHARACTERS);assertTrue(area.getDocument().getLength()<=GuiLog.DISPLAY_CHARACTERS);});
   });
  }
  assertEquals(input,Files.readString(log));assertEquals(input.substring(input.length()-GuiLog.DISPLAY_CHARACTERS),area.getText());
  assertTrue(batches[0]<200); // updates are per batch, not per line
 }
 @Test void longLinesArePreservedInFileAndSummaryTailIsBounded()throws Exception{
  String line="x".repeat(GuiLog.DISPLAY_CHARACTERS*2)+"last";
  var saved=new StringWriter();var tail=new StringBuilder();
  GuiLog.copy(new BufferedReader(new StringReader(line)),saved,lines->{for(String item:lines)GuiLog.tail(tail,item,GuiLog.SUMMARY_CHARACTERS);});
  assertEquals(line+System.lineSeparator(),saved.toString());assertEquals(GuiLog.SUMMARY_CHARACTERS,tail.length());assertTrue(tail.toString().endsWith("last"));
 }
 @Test void logFailureAndDisplayFailurePropagate()throws Exception{
  var bad=new Writer(){public void write(char[] c,int o,int n)throws IOException{throw new IOException("disk full");}public void flush(){}public void close(){}};
  assertThrows(IOException.class,()->GuiLog.copy(new BufferedReader(new StringReader("important")),bad,lines->fail("Must not display before persisting")));
  var saved=new StringWriter();assertThrows(IllegalStateException.class,()->GuiLog.copy(new BufferedReader(new StringReader("important")),saved,lines->{throw new IllegalStateException("display closed");}));
  assertEquals("important"+System.lineSeparator(),saved.toString());
 }
}
