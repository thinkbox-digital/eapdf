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
  static final String SIGNATURE_MARKER="<eapdfindex:signatureContents>";
  static final String EMPTY_SIGNATURE_RANGE="0".repeat(20)+":"+"0".repeat(20);
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
        if(xmp.contains("<eapdfindex:fulltext>"))throw new IOException("Existing full-text index must not be replaced implicitly");
        {
          var zipped=new MetadataSearchIndex.LimitedBytes(MAX_COMPRESSED);
          try(var builder=new Builder(store);var gzip=new java.util.zip.GZIPOutputStream(zipped)){
            builder.visit(file,root,List.of(),0);builder.writeEncoded(profile,gzip);
          }
          String extension="<rdf:Description rdf:about=\"\" xmlns:eapdfindex=\""+NS+"\"><eapdfindex:fulltext>"
              +Base64.getEncoder().encodeToString(zipped.toByteArray())+"</eapdfindex:fulltext>"+MARKER+"0".repeat(64)
              +"</eapdfindex:fileDigest>"+SIGNATURE_MARKER+EMPTY_SIGNATURE_RANGE
              +"</eapdfindex:signatureContents></rdf:Description>";
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
        @Override protected void writeString(String text,List<org.apache.pdfbox.text.TextPosition> positions)throws IOException{check();super.writeString(spatialText(text,positions),positions);}
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
    /** Convenience for small regression fixtures; production writes directly into gzip. */
    byte[] encode(String profile)throws Exception{
      var output=new MetadataSearchIndex.LimitedBytes(budget);writeEncoded(profile,output);return output.toByteArray();
    }
    void writeEncoded(String profile,OutputStream destination)throws Exception{
      Path bodyPath=Files.createTempFile(store.directory,"index-body-",".jsonl");
      var digest=MessageDigest.getInstance("SHA-256");
      try{
        try(var body=new LimitedOutput(new java.security.DigestOutputStream(new BufferedOutputStream(Files.newOutputStream(bodyPath)),digest),budget-1024)){
          for(var doc:docs){check();line(body,"D",doc);}
          for(var page:pages){check();line(body,"P",page);}
          textOutput.flush();copy(textSpool,body);
          var textWriter=new BufferedWriter(new OutputStreamWriter(body,StandardCharsets.UTF_8));
          var writer=new com.google.gson.stream.JsonWriter(textWriter);writer.setStrictness(com.google.gson.Strictness.LENIENT);
          for(var term:terms.entrySet()){
            check();Ints list=term.getValue();int lastText=0,lastPosition=0;
            writer.beginArray().value("W").beginArray().value(term.getKey()).beginArray();
            for(int i=0;i<list.size;i+=2){
              if(i%16384==0)check();int text=list.values[i],position=list.values[i+1];
              writer.value(text-lastText).value(position-(text==lastText?lastPosition:0));lastText=text;lastPosition=position;
            }
            writer.endArray().endArray().endArray();textWriter.write('\n');
          }
          writer.flush();
        }
        var header=new LinkedHashMap<String,Object>();header.put("format","eapdf-search-index");header.put("version",3);header.put("profile","1"+profile);header.put("engine","pdfbox-nfkd-space-fold-v3");header.put("complete",true);header.put("documents",docs.size());header.put("pages",pages.size());header.put("texts",texts.size());header.put("terms",terms.size());header.put("postings",postings);header.put("sha256",HexFormat.of().formatHex(digest.digest()));
        var output=new LimitedOutput(destination,budget);output.write((JSON.toJson(header)+"\n").getBytes(StandardCharsets.UTF_8));copy(bodyPath,output);output.flush();
      }finally{Files.deleteIfExists(bodyPath);}
    }
    void copy(Path path,OutputStream output)throws IOException{
      try(var input=Files.newInputStream(path)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))>=0){check();output.write(buffer,0,n);}}
    }
    void line(OutputStream output,String type,Object value)throws IOException{output.write((JSON.toJson(new Object[]{type,value})+"\n").getBytes(StandardCharsets.UTF_8));}
  }
  /** Enforces a serialized-byte budget without retaining those bytes in memory. */
  static final class LimitedOutput extends FilterOutputStream {
    final long limit;long count;
    LimitedOutput(OutputStream output,long limit){super(output);this.limit=limit;}
    void reserve(int length)throws IOException{if(length<0||count+length>limit)throw new IOException("Search index byte limit");count+=length;}
    @Override public void write(int value)throws IOException{reserve(1);out.write(value);}
    @Override public void write(byte[] bytes,int offset,int length)throws IOException{reserve(length);out.write(bytes,offset,length);}
  }
  static void collect(PDEmbeddedFilesNameTreeNode tree,Map<String,PDComplexFileSpecification> names,Set<COSDictionary> seen)throws IOException{
    if(!seen.add(tree.getCOSObject())||seen.size()>100000)throw new IOException("Invalid name tree");
    if(tree.getNames()!=null)for(var entry:tree.getNames().entrySet())if(names.put(entry.getKey(),entry.getValue())!=null)throw new IOException("Duplicate collection key");
    if(tree.getKids()!=null)for(var child:tree.getKids())collect((PDEmbeddedFilesNameTreeNode)child,names,seen);
  }
  /** Keep PDFBox's Unicode/word normalization, adding only proven spatial boundaries. */
  static String spatialText(String text,List<org.apache.pdfbox.text.TextPosition> positions)throws IOException{
    boolean backward=false;
    for(int i=1;i<positions.size();i++){if(i%4096==0)ProcessingLimits.checkCancelled();if(backward(positions.get(i-1),positions.get(i))){backward=true;break;}}
    if(!backward)return text;
    var raw=new StringBuilder();var runs=new ArrayList<TextRun>();TextRun run=null;
    org.apache.pdfbox.text.TextPosition previous=null;int scanned=0;
    for(var position:positions){
      if(scanned++%4096==0)ProcessingLimits.checkCancelled();
      String value=position.getVisuallyOrderedUnicode();if(value==null)value="";
      int start=raw.length();if((long)start+value.length()>1_000_000)throw new IOException("Page text character limit");raw.append(value);
      if(value.isEmpty())continue;
      if(value.codePoints().anyMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))||!horizontal(position)){
        run=null;previous=null;continue;
      }
      boolean mark=isMark(value.codePointAt(0));
      if(run==null||(!mark&&(previous.getFont()!=position.getFont()||backward(previous,position)
          ||Math.abs(previous.getTextMatrix().getTranslateY()-position.getTextMatrix().getTranslateY())>tolerance(previous,position)))){
        run=new TextRun(position,start,raw.length(),mark);runs.add(run);
      }else run.extend(position,raw.length());
      previous=position;
    }
    // PDFBox can reorder RTL glyphs or expand presentation forms. Never guess a
    // source offset when that transformation obscures an affected boundary.
    if(java.text.Bidi.requiresBidi(raw.toString().toCharArray(),0,raw.length()))return text;
    var cuts=new ArrayList<Integer>();
    for(int i=1;i<runs.size();i++){
      TextRun a=runs.get(i-1),b=runs.get(i);float tolerance=Math.max(a.scale,b.scale)*0.1f;
      if(a.end==b.start&&!b.mark&&Math.abs(a.y-b.y)<=tolerance&&b.right<a.left-tolerance)cuts.add(b.start);
    }
    if(cuts.isEmpty())return text;
    if(!text.contentEquals(raw))throw new IOException("Ambiguous Unicode mapping at a disjoint PDF text run");
    var result=new StringBuilder(text.length()+cuts.size());int from=0;
    for(int cut:cuts){result.append(text,from,cut).append(' ');from=cut;}
    return result.append(text,from,text.length()).toString();
  }
  static boolean isMark(int cp){int type=Character.getType(cp);return type==Character.NON_SPACING_MARK||type==Character.COMBINING_SPACING_MARK||type==Character.ENCLOSING_MARK;}
  static boolean horizontal(org.apache.pdfbox.text.TextPosition p){
    var m=p.getTextMatrix();return m.getScaleX()>0&&m.getShearX()==0&&m.getShearY()==0&&p.getWidth()>0
        &&Float.isFinite(m.getScaleX())&&Float.isFinite(m.getScaleY())&&Float.isFinite(m.getTranslateX())
        &&Float.isFinite(m.getTranslateY())&&Float.isFinite(p.getWidth());
  }
  static float tolerance(org.apache.pdfbox.text.TextPosition a,org.apache.pdfbox.text.TextPosition b){
    return Math.max(Math.max(Math.abs(a.getTextMatrix().getScaleX()),Math.abs(a.getTextMatrix().getScaleY())),
        Math.max(Math.abs(b.getTextMatrix().getScaleX()),Math.abs(b.getTextMatrix().getScaleY())))*0.1f;
  }
  static boolean backward(org.apache.pdfbox.text.TextPosition a,org.apache.pdfbox.text.TextPosition b){
    if(!horizontal(a)||!horizontal(b))return false;float tolerance=tolerance(a,b);
    return Math.abs(a.getTextMatrix().getTranslateY()-b.getTextMatrix().getTranslateY())<=tolerance
        &&b.getTextMatrix().getTranslateX()+b.getWidth()<a.getTextMatrix().getTranslateX()-tolerance;
  }
  static final class TextRun {
    final int start;int end;float left,right;final float y;float scale;final boolean mark;
    TextRun(org.apache.pdfbox.text.TextPosition p,int start,int end,boolean mark){this.start=start;this.end=end;this.mark=mark;
      var m=p.getTextMatrix();left=m.getTranslateX();right=left+p.getWidth();y=m.getTranslateY();scale=Math.max(Math.abs(m.getScaleX()),Math.abs(m.getScaleY()));}
    void extend(org.apache.pdfbox.text.TextPosition p,int end){this.end=end;var m=p.getTextMatrix();left=Math.min(left,m.getTranslateX());
      right=Math.max(right,m.getTranslateX()+p.getWidth());scale=Math.max(scale,Math.max(Math.abs(m.getScaleX()),Math.abs(m.getScaleY())));}
  }
  /** Scalar normalization is shared with the viewer, including UTF-16 posting offsets. */
  static String normalize(String text){
    text=text.replaceAll("(\\p{Ll})-\\n(?=\\p{Ll})|(\\p{Lu})-\\n(?=\\p{L})", "$1$2");
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
  static void verifyUnsignedBinding(Path file)throws Exception{
    long slot=findMarker(file,MARKER),descriptor=findMarker(file,SIGNATURE_MARKER);
    if(slot<0||descriptor<0)throw new IOException("Missing index binding");
    byte[] expected=new byte[64],range=new byte[41];
    try(var input=new RandomAccessFile(file.toFile(),"r")){
      input.seek(slot);input.readFully(expected);input.seek(descriptor);input.readFully(range);
    }
    if(!Arrays.equals(range,EMPTY_SIGNATURE_RANGE.getBytes(StandardCharsets.US_ASCII)))
      throw new IOException("Expected unsigned index binding");
    var hash=MessageDigest.getInstance("SHA-256");long position=0;
    try(var input=Files.newInputStream(file)){
      byte[] buffer=new byte[65536];int n;
      while((n=input.read(buffer))>=0){
        ProcessingLimits.checkCancelled();
        for(long offset=Math.max(position,slot);offset<Math.min(position+n,slot+64);offset++)buffer[(int)(offset-position)]='0';
        hash.update(buffer,0,n);position+=n;
      }
    }
    if(!MessageDigest.isEqual(expected,HexFormat.of().formatHex(hash.digest()).getBytes(StandardCharsets.US_ASCII)))
      throw new IOException("PDF changed after index generation; refusing to reseal stale index");
  }
  /** Patch the binding before CMS creation; only the signature's hex Contents is excluded. */
  static void sealForSigning(Path file,long[] range)throws Exception{
    long descriptor=findMarker(file,SIGNATURE_MARKER);
    if(descriptor<0)return; // Older outputs retain safe viewer fallback after signing.
    long digest=findMarker(file,MARKER),length=Files.size(file);
    if(digest<0||range.length!=4||range[0]!=0||range[1]<=Math.max(digest+64,descriptor+41)
        ||range[2]<=range[1]+2||range[2]-range[1]>1024*1024||range[3]!=length-range[2])
      throw new IOException("Invalid indexed signature ranges");
    try(var output=new RandomAccessFile(file.toFile(),"rw")){
      output.seek(descriptor);byte[] previous=new byte[41];output.readFully(previous);
      if(!Arrays.equals(previous,EMPTY_SIGNATURE_RANGE.getBytes(StandardCharsets.US_ASCII)))
        throw new IOException("Index already has a signature binding");
      output.seek(range[1]);if(output.read()!='<')throw new IOException("Missing signature Contents start");
      output.seek(range[2]-1);if(output.read()!='>')throw new IOException("Missing signature Contents end");
      output.seek(descriptor);output.write(String.format(Locale.ROOT,"%020d:%020d",range[1],range[2]).getBytes(StandardCharsets.US_ASCII));
      output.seek(digest);output.write("0".repeat(64).getBytes(StandardCharsets.US_ASCII));
    }
    var hash=MessageDigest.getInstance("SHA-256");
    try(var input=new PdfSigning.ByteRanges(file,range)){
      byte[] buffer=new byte[65536];int n;
      while((n=input.read(buffer))>=0){ProcessingLimits.checkCancelled();hash.update(buffer,0,n);}
    }
    try(var output=new RandomAccessFile(file.toFile(),"rw")){
      output.seek(digest);output.write(HexFormat.of().formatHex(hash.digest()).getBytes(StandardCharsets.US_ASCII));
    }
  }
  static long findMarker(Path file,String value)throws IOException{
    byte[] marker=value.getBytes(StandardCharsets.US_ASCII);long offset=-1,position=0;int matched=0;
    try(var input=Files.newInputStream(file)){byte[] chunk=new byte[65536];int n;while((n=input.read(chunk))>=0){ProcessingLimits.checkCancelled();for(int i=0;i<n;i++,position++){byte b=chunk[i];if(b==marker[matched])matched++;else matched=b==marker[0]?1:0;if(matched==marker.length){if(offset!=-1)throw new IOException("Ambiguous digest marker");offset=position+1;matched=0;}}}}
    return offset;
  }
  static void seal(Path file)throws Exception{
    long offset=findMarker(file,MARKER);
    if(offset<0)throw new IOException("Unfiltered digest marker missing");
    // The digest slot is zero-filled in the newly saved PDF; offsets and stream lengths stay fixed.
    String hash=Common.digestHex(file,"SHA-256");
    try(var output=new RandomAccessFile(file.toFile(),"rw")){output.seek(offset);byte[] slot=new byte[64];output.readFully(slot);if(!Arrays.equals(slot,"0".repeat(64).getBytes(StandardCharsets.US_ASCII)))throw new IOException("Digest slot is not empty");output.seek(offset);output.write(hash.getBytes(StandardCharsets.US_ASCII));}
  }
}
