package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RuleAlignmentTests {
  @ParameterizedTest @ValueSource(strings = {"s", "si", "m", "mi", "c", "ci"})
  void creationChecksRequireAnAttachmentBagInEveryProfile(String profile) throws Exception {
    String kind = profile.equals("m") || profile.equals("mi") ? "pst" : "eml";
    try (var document = Loader.loadPDF(Path.of("examples", kind, (kind.equals("pst") ? "pst_quality-attached-message" : "eml_Agenda_and_timeline_slides" + (profile.equals("c") || profile.equals("ci") ? "_archive" : "")) + "_1" + profile + ".pdf").toFile())) {
      PdfCreationValidator.validate(document);
      var metadata = document.getDocumentCatalog().getMetadata();
      String original = new String(metadata.toByteArray(), StandardCharsets.UTF_8);
      String pattern = "(?s)<pdfmailmeta:attachments>.*?</pdfmailmeta:attachments>";
      assertTrue(original.matches("(?s).*" + pattern + ".*"));
      metadata.importXMPMetadata(original.replaceFirst(pattern,
          "<pdfmailmeta:attachments><rdf:Bag/></pdfmailmeta:attachments>").getBytes(StandardCharsets.UTF_8));
      PdfCreationValidator.validate(document);
      for (String replacement : new String[] {"", "<pdfmailmeta:attachments><rdf:Seq/></pdfmailmeta:attachments>",
          "<pdfmailmeta:attachments>not an array</pdfmailmeta:attachments>"}) {
        metadata.importXMPMetadata(original.replaceFirst(pattern, replacement).getBytes(StandardCharsets.UTF_8));
        var error = assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document));
        assertTrue(error.getMessage().contains("attachments"), error.getMessage());
      }
    }
  }
}
