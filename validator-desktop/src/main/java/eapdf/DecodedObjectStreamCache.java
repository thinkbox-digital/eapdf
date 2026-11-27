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
  private static final long DEFAULT_BYTES = 256L * 1024 * 1024;
  private final int maximum;
  private final long byteBudget;
  private long bytes;
  private long peakBytes;
  long evictions;
  private DecodedObjectStreamCache(int maximum, long byteBudget) {
    super(128, .75f, true);
    this.maximum = maximum;
    this.byteBudget = byteBudget;
  }
  static DecodedObjectStreamCache install(PDFAParser parser) throws IOException { return install(parser, 1024); }
  static DecodedObjectStreamCache install(PDFAParser parser, int maximum) throws IOException {
    return install(parser, maximum, DEFAULT_BYTES);
  }
  @SuppressWarnings("unchecked")
  static DecodedObjectStreamCache install(PDFAParser parser, int maximum, long byteBudget) throws IOException {
    if(maximum<1 || byteBudget<1)throw new IllegalArgumentException("Positive object-stream cache limits required");
    if(!(parser instanceof GFModelParser green))throw new IOException("Unsupported veraPDF parser cache integration");
    try {
      var readerField=COSDocument.class.getDeclaredField("reader");readerField.setAccessible(true);
      var reader=readerField.get(green.getPDDocument().getDocument());
      if(!(reader instanceof Reader))throw new IOException("Unsupported veraPDF reader cache integration");
      var cacheField=Reader.class.getDeclaredField("objectStreams");cacheField.setAccessible(true);
      var cache=new DecodedObjectStreamCache(maximum, byteBudget);
      var previous = (Map<Long,DecodedObjectStreamParser>)cacheField.get(reader);
      previous.forEach(cache::put);
      cacheField.set(reader,cache);
      return cache;
    } catch(ReflectiveOperationException error) {
      throw new IOException("Cannot install bounded cache for pinned veraPDF Parser 1.31.72",error);
    }
  }
  @Override public DecodedObjectStreamParser put(Long key, DecodedObjectStreamParser parser) {
    try {
      long length = parser.getSource().getStreamLength();
      var previous = super.put(key, parser);
      if (previous == parser) return previous;
      if (previous != null) release(previous);
      bytes += length;
      // Reader uses the just-inserted parser immediately. A single stream can exceed
      // the budget, so leave that parser open until another one replaces it.
      while (size() > 1 && (size() > maximum || bytes > byteBudget)) {
        release(pollFirstEntry().getValue());
        evictions++;
      }
      peakBytes = Math.max(peakBytes, bytes);
      return previous;
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  private void release(DecodedObjectStreamParser parser) throws IOException {
    bytes -= parser.getSource().getStreamLength();
    parser.closeInputStream();
  }

  long peakBytes() { return peakBytes; }
}
