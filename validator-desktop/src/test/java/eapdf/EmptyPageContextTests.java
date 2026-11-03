package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class EmptyPageContextTests {
  @TempDir Path temporary;

  static Stream<Arguments> layouts() {
    return Stream.of(
        Arguments.of("E", List.of(1)), Arguments.of("EEE", List.of(1, 2, 3)),
        Arguments.of("ET", List.of(1)), Arguments.of("TE", List.of(2)),
        Arguments.of("TET", List.of(2)), Arguments.of("S", List.of(1)),
        Arguments.of("TST", List.of(2)), Arguments.of("TT", List.of()),
        Arguments.of("", List.of()));
  }

  Path fixture(String layout) throws Exception {
    Path file = temporary.resolve("pages-" + layout + ".pdf");
    try (var document = new PDDocument()) {
      int index = 0;
      for (char kind : layout.toCharArray()) {
        var page = new PDPage(); document.addPage(page); index++;
        if (kind != 'E') try (var content = new PDPageContentStream(document, page)) {
          if (kind == 'T') {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(46, 720);
            content.showText("Page " + index + " of " + layout.length());
            content.endText();
          }
        }
      }
      document.save(file.toFile());
    }
    return file;
  }

  static void assertContext(JsonArray items, String level, List<Integer> missing) {
    for (String code : List.of("PAGE_CONTEXT_PRESENT_RECOMMENDED", "PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED")) {
      var rows = items.asList().stream().map(v -> v.getAsJsonObject())
          .filter(v -> code.equals(v.get("code").getAsString())).toList();
      assertEquals(1, rows.size(), code);
      var row = rows.getFirst();
      assertEquals(level, row.get("level").getAsString(), code);
      if (!level.equals("N/A")) assertEquals(missing,
          row.getAsJsonObject("details").getAsJsonArray("pages_unassessed")
              .asList().stream().map(v -> v.getAsInt()).toList(), code);
    }
    assertTrue(items.asList().stream().map(v -> v.getAsJsonObject())
        .anyMatch(v -> "TEXT_CONTENT_PDF_TEXT_OBJECTS".equals(v.get("code").getAsString())),
        "Checks following page context must still execute");
    assertFalse(items.asList().stream().map(v -> v.getAsJsonObject())
        .anyMatch(v -> "VALIDATION_ERROR".equals(v.get("code").getAsString())));
  }

  @ParameterizedTest @MethodSource("layouts")
  void missingContentIsARecommendationNotAnInspectionError(String layout, List<Integer> missing) throws Exception {
    Path file = fixture(layout);
    try (var document = Loader.loadPDF(file.toFile())) {
      for (int i = 0; i < layout.length(); i++)
        assertEquals(layout.charAt(i) != 'E', document.getPage(i).getCOSObject().containsKey(COSName.CONTENTS));
      var report = new JavaValidation.Report(file.toString());
      assertNotNull(new PageContentChecks(document, report).run());
      assertContext(report.items, layout.isEmpty() ? "N/A" : missing.isEmpty() ? "PASS" : "WARN", missing);
    }
  }

  @Test void completeValidationDoesNotReportAnEngineErrorForAnEmptyPage() throws Exception {
    JsonObject report = JavaValidation.validate(fixture("TET"));
    assertContext(report.getAsJsonArray("items"), "WARN", List.of(2));
  }
}
