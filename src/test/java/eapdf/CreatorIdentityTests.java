package eapdf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CreatorIdentityTests {
  @Test void creatorIdentityIsExplicitAndSeparateFromTheSoftware() throws Exception {
    String previous = PdfMailWriter.CONFIG.creator;
    try {
      var record = new SpecRepairTests().record("text/plain", "Body");
      PdfMailWriter.CONFIG.creator = "Archive & Records Office";
      for (String profile : java.util.List.of("s", "si", "m", "mi", "c", "ci")) {
        String xml = profile.startsWith("s") ? PdfMailWriter.buildXmpMetadata(record, profile)
            : PdfMailWriter.buildXmpMetadata(java.util.List.of(record), profile);
        var metadata = TestSupport.parseMetadata(xml);
        var identification = metadata.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/id/", "conformance").item(0);
        assertEquals("http://www.pdfa.org/eapdf/", identification.lookupNamespaceURI("pdfmail"));
        var creator = (org.w3c.dom.Element) metadata.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "creator").item(0);
        var sequence = (org.w3c.dom.Element) creator.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Seq").item(0);
        assertEquals("Archive & Records Office", sequence.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li").item(0).getTextContent());
        assertEquals(Common.productLabel(), metadata.getElementsByTagNameNS("http://ns.adobe.com/xap/1.0/", "CreatorTool").item(0).getTextContent());
      }
      PdfMailWriter.CONFIG.creator = " ";
      assertTrue(PdfMailWriter.buildXmpMetadata(record, "s").contains("<rdf:li>Thinkbox.DIGITAL</rdf:li>"));
      assertEquals("Thinkbox.DIGITAL", new EapdfConfig().requireCreator());
      PdfMailWriter.CONFIG.creator = null;
      assertEquals("Thinkbox.DIGITAL", PdfMailWriter.CONFIG.requireCreator());
    } finally { PdfMailWriter.CONFIG.creator = previous; }
  }
}
