package eapdf;

import com.google.gson.stream.JsonWriter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.*;

/** Optional, bounded XMP extension. Email metadata and collection membership remain authoritative. */
final class MetadataSearchIndex {
  static final int MAX_BYTES = 8 * 1024 * 1024, MAX_MESSAGES = 100_000, MAX_ROW_BYTES = 128 * 1024;
  private MetadataSearchIndex() {}

  static void embed(PDDocument doc, List<PdfCollectionSupport.CollectionMember> members,
      Map<EmailRecord, PDComplexFileSpecification> files, Map<String, PDComplexFileSpecification> names) throws Exception {
    if (!PdfMailWriter.CONFIG.searchIndex.enabled) return;
    try {
      byte[] bytes = encode(doc, members, files, names);
      var compressed = new LimitedBytes(4 * 1024 * 1024);
      try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(bytes); }
      String encoded = Base64.getEncoder().encodeToString(compressed.toByteArray());
      var metadata = doc.getDocumentCatalog().getMetadata();
      if (metadata == null) throw new IOException("Catalog XMP must precede metadata index generation");
      String xmp = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
      String extension = "<rdf:Description rdf:about=\"\" xmlns:eapdfindex=\"https://thinkbox.digital/eapdf/search-index/\">"
          + "<eapdfindex:payload>" + encoded + "</eapdfindex:payload></rdf:Description>";
      if (!xmp.contains("</rdf:RDF>")) throw new IOException("Catalog RDF is unavailable");
      PdfMailWriter.setCompressedMetadata(doc, xmp.replace("</rdf:RDF>", extension + "</rdf:RDF>"));
    } catch (java.util.concurrent.CancellationException error) { throw error; }
    catch (Exception error) {
      if (Thread.currentThread().isInterrupted()) throw error;
      Common.warn(null, ConversionDiagnostic.Code.SEARCH_INDEX_UNAVAILABLE,
          "Metadata search index omitted; the viewer will inspect child PDFs: " + error.getMessage());
    }
  }

  static byte[] encode(PDDocument doc, List<PdfCollectionSupport.CollectionMember> members,
      Map<EmailRecord, PDComplexFileSpecification> files, Map<String, PDComplexFileSpecification> names) throws IOException {
    if (files.size() > MAX_MESSAGES) throw new IOException("message limit exceeded");
    var bySpec = new IdentityHashMap<COSDictionary, EmailRecord>();
    for (var entry : files.entrySet()) bySpec.put(entry.getValue().getCOSObject(), entry.getKey());
    var keys = new IdentityHashMap<COSDictionary, List<String>>();
    var ordered = new LinkedHashMap<COSDictionary, EmailRecord>();
    for (var entry : new TreeMap<>(names).entrySet()) {
      var spec = entry.getValue().getCOSObject();var record = bySpec.get(spec);
      if (record == null) continue;
      ordered.putIfAbsent(spec, record);keys.computeIfAbsent(spec, ignored -> new ArrayList<>()).add(entry.getKey());
    }
    if (ordered.size() != files.size()) throw new IOException("incomplete member mapping");
    if (members.stream().anyMatch(m -> m.records.size() != 1 || !Set.of("s", "si").contains(m.childProfile)))
      throw new IOException("only single-message children are supported in index version 1");
    var folderPaths = folderPaths(doc);
    var guids = new HashSet<String>();
    var payload = new LimitedBytes(MAX_BYTES - 1024);
    for (var entry : ordered.entrySet()) {
      ProcessingLimits.checkCancelled();
      var record = entry.getValue();var spec = entry.getKey();
      if (Common.nz(record.mailGuid).isBlank() || !guids.add(record.mailGuid)) throw new IOException("missing or ambiguous Mail_GUID");
      var ef = new PDComplexFileSpecification(spec).getEmbeddedFile();
      var params = (COSDictionary)ef.getCOSObject().getDictionaryObject(COSName.PARAMS);
      var checksum = (COSString)params.getDictionaryObject(COSName.getPDFName("CheckSum"));
      if (keys.get(spec).size() > 256) throw new IOException("folder membership limit exceeded");
      for (String key : keys.get(spec)) {
        var match = java.util.regex.Pattern.compile("^<(\\d+)>").matcher(key);
        int folder = match.find() ? Integer.parseInt(match.group(1)) : 0;
        String path = folderPaths.get(folder);
        if (path == null) throw new IOException("unresolved collection folder");
      }
      var row = new LimitedBytes(MAX_ROW_BYTES);
      try (var json = new JsonWriter(new OutputStreamWriter(row, StandardCharsets.UTF_8))) {
        json.beginArray();
        json.value(record.mailGuid); // Display/search fields remain authoritative in catalog XMP.
        json.beginArray();for (String key : keys.get(spec)) json.value(key);json.endArray();
        json.value(HexFormat.of().formatHex(checksum.getBytes()));json.value(ef.getSize());
        json.endArray();
      }
      row.writeTo(payload);payload.write('\n');
    }
    String profile = members.isEmpty() || "s".equals(members.get(0).childProfile) ? "1c" : "1ci";
    String hash;
    try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    var output = new LimitedBytes(MAX_BYTES);
    String header = "{\"format\":\"eapdf-metadata-index\",\"version\":2,\"profile\":\"" + profile
        + "\",\"messages\":" + ordered.size() + ",\"sha256\":\"" + hash + "\"}\n";
    output.write(header.getBytes(StandardCharsets.UTF_8));payload.writeTo(output);return output.toByteArray();
  }

  static Map<Integer, String> folderPaths(PDDocument doc) throws IOException {
    var paths = new HashMap<Integer, String>();paths.put(0, "");
    var collection = (COSDictionary)doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("Collection"));
    if (collection == null) throw new IOException("no collection");
    var root = (COSDictionary)collection.getDictionaryObject(COSName.getPDFName("Folders"));
    if (root == null) return paths;
    record Pending(COSDictionary folder, String parent, int depth) {}
    var pending = new ArrayDeque<Pending>();pending.add(new Pending(root, "", 1));
    var seen = Collections.newSetFromMap(new IdentityHashMap<COSDictionary, Boolean>());
    while (!pending.isEmpty()) {
      var next = pending.removeFirst();var folder = next.folder();
      if (next.depth() > 256) throw new IOException("folder depth limit exceeded");
      if (!seen.add(folder)) throw new IOException("cyclic folder tree");
      String name = folder.getString(COSName.NAME, "").replace("\\", "\\\\").replace("/", "\\/");
      String path = next.parent().isEmpty() ? name : next.parent() + "/" + name;
      if (path.length() > 16384) throw new IOException("folder path limit exceeded");
      paths.put(folder.getInt(COSName.getPDFName("ID")), path);
      if (folder.getDictionaryObject(COSName.getPDFName("Child")) instanceof COSDictionary child) pending.add(new Pending(child, path, next.depth() + 1));
      if (folder.getDictionaryObject(COSName.NEXT) instanceof COSDictionary sibling) pending.add(new Pending(sibling, next.parent(), next.depth()));
    }
    return paths;
  }

  static final class LimitedBytes extends ByteArrayOutputStream {
    private final int limit;
    LimitedBytes(int limit) { this.limit = limit; }
    private void check(int size) { if (size > limit - count) throw new IllegalArgumentException("index byte limit exceeded"); }
    @Override public synchronized void write(int b) { check(1);super.write(b); }
    @Override public synchronized void write(byte[] b, int offset, int length) { check(length);super.write(b, offset, length); }
  }
}
