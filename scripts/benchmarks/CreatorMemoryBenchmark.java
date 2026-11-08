package eapdf;

import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import javax.swing.*;

/**
 * Isolated before/after probe; compile against either the baseline or current creator.
 * Compile: javac -cp /tmp/baseline-creator.jar -d /tmp/memory-probe CreatorMemoryBenchmark.java
 * Run: java -Xmx1g -XX:ActiveProcessorCount=2 -cp /tmp/memory-probe:/tmp/creator.jar
 *      eapdf.CreatorMemoryBenchmark index /tmp/corpus_1ci.pdf
 * GUI model: replace the final arguments with "gui false 50000" (old display loop)
 * or "gui true 50000" (current bounded/batched display). Use -Djava.awt.headless=true.
 * Index round zero warms serialization; subsequent rounds reuse the extracted corpus.
 * Allocation is current-thread bytes. Heap metric sums per-pool peaks after explicit GC;
 * it is an allocation-pressure proxy, not simultaneous peak heap or process RSS.
 * GUI timing models Swing display work; it excludes disk I/O and a visible desktop.
 */
public final class CreatorMemoryBenchmark {
  public static void main(String[] args)throws Exception {
    if(args[0].equals("index"))index(Path.of(args[1]));else gui(Boolean.parseBoolean(args[1]),Integer.parseInt(args[2]));
  }
  static long allocated(){return ((com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean()).getThreadAllocatedBytes(Thread.currentThread().threadId());}
  static void resetPeak(){System.gc();for(var pool:ManagementFactory.getMemoryPoolMXBeans())pool.resetPeakUsage();}
  static long peak(){return ManagementFactory.getMemoryPoolMXBeans().stream().filter(p->p.getType()==MemoryType.HEAP).mapToLong(p->p.getPeakUsage().getUsed()).sum();}
  static void index(Path input)throws Exception {
    Method streaming;try{streaming=FullTextSearchIndex.Builder.class.getDeclaredMethod("writeEncoded",String.class,OutputStream.class);}catch(NoSuchMethodException old){streaming=null;}
    try(var store=new PayloadStore();var pdf=FullTextSearchIndex.load(input);var builder=new FullTextSearchIndex.Builder(store)){
      builder.visit(input,pdf,List.of(),0);
      String profile=FullTextSearchIndex.profile(new String(pdf.getDocumentCatalog().getMetadata().toByteArray(),StandardCharsets.UTF_8));
      for(int round=0;round<4;round++){
        resetPeak();long before=allocated(),start=System.nanoTime();var compressed=new ByteArrayOutputStream();
        try(var gzip=new GZIPOutputStream(compressed)){
          if(streaming==null)gzip.write(builder.encode(profile));else streaming.invoke(builder,profile,gzip);
        }
        long elapsed=System.nanoTime()-start,allocation=allocated()-before;
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compressed.toByteArray()));
        System.out.printf(Locale.ROOT,"{\"mode\":\"index\",\"streaming\":%s,\"round\":%d,\"documents\":%d,\"pages\":%d,\"ms\":%.3f,\"allocated\":%d,\"heapPoolPeakSumBytes\":%d,\"gzipBytes\":%d,\"sha256\":\"%s\"}%n",streaming!=null,round,builder.docs.size(),builder.pages.size(),elapsed/1e6,allocation,peak(),compressed.size(),hash);
      }
    }
  }
  static void gui(boolean batched,int count)throws Exception {
    String line="[debug] Retaining original email attachment bytes and checking preservation metadata: "+"x".repeat(32);
    String input=(line+System.lineSeparator()).repeat(count);
    SwingUtilities.invokeAndWait(()->{
      try{
        var area=new JTextArea();area.setLineWrap(true);area.setWrapStyleWord(true);area.setSize(800,400);
        resetPeak();long before=allocated(),start=System.nanoTime();int[] updates={0};
        if(batched){
          Class<?> log=Class.forName("eapdf.GuiLog"),consumer=Class.forName("eapdf.GuiLog$BatchConsumer");
          Method copy=log.getDeclaredMethod("copy",BufferedReader.class,Writer.class,consumer),append=log.getDeclaredMethod("append",JTextArea.class,String.class,int.class);
          Object display=Proxy.newProxyInstance(consumer.getClassLoader(),new Class<?>[]{consumer},(proxy,method,args)->{
            if(method.getName().equals("accept")){
              @SuppressWarnings("unchecked") var lines=(List<String>)args[0];append.invoke(null,area,String.join(System.lineSeparator(),lines)+System.lineSeparator(),500000);updates[0]++;
            }return null;
          });
          copy.invoke(null,new BufferedReader(new StringReader(input)),Writer.nullWriter(),display);
        }else for(int i=0;i<count;i++){area.append(line+System.lineSeparator());area.setCaretPosition(area.getDocument().getLength());updates[0]++;}
        System.out.printf(Locale.ROOT,"{\"mode\":\"gui\",\"batched\":%s,\"lines\":%d,\"updates\":%d,\"retainedCharacters\":%d,\"ms\":%.3f,\"allocated\":%d,\"heapPoolPeakSumBytes\":%d}%n",batched,count,updates[0],area.getDocument().getLength(),(System.nanoTime()-start)/1e6,allocated()-before,peak());
      }catch(Exception error){throw new RuntimeException(error);}
    });
  }
}
