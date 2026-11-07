package eapdf;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.common.filespecification.*;

/** Optional positional index of the final rendered PDFs, before atomic publication. */
final class FullTextSearchIndex {
  static final String NS="https://thinkbox.digital/eapdf/search-index/";
  static final String MARKER="<eapdfindex:fileDigest>";
  static final int MAX_BYTES=128*1024*1024, MAX_COMPRESSED=16*1024*1024, MAX_CHARS=32_000_000;
  static final int MAX_DOCS=100_000, MAX_PAGES=200_000, MAX_TERMS=250_000, MAX_POSTINGS=5_000_000;
  static final Gson JSON=new GsonBuilder().disableHtmlEscaping().create();
  private FullTextSearchIndex() {}

  static void add(Path file) throws Exception {
    var config=EapdfConfig.get();
    if(!config.searchIndex.enabled||!config.searchIndex.fullTextEnabled)return;
    try(var store=new PayloadStore()) {
      Path replacement=store.directory.resolve("indexed.pdf");
      try(var root=load(file)) {
        if(root.getDocumentCatalog().getMetadata()==null)return;
        String xmp=new String(root.getDocumentCatalog().getMetadata().toByteArray(),StandardCharsets.UTF_8);
        String profile=profile(xmp);
        if(!Set.of("m","mi","c","ci").contains(profile))return;
        if(Files.size(file)>512L*1024*1024)throw new IOException("Full-text input byte limit (512 MiB)");
        if(config.signing.enabled)throw new IOException("Full-text byte binding is unavailable for signed output");
        if(xmp.contains("<eapdfindex:fulltext>"))throw new IOException("Existing full-text index must not be replaced implicitly");
        {
          byte[] payload;
          try(var builder=new Builder(store)){builder.visit(file,root,List.of(),0);payload=builder.encode(profile);}
          var zipped=new MetadataSearchIndex.LimitedBytes(MAX_COMPRESSED);
          try(var gzip=new java.util.zip.GZIPOutputStream(zipped)){gzip.write(payload);}
          String extension="<rdf:Description rdf:about=\"\" xmlns:eapdfindex=\""+NS+"\"><eapdfindex:fulltext>"
              +Base64.getEncoder().encodeToString(zipped.toByteArray())+"</eapdfindex:fulltext>"+MARKER+"0".repeat(64)
              +"</eapdfindex:fileDigest></rdf:Description>";
          if(!xmp.contains("</rdf:RDF>"))throw new IOException("Missing catalog RDF");
          // Unfiltered XMP permits a fixed-width digest patch without changing PDF offsets.
          var metadata=new PDMetadata(root);
          metadata.importXMPMetadata(xmp.replace("</rdf:RDF>",extension+"</rdf:RDF>").getBytes(StandardCharsets.UTF_8));
          root.getDocumentCatalog().setMetadata(metadata);
          PdfFontMetadata.save(root,replacement.toFile());
        }
      }
      seal(replacement);
      Files.move(replacement,file,StandardCopyOption.REPLACE_EXISTING);
    } catch(CancellationException error){throw error;}
    catch(Exception error){
      ProcessingLimits.checkCancelled();
      Common.warn(null,ConversionDiagnostic.Code.SEARCH_INDEX_UNAVAILABLE,
          "Full-text search index omitted; PDF.js search remains available: "+error.getMessage());
    }
  }
  static PDDocument load(Path path)throws IOException{return IndexedPdfLoader.load(path,org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());}
  static String profile(String xmp)throws Exception{
    var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
    factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
    var xml=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8)));
    var values=xml.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/id/","conformance");
    return values.getLength()==1?values.item(0).getTextContent():"";
  }
  static String pageGuid(PDPage page)throws IOException{
    COSDictionary node=(COSDictionary)page.getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
    var seen=Collections.newSetFromMap(new IdentityHashMap<COSDictionary,Boolean>());
    while(node!=null){
      if(!seen.add(node)||seen.size()>64)throw new IOException("Ambiguous page ownership");
      if(node.getDictionaryObject(COSName.getPDFName("DPM")) instanceof COSDictionary dpm){
        String guid=dpm.getString(COSName.getPDFName("Mail_GUID"));if(guid!=null)return guid;
      }
      node=node.getDictionaryObject(COSName.PARENT) instanceof COSDictionary p?p:null;
    }
    return "";
  }
  static class Ints {int[] values=new int[16];int size;void add(int value){if(size==values.length)values=Arrays.copyOf(values,size*2);values[size++]=value;}}
  static class Builder implements AutoCloseable {
    final PayloadStore store;
    final List<Object[]> docs=new ArrayList<>(),pages=new ArrayList<>();
    final List<String> texts=new ArrayList<>();final Map<String,Integer> textIds=new HashMap<>();
    final TreeMap<String,Ints> terms=new TreeMap<>();int characters,postings;
    final int budget=(int)Math.min(MAX_BYTES,Math.max(1024*1024,Runtime.getRuntime().maxMemory()/16));
    final Path textSpool;final OutputStream textOutput;
    final long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(30);
    Builder(PayloadStore store)throws IOException{this.store=store;textSpool=Files.createTempFile(store.directory,"text-",".jsonl");textOutput=new BufferedOutputStream(Files.newOutputStream(textSpool));}
    public void close()throws IOException{textOutput.close();}
    void check()throws IOException{ProcessingLimits.checkCancelled();if(System.nanoTime()>deadline)throw new IOException("Full-text generation time limit");}
    void visit(Path path,PDDocument doc,List<List<String>> route,int depth)throws Exception{
      check();if(depth>8||docs.size()>=MAX_DOCS||pages.size()+doc.getNumberOfPages()>MAX_PAGES)throw new IOException("Document/page limit");
      int id=docs.size();docs.add(new Object[]{route,depth==0?"":Common.digestHex(path,"MD5"),depth==0?0:Files.size(path),doc.getNumberOfPages()});
      var pageOutput=new Writer(){
        final StringBuilder value=new StringBuilder();
        public void write(char[] chars,int offset,int length)throws IOException{check();if(value.length()+length>1_000_000)throw new IOException("Page text character limit");value.append(chars,offset,length);}
        public void flush(){} public void close(){}
      };
      int[] extracted={0};
      var stripper=new org.apache.pdfbox.text.PDFTextStripper(){
        @Override protected void writeString(String text,List<org.apache.pdfbox.text.TextPosition> positions)throws IOException{check();super.writeString(text,positions);}
        @Override protected void endPage(PDPage page)throws IOException{
          super.endPage(page);check();
          try{
            while(extracted[0]+1<getCurrentPageNo()){int number=++extracted[0];addPage(id,number,doc.getPage(number-1),"");}
            addPage(id,getCurrentPageNo(),page,normalize(pageOutput.value.toString()));extracted[0]=getCurrentPageNo();
          }
          catch(IOException error){throw error;}catch(Exception error){throw new IOException("Page indexing failed",error);}
          pageOutput.value.setLength(0);
        }
      };
      stripper.setSortByPosition(false);stripper.setLineSeparator("\n");stripper.writeText(doc,pageOutput);
      // PDFTextStripper skips pages without /Contents; their destinations still belong in the inventory.
      while(extracted[0]<doc.getNumberOfPages()){check();int number=++extracted[0];addPage(id,number,doc.getPage(number-1),"");}
      if(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("Collection"))==null)return;
      var tree=doc.getDocumentCatalog().getNames();
      if(tree==null||tree.getEmbeddedFiles()==null)throw new IOException("Missing collection members");
      var names=new TreeMap<String,PDComplexFileSpecification>();collect(tree.getEmbeddedFiles(),names,new HashSet<>());
      var keys=new IdentityHashMap<COSDictionary,List<String>>();var ordered=new ArrayList<PDComplexFileSpecification>();
      for(var item:names.entrySet()){
        var spec=item.getValue();if(spec.getCOSObject().getDictionaryObject(COSName.getPDFName("CI"))==null)continue;
        if(!keys.containsKey(spec.getCOSObject())){keys.put(spec.getCOSObject(),new ArrayList<>());ordered.add(spec);}keys.get(spec.getCOSObject()).add(item.getKey());
      }
      for(var spec:ordered){
        var ef=spec.getEmbeddedFileUnicode();if(ef==null)ef=spec.getEmbeddedFile();
        if(ef==null||!"application/pdf".equals(ef.getSubtype()))throw new IOException("Non-PDF collection member");
        Path child=store.createPdf();
        try(var input=ef.createInputStream();var output=Files.newOutputStream(child)){byte[] buffer=new byte[65536];long size=0;int n;while((n=input.read(buffer))>=0){check();if((size+=n)>512L*1024*1024)throw new IOException("Child byte limit");output.write(buffer,0,n);}}
        var next=new ArrayList<>(route);next.add(List.copyOf(keys.get(spec.getCOSObject())));
        try(var nested=load(child)){visit(child,nested,next,depth+1);}finally{Files.deleteIfExists(child);}
      }
    }
    void addPage(int id,int number,PDPage page,String text)throws Exception{
        String folded=text;
        String textHash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        Integer textId=textIds.get(textHash);
        if(textId==null){
          if(text.length()>1_000_000||(characters+=text.length())>Math.min(MAX_CHARS,budget/4))throw new IOException("Text character limit");
          textId=texts.size();texts.add(textHash);textIds.put(textHash,textId);line(textOutput,"T",text);
          var tokens=Pattern.compile("[^ ]+").matcher(folded);
          while(tokens.find()){
            if(++postings>Math.min(MAX_POSTINGS,budget/16))throw new IOException("Posting limit");
            Ints list=terms.computeIfAbsent(tokens.group(),ignored->new Ints());
            if(terms.size()>Math.min(MAX_TERMS,budget/256))throw new IOException("Term limit");list.add(textId);list.add(tokens.start());
          }
        }
        pages.add(new Object[]{id,number,textId,pageGuid(page)});
    }
    byte[] encode(String profile)throws Exception{
      var body=new MetadataSearchIndex.LimitedBytes(budget-1024);
      for(var doc:docs)line(body,"D",doc);
      for(var page:pages)line(body,"P",page);
      textOutput.flush();try(var input=Files.newInputStream(textSpool)){input.transferTo(body);}
      for(var term:terms.entrySet()){
        Ints list=term.getValue();int[] deltas=Arrays.copyOf(list.values,list.size);int lastText=0,lastPosition=0;
        for(int i=0;i<list.size;i+=2){int text=list.values[i],position=list.values[i+1];deltas[i]=text-lastText;deltas[i+1]=position-(text==lastText?lastPosition:0);lastText=text;lastPosition=position;}
        line(body,"W",new Object[]{term.getKey(),deltas});
      }
      String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()));
      var header=new LinkedHashMap<String,Object>();header.put("format","eapdf-search-index");header.put("version",3);header.put("profile","1"+profile);header.put("engine","pdfbox-nfkd-space-fold-v2");header.put("complete",true);header.put("documents",docs.size());header.put("pages",pages.size());header.put("texts",texts.size());header.put("terms",terms.size());header.put("postings",postings);header.put("sha256",digest);
      var output=new MetadataSearchIndex.LimitedBytes(budget);output.write((JSON.toJson(header)+"\n").getBytes(StandardCharsets.UTF_8));body.writeTo(output);return output.toByteArray();
    }
    void line(OutputStream output,String type,Object value)throws IOException{output.write((JSON.toJson(new Object[]{type,value})+"\n").getBytes(StandardCharsets.UTF_8));}
  }
  static void collect(PDEmbeddedFilesNameTreeNode tree,Map<String,PDComplexFileSpecification> names,Set<COSDictionary> seen)throws IOException{
    if(!seen.add(tree.getCOSObject())||seen.size()>100000)throw new IOException("Invalid name tree");
    if(tree.getNames()!=null)for(var entry:tree.getNames().entrySet())if(names.put(entry.getKey(),entry.getValue())!=null)throw new IOException("Duplicate collection key");
    if(tree.getKids()!=null)for(var child:tree.getKids())collect((PDEmbeddedFilesNameTreeNode)child,names,seen);
  }
  /** Scalar normalization is shared with the viewer, including UTF-16 posting offsets. */
  static String normalize(String text){
    text=text.replaceAll("(\\p{Ll})-\\n(?=\\p{Ll})|((?:\\p{Lu}))-\\n(?=\\p{L})", "$1$2");
    var result=new StringBuilder();boolean space=false;
    for(int offset=0;offset<text.length();){
      int cp=text.codePointAt(offset);offset+=Character.charCount(cp);
      String part=java.text.Normalizer.normalize(new String(Character.toChars(cp)),java.text.Normalizer.Form.NFKD).toLowerCase(Locale.ROOT).replace('ς','σ');
      for(int i=0;i<part.length();i++){
        char c=part.charAt(i);if(c=='\u00ad')continue;
        if(c==' '||(c>='\t'&&c<='\r')||c=='\u0085'||c=='\u00a0'||c=='\u1680'||(c>='\u2000'&&c<='\u200a')||c=='\u2028'||c=='\u2029'||c=='\u202f'||c=='\u205f'||c=='\u3000'){space=result.length()>0;continue;}
        if(space){result.append(' ');space=false;}result.append(c);
      }
    }
    return result.toString();
  }
  static void seal(Path file)throws Exception{
    byte[] marker=MARKER.getBytes(StandardCharsets.US_ASCII);long offset=-1,position=0;int matched=0;
    try(var input=Files.newInputStream(file)){byte[] chunk=new byte[65536];int n;while((n=input.read(chunk))>=0){ProcessingLimits.checkCancelled();for(int i=0;i<n;i++,position++){byte b=chunk[i];if(b==marker[matched])matched++;else matched=b==marker[0]?1:0;if(matched==marker.length){if(offset!=-1)throw new IOException("Ambiguous digest marker");offset=position+1;matched=0;}}}}
    if(offset<0)throw new IOException("Unfiltered digest marker missing");
    // The digest slot is zero-filled in the newly saved PDF; offsets and stream lengths stay fixed.
    String hash=Common.digestHex(file,"SHA-256");
    try(var output=new RandomAccessFile(file.toFile(),"rw")){output.seek(offset);byte[] slot=new byte[64];output.readFully(slot);if(!Arrays.equals(slot,"0".repeat(64).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Digest slot is not empty");output.seek(offset);output.write(hash.getBytes(StandardCharsets.US_ASCII));}
  }
}
