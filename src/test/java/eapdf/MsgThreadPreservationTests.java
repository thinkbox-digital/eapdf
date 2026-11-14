package eapdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import static org.junit.jupiter.api.Assertions.*;

class MsgThreadPreservationTests {
  static final Path SOURCE = Path.of("src/test/resources/eapdf/outlook-regression/native-reply.msg");
  static final String PARENT = "<parent@example.org>";

  @Test
  void syntheticOutlookReplyRetainsItsNativeThreadFields() throws Exception {
    EmailRecord record = MsgReader.readMsg(SOURCE.toString(), "", List.of(), false);
    assertEquals(List.of(PARENT), record.inReplyTo);
    assertEquals(List.of(PARENT), record.references);
    assertArrayEquals(Files.readAllBytes(SOURCE), record.sourceBytes);
    for (String metadata : List.of(PdfMailWriter.buildXmpMetadata(record),
        PdfMailWriter.buildXmpMetadata(List.of(record), "m"))) {
      var xml = TestSupport.parseMetadata(metadata);
      var replies = (org.w3c.dom.Element) xml.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/meta/", "In-Reply-To").item(0);
      var bag = (org.w3c.dom.Element) replies.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Bag").item(0);
      var identifiers = bag.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li");
      assertEquals(1, identifiers.getLength());
      assertEquals(PARENT, identifiers.item(0).getTextContent());
    }
    EmailRecord reconstructed = EmlReader.readRfc822Bytes(Common.synthesizeEml(record),
        "reply.eml", "reply.eml", "", List.of(), false);
    assertEquals(record.inReplyTo, reconstructed.inReplyTo);
    assertEquals(record.references, reconstructed.references);
  }

  @Test
  void suppliedTransportFieldsKeepPriorityWhileMissingFieldsAreRecovered() throws Exception {
    var message = new OutlookMessageParser().parseMsg(SOURCE.toFile());
    EmailRecord record = new EmailRecord();
    record.inReplyTo.add("<transport-parent@example.org>");
    MsgReader.recoverNativeThreadHeaders(SOURCE, message, record);
    assertEquals(List.of("<transport-parent@example.org>"), record.inReplyTo);
    assertEquals(List.of(PARENT), record.references);
    record.references.clear();
    record.references.add("<transport-root@example.org>");
    MsgReader.recoverNativeThreadHeaders(SOURCE, message, record);
    assertEquals(List.of("<transport-parent@example.org>"), record.inReplyTo);
    assertEquals(List.of("<transport-root@example.org>"), record.references);
  }
}
