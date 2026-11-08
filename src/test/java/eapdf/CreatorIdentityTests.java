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
        assertTrue(xml.contains("<dc:creator><rdf:Seq><rdf:li>Archive &amp; Records Office</rdf:li>"));
        assertTrue(xml.contains("<xmp:CreatorTool>" + Common.xml(Common.productLabel()) + "</xmp:CreatorTool>"));
      }
      PdfMailWriter.CONFIG.creator = " ";
      assertTrue(PdfMailWriter.buildXmpMetadata(record, "s").contains("<rdf:li>Thinkbox.DIGITAL</rdf:li>"));
      assertEquals("Thinkbox.DIGITAL", new EapdfConfig().requireCreator());
      PdfMailWriter.CONFIG.creator = null;
      assertEquals("Thinkbox.DIGITAL", PdfMailWriter.CONFIG.requireCreator());
    } finally { PdfMailWriter.CONFIG.creator = previous; }
  }
}
