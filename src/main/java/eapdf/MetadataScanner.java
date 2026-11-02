package eapdf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.*;
import org.apache.pdfbox.pdfparser.*;

/** Read-only metadata enumeration. Indirect references are visited by xref key,
 * not followed into a retained document graph. Includes unreachable objects. */
final class MetadataScanner extends PDFParser {
  private final Set<COSObjectKey> checkedMetadata = new HashSet<>();
  private final Map<Long,Map<COSObjectKey,COSBase>> objectStreams = new LinkedHashMap<>(8,.75f,true) {
    @Override protected boolean removeEldestEntry(Map.Entry<Long,Map<COSObjectKey,COSBase>> entry) { return size()>8; }
  };
  private MetadataScanner(RandomAccessRead source) throws IOException {
    super(source);
    document.close();
    document = new COSDocument(this) {
      private final Map<COSObjectKey,COSObject> pool = new LinkedHashMap<>(4096,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<COSObjectKey,COSObject> entry) { return size()>4096; }
      };
      @Override public COSObject getObjectFromPool(COSObjectKey key) {
        return pool.computeIfAbsent(key, k -> new COSObject(k, MetadataScanner.this));
      }
    };
  }
  static void validate(Path path, boolean progress) throws Exception {
    try (var source = new RandomAccessReadBufferedFile(path.toFile())) {
      var scanner = new MetadataScanner(source);
      try {
        if (!scanner.parsePDFHeader()) throw new IOException("Missing PDF header");
        scanner.retrieveTrailer(); scanner.initialParseDone = true;
        if (progress) ConversionProgress.beginUnknown("checking_metadata_objects", "objects");
        scanner.direct(scanner.document.getTrailer(), null);
        var xref = scanner.document.getXrefTable();
        var ordered = new ArrayList<>(xref.entrySet());
        // Keep members of each compressed object stream adjacent. Xref hash order
        // otherwise defeats the bounded cache and repeatedly decompresses a stream.
        ordered.sort(Comparator.<Map.Entry<COSObjectKey,Long>>comparingLong(Map.Entry::getValue)
            .thenComparingLong(entry -> entry.getKey().getNumber()));
        for (var entry : ordered) {
          var key = entry.getKey();
          ProcessingLimits.checkCancelled();
          scanner.direct(scanner.document.getObjectFromPool(key).getObject(), key);
          if (progress) ConversionProgress.advance("checking_metadata_objects");
        }
      } finally { scanner.document.close(); }
    }
  }
  private void direct(COSBase root, COSObjectKey key) throws Exception {
    if (root == null) return;
    var pending = new ArrayDeque<COSBase>(); pending.add(root);
    var seen = Collections.newSetFromMap(new IdentityHashMap<COSBase,Boolean>());
    while (!pending.isEmpty()) {
      COSBase value = pending.removeFirst();
      if (value instanceof COSObject || !seen.add(value)) continue;
      if (value instanceof COSDictionary dictionary) {
        COSBase reference = dictionary.getItem(COSName.METADATA);
        COSObjectKey metadataKey = reference instanceof COSObject object
            ? new COSObjectKey(object.getObjectNumber(), object.getGenerationNumber()) : null;
        if (reference != null && (metadataKey == null || !checkedMetadata.contains(metadataKey))) {
          COSBase metadata = reference instanceof COSObject object ? object.getObject() : reference;
          if (metadata instanceof COSStream stream) {
            check(stream);
            if (metadataKey != null) checkedMetadata.add(metadataKey);
          }
        }
        if (dictionary instanceof COSStream stream && COSName.METADATA.equals(stream.getCOSName(COSName.TYPE))
            && (key == null || !checkedMetadata.contains(key))) {
          check(stream);
          if (key != null) checkedMetadata.add(key);
        }
        for (COSBase item : dictionary.getValues()) if (item != null && !(item instanceof COSObject)) pending.add(item);
      } else if (value instanceof COSArray array) {
        for (COSBase item : array) if (item != null && !(item instanceof COSObject)) pending.add(item);
      }
    }
  }
  private static void check(COSStream stream) throws Exception {
    try (var input = stream.createInputStream()) { PdfCreationValidator.validateUtf8(input); }
  }
  private ObjectKeyIndex objectKeys;
  @Override protected COSObjectKey getObjectKey(long number,int generation) {
    if(objectKeys==null)objectKeys=new ObjectKeyIndex();
    return objectKeys.get(document.getXrefTable(),number,generation);
  }
  @Override protected COSBase parseObjectStreamObject(long number, COSObjectKey key) throws IOException {
    Map<COSObjectKey,COSBase> parsed = objectStreams.get(number);
    if (parsed == null) {
      COSBase stream = document.getObjectFromPool(getObjectKey(number,0)).getObject();
      if (!(stream instanceof COSStream contents)) throw new IOException("Invalid PDF object stream");
      var parser = new PDFObjectStreamParser(contents, document) {
        @Override protected COSObjectKey getObjectKey(long object, int generation) { return MetadataScanner.this.getObjectKey(object,generation); }
      };
      parsed = parser.parseAllObjects(); objectStreams.put(number,parsed);
    }
    return parsed.get(key);
  }
}
