package eapdf;

import java.io.*;
import java.util.*;
import javax.swing.JTextArea;

/** Complete durable process log with bounded, back-pressured updates to the GUI. */
final class GuiLog {
  static final int DISPLAY_CHARACTERS=500_000, SUMMARY_CHARACTERS=100_000;
  static final int BATCH_LINES=128, BATCH_CHARACTERS=64*1024;
  private GuiLog() {}
  @FunctionalInterface interface BatchConsumer {void accept(List<String> lines)throws Exception;}

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
