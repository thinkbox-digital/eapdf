package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

class NamespaceDiagnosticTests {
  @TempDir Path temporary;

  @Test void missingBindingIsDistinguishedFromWrongBindingAndDeclarationOrder() throws Exception {
    Path source = temporary.resolve("namespace.pdf");
    for (String variant : List.of("missing", "wrong", "first", "last")) {
      try (var document = new PDDocument()) {
        var page = new PDPage(); document.addPage(page);
        try (var content = new PDPageContentStream(document, page)) {
          content.beginText();
          content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
          content.newLineAtOffset(36, 720); content.showText("Namespace diagnostic test"); content.endText();
        }
        var metadata = new PDMetadata(document);
        document.getDocumentCatalog().setMetadata(metadata);
        // Isolate namespace diagnostics without depending on private comparison files.
        String xml = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\""
            + EaPdfChecks.RDF + "\"><rdf:Description rdf:about=\"\"/></rdf:RDF></x:xmpmeta>";
        String declaration = "xmlns:pdfmail=\"" + (variant.equals("wrong") ? "urn:wrong" : "http://www.pdfa.org/eapdf/") + "\"";
        if (variant.equals("last")) {
          xml = xml.replace("xmlns:rdf=\"" + EaPdfChecks.RDF + "\"", "xmlns:rdf=\"" + EaPdfChecks.RDF + "\" " + declaration);
        } else if (!variant.equals("missing")) xml = xml.replace("<rdf:RDF ", "<rdf:RDF " + declaration + " ");
        metadata.importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        document.save(source.toFile());
        var report = new JavaValidation.Report(source.toString());
        report.pdfa = new com.google.gson.JsonObject();
        new EaPdfChecks(document, report, new JavaValidation.Children(), 0).run(source);
        var row = report.items.asList().stream().map(value -> value.getAsJsonObject())
            .filter(item -> item.get("code").getAsString().equals("XMP_NS_PREFIX")
                && item.getAsJsonObject("details").get("expected_prefix").getAsString().equals("pdfmail"))
            .findFirst().orElseThrow();
        var details = row.getAsJsonObject("details");
        assertEquals("http://www.pdfa.org/eapdf/", details.get("expected_namespace").getAsString());
        assertEquals(!variant.equals("missing"), details.get("prefix_present").getAsBoolean());
        assertEquals(variant.equals("first") || variant.equals("last") ? "PASS" : "FAIL", row.get("level").getAsString());
        if (variant.equals("missing")) {
          assertTrue(details.getAsJsonArray("observed_namespaces_for_prefix").isEmpty());
          assertTrue(row.get("message").getAsString().contains("declaration is missing"));
        } else assertEquals(variant.equals("wrong") ? "urn:wrong" : "http://www.pdfa.org/eapdf/",
            details.getAsJsonArray("observed_namespaces_for_prefix").get(0).getAsString());
      }
    }
  }
}
