package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MetadataSearchIndexTests {
  @TempDir Path temporary;
  static byte[] decode(PDDocument doc) throws Exception {
    String xml=TestSupport.metadataXml(doc),open="<eapdfindex:payload>",close="</eapdfindex:payload>";
    int start=xml.indexOf(open);assertTrue(start>=0,"index must be present");
    byte[] zipped=Base64.getDecoder().decode(xml.substring(start+open.length(),xml.indexOf(close,start)));
    try(var input=new GZIPInputStream(new ByteArrayInputStream(zipped))){return input.readAllBytes();}
  }
  @Test void realCollectionsHaveAnIndexWithoutAddingFilesOrChangingSources() throws Exception {
    for(String profile:List.of("c","ci")){
      var record=EmlReader.readEml(Path.of("in/sample.eml").toString(),"",List.of(),false);
      record.folderMemberships.addAll(List.of("Inbox","Projects/日本語"));
      byte[] original=Files.readAllBytes(Path.of("in/sample.eml"));
      Path output=temporary.resolve("eml_sample_1"+profile+".pdf");
      PdfMailWriter.writeEaPdfCollection(output,List.of(record),TestSupport.fontPath(),TestSupport.iccPath(),false,temporary,profile);
      try(var doc=Loader.loadPDF(output.toFile())){
        byte[] bytes=decode(doc);int boundary=0;while(bytes[boundary]!=10)boundary++;
        var header=JsonParser.parseString(new String(bytes,0,boundary,StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("1"+profile,header.get("profile").getAsString());assertEquals(1,header.get("messages").getAsInt());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Arrays.copyOfRange(bytes,boundary+1,bytes.length))),header.get("sha256").getAsString());
        var row=JsonParser.parseString(new String(bytes,boundary+1,bytes.length-boundary-1,StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(record.mailGuid,row.get(0).getAsString());assertEquals(2,row.get(1).getAsJsonArray().size());assertEquals(4,row.size());
        assertTrue(TestSupport.embeddedFileNames(doc).stream().noneMatch(n->n.contains("metadata-index")));
        var files=AttachmentProfilePolicyTests.files(doc);var unique=Collections.newSetFromMap(new IdentityHashMap<COSDictionary,Boolean>());
        int children=0;
        for(var file:files.values())if(unique.add(file.getCOSObject())&&file.getFilename().endsWith(".pdf")){
          children++;try(var child=Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))){assertFalse(TestSupport.metadataXml(child).contains("eapdfindex:payload"));}
        }
        assertEquals(1,children);
      }
      assertArrayEquals(original,Files.readAllBytes(Path.of("in/sample.eml")));
    }
  }
  static record Fixture(PDDocument doc,EmailRecord record,List<PdfMailWriter.CollectionMember> members,
      Map<EmailRecord,PDComplexFileSpecification> files,Map<String,PDComplexFileSpecification> names) implements AutoCloseable {
    public void close() throws IOException{doc.close();}
    byte[] encode() throws IOException{return MetadataSearchIndex.encode(doc,members,files,names);}
  }
  static Fixture fixture() throws Exception {
    var doc=new PDDocument();PdfCollectionSupport.addMessageCollectionDictionary(doc);
    var record=new EmailRecord();record.mailGuid="stable-guid";record.messageId="<same@example.org>";record.subject="日本語 — café";
    record.rawFrom="Sender <sender@example.org>";record.rawTo="to@example.org";record.rawCc="cc@example.org";record.rawBcc="bcc@example.org";record.sentXmp="2026-11-07T00:00:00Z";
    var file=new PDComplexFileSpecification();file.setFile("child.pdf");file.setFileUnicode("child.pdf");
    byte[] child="fixture bytes".getBytes(StandardCharsets.UTF_8);
    var stream=new PDEmbeddedFile(doc,new ByteArrayInputStream(child));stream.setSize(child.length);PdfMailWriter.setEmbeddedChecksum(stream,Common.md5Hex(child));file.setEmbeddedFile(stream);
    var member=new PdfMailWriter.CollectionMember("",record.subject,"s",List.of(record),0,1,"child.pdf",child,0,record.sentXmp);
    return new Fixture(doc,record,List.of(member),Map.of(record,file),Map.of("child.pdf",file));
  }
  @Test void encodingIsDeterministicAndPreservesUnicodeAndAllRecipients() throws Exception {
    try(var f=fixture()){
      assertArrayEquals(f.encode(),f.encode());String value=new String(f.encode(),StandardCharsets.UTF_8);
      assertTrue(value.contains("stable-guid"));assertFalse(value.contains("日本語"));assertFalse(value.contains("bcc@example.org"));
      assertTrue(value.contains("\"version\":2"));
    }
  }
  @Test void missingIdentityAndOversizedRowsAreRejectedBeforeEmbedding() throws Exception {
    try(var f=fixture()){
      f.record.mailGuid="";assertThrows(IOException.class,f::encode);f.record.mailGuid="stable";
      f.record.subject="x".repeat(MetadataSearchIndex.MAX_ROW_BYTES);assertTrue(f.encode().length<1024,"metadata must not be duplicated in the compact index");
      f.record.mailGuid="x".repeat(MetadataSearchIndex.MAX_ROW_BYTES);assertThrows(IllegalArgumentException.class,f::encode);
      MetadataSearchIndex.embed(f.doc,f.members,f.files,f.names);
      assertNull(f.doc.getDocumentCatalog().getMetadata(),"failed index must not create partial XMP");
    }
  }
  @Test void configurationCanDisableTheOptionalIndex() throws Exception {
    boolean previous=PdfMailWriter.CONFIG.searchIndex.enabled;
    try(var f=fixture()){
      PdfMailWriter.CONFIG.searchIndex.enabled=false;MetadataSearchIndex.embed(f.doc,f.members,f.files,f.names);assertNull(f.doc.getDocumentCatalog().getMetadata());
    }finally{PdfMailWriter.CONFIG.searchIndex.enabled=previous;}
  }
  @Test void duplicateGuidsAndUnsupportedChildProfilesFailClosed() throws Exception {
    try(var f=fixture()){
      var duplicate=new EmailRecord();duplicate.mailGuid=f.record.mailGuid;
      var file=new PDComplexFileSpecification(new COSDictionary(f.files.get(f.record).getCOSObject()));
      assertThrows(IOException.class,()->MetadataSearchIndex.encode(f.doc,f.members,Map.of(f.record,f.files.get(f.record),duplicate,file),Map.of("child.pdf",f.files.get(f.record),"other.pdf",file)));
      var nested=new PdfMailWriter.CollectionMember("","Nested","c",List.of(f.record),0,1,"child.pdf",new byte[0],0,"");
      assertThrows(IOException.class,()->MetadataSearchIndex.encode(f.doc,List.of(nested),f.files,f.names));
    }
  }
}
