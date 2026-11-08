package eapdf;

import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Development-only warmed reader comparison; run with each implementation's classpath. */
public final class MimeReaderBenchmark {
  static volatile EmailRecord retained;
  public static void main(String[] args)throws Exception {
    boolean deep=args[0].equals("--deep");
    if(!deep&&Files.isDirectory(Path.of(args[0]))){
      try(var files=Files.walk(Path.of(args[0]))){for(Path file:files.filter(Files::isRegularFile).filter(p->p.toString().toLowerCase(Locale.ROOT).endsWith(".eml")).sorted().toList())main(new String[]{file.toString(),"0","1"});}
      return;
    }
    int warm=deep?0:Integer.parseInt(args[1]),iterations=deep?1:Integer.parseInt(args[2]);
    byte[] raw=deep?nested(Integer.parseInt(args[1])):Files.readAllBytes(Path.of(args[0]));
    String name=deep?"deep.eml":Path.of(args[0]).getFileName().toString();
    var result=new LinkedHashMap<String,Object>();result.put("source",name);result.put("sourceBytes",raw.length);result.put("sourceSha256",Common.sha256Hex(raw));
    try{
      for(int i=0;i<warm;i++)retained=parse(raw,name);
      var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
      long id=Thread.currentThread().threadId(),allocated=bean.getThreadAllocatedBytes(id),start=System.nanoTime();
      for(int i=0;i<iterations;i++)retained=parse(raw,name);
      result.put("elapsedMs",(System.nanoTime()-start)/1e6);result.put("allocatedBytes",bean.getThreadAllocatedBytes(id)-allocated);
      result.put("warmups",warm);result.put("iterations",iterations);result.put("status","PASS");
      var fields=new LinkedHashMap<String,Object>();
      fields.put("subject",retained.subject);fields.put("from",retained.rawFrom);fields.put("to",retained.rawTo);fields.put("cc",retained.rawCc);fields.put("bcc",retained.rawBcc);
      fields.put("bodyMime",retained.bodyMime);fields.put("text",retained.textBody);fields.put("html",retained.htmlBody);fields.put("base",retained.bodyBaseUri);
      var files=new ArrayList<Object[]>();var payloads=new ArrayList<String>();
      for(var f:retained.associatedFiles){String hash=Common.sha256Hex(f.data());payloads.add(hash);files.add(new Object[]{f.filename,f.mime,f.afRelationship,hash,f.emailAttachment,f.contentId,f.contentLocation,f.locationScope});}
      fields.put("files",files);
      String json=new com.google.gson.Gson().toJson(fields);result.put("resultSha256",Common.sha256Hex(json.getBytes(StandardCharsets.UTF_8)));
      result.put("payloadSha256",payloads);
    }catch(Throwable error){result.put("status",error.getClass().getSimpleName());}
    System.out.println(new com.google.gson.Gson().toJson(result));
  }
  static EmailRecord parse(byte[] raw,String name)throws Exception{return EmlReader.readRfc822Bytes(raw,name,name,"",List.of(),false);}
  static byte[] nested(int depth){
    var text=new StringBuilder("From: sender@example.org\r\nSubject: Deep MIME\r\nMIME-Version: 1.0\r\n");
    for(int i=0;i<depth;i++)text.append("Content-Type: multipart/mixed; boundary=b").append(i).append("\r\n\r\n--b").append(i).append("\r\n");
    text.append("Content-Type: text/plain; charset=UTF-8\r\n\r\nDeep body\r\n");
    text.append("--b").append(depth-1).append("\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=deep.bin\r\nContent-Transfer-Encoding: base64\r\n\r\nAQID\r\n");
    for(int i=depth-1;i>=0;i--)text.append("--b").append(i).append("--\r\n");
    return text.toString().getBytes(StandardCharsets.US_ASCII);
  }
}
