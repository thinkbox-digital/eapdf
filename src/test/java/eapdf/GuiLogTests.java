package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GuiLogTests {
 @TempDir Path directory;
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
