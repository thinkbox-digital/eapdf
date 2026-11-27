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
  private static final COSName AF = COSName.getPDFName("AF");
  private long parsedStreams, evictedStreams;
  private final Map<Long,Map<COSObjectKey,COSBase>> streams = new LinkedHashMap<>(8,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long,Map<COSObjectKey,COSBase>> entry) {
      if (size() <= 8) return false;
      evictedStreams++;
      return true;
    }
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
  private record Step(Step parent, Object part) {}
  private record Reference(COSObjectKey key, Step path) {
    COSBase resolve(PDDocument target) {
      COSBase value=key==null?target.getDocumentCatalog().getCOSObject():target.getDocument().getObjectFromPool(key).getObject();
      Deque<Object> parts = new ArrayDeque<>();
      for (Step step = path; step != null; step = step.parent()) parts.push(step.part());
      for(Object part:parts) {
        value=EaPdfChecks.resolve(value);
        if(part instanceof COSName name&&value instanceof COSDictionary dict)value=dict.getItem(name);
        else if(part instanceof Integer index&&value instanceof COSArray array&&index<array.size())value=array.get(index);
        else return null;
      }
      return EaPdfChecks.resolve(value);
    }
    Reference child(Object part) { return new Reference(key, new Step(path, part)); }
  }
  private record Direct(COSBase value,Reference location) {}

  static Set<COSDictionary> collect(Path path,PDDocument target,int limit) throws IOException {
    return collect(path, target, limit, null);
  }

  static Set<COSDictionary> collect(Path path, PDDocument target, int limit, ValidationSession.CountedProgress progress)
      throws IOException {
    Set<Reference> found;
    try(var source=new RandomAccessReadBufferedFile(path.toFile())) {
      var parser=new PdfAssociationScanner(source);
      try {
        if(!parser.parsePDFHeader())throw new IOException("Missing PDF header");
        parser.retrieveTrailer();parser.initialParseDone=true;
        found=parser.discover(progress);
      } finally {parser.document.close();}
    }
    Set<COSDictionary> result=EaPdfChecks.identitySet();
    for(var reference:found)if(reference.resolve(target) instanceof COSDictionary spec)result.add(spec);
    return result;
  }
  private Set<Reference> discover(ValidationSession.CountedProgress progress) throws IOException {
    var result=new LinkedHashSet<Reference>();
    var seen=new HashSet<COSObjectKey>();
    // Compressed members use the physical offset of their owning stream. Drain
    // each group together, including members discovered while parsing the group.
    var pending=new TreeMap<Long, ArrayDeque<COSObjectKey>>();
    var direct=new ArrayDeque<Direct>();
    direct.add(new Direct(document.getTrailer().getItem(COSName.ROOT),new Reference(null,null)));
    long visited=0;
    Long groupOffset = null;
    while(!direct.isEmpty()||!pending.isEmpty()) {
      if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Association inspection cancelled");
      if(direct.isEmpty()) {
        if (groupOffset == null || pending.get(groupOffset).isEmpty()) {
          if (groupOffset != null) pending.remove(groupOffset);
          if (pending.isEmpty()) break;
          groupOffset = pending.firstKey();
        }
        var key = pending.get(groupOffset).removeFirst();
        direct.add(new Direct(document.getObjectFromPool(key).getObject(),new Reference(key,null)));
        if (progress != null && (++visited & 1023) == 0) progress.advance(visited);
      }
      var local=Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>(4));
      while(!direct.isEmpty()) {
        var item=direct.removeLast();COSBase value=item.value();
        if(value instanceof COSObject ref) {
          var key=getObjectKey(ref.getObjectNumber(),ref.getGenerationNumber());
          if(seen.add(key)) pending.computeIfAbsent(physicalOffset(key), ignored -> new ArrayDeque<>()).add(key);
          continue;
        }
        if(!(value instanceof COSDictionary||value instanceof COSArray)||!local.add(value))continue;
        if(value instanceof COSDictionary dict) {
          COSBase af=EaPdfChecks.resolve(dict.getItem(AF));
          if(af instanceof COSArray array)for(int i=0;i<array.size();i++) {
            COSBase spec=array.get(i);
            // The normal walk discovers these references in stream order.
            // Check the type when resolving in the target, after discovery.
            if(spec instanceof COSObject ref) result.add(new Reference(getObjectKey(ref.getObjectNumber(),ref.getGenerationNumber()),null));
            else if(spec instanceof COSDictionary) result.add(item.location().child(AF).child(i));
          }
          for(var entry:dict.entrySet())push(direct,entry.getValue(),item.location(),entry.getKey());
        } else if(value instanceof COSArray array)for(int i=0;i<array.size();i++)push(direct,array.get(i),item.location(),i);
      }
    }
    if (progress != null) {
      progress.finish(visited);
      System.out.println("[validation] Association discovery parsed " + parsedStreams
          + " object streams, with " + evictedStreams + " cache evictions.");
    }
    return result;
  }
  private long physicalOffset(COSObjectKey key) {
    Map<COSObjectKey, Long> xref = document.getXrefTable();
    long offset = xref.getOrDefault(key, Long.MAX_VALUE);
    if (offset >= 0 || offset == Long.MIN_VALUE) return offset;
    long ownerOffset = xref.getOrDefault(getObjectKey(-offset, 0), Long.MAX_VALUE);
    return ownerOffset >= 0 ? ownerOffset : Long.MAX_VALUE;
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
      parsed=parser.parseAllObjects();
      parsedStreams++;
      streams.put(number,parsed);
    }
    return parsed.get(key);
  }
}
