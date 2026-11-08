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
 @Test void streamingEncodingPreservesSavedPayloadAndCleansSpoolsOnFailure()throws Exception{
  Path input=Path.of("browser-viewer/tests/fulltext-index/mbox_sample_1ci.pdf");
  try(var store=new PayloadStore();var doc=FullTextSearchIndex.load(input);var builder=new FullTextSearchIndex.Builder(store)){
   builder.visit(input,doc,List.of(),0);
   String xmp=new String(doc.getDocumentCatalog().getMetadata().toByteArray(),StandardCharsets.UTF_8);
   var found=java.util.regex.Pattern.compile("<eapdfindex:fulltext>([^<]+)</eapdfindex:fulltext>").matcher(xmp);assertTrue(found.find());
   byte[] expected;try(var gzip=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(found.group(1))))){expected=gzip.readAllBytes();}
   var actual=new java.io.ByteArrayOutputStream();builder.writeEncoded("ci",actual);assertArrayEquals(expected,actual.toByteArray());
   assertThrows(java.io.IOException.class,()->builder.writeEncoded("ci",new java.io.OutputStream(){public void write(int b)throws java.io.IOException{throw new java.io.IOException("simulated output failure");}}));
   try(var files=Files.list(store.directory)){assertFalse(files.anyMatch(p->p.getFileName().toString().startsWith("index-body-")));}
  }
 }
 @Test void streamedByteBudgetFailsBeforeWritingBeyondItsLimit()throws Exception{
  var bytes=new java.io.ByteArrayOutputStream();var limited=new FullTextSearchIndex.LimitedOutput(bytes,4);
  limited.write(new byte[]{1,2,3});assertThrows(java.io.IOException.class,()->limited.write(new byte[]{4,5}));assertEquals(3,bytes.size());
  limited.write(4);assertThrows(java.io.IOException.class,()->limited.write(5));assertEquals(4,bytes.size());
 }

 @Test void backwardDeferredLinkCannotManufactureAnEmailMatch()throws Exception{
  Path input=temporary.resolve("deferred-link.pdf");
  try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()){
   var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
   var font=new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.COURIER);
   try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)){
    content.beginText();content.setFont(font,12);content.newLineAtOffset(300,700);content.showText(">Russ White");content.endText();
    content.beginText();content.setFont(font,12);content.newLineAtOffset(105,700);content.showText("mailto:reader@example.org");content.endText();
    content.beginText();content.setFont(font,12);content.newLineAtOffset(40,650);content.showText("Real email");content.endText();
   }
   String old=FullTextSearchIndex.normalize(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf));
   assertTrue(old.contains("whitemailto:"),"Fixture must reproduce PDFBox's merged backward runs");
   pdf.save(input.toFile());
  }
  byte[] original=Files.readAllBytes(input);
  try(var store=new PayloadStore();var doc=FullTextSearchIndex.load(input);var builder=new FullTextSearchIndex.Builder(store)){
   builder.visit(input,doc,List.of(),0);String encoded=new String(builder.encode("mi"),StandardCharsets.UTF_8);
   String text=Arrays.stream(encoded.split("\n")).filter(row->row.startsWith("[\"T\"")).map(row->FullTextSearchIndex.JSON.fromJson(row,com.google.gson.JsonArray.class).get(1).getAsString()).findFirst().orElseThrow();
   assertTrue(encoded.contains("pdfbox-nfkd-space-fold-v3"));assertTrue(text.contains("white mailto:"));
   assertEquals(1,text.split("email",-1).length-1);assertArrayEquals(original,Files.readAllBytes(input));
  }
 }
 static org.apache.pdfbox.text.TextPosition position(String text,float x,float width,org.apache.pdfbox.pdmodel.font.PDFont font){
  return new org.apache.pdfbox.text.TextPosition(0,612,792,new org.apache.pdfbox.util.Matrix(12,0,0,12,x,700),x+width,700,12,width,4,text,new int[]{1},font,12,12);
 }
 @Test void boundariesPreserveWholeOverlappingRunsAndContiguousDifferentFonts()throws Exception{
  var regular=new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.COURIER);
  var bold=new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.COURIER_BOLD);
  assertEquals("contract",FullTextSearchIndex.spatialText("contract",List.of(position("con",40,18,regular),position("tract",58,30,bold))));
  assertEquals("contract",FullTextSearchIndex.spatialText("contract",List.of(position("con",40,18,regular),position("tract",57,30,bold))));
  assertEquals("White mailto",FullTextSearchIndex.spatialText("Whitemailto",List.of(position("White",340,30,regular),position("mailto",200,90,bold))));
  // A glyph-only backward-jump check would insert a false separator here. The
  // entire following run overlaps White, so it must retain the existing text.
  assertEquals("Whitemailto",FullTextSearchIndex.spatialText("Whitemailto",List.of(position("White",340,30,regular),position("m",300,10,regular),position("ailto",310,60,regular))));
 }
 @Test void boundaryHandlingRetainsMarksRtlRotationAndExistingUnicodeNormalization()throws Exception{
  var font=new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.COURIER);
  assertEquals("é",FullTextSearchIndex.spatialText("é",List.of(position("e",340,6,font),position("\u0301",100,2,font))));
  assertEquals("אב",FullTextSearchIndex.spatialText("אב",List.of(position("א",340,6,font),position("ב",100,6,font))));
  var rotated=new org.apache.pdfbox.text.TextPosition(0,612,792,new org.apache.pdfbox.util.Matrix(0,12,-12,0,100,700),100,706,12,6,4,"b",new int[]{1},font,12,12);
  assertEquals("ab",FullTextSearchIndex.spatialText("ab",List.of(position("a",340,6,font),rotated)));
  assertEquals("office",FullTextSearchIndex.spatialText("office",List.of(position("ofﬁce",40,36,font))));
  assertThrows(java.io.IOException.class,()->FullTextSearchIndex.spatialText("officemailto",List.of(position("ofﬁce",340,36,font),position("mailto",100,50,font))));
 }

}
