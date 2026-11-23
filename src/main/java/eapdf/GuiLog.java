package eapdf;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.swing.JTextArea;

/** Complete durable process log with bounded, back-pressured updates to the GUI. */
final class GuiLog {
  static final int DISPLAY_CHARACTERS=500_000, SUMMARY_CHARACTERS=100_000;
  static final int BATCH_LINES=128, BATCH_CHARACTERS=64*1024;
  static final int PROGRESS_REFRESH_MILLIS = 1000;
  static final String PROGRESS_PREFIX = "[progress] ";
  private static final String PROGRESS_SEPARATOR = " — ";
  private static final Pattern PROGRESS_COUNTS =
      Pattern.compile("(?: — |; )emails completed (\\d{1,10})/(\\d{1,10}|\\?)$");
  private static final Pattern CPU_TIMING = Pattern.compile("(?: — |; )CPU (?:\\d+|null)s$");
  private static final Pattern WALL_TIMING = Pattern.compile(" — \\d+s in stage; \\d+s total$");
  private GuiLog() {}
  @FunctionalInterface interface BatchConsumer {void accept(List<String> lines)throws Exception;}

  record Progress(String description, String stage, String completed, String total) {
    static Progress parse(String line) {
      String description = line.substring(PROGRESS_PREFIX.length());
      var counts = PROGRESS_COUNTS.matcher(description);
      String completed = null;
      String total = null;
      if (counts.find()) {
        completed = counts.group(1);
        total = counts.group(2);
        description = description.substring(0, counts.start());
      }
      description = WALL_TIMING.matcher(CPU_TIMING.matcher(description).replaceFirst("")).replaceFirst("");
      int separator = description.indexOf(PROGRESS_SEPARATOR);
      return new Progress(description, separator < 0 ? description : description.substring(0, separator),
          completed, total);
    }
  }

  static final class ProgressStatus {
    private Progress latest;
    private long stageStarted;

    void accept(Progress progress, long now) {
      if (latest == null || !latest.stage().equals(progress.stage())) stageStarted = now;
      latest = progress;
    }

    Progress latest() { return latest; }

    String text(String fallback, long now) {
      return latest != null && now - stageStarted >= TimeUnit.MILLISECONDS.toNanos(PROGRESS_REFRESH_MILLIS)
          ? latest.description() : fallback;
    }

    void clear() { latest = null; }
  }

  static void copy(BufferedReader reader,Writer saved,BatchConsumer display)throws Exception {
    var batch=new ArrayList<String>();int characters=0;long last=System.nanoTime();String line;
    while((line=reader.readLine())!=null){
      saved.write(line);saved.write(System.lineSeparator());
      batch.add(line);characters+=line.length();
      if(batch.size()>=BATCH_LINES||characters>=BATCH_CHARACTERS||System.nanoTime()-last>=100_000_000L||!reader.ready()){
        saved.flush();display.accept(List.copyOf(batch));batch.clear();characters=0;last=System.nanoTime();
      }
    }
    saved.flush();if(!batch.isEmpty())display.accept(List.copyOf(batch));
  }

  /** Append once per batch and discard only the on-screen tail, never the saved log. */
  static void append(JTextArea area,String text,int maximum) {
    if(text.length()>maximum)text=text.substring(text.length()-maximum);
    int remove=Math.max(0,area.getDocument().getLength()+text.length()-maximum);
    try{if(remove>0)area.getDocument().remove(0,remove);}
    catch(javax.swing.text.BadLocationException error){throw new IllegalStateException(error);}
    area.append(text);area.setCaretPosition(area.getDocument().getLength());
  }

  static void tail(StringBuilder builder,String text,int maximum){
    if(text.length()>=maximum){builder.setLength(0);builder.append(text,text.length()-maximum,text.length());return;}
    int remove=Math.max(0,builder.length()+text.length()-maximum);if(remove>0)builder.delete(0,remove);builder.append(text);
  }
}
