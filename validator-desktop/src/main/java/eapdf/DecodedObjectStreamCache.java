package eapdf;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.verapdf.cos.COSDocument;
import org.verapdf.gf.model.GFModelParser;
import org.verapdf.io.Reader;
import org.verapdf.parser.DecodedObjectStreamParser;
import org.verapdf.pdfa.PDFAParser;

/** Bounds reconstructible parser buffers, retaining all COS objects and rules. */
final class DecodedObjectStreamCache extends LinkedHashMap<Long,DecodedObjectStreamParser> {
  private final int maximum;
  long evictions;
  private DecodedObjectStreamCache(int maximum) { super(128,.75f,true);this.maximum=maximum; }
  static void install(PDFAParser parser) throws IOException { install(parser,1024); }
  @SuppressWarnings("unchecked")
  static DecodedObjectStreamCache install(PDFAParser parser,int maximum) throws IOException {
    if(maximum<1)throw new IllegalArgumentException("Positive object-stream cache limit required");
    if(!(parser instanceof GFModelParser green))throw new IOException("Unsupported veraPDF parser cache integration");
    try {
      var readerField=COSDocument.class.getDeclaredField("reader");readerField.setAccessible(true);
      var reader=readerField.get(green.getPDDocument().getDocument());
      if(!(reader instanceof Reader))throw new IOException("Unsupported veraPDF reader cache integration");
      var cacheField=Reader.class.getDeclaredField("objectStreams");cacheField.setAccessible(true);
      var cache=new DecodedObjectStreamCache(maximum);
      cache.putAll((Map<Long,DecodedObjectStreamParser>)cacheField.get(reader));
      cacheField.set(reader,cache);
      return cache;
    } catch(ReflectiveOperationException error) {
      throw new IOException("Cannot install bounded cache for pinned veraPDF Parser 1.31.7",error);
    }
  }
  @Override protected boolean removeEldestEntry(Map.Entry<Long,DecodedObjectStreamParser> entry) {
    if(size()<=maximum)return false;
    try { entry.getValue().closeInputStream(); }
    catch(IOException error) {throw new UncheckedIOException("Cannot close evicted object-stream parser",error);}
    evictions++;return true;
  }
}
