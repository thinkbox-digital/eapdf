package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MsgAddressPreservationTests {
  static final String FROM = "\"Sender\" (retained & original) <sender@example.org>";
  static final String TO = "\"Recipient\" (source wording) <recipient@example.org>";
  @TempDir Path tempDir;

  static void fixture(Path path, boolean transport, boolean recipient) throws Exception {
    MsgBodySelectionTests.writeMsg(path, 3, false);
    try (POIFSFileSystem storage = new POIFSFileSystem(new ByteArrayInputStream(Files.readAllBytes(path)))) {
      String headers = (transport ? "From: " + FROM + "\r\nTo: " + TO + "\r\n" : "")
          + "Date: Thu, 22 Oct 2026 12:00:00 +0000\r\nMessage-ID: <addresses@example.org>\r\n";
      byte[] headerBytes = MsgBodySelectionTests.unicode(headers);
      storage.getRoot().createOrUpdateDocument("__substg1.0_007D001F", new ByteArrayInputStream(headerBytes));
      byte[] originalProperties;
      try (var input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) {
        originalProperties = input.readAllBytes();
      }
      ByteBuffer properties = ByteBuffer.wrap(originalProperties).order(ByteOrder.LITTLE_ENDIAN);
      for (int offset = 32; offset + 16 <= originalProperties.length; offset += 16) {
        if (properties.getInt(offset) == 0x007d001f) properties.putInt(offset + 8, headerBytes.length);
      }
      if (!recipient) {
        var table = (org.apache.poi.poifs.filesystem.DirectoryNode) storage.getRoot().getEntry("__recip_version1.0_#00000000");
        for (String name : List.of("__substg1.0_3001001F", "__substg1.0_3003001F", "__properties_version1.0")) {
          table.getEntry(name).delete();
        }
        table.delete();
        properties.putInt(8, 0).putInt(16, 0);
      }
      storage.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(originalProperties));
      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        storage.writeFilesystem(output);
        Files.write(path, output.toByteArray());
      }
    }
  }

  EmailRecord record(boolean transport, boolean recipient) throws Exception {
    Path source = tempDir.resolve("addresses.msg");
    fixture(source, transport, recipient);
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    Assertions.assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
    return record;
  }

  @Test
  void rawTransportAddressTextSurvivesIntoXmpExactly() throws Exception {
    EmailRecord record = record(true, true);
    Assertions.assertEquals(FROM, record.rawFrom);
    Assertions.assertEquals(TO, record.rawTo);
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(
        PdfMailWriter.buildXmpMetadata(record).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    String namespace = "http://www.pdfa.org/eapdf/ns/meta/";
    Assertions.assertEquals(FROM, xml.getElementsByTagNameNS(namespace, "Raw-From").item(0).getTextContent());
    Assertions.assertEquals(TO, xml.getElementsByTagNameNS(namespace, "Raw-To").item(0).getTextContent());
  }

  @Test
  void knownRecipientMailboxIsVisibleInTheGeneratedHeader() throws Exception {
    EmailRecord record = record(true, true);
    Assertions.assertEquals("recipient@example.org", record.toAgents.get(0).email);
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("addresses.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "si").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      Assertions.assertTrue(new PDFTextStripper().getText(pdf).contains("recipient@example.org"));
    }
  }

  @Test
  void absentTransportHeadersKeepKnownMapiMailboxes() throws Exception {
    EmailRecord record = record(false, true);
    Assertions.assertEquals("Recipient <recipient@example.org>", record.rawTo);
    Assertions.assertEquals("Sender <sender@example.org>", record.rawFrom);
  }

  @Test
  void transportMailboxSurvivesWhenMapiRecipientRowsAreAbsent() throws Exception {
    EmailRecord record = record(true, false);
    Assertions.assertEquals(TO, record.rawTo);
    Assertions.assertEquals("recipient@example.org", record.toAgents.get(0).email);
    Assertions.assertTrue(record.to.contains("recipient@example.org"));
    byte[] bytes = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("transport-only.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
    try (var pdf = Loader.loadPDF(bytes)) {
      Assertions.assertTrue(new PDFTextStripper().getText(pdf).contains("recipient@example.org"));
      Assertions.assertTrue(TestSupport.metadataXml(pdf).contains("mailto:recipient@example.org"));
    }
  }

  @Test
  void displayOnlyMapiFallbackDoesNotRetainAStorageTerminatorOrInventMailbox() throws Exception {
    EmailRecord record = record(false, false);
    Assertions.assertTrue(record.toAgents.isEmpty());
    Assertions.assertEquals("Recipient", record.to);
    Assertions.assertEquals("Recipient", record.rawTo);
  }

  @Test
  void suppliedRecipientOrderSurvivesWithoutReplacingNativeNamesOrValues() {
    MailAgent first = new MailAgent(); first.email = "first@example.org"; first.name = "Native first";
    MailAgent second = new MailAgent(); second.email = "second@example.org"; second.name = "Native second";
    var agents = new java.util.ArrayList<>(List.of(second, first));
    MsgReader.recoverTransportAgents(agents, "Header first <first@example.org>, Header second <second@example.org>");
    Assertions.assertEquals(List.of(first, second), agents);
    Assertions.assertEquals("Native first", agents.getFirst().name);
    MsgReader.recoverTransportAgents(agents, "other@example.org, first@example.org");
    Assertions.assertEquals(List.of(first, second), agents);
  }

  @Test
  void syntheticOutlookMessageRetainsItsTransportRecipientOrder() throws Exception {
    Path source = Path.of("src/test/resources/eapdf/outlook-regression/transport-recipient-order.msg");
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    Assertions.assertEquals(List.of("first@example.org", "second@example.org"),
        record.toAgents.stream().map(agent -> agent.email).toList());
    Assertions.assertEquals(List.of("Native first", "Native second"),
        record.toAgents.stream().map(agent -> agent.name).toList());
    Assertions.assertEquals("Header first <first@example.org>, Header second <second@example.org>", record.rawTo);
    Assertions.assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
  }

  @Test
  void nativeCaseDifferencesDoNotLoseTheOriginalHeaderOrderOrSpelling() throws Exception {
    Path source = Path.of("in/sediment.msg");
    EmailRecord record = MsgReader.readMsg(source.toString(), "", List.of(), false);
    Assertions.assertEquals("jami.macneil@maine.gov", record.ccAgents.get(0).email);
    Assertions.assertEquals("eric.p.hamlin@maine.gov", record.ccAgents.get(2).email);
    Assertions.assertArrayEquals(Files.readAllBytes(source), record.sourceBytes);
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    Path path = directory.resolve("addresses.msg");
    fixture(path, true, true);
    EmailRecord record = MsgReader.readMsg(path.toString(), "", List.of(), false);
    System.out.println("rawFrom=" + record.rawFrom + " rawTo=" + record.rawTo + " visibleTo=" + record.to);
    if (args.length > 1) Thread.sleep(45000);
  }
}
