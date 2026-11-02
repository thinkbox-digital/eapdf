package eapdf;

import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.DefaultResourceCache;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;

/** Reconstructible wrappers only; canonical COS objects remain document-owned. */
final class ValidationResourceCache extends DefaultResourceCache {
  private final Map<COSObject, PDPropertyList> properties;

  ValidationResourceCache() { this(2048); }

  ValidationResourceCache(int maximum) {
    // Property evictions bypass removal-history bookkeeping through the overrides
    // below. Keep the default policies for the more expensive resource types.
    super();
    if (maximum < 1) throw new IllegalArgumentException("Positive property cache limit required");
    properties = new LinkedHashMap<>(16, .75f, true) {
      @Override protected boolean removeEldestEntry(Map.Entry<COSObject, PDPropertyList> entry) {
        return size() > maximum;
      }
    };
  }

  @Override public PDPropertyList getProperties(COSObject indirect) { return properties.get(indirect); }
  @Override public void put(COSObject indirect, PDPropertyList value) { properties.put(indirect, value); }
  @Override public PDPropertyList removeProperties(COSObject indirect) { return properties.remove(indirect); }
}
