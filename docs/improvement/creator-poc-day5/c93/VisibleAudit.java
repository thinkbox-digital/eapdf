package eapdf;
import java.nio.file.*;import java.util.*;import org.apache.pdfbox.Loader;import org.apache.pdfbox.cos.*;import org.apache.pdfbox.text.PDFTextStripper;import com.google.gson.GsonBuilder;
public class VisibleAudit {
 public static void main(String[] args)throws Exception {
  var records=PstReader.readPst("in/email/test.pst","",List.of(),false);
  Map<String,StringBuilder> groups=new LinkedHashMap<>();
  try(var doc=Loader.loadPDF(Path.of("examples/sample_1m.pdf").toFile())){
   var stripper=new PDFTextStripper();stripper.setSortByPosition(true);
   for(int i=0;i<doc.getNumberOfPages();i++){
    var part=(COSDictionary)doc.getPage(i).getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
    String guid=PdfCreationValidator.messageIdentifier(part);if(guid.isBlank())continue;
    stripper.setStartPage(i+1);stripper.setEndPage(i+1);groups.computeIfAbsent(guid,k->new StringBuilder()).append(stripper.getText(doc));
   }
  }
  var rendered=new ArrayList<>(groups.values());var result=new ArrayList<Map<String,Object>>();
  for(int i=0;i<records.size();i++){
   var r=records.get(i);String expected=org.jsoup.Jsoup.parse(r.htmlBody).body().text();String actual=rendered.get(i).toString();
   result.add(Map.of("index",i,"subject",r.subject,"expected",expected,"actual",actual));
  }
  Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(result));
 }
}
