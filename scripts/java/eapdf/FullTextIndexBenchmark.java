package eapdf;
import java.nio.file.*;
import java.util.*;
/** Add full-text indexes to paired public-source scaling fixtures; measure the added creator stage. */
public final class FullTextIndexBenchmark {
 public static void main(String[] args)throws Exception{
  Path source=Path.of(args[0]),output=Path.of(args[1]);Files.createDirectories(output);
  for(int count:new int[]{100,1000,10000}){
   String name="eml_repeated_"+count+"_1ci.pdf";
   Path input=source.resolve(name),legacy=output.resolve("eml_repeated_"+count+"_legacy_1ci.pdf"),indexed=output.resolve(name);
   String sourceHash=Common.digestHex(input,"SHA-256");long copied=System.nanoTime();Files.copy(input,legacy,StandardCopyOption.REPLACE_EXISTING);Files.copy(input,indexed,StandardCopyOption.REPLACE_EXISTING);long copyMs=(System.nanoTime()-copied)/1_000_000;
   long started=System.nanoTime();FullTextSearchIndex.add(indexed);long elapsed=(System.nanoTime()-started)/1_000_000;
   try(var doc=FullTextSearchIndex.load(indexed)){String xml=new String(doc.getDocumentCatalog().getMetadata().toByteArray(),java.nio.charset.StandardCharsets.UTF_8);if(!xml.contains("<eapdfindex:fulltext>"))throw new IllegalStateException("Index was omitted");}
   if(!sourceHash.equals(Common.digestHex(input,"SHA-256")))throw new IllegalStateException("Original fixture changed");
   System.out.println(FullTextSearchIndex.JSON.toJson(Map.of("messages",count,"inputSha256",sourceHash,"copyMs",copyMs,"indexGenerationMs",elapsed,"legacyBytes",Files.size(legacy),"indexedBytes",Files.size(indexed))));
  }
 }
}
