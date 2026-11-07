package eapdf;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class FullTextSearchIndexTests {
 @TempDir Path temporary;
 @Test void sealChangesOnlyTheFixedWidthDigestSlot()throws Exception{
  String original="prefix"+FullTextSearchIndex.MARKER+"0".repeat(64)+"</eapdfindex:fileDigest>suffix";
  Path path=temporary.resolve("binding.pdf");Files.writeString(path,original);
  FullTextSearchIndex.seal(path);String actual=Files.readString(path);
  String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8)));
  assertEquals(original.replace("0".repeat(64),digest),actual);
  assertThrows(Exception.class,()->FullTextSearchIndex.seal(path));
 }
 @Test void ambiguousOrMissingDigestMarkersFailClosed()throws Exception{
  Path path=temporary.resolve("binding.pdf");Files.writeString(path,"none");assertThrows(Exception.class,()->FullTextSearchIndex.seal(path));
  Files.writeString(path,(FullTextSearchIndex.MARKER+"0".repeat(64)).repeat(2));assertThrows(Exception.class,()->FullTextSearchIndex.seal(path));
 }
 @Test void helperExtractsActualPdfTextAndIndexesOnlyRenderedPages()throws Exception{
  Path input=Path.of("browser-viewer/tests/fulltext-index/mbox_sample_1ci.pdf");
  try(var store=new PayloadStore();var doc=FullTextSearchIndex.load(input)){
   try(var builder=new FullTextSearchIndex.Builder(store)){builder.visit(input,doc,List.of(),0);
   assertEquals(3,builder.docs.size());assertEquals(3,builder.pages.size());

   byte[] first=builder.encode("ci"),second=builder.encode("ci");assertArrayEquals(first,second);
   String encoded=new String(first,StandardCharsets.UTF_8);assertTrue(encoded.contains("\"W\""));assertTrue(encoded.contains("power var"));assertFalse(encoded.contains("MIME-Version:"));
   }
  }
 }
 @Test void normalizationHandlesWhitespaceLigaturesAndCombiningMarks(){
  assertEquals("power var café fiancée σσ",FullTextSearchIndex.normalize("  Power \n VaR Café ﬁancée Σς  "));
  assertEquals("contract",FullTextSearchIndex.normalize("con-\ntract"));
  assertEquals("well-known",FullTextSearchIndex.normalize("well-known"));
  assertEquals("cooperate",FullTextSearchIndex.normalize("co\u00adoperate"));
 }
 @Test void disablingFullTextLeavesOriginalBytesUnchanged()throws Exception{
  Path copy=temporary.resolve("sample_1ci.pdf");Files.copy(Path.of("browser-viewer/tests/fulltext-index/mbox_sample_1ci.pdf"),copy);byte[] original=Files.readAllBytes(copy);
  boolean previous=EapdfConfig.get().searchIndex.fullTextEnabled;
  try{EapdfConfig.get().searchIndex.fullTextEnabled=false;FullTextSearchIndex.add(copy);assertArrayEquals(original,Files.readAllBytes(copy));}
  finally{EapdfConfig.get().searchIndex.fullTextEnabled=previous;}
 }
 @Test void extractionPreservesInternationalRenderedTextAndEmptyPages()throws Exception{
  Path input=temporary.resolve("text.pdf");
  try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){
   var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);pdf.addPage(new org.apache.pdfbox.pdmodel.PDPage());
   var font=org.apache.pdfbox.pdmodel.font.PDType0Font.load(pdf,Path.of(TestSupport.fontPath()).toFile());
   try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){
    content.beginText();content.setFont(font,12);content.newLineAtOffset(40,700);
    content.showText("Café Straße Ελληνικά ");content.showText("repeat repeat");content.endText();
   }
   pdf.save(input.toFile());
  }
  try(var store=new PayloadStore()){
   try(var doc=FullTextSearchIndex.load(input);var builder=new FullTextSearchIndex.Builder(store)){
    builder.visit(input,doc,List.of(),0);String encoded=new String(builder.encode("mi"),StandardCharsets.UTF_8);
    assertEquals(2,builder.pages.size());assertTrue(encoded.contains("café straße ελληνικά repeat repeat"));assertTrue(encoded.contains("[\"T\",\"\"]"));
   }
  }
 }
}
