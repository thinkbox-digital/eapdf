package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class PdfFontMetadataTests {
  @Test void finalizedSubsetPreservesTextAndUsesPdf20Metadata() throws Exception {
    try (var document = new PDDocument(); var input = new FileInputStream(TestSupport.fontPath())) {
      document.setVersion(2.0f);
      var font = PDType0Font.load(document,input,true);
      font.getFontDescriptor().getCOSObject().setFloat(COSName.FONT_WEIGHT,400f);
      var page = new PDPage();document.addPage(page);
      String text = "Subset glyphs é Ω 012345";
      try (var content = new PDPageContentStream(document,page)) {
        content.beginText();content.setFont(font,12);content.newLineAtOffset(40,700);content.showText(text);content.endText();
      }
      var output = new ByteArrayOutputStream();PdfFontMetadata.save(document,output);
      try (var reopened = Loader.loadPDF(output.toByteArray())) {
        assertEquals(text,new PDFTextStripper().getText(reopened).strip());
        var loaded = reopened.getPage(0).getResources().getFont(COSName.getPDFName("F1"));
        var descriptor = loaded.getFontDescriptor().getCOSObject();
        assertInstanceOf(COSInteger.class,descriptor.getDictionaryObject(COSName.FONT_WEIGHT));
        assertFalse(descriptor.containsKey(COSName.CID_SET));
        assertTrue(loaded.getName().contains("+"));
        assertNotNull(loaded.getFontDescriptor().getFontFile2());
      }
      // A second save must not subset the already finalized font again.
      PdfFontMetadata.save(document,new ByteArrayOutputStream());
    }
  }
}
