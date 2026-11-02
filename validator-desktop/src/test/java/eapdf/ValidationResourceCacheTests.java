package eapdf;

import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDResources;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ValidationResourceCacheTests {
  @Test void evictedAndRemovedPropertiesReloadWithCanonicalIdentityAndValues() {
    var cache = new ValidationResourceCache(2);
    var dictionary = new COSDictionary();
    var properties = new COSDictionary();
    dictionary.setItem(COSName.PROPERTIES, properties);
    COSObject[] references = new COSObject[3];
    for (int i = 0; i < references.length; i++) {
      var value = new COSDictionary();
      value.setInt(COSName.getPDFName("Marker"), i);
      references[i] = new COSObject(value, new COSObjectKey(i + 1, 0));
      properties.setItem(COSName.getPDFName("P" + i), references[i]);
    }
    var resources = new PDResources(dictionary, cache);
    var first = resources.getProperties(COSName.getPDFName("P0"));
    assertSame(first, cache.getProperties(references[0]));
    resources.getProperties(COSName.getPDFName("P1"));
    resources.getProperties(COSName.getPDFName("P2"));
    assertNull(cache.getProperties(references[0]));
    var reloaded = resources.getProperties(COSName.getPDFName("P0"));
    assertNotSame(first, reloaded);
    assertSame(first.getCOSObject(), reloaded.getCOSObject());
    assertSame(references[0].getObject(), reloaded.getCOSObject());
    assertEquals(0, reloaded.getCOSObject().getInt(COSName.getPDFName("Marker")));
    assertSame(reloaded, cache.removeProperties(references[0]));
    assertNull(cache.getProperties(references[0]));
    assertSame(first.getCOSObject(), resources.getProperties(COSName.getPDFName("P0")).getCOSObject());
  }
}
