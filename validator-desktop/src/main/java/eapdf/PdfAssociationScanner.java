package eapdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.*;
import org.apache.pdfbox.pdfparser.*;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Discover reachable AF entries without retaining unrelated fonts/content graphs. */
final class PdfAssociationScanner extends PDFParser {
  private final Map<Long,Map<COSObjectKey,COSBase>> streams = new LinkedHashMap<>(8,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long,Map<COSObjectKey,COSBase>> entry) { return size()>8; }
  };
  private PdfAssociationScanner(RandomAccessRead source) throws IOException {
    super(source); document.close();
    document = new COSDocument(this) {
      private final Map<COSObjectKey,COSObject> pool = new LinkedHashMap<>(4096,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<COSObjectKey,COSObject> entry) { return size()>4096; }
      };
      @Override public COSObject getObjectFromPool(COSObjectKey key) {
        return pool.computeIfAbsent(key,k->new COSObject(k,PdfAssociationScanner.this));
      }
    };
  }
  private record Reference(COSObjectKey key,List<Object> path) {
    COSBase resolve(PDDocument target) {
      COSBase value=key==null?target.getDocumentCatalog().getCOSObject():target.getDocument().getObjectFromPool(key).getObject();
      for(Object part:path) {
        value=EaPdfChecks.resolve(value);
        if(part instanceof String name&&value instanceof COSDictionary dict)value=dict.getItem(COSName.getPDFName(name));
        else if(part instanceof Integer index&&value instanceof COSArray array&&index<array.size())value=array.get(index);
        else return null;
      }
      return EaPdfChecks.resolve(value);
    }
    Reference child(Object part) { var next=new ArrayList<>(path);next.add(part);return new Reference(key,List.copyOf(next)); }
  }
  private record Pending(COSObjectKey key,long offset) {}
  private record Direct(COSBase value,Reference location) {}

  static Set<COSDictionary> collect(Path path,PDDocument target,int limit) throws IOException {
    List<Reference> found;
    try(var source=new RandomAccessReadBufferedFile(path.toFile())) {
      var parser=new PdfAssociationScanner(source);
      try {
        if(!parser.parsePDFHeader())throw new IOException("Missing PDF header");
        parser.retrieveTrailer();parser.initialParseDone=true;
        found=parser.discover(limit);
      } finally {parser.document.close();}
    }
    Set<COSDictionary> result=EaPdfChecks.identitySet();
    for(var reference:found)if(reference.resolve(target) instanceof COSDictionary spec)result.add(spec);
    return result;
  }
  private List<Reference> discover(int limit) throws IOException {
    var result=new ArrayList<Reference>();
    var seen=new HashSet<COSObjectKey>();
    var pending=new PriorityQueue<Pending>(Comparator.comparingLong(Pending::offset).thenComparingLong(p->p.key().getNumber()));
    var direct=new ArrayDeque<Direct>();
    direct.add(new Direct(document.getTrailer().getItem(COSName.ROOT),new Reference(null,List.of())));
    long containers=0;
    while(!direct.isEmpty()||!pending.isEmpty()) {
      if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Association inspection cancelled");
      if(direct.isEmpty()) {
        var next=pending.remove();
        direct.add(new Direct(document.getObjectFromPool(next.key()).getObject(),new Reference(next.key(),List.of())));
      }
      var local=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
      while(!direct.isEmpty()) {
        var item=direct.removeLast();COSBase value=item.value();
        if(value instanceof COSObject ref) {
          var key=getObjectKey(ref.getObjectNumber(),ref.getGenerationNumber());
          if(seen.add(key))pending.add(new Pending(key,document.getXrefTable().getOrDefault(key,Long.MAX_VALUE)));
          continue;
        }
        if(!(value instanceof COSDictionary||value instanceof COSArray)||!local.add(value))continue;
        if(value instanceof COSDictionary dict) {
          COSBase af=EaPdfChecks.resolve(dict.getItem(COSName.getPDFName("AF")));
          if(af instanceof COSArray array)for(int i=0;i<array.size();i++) {
            COSBase spec=array.get(i);
            if(EaPdfChecks.resolve(spec) instanceof COSDictionary) {
              if(spec instanceof COSObject ref)result.add(new Reference(getObjectKey(ref.getObjectNumber(),ref.getGenerationNumber()),List.of()));
              else result.add(item.location().child("AF").child(i));
            }
          }
          for(var entry:dict.entrySet())push(direct,entry.getValue(),item.location(),entry.getKey().getName());
        } else if(value instanceof COSArray array)for(int i=0;i<array.size();i++)push(direct,array.get(i),item.location(),i);
      }
    }
    return result;
  }
  private static void push(ArrayDeque<Direct> pending,COSBase value,Reference location,Object part) {
    if(value instanceof COSObject)pending.add(new Direct(value,null));
    else if(value instanceof COSDictionary||value instanceof COSArray)pending.add(new Direct(value,location.child(part)));
  }
  private ObjectKeyIndex objectKeys;
  @Override protected COSObjectKey getObjectKey(long number,int generation) {
    if(objectKeys==null)objectKeys=new ObjectKeyIndex();
    return objectKeys.get(document.getXrefTable(),number,generation);
  }
  @Override protected COSBase parseObjectStreamObject(long number,COSObjectKey key) throws IOException {
    var parsed=streams.get(number);
    if(parsed==null) {
      COSBase value=document.getObjectFromPool(getObjectKey(number,0)).getObject();
      if(!(value instanceof COSStream stream))throw new IOException("Invalid PDF object stream");
      var parser=new PDFObjectStreamParser(stream,document) {
        @Override protected COSObjectKey getObjectKey(long object,int generation) { return PdfAssociationScanner.this.getObjectKey(object,generation); }
      };
      parsed=parser.parseAllObjects();streams.put(number,parsed);
    }
    return parsed.get(key);
  }
}
