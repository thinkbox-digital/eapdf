package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FindingAidSpreadsheetTests {
  @Test void oversizedFieldsRoundTripWithoutChangingTheIndexOrEmailRecords() throws Exception {
    String boundary = variedText(32767);
    String oversized = "=" + variedText(32765) + "😀" + "é漢\r\n".repeat(9000);
    var longRow = new PdfMailWriter.FindingAidRow(1, oversized, oversized, oversized,
        oversized, oversized, "<long@example.org>", 2);
    var ordinaryRow = new PdfMailWriter.FindingAidRow(2, "Inbox", "2026-10-29", "Sender",
        boundary, "ordinary subject", "<ordinary@example.org>", 0);
    for (String profile : List.of("m", "mi", "c", "ci")) {
      byte[] bytes = PdfFindingAidSupport.buildFindingAidSpreadsheetBytes(List.of(longRow, ordinaryRow), profile);
      try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
        var index = workbook.getSheetAt(0);
        assertEquals(2, index.getLastRowNum());
        int toColumn = "Folder".equals(index.getRow(0).getCell(1).getStringCellValue()) ? 3 : 2;
        assertEquals(boundary, index.getRow(2).getCell(toColumn).getStringCellValue());
        assertNull(index.getRow(2).getCell(toColumn).getHyperlink());
        Map<String, StringBuilder> restored = new LinkedHashMap<>();
        Map<String, Integer> partNumbers = new LinkedHashMap<>();
        for (var row : workbook.getSheetAt(1)) {
          if (row.getRowNum() == 0) continue;
          assertEquals(1, row.getCell(0).getNumericCellValue());
          String field = row.getCell(1).getStringCellValue();
          assertEquals(partNumbers.merge(field, 1, Integer::sum).intValue(), row.getCell(2).getNumericCellValue());
          var cell = row.getCell(3);
          assertEquals(CellType.STRING, cell.getCellType());
          String chunk = cell.getStringCellValue();
          assertTrue(chunk.length() <= 32767);
          assertFalse(Character.isHighSurrogate(chunk.charAt(chunk.length() - 1)));
          assertFalse(Character.isLowSurrogate(chunk.charAt(0)));
          restored.computeIfAbsent(field, ignored -> new StringBuilder()).append(chunk);
        }
        assertEquals(toColumn == 3 ? 5 : 4, restored.size());
        for (var value : restored.values()) assertEquals(oversized, value.toString());
        for (int col = 1; col < index.getRow(0).getLastCellNum() - 2; col++) {
          var cell = index.getRow(1).getCell(col);
          assertNotNull(cell.getHyperlink());
          assertTrue(cell.getStringCellValue().startsWith("Full text: "));
          assertTrue(cell.getHyperlink().getAddress().startsWith("'Continuation 1'!D"));
        }
      }
      assertEquals(oversized, longRow.to);
      assertEquals(oversized, longRow.subject);
    }
  }

  @Test void continuationSheetsRollOverWithoutLosingOrderOrMixingEmails() throws Exception {
    String value = "z".repeat(32767 * 3 + 1);
    try (var workbook = new XSSFWorkbook()) {
      var index = workbook.createSheet("Finding Aid");
      // Two data rows per sheet exercises the same rollover without a million-row fixture.
      var writer = new PdfFindingAidSupport.SpreadsheetTextWriter(workbook, 3);
      writer.setCell(index.createRow(0), 0, 17, "To", value);
      writer.setCell(index.createRow(1), 0, 18, "Subject", "q".repeat(32768));
      try (var output = new ByteArrayOutputStream()) {
        workbook.write(output);
        try (var reopened = new XSSFWorkbook(new ByteArrayInputStream(output.toByteArray()))) {
          assertEquals(4, reopened.getNumberOfSheets());
          Map<Integer, StringBuilder> restored = new LinkedHashMap<>();
          Map<Integer, Integer> parts = new LinkedHashMap<>();
          for (int i = 1; i < reopened.getNumberOfSheets(); i++) {
            var sheet = reopened.getSheetAt(i);
            assertEquals("Continuation " + i, sheet.getSheetName());
            assertEquals(2, sheet.getLastRowNum());
            for (int row = 1; row <= 2; row++) {
              var chunk = sheet.getRow(row);
              int email = (int) chunk.getCell(0).getNumericCellValue();
              assertEquals(parts.merge(email, 1, Integer::sum).intValue(), chunk.getCell(2).getNumericCellValue());
              restored.computeIfAbsent(email, ignored -> new StringBuilder()).append(chunk.getCell(3).getStringCellValue());
            }
          }
          assertEquals(value, restored.get(17).toString());
          assertEquals("q".repeat(32768), restored.get(18).toString());
          assertEquals("'Continuation 3'!D2", reopened.getSheetAt(0).getRow(1).getCell(0).getHyperlink().getAddress());
        }
      }
    }
  }

  @Test void ordinaryIndexDoesNotNeedContinuationSheets() throws Exception {
    var row = new PdfMailWriter.FindingAidRow(1, "Inbox", "2026-10-29", null,
        "recipient@example.org", "Subject", "id", 1);
    try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(
        PdfFindingAidSupport.buildFindingAidSpreadsheetBytes(List.of(row), "m")))) {
      assertEquals(1, workbook.getNumberOfSheets());
      assertEquals("recipient@example.org", workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue());
    }
  }

  private static String variedText(int length) {
    var random = new java.util.Random(42);
    StringBuilder text = new StringBuilder(length);
    for (int i = 0; i < length; i++) text.append((char) ('a' + random.nextInt(26)));
    return text.toString();
  }

}
