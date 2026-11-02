package eapdf;
import java.nio.file.*;
import java.util.*;
import com.pff.*;
import com.google.gson.GsonBuilder;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
public class SourceAudit {
 static List<Map<String,Object>> results=new ArrayList<>();
 static List<String> errors=new ArrayList<>();
 static List<String> pdfNativeHashes(String path,String relationship)throws Exception{
  var hashes=new ArrayList<String>();
  try(var doc=org.apache.pdfbox.Loader.loadPDF(new File(path))){
   var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<org.apache.pdfbox.cos.COSDictionary,Boolean>());
   var af=doc.getDocumentCatalog().getCOSObject().getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("AF"));
   var candidates=new ArrayList<org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification>();
   if(!relationship.equals("Data") && af instanceof org.apache.pdfbox.cos.COSArray array)for(int i=0;i<array.size();i++)if(array.getObject(i) instanceof org.apache.pdfbox.cos.COSDictionary d)candidates.add(new org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification(d));
   for(var page:doc.getPages())for(var a:page.getAnnotations())if(a instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment attachment && attachment.getFile() instanceof org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification f)candidates.add(f);
   for(var f:candidates)if(relationship.equals(f.getCOSObject().getNameAsString(org.apache.pdfbox.cos.COSName.getPDFName("AFRelationship"))) && seen.add(f.getCOSObject())){
    var ef=f.getEmbeddedFileUnicode()==null?f.getEmbeddedFile():f.getEmbeddedFileUnicode();try(var in=ef.createInputStream()){hashes.add(hash(in.readAllBytes()));}
   }
  }return hashes;
 }
 static void pdfSources(String path,String pdf)throws Exception{
  List<String> expected=new ArrayList<>();
  if(path.endsWith(".pst"))for(var record:PstReader.readPst(path,"",List.of(),false))expected.add(hash(Common.synthesizeEml(record)));
  else expected.add(hash(Files.readAllBytes(Path.of(path))));
  var actual=pdfNativeHashes(pdf,"Source");Collections.sort(expected);Collections.sort(actual);check(expected.equals(actual),pdf+": retained sources differ from original/export");
  results.add(Map.of("pdf",pdf,"exact_sources_checked",expected.size()));
 }

 static String hash(byte[] b)throws Exception{return Common.sha256Hex(b);}
 static void check(boolean b,String s){if(!b)errors.add(s);}
 static void pst(PSTFolder folder,List<PSTMessage> out)throws Exception{
  if(folder.getContentCount()>0){PSTObject child;while((child=folder.getNextChild())!=null){if(child instanceof PSTMessage m)out.add(m);}}
  if(folder.hasSubfolders())for(PSTFolder f:folder.getSubFolders())pst(f,out);
 }
 static String text(String s){return Common.nz(s).replace("\r\n","\n").replace('\r','\n').replace("\0","");}
 static void auditPst(String path)throws Exception{
  var records=PstReader.readPst(path,"",List.of(),false);var raw=new ArrayList<PSTMessage>();
  PSTFile source=new PSTFile(path);try{
   pst(source.getRootFolder(),raw);check(raw.size()==records.size(),path+": message count");int attachments=0;
   for(int i=0;i<raw.size();i++){
    var m=raw.get(i);var r=records.get(i);String id=path+" #"+i+" "+r.subject;
    check(text(m.getSubject()).equals(r.subject),id+": subject changed");
    String body=text(m.getBody());if(!body.isBlank())check(body.equals(r.textBody),id+": plain body changed");
    var files=r.associatedFiles.stream().filter(f->!"Source".equals(f.afRelationship)).toList();
    check(files.size()==m.getNumberOfAttachments(),id+": attachment count");
    byte[] exported=Common.synthesizeEml(r);var parsed=new MimeMessage(EmlReader.mimeSession(exported),new ByteArrayInputStream(exported));
    check(Common.nz(parsed.getSubject()).equals(r.subject),id+": exported subject changed");
    List<byte[]> exportedFiles=new ArrayList<>();collect(parsed,exportedFiles,false);
    for(int a=0;a<m.getNumberOfAttachments();a++){
     var nativeFile=m.getAttachment(a);var f=files.get(a);attachments++;
     if(nativeFile.getAttachMethod()==PSTAttachment.ATTACHMENT_METHOD_BY_VALUE || nativeFile.getAttachMethod()==PSTAttachment.ATTACHMENT_METHOD_OLE){
      byte[] bytes;try(var in=nativeFile.getFileInputStream()){bytes=in.readAllBytes();}
      check(Arrays.equals(bytes,f.data()),id+": native attachment changed "+f.filename);
      int match=-1;for(int n=0;n<exportedFiles.size();n++)if(Arrays.equals(exportedFiles.get(n),bytes)){match=n;break;}
      check(match>=0,id+": attachment missing from exported EML "+f.filename);if(match>=0)exportedFiles.remove(match);
     }
    }
   }
   results.add(Map.of("input",path,"messages",records.size(),"attachments",attachments));
  }finally{source.close();}
 }
 static void collect(Part p,List<byte[]> files,boolean nested)throws Exception{
  if(p.isMimeType("multipart/*")){var multi=(Multipart)p.getContent();for(int i=0;i<multi.getCount();i++)collect(multi.getBodyPart(i),files,true);return;}
  if(nested && (!EmlReader.safeFileName(p).isBlank() || Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || !Common.header1(p,"Content-ID").isBlank()))try(var in=p.getInputStream()){files.add(in.readAllBytes());}
 }
 static void auditEml(String path)throws Exception{
  byte[] bytes=Files.readAllBytes(Path.of(path));var r=EmlReader.readEml(path,"",List.of(),false);
  check(Arrays.equals(bytes,r.sourceBytes),path+": original source changed");
  var parsed=new MimeMessage(EmlReader.mimeSession(bytes),new ByteArrayInputStream(bytes));
  List<byte[]> files=new ArrayList<>();collect(parsed,files,false);
  var retained=new ArrayList<>(r.associatedFiles.stream().filter(f->!"Source".equals(f.afRelationship)).toList());
  for(byte[] data:files){int match=-1;for(int i=0;i<retained.size();i++)if(Arrays.equals(data,retained.get(i).data())){match=i;break;}
   check(match>=0,path+": native MIME part missing "+hash(data));if(match>=0)retained.remove(match);
  }
  results.add(Map.of("input",path,"messages",1,"native_parts_checked",files.size(),"source_sha256",hash(bytes)));
 }
 static void auditMsg(String path)throws Exception{
  var r=MsgReader.readMsg(path,"",List.of(),false);byte[] bytes=Files.readAllBytes(Path.of(path));
  check(Arrays.equals(bytes,r.sourceBytes),path+": original MSG changed");
  var nativeMsg=new org.simplejavamail.outlookmessageparser.OutlookMessageParser().parseMsg(new File(path));
  List<byte[]> files=new ArrayList<>();for(var f:nativeMsg.fetchTrueAttachments())if(f.getData()!=null)files.add(f.getData());
  for(var f:nativeMsg.fetchCIDMap().values())if(f.getData()!=null && files.stream().noneMatch(x->Arrays.equals(x,f.getData())))files.add(f.getData());
  for(byte[] data:files)check(r.associatedFiles.stream().anyMatch(f->Arrays.equals(data,f.bytes)),path+": native MSG attachment missing");
  results.add(Map.of("input",path,"messages",1,"native_parts_checked",files.size(),"source_sha256",hash(bytes)));
 }
 public static void main(String[] args)throws Exception{
  auditEml("in/email/test3.eml");auditEml("dist/demo/inputs/Weekly project review.eml");
  auditMsg("in/email/MSGReader-master/HtmlSampleEmailWithAttachment.msg");
  auditMsg("in/email/MSGReader-master/2019.10.10a additional comments and questions for DEP.msg");
  auditPst("src/test/resources/eapdf/email/attached-message.pst");auditPst("in/email/test.pst");
  pdfSources("in/email/test3.eml","examples/sample_1s.pdf");
  pdfSources("dist/demo/inputs/Weekly project review.eml","dist/demo/Weekly project review.pdf");
  pdfSources("in/email/MSGReader-master/HtmlSampleEmailWithAttachment.msg","output/creator-preview/msg/HtmlSampleEmailWithAttachment_000001.pdf");
  pdfSources("in/email/test.pst","examples/sample_1m.pdf");
  List<String> expected=new ArrayList<>();for(var record:PstReader.readPst("in/email/test.pst","",List.of(),false))for(var f:record.associatedFiles)if(f.emailAttachment)expected.add(hash(f.data()));
  var actual=pdfNativeHashes("examples/sample_1m.pdf","Data");Collections.sort(expected);Collections.sort(actual);System.out.println("Native count expected="+expected.size()+" actual="+actual.size());var missing=new ArrayList<>(expected);for(var h:actual)missing.remove(h);var extra=new ArrayList<>(actual);for(var h:expected)extra.remove(h);System.out.println("Missing hashes="+missing+" extra="+extra);check(expected.equals(actual),"Large PST PDF native attachment contents/occurrences differ");
  results.add(Map.of("pdf","examples/sample_1m.pdf","native_attachments_checked",expected.size()));
  var out=Map.of("inputs",results,"errors",errors);String json=new GsonBuilder().setPrettyPrinting().create().toJson(out);
  Files.writeString(Path.of(args[0]),json+"\n");System.out.println("Source/PDF checks: "+results.size()+"; errors: "+errors);if(!errors.isEmpty())System.exit(1);
 }
}
