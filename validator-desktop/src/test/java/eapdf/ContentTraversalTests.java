package eapdf;

import org.apache.pdfbox.cos.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentTraversalTests {
  @Test void deepAndSharedArraysPreserveEveryOccurrence() {
    COSDictionary leaf = new COSDictionary();
    COSBase nested = leaf;
    for (int i = 0; i < 10000; i++) { COSArray parent = new COSArray(); parent.add(nested); nested = parent; }
    COSArray root = new COSArray(); root.add(nested); root.add(nested);
    assertEquals(java.util.List.of(leaf, leaf), PageContentChecks.dictionaries(root));
  }
  @Test void actualArrayCyclesAreRejected() {
    COSArray cyclic = new COSArray(); cyclic.add(cyclic);
    assertThrows(IllegalArgumentException.class, () -> PageContentChecks.dictionaries(cyclic));
  }
}
