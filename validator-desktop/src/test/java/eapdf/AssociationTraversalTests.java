package eapdf;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssociationTraversalTests {
  @Test void siblingChainsAndCyclesDoNotCountAsTreeNesting() throws Exception {
    COSDictionary root = new COSDictionary(), last = root;
    for (int i = 0; i < 2000; i++) {
      COSDictionary next = new COSDictionary();
      last.setItem(COSName.getPDFName("Next"), next);
      next.setItem(COSName.getPDFName("Prev"), last);
      last = next;
    }
    last.setItem(COSName.getPDFName("Next"), root);
    COSDictionary spec = new COSDictionary(); COSArray af = new COSArray(); af.add(spec);
    last.setItem(COSName.getPDFName("AF"), af);
    Set<COSDictionary> found = Collections.newSetFromMap(new IdentityHashMap<>());
    EaPdfChecks.scanAssociations(root, found, Collections.newSetFromMap(new IdentityHashMap<>()));
    assertEquals(Set.of(spec), found);
  }

  @Test void objectBudgetStillStopsExcessiveGraphs() {
    COSArray root = new COSArray();
    for (int i = 0; i < 1001; i++) root.add(new COSDictionary());
    var error = assertThrows(java.io.IOException.class, () -> EaPdfChecks.scanAssociations(root,
        Collections.newSetFromMap(new IdentityHashMap<>()), Collections.newSetFromMap(new IdentityHashMap<>()), 1000));
    assertTrue(error.getMessage().contains("1000-container"));
  }
  @Test void boundedDiscoveryMatchesCanonicalGraphAssociationsOnRealArchives() throws Exception {
    for(String source:java.util.List.of("../examples/pst/pst_quality-attached-message_1m.pdf","../examples/eml/eml_Agenda_and_timeline_slides_1s.pdf")) {
      var path=java.nio.file.Path.of(source);
      try(var doc=IndexedPdfLoader.load(path)) {
        Set<COSDictionary> expected=EaPdfChecks.identitySet();
        EaPdfChecks.scanAssociations(doc.getDocumentCatalog().getCOSObject(),expected,EaPdfChecks.identitySet(),1000000);
        assertEquals(expected,PdfAssociationScanner.collect(path,doc,1000000));
        assertThrows(java.io.IOException.class,()->PdfAssociationScanner.collect(path,doc,1));
      }
    }
  }

  @Test void boundedDiscoveryPreservesDirectFileSpecsAndCycles() throws Exception {
    var path=java.nio.file.Files.createTempFile("eapdf-association-test-",".pdf");
    try {
      try(var doc=new org.apache.pdfbox.pdmodel.PDDocument()) {
        var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);
        var file=new COSDictionary();file.setString(COSName.UF,"direct.eml");
        var array=new COSArray();array.add(file);page.getCOSObject().setItem(COSName.getPDFName("AF"),array);
        doc.save(path.toFile());
      }
      try(var doc=IndexedPdfLoader.load(path)) {
        Set<COSDictionary> expected=EaPdfChecks.identitySet();
        EaPdfChecks.scanAssociations(doc.getDocumentCatalog().getCOSObject(),expected,EaPdfChecks.identitySet());
        assertFalse(expected.isEmpty());assertEquals(expected,PdfAssociationScanner.collect(path,doc,1000000));
      }
    } finally {java.nio.file.Files.deleteIfExists(path);}
  }
}
