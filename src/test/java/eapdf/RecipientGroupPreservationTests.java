package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecipientGroupPreservationTests {
  @TempDir Path directory;
  static final String TO = "Project team: Alice <alice@example.org>, Bob <bob@example.org>;";
  static final String CC = "Reviewers: Carol <carol@example.org>;";
  static final String BCC = "Private reviewers: Dave <dave@example.org>;";

  static byte[] message() {
    return ("From: Sender <sender@example.org>\r\nTo: " + TO + "\r\nCc: " + CC + "\r\nBcc: " + BCC
        + "\r\nSubject: Group recipients\r\nDate: Fri, 23 Oct 2026 12:00:00 +0000\r\n"
        + "Message-ID: <group-recipients@example.org>\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n\r\nProject review is scheduled for Friday.\r\n")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void groupMembersHaveIndividualMailboxMetadataWithoutAlteringTheOriginalHeaders() throws Exception {
    byte[] raw = message();
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "groups.eml", "groups.eml", "", List.of(), false);
    assertEquals(List.of("alice@example.org", "bob@example.org"), record.toAgents.stream().map(agent -> agent.email).toList());
    assertEquals("carol@example.org", record.ccAgents.getFirst().email);
    assertEquals("dave@example.org", record.bccAgents.getFirst().email);
    assertEquals(TO, record.rawTo);
    assertEquals(CC, record.rawCc);
    assertEquals(BCC, record.rawBcc);
    var output = PdfMailWriter.buildSingleMessagePdf(directory.resolve("groups.pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, "s");
    try (var document = Loader.loadPDF(output.pdfBytes)) {
      var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      var xml = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(document.getDocumentCatalog().getMetadata().toByteArray()));
      var descriptions = xml.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description");
      var mailboxes = new java.util.ArrayList<String>();
      for (int i = 0; i < descriptions.getLength(); i++) {
        var item = (org.w3c.dom.Element) descriptions.item(i);
        if (item.hasAttributeNS("http://xmlns.com/foaf/0.1/", "mbox")) mailboxes.add(item.getAttributeNS("http://xmlns.com/foaf/0.1/", "mbox"));
      }
      assertEquals(List.of("mailto:sender@example.org", "mailto:alice@example.org", "mailto:bob@example.org",
          "mailto:carol@example.org", "mailto:dave@example.org"), mailboxes);
      var files = AttachmentProfilePolicyTests.files(document);
      assertEquals(1, files.size());
      assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
  }

  @Test
  void emptyGroupsDoNotInventMailboxesAndPstHeaderRecoveryExpandsSuppliedMembers() throws Exception {
    assertTrue(Common.internetAddressListLenient("undisclosed-recipients:;").isEmpty());
    assertFalse(Common.smtpMailbox(TO));
    assertTrue(Common.smtpMailbox("alice@example.org"));
    var members = new java.util.ArrayList<MailAgent>();
    PstReader.addHeaderAgents(members, TO,
        () -> jakarta.mail.internet.InternetAddress.parseHeader(TO, false));
    assertEquals(List.of("alice@example.org", "bob@example.org"), members.stream().map(agent -> agent.email).toList());
    members.clear();
    PstReader.addHeaderAgents(members, "undisclosed-recipients:;",
        () -> jakarta.mail.internet.InternetAddress.parseHeader("undisclosed-recipients:;", false));
    assertTrue(members.isEmpty());
    PstReader.addHeaderAgents(members, TO, () -> { throw new jakarta.mail.internet.AddressException("recover header"); });
    assertEquals(List.of("alice@example.org", "bob@example.org"), members.stream().map(agent -> agent.email).toList());
    members.clear();
    PstReader.addHeaderAgents(members, "undisclosed-recipients:;", () -> null);
    assertTrue(members.isEmpty());
  }
}
