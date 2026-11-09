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
      assertTrue(metadata.contains("<pdfmailmeta:In-Reply-To><rdf:Bag><rdf:li>" + Common.xml(PARENT) + "</rdf:li>"));
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
