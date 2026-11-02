package eapdf;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.multipdf.PDFCloneUtility;
import org.apache.pdfbox.pdmodel.PDDocument;

/** Share only byte-identical rendered font resources, including their encodings and glyph maps. */
final class ArchiveResourcePool {
  private final Map<String, COSDictionary> fonts = new LinkedHashMap<>(256, .75f, true);
  long reusedFonts;

  PDFCloneUtility cloner(PDDocument target) {
    return new PDFCloneUtility(target) {
      private final Map<COSDictionary, String> fingerprints = new IdentityHashMap<>();
      private final Map<COSDictionary, COSDictionary> copied = new IdentityHashMap<>();
      private COSDictionary dictionary(COSDictionary original) throws IOException {
        COSDictionary existing = copied.get(original);
        if (existing != null) return existing;
        COSDictionary copy = new CompactPdfDictionary(original.size());
        copied.put(original, copy);
        copied.put(copy, copy);
        for (var entry : original.entrySet()) copy.setItem(entry.getKey(), cloneForNewDocument(entry.getValue()));
        return copy;
      }
      @Override @SuppressWarnings("unchecked")
      public <T extends COSBase> T cloneForNewDocument(T value) throws IOException {
        if (!(value instanceof COSDictionary dictionary) || value instanceof COSStream)
          return super.cloneForNewDocument(value);
        COSDictionary known = copied.get(dictionary);
        if (known != null) return (T)known;
        if (!"Font".equals(dictionary.getNameAsString(COSName.TYPE))) return (T)dictionary(dictionary);
        String key;
        if (fingerprints.containsKey(dictionary)) key = fingerprints.get(dictionary);
        else { key = fingerprint(dictionary); fingerprints.put(dictionary, key); }
        if (key == null) return (T)dictionary(dictionary);
        COSDictionary existing = fonts.get(key);
        if (existing != null) { reusedFonts++; copied.put(dictionary, existing); return (T)existing; }
        T copy = (T)dictionary(dictionary);
        if (fonts.size() >= 4096) fonts.remove(fonts.keySet().iterator().next());
        fonts.put(key, (COSDictionary)copy);
        return copy;
      }
    };
  }

  private static String fingerprint(COSBase value) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
        Set<COSBase> active = Collections.newSetFromMap(new IdentityHashMap<>());
        if (!write(value, output, active, 0)) return null;
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
  }

  private static void bytes(DataOutputStream output, byte[] bytes) throws IOException {
    output.writeInt(bytes.length); output.write(bytes);
  }

  private static boolean write(COSBase value, DataOutputStream out, Set<COSBase> active, int depth) throws IOException {
    if (value instanceof COSObject reference) value = reference.getObject();
    if (depth > 64 || value != null && !active.add(value)) return false;
    try {
      if (value == null || value instanceof COSNull) out.writeByte(0);
      else if (value instanceof COSName name) { out.writeByte(1); bytes(out, name.getName().getBytes(StandardCharsets.UTF_8)); }
      else if (value instanceof COSString string) { out.writeByte(2); bytes(out, string.getBytes()); }
      else if (value instanceof COSInteger number) { out.writeByte(3); out.writeLong(number.longValue()); }
      else if (value instanceof COSFloat number) { out.writeByte(4); out.writeFloat(number.floatValue()); }
      else if (value instanceof COSBoolean bool) { out.writeByte(5); out.writeBoolean(bool.getValue()); }
      else if (value instanceof COSArray array) {
        out.writeByte(6); out.writeInt(array.size());
        for (COSBase child : array) if (!write(child, out, active, depth + 1)) return false;
      } else if (value instanceof COSDictionary dictionary) {
        out.writeByte(value instanceof COSStream ? 8 : 7);
        var keys = dictionary.keySet().stream().sorted(java.util.Comparator.comparing(COSName::getName)).toList();
        out.writeInt(keys.size());
        for (COSName key : keys) {
          bytes(out, key.getName().getBytes(StandardCharsets.UTF_8));
          if (!write(dictionary.getItem(key), out, active, depth + 1)) return false;
        }
        if (dictionary instanceof COSStream stream) {
          out.writeLong(stream.getLength());
          try (var input = stream.createRawInputStream()) { input.transferTo(out); }
        }
      } else return false;
      return true;
    } finally { active.remove(value); }
  }
}
