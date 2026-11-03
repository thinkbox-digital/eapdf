package eapdf;

import java.util.List;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DirectFindingAidTests {
  @Test void collectionFindingAidSidecarContainsNoEmbeddedFilesOrSyntheticEmail() throws Exception {
    EmailRecord record = new AdditionalSpecRuleTests().record();
    var records = List.of(record);
    var summary = PdfMailWriter.buildCollectionRunSummary(records, PdfCollectionSupport.buildFolderSections(records));
    var rows = PdfFindingAidSupport.buildFindingAidRows(records);
    boolean embed = PdfMailWriter.CONFIG.findingAid.embedExcel;
    try {
      PdfMailWriter.CONFIG.findingAid.embedExcel = true;
      for (String profile : List.of("c", "ci")) {
        byte[] bytes = PdfFindingAidSupport.buildFindingAidSidecar(java.nio.file.Path.of("collection.pdf"),
            rows, profile, summary, TestSupport.fontPath(), TestSupport.iccPath());
        try (var document = Loader.loadPDF(bytes)) {
          Assertions.assertTrue(AttachmentProfilePolicyTests.files(document).isEmpty());
          Assertions.assertFalse(document.getDocumentCatalog().getCOSObject().containsKey(org.apache.pdfbox.cos.COSName.getPDFName("AF")));
          Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfaid:part>4</pdfaid:part>"));
          Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfaid:rev>2020</pdfaid:rev>"));
          Assertions.assertFalse(TestSupport.metadataXml(document).contains("<pdfaid:conformance>F</pdfaid:conformance>"));
          String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
          Assertions.assertTrue(text.contains("Finding Aid"));
          if (PdfMailWriter.CONFIG.branding.enabled) {
            new org.apache.pdfbox.text.PDFTextStripper() {
              @Override protected void writeString(String value, List<org.apache.pdfbox.text.TextPosition> positions) throws java.io.IOException {
                if (getCurrentPageNo() == 1 && value.contains("Finding Aid"))
                  for (var position : positions) Assertions.assertTrue(position.getYDirAdj() - position.getHeightDir() > 40f,
                      "Finding-aid heading must clear the banner plus an eight-point gap");
                if (value.contains("Email converted by ThinkBox")) {
                  float left = positions.getFirst().getXDirAdj();
                  var last = positions.getLast();
                  float right = last.getXDirAdj() + last.getWidthDirAdj();
                  Assertions.assertEquals(getCurrentPage().getCropBox().getWidth() / 2f, (left + right) / 2f, 1f);
                }
                super.writeString(value, positions);
              }
            }.getText(document);
          }
          Assertions.assertFalse(text.contains("software-generated index"));
          Assertions.assertFalse(text.contains("Message-ID:"));
          Assertions.assertFalse(TestSupport.metadataXml(document).contains("pdfmailid:conformance"));
        }
      }
    } finally { PdfMailWriter.CONFIG.findingAid.embedExcel = embed; }
  }
}
