package eapdf;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocaleDateTests {
  @TempDir Path directory;

  @Test void thaiAndUsLocalesKeepGregorianDatesInFinalEaPdf() throws Exception {
    Locale previous = Locale.getDefault();
    Locale format = Locale.getDefault(Locale.Category.FORMAT);
    TimeZone zone = TimeZone.getDefault();
    var config = EapdfConfig.get();
    boolean accessibility = config.accessibility.addPdfUaFeatures;
    try {
      config.accessibility.addPdfUaFeatures = true;
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
      for (Locale locale : new Locale[]{Locale.US, Locale.forLanguageTag("th-TH")}) {
        Locale.setDefault(locale);
        Date fixed = Date.from(Instant.parse("2000-01-06T10:00:00Z"));
        assertEquals("2000-01-06T10:00:00Z", Common.xmpDate(fixed));
        assertEquals("2000-01-06 10:00:00 +0000", Common.fmtDate(fixed));
        var record = EmlReader.readEml("src/test/resources/eapdf/patched-renderer-table.eml", "", java.util.List.of(), false);
        Path output = directory.resolve("eml_locale_" + locale.toLanguageTag() + "_1s.pdf");
        PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1);
        try (var pdf = Loader.loadPDF(output.toFile())) {
          var xml = TestSupport.parseMetadata(TestSupport.metadataXml(pdf));
          var xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath();
          assertTrue(xpath.evaluate("string(//*[local-name()='Sent'])", xml).startsWith("2020-01-06T10:00:00"));
          String year = Integer.toString(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getYear());
          for (String name : new String[]{"CreateDate", "ModifyDate", "MetadataDate"})
            assertTrue(xpath.evaluate("string(//*[local-name()='" + name + "'])", xml).startsWith(year + "-"), name);
        }
      }
    } finally {
      Locale.setDefault(previous);
      Locale.setDefault(Locale.Category.FORMAT, format);
      TimeZone.setDefault(zone);
      config.accessibility.addPdfUaFeatures = accessibility;
    }
  }
}
