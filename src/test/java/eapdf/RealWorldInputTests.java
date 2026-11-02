package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealWorldInputTests {
  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"2175492,9", "2131684,1", "2148484,2"})
  void realPstInlineTextAndDeliveryStatusRetainTheirAttachmentBytes(long descriptor, int count) throws Exception {
    Path source = Path.of("in/pst_14_05+May+2003+Public+2.pst");
    assumeTrue(Files.isRegularFile(source), "Real-world PST is not installed");
    var pst = new com.pff.EaPdfPstFile(source.toFile());
    try {
      var message = (com.pff.PSTMessage) com.pff.PSTObject.detectAndLoadPSTObject(pst, descriptor);
      var record = PstReader.readMessage(message, "", source.toString(), "", List.of(), false, 0);
      var exported = EmlReader.readRfc822Bytes(Common.synthesizeEml(record), "export.eml", "export.eml", "", List.of(), false);
      var expected = record.associatedFiles.stream().filter(a -> a.emailAttachment).toList();
      var actual = new java.util.ArrayList<>(exported.associatedFiles.stream().filter(a -> a.emailAttachment).toList());
      assertEquals(count, exported.attachmentCount);
      for (var attachment : expected) {
        var match = actual.stream().filter(a -> a.filename.equals(attachment.filename)
            && java.util.Arrays.equals(a.bytes, attachment.bytes)).findFirst().orElseThrow();
        actual.remove(match);
      }
      assertTrue(actual.isEmpty());
    } finally { pst.close(); }
  }
  @ParameterizedTest
  @ValueSource(strings = {"eml_03_994.eml", "eml_10_16554.eml", "eml_18_2058.eml", "eml_24_5870.eml",
      "eml_25_1447.eml", "eml_14_7246.eml", "eml_02_23641.eml"})
  void unsupportedRealWorldCharsetIsRecoveredWithOriginalBytes(String name) throws Exception {
    Path source = Path.of("in", name);
    assumeTrue(Files.isRegularFile(source), "Real-world corpus file is not installed: " + source);
    EmailRecord record = EmlReader.readEml(source.toString(), "", List.of(), false);
    assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
    assertFalse(record.htmlBody.isBlank());
    assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains("windows-3839")));
  }

  @Test void realMailingListDatePreservesUnknownTimezone() throws Exception {
    Path source = Path.of("in/agileuml-dev.mbox");
    assumeTrue(Files.isRegularFile(source), "Real-world mailbox is not installed");
    var records = MboxReader.readMbox(source.toString(), "", List.of(), false);
    EmailRecord welcome = records.stream().filter(r -> r.subject.equals("Welcome to agileuml-dev")).findFirst().orElseThrow();
    Common.requireCreationDate(welcome);
    assertEquals("2019-03-05T16:08:13", welcome.sentXmp);
    assertEquals("Tue Mar  5 16:08:13 2019", welcome.rawDateHeader);
    assertNull(Common.parseMessageDate(welcome.sentXmp));
    assertTrue(PdfMailWriter.shouldEmitRawSent(welcome));
  }

  @Test void realPstEmptyRtfBodyRetainsTheNativeRtf() throws Exception {
    Path source = Path.of("in/pst_02_07+July+2003+Public+2.pst");
    assumeTrue(Files.isRegularFile(source), "Real-world PST is not installed");
    com.pff.PSTFile pst = new com.pff.EaPdfPstFile(source.toFile());
    try {
      var message = (com.pff.PSTMessage) com.pff.PSTObject.detectAndLoadPSTObject(pst, 2137284);
      EmailRecord record = PstReader.readMessage(message, "", source.toString(), "", List.of(), false, 0);
      assertEquals("Re:  Attached letter (correct date of July 24, 2003)", record.subject);
      assertEquals(message.getRTFBody(), record.rtfBody);
      assertTrue(org.jsoup.Jsoup.parse(record.htmlBody).text().isBlank());
      assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains("no visible text")));
    } finally { pst.close(); }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
      "pst_16_2005b_May-Jul_GovernF.pst,3874244,158",
      "pst_20_200501-06JanJun.pst,3830756,195",
      "pst_22_200401-06JanJun.pst,3385060,270",
      "pst_23_2005d_Nov-Dec_GovernF.pst,3970980,139",
      "pst_24_GovernFJan-MAr2003.pst,2132356,174",
      "pst_25_2005b_Mar-April_GovernFShiavo.pst,2192708,232"})
  void realAnsiPstRecipientTablesCrossBlockBoundaries(String name, long descriptor, int expected) throws Exception {
    Path source = Path.of("in", name);
    assumeTrue(Files.isRegularFile(source), "Real-world PST is not installed: " + source);
    com.pff.PSTFile pst = new com.pff.EaPdfPstFile(source.toFile());
    try {
      var message = (com.pff.PSTMessage) com.pff.PSTObject.detectAndLoadPSTObject(pst, descriptor);
      assertEquals(expected, message.getNumberOfRecipients());
      for (int index = 0; index < expected; index++) {
        var recipient = message.getRecipient(index);
        assertNotNull(recipient);
        assertFalse(recipient.getEmailAddress().isBlank(), "Recipient " + index);
        assertTrue(recipient.getRecipientType() >= 1 && recipient.getRecipientType() <= 3);
      }
    } finally { pst.close(); }
  }

  @Test void missingRealPstAttachmentIsReportedWithoutNullDereference() throws Exception {
    Path source = Path.of("in/pst_06_1999.pst");
    assumeTrue(Files.isRegularFile(source), "Real-world PST is not installed");
    com.pff.PSTFile pst = new com.pff.EaPdfPstFile(source.toFile());
    try {
      var message = (com.pff.PSTMessage) com.pff.PSTObject.detectAndLoadPSTObject(pst, 5068004);
      var record = PstReader.readMessage(message, "", source.toString(), "", List.of(), false, 0);
      assertTrue(record.sourceProblems.stream().anyMatch(p -> p.contains("could not be recovered from the original PST file") && p.contains("5068004")));
      assertTrue(record.associatedFiles.stream().filter(f -> f.emailAttachment).allMatch(f -> f.hasData()));
      assertFalse(Common.nz(record.subject).isBlank());
    } finally { pst.close(); }
  }

  @Test void realPstSubnodeIndexesRetainAllMessageAndRecipientRows() throws Exception {
    Path source = Path.of("in/pst_22_200401-06JanJun.pst");
    assumeTrue(Files.isRegularFile(source), "Real-world PST is not installed");
    com.pff.PSTFile pst = new com.pff.EaPdfPstFile(source.toFile());
    try {
      long[] counts = new long[2];
      scanRecipients(pst.getRootFolder(), counts);
      assertArrayEquals(new long[]{23173, 65783}, counts);
    } finally { pst.close(); }
  }

  static void scanRecipients(com.pff.PSTFolder folder, long[] counts) throws Exception {
    if (folder.getContentCount() > 0) {
      com.pff.PSTObject item;
      while ((item = folder.getNextChild()) != null) {
        if (!(item instanceof com.pff.PSTMessage message)) continue;
        counts[0]++;
        for (int index = 0; index < message.getNumberOfRecipients(); index++) {
          assertNotNull(message.getRecipient(index));
          counts[1]++;
        }
      }
    }
    if (folder.hasSubfolders()) for (var child : folder.getSubFolders()) scanRecipients(child, counts);
  }

  @Test void realHtmlParagraphsStayOutOfInlineStructure() throws Exception {
    Path source = Path.of("in/acceleo-dev.mbox");
    assumeTrue(Files.isRegularFile(source), "Real-world MBOX is not installed");
    var records = MboxReader.readMbox(source.toString(), "", List.of(), false);
    EmailRecord record = records.get(14);
    Path temporary = Files.createTempDirectory("eapdf-real-inline-test-");
    try {
      var output = PdfMailWriter.buildSingleMessagePdf(temporary.resolve("message.pdf"), record,
          TestSupport.fontPath(), TestSupport.iccPath(), false, temporary, 1, "s");
      try (var pdf = org.apache.pdfbox.Loader.loadPDF(output.pdfBytes)) {
        assertNoInlineBlocks(pdf.getDocumentCatalog().getCOSObject()
            .getDictionaryObject(org.apache.pdfbox.cos.COSName.STRUCT_TREE_ROOT), "");
      }
    } finally { Common.deleteTree(temporary); }
  }

  static void assertNoInlineBlocks(org.apache.pdfbox.cos.COSBase value, String parent) {
    if (value instanceof org.apache.pdfbox.cos.COSObject object) value = object.getObject();
    if (value instanceof org.apache.pdfbox.cos.COSArray array) {
      for (var child : array) assertNoInlineBlocks(child, parent);
    } else if (value instanceof org.apache.pdfbox.cos.COSDictionary dictionary) {
      String role = dictionary.getNameAsString(org.apache.pdfbox.cos.COSName.S, "");
      if (parent.equals("Span")) assertFalse(List.of("P", "Div", "Table", "BlockQuote").contains(role), role);
      assertNoInlineBlocks(dictionary.getDictionaryObject(org.apache.pdfbox.cos.COSName.K), role);
    }
  }

  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({"2097412,Hope_Act.doc,application/msword",
      "2139332,NCLBUICpositionpaper.doc,application/octet-stream"})
  void realPstAttachmentExportsRetainCountAndPayload(long descriptor, String filename, String mime) throws Exception {
    Path source = Path.of("in/pst_02_07+July+2003+Public+2.pst");
    assumeTrue(Files.isRegularFile(source), "Real-world PST is not installed");
    com.pff.PSTFile pst = new com.pff.EaPdfPstFile(source.toFile());
    try {
      var message = (com.pff.PSTMessage) com.pff.PSTObject.detectAndLoadPSTObject(pst, descriptor);
      var record = PstReader.readMessage(message, "", source.toString(), "", List.of(), false, 0);
      var attachment = record.associatedFiles.stream().filter(a -> a.filename.equals(filename)).findFirst().orElseThrow();
      assertTrue(attachment.emailAttachment);
      assertEquals(1, record.attachmentCount);
      assertEquals(mime, attachment.rawContentType);
      byte[] original;
      try (var input = message.getAttachment(0).getFileInputStream()) { original = input.readAllBytes(); }
      assertArrayEquals(original, attachment.bytes);
      var exported = EmlReader.readRfc822Bytes(Common.synthesizeEml(record), "export.eml", "export.eml", "", List.of(), false);
      var recovered = exported.associatedFiles.stream().filter(a -> a.filename.equals(filename)).findFirst().orElseThrow();
      assertEquals(1, exported.attachmentCount);
      assertArrayEquals(original, recovered.bytes);
      if (descriptor == 2139332) assertTrue(attachment.description.contains("multipart/appledouble"));
    } finally { pst.close(); }
  }

  @Test void invalidLocalDatesDoNotAcquireInventedValues() {
    assertEquals("", Common.sourceLocalDate("Fri Feb 30 12:00:00 2019"));
    assertEquals("", Common.sourceLocalDate("Tue Mar 5 16:08:13 2019 garbage"));
    assertFalse(Common.validSentDate("2019-02-30T12:00:00"));
    assertFalse(Common.validSentDate(""));
    assertFalse(Common.validSentDate("0000-03-05T16:08:13"));
    assertFalse(Common.validSentDate("2019-03-05T16:08"));
    assertTrue(Common.validSentDate("2019-03-05T16:08:13"));
    assertTrue(Common.validSentDate("2019-03-05T16:08:13Z"));
  }
}
