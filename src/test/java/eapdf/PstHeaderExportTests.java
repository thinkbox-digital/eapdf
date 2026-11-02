package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PstHeaderExportTests {
  @Test
  void malformedPstAttachmentTypeExportsTheOriginalPayload() throws Exception {
    for (String storedType : List.of(",bmp/octet-stream", "multipart/mixed")) {
      var record = new SpecRepairTests().record("text/plain", "Body");
      var attachment = new AssociatedFile();
      attachment.filename = "image.bmp";
      attachment.mime = storedType;
      attachment.rawContentType = storedType;
      attachment.emailAttachment = true;
      attachment.bytes = new byte[] {0, 1, 2, (byte) 255};
      PstReader.normalizeAttachmentContentType(record, attachment);
      record.associatedFiles.add(attachment);
      var exported = new MimeMessage(Session.getInstance(new Properties()),
          new ByteArrayInputStream(Common.synthesizeEml(record)));
      var parts = (jakarta.mail.Multipart) exported.getContent();
      var part = parts.getBodyPart(parts.getCount() - 1);
      Assertions.assertTrue(part.isMimeType("application/octet-stream"));
      Assertions.assertArrayEquals(attachment.bytes, part.getInputStream().readAllBytes());
      Assertions.assertTrue(attachment.description.contains(storedType));
      Assertions.assertTrue(record.conversionWarnings.stream().anyMatch(w -> w.contains(storedType)));
    }
    var valid = new AssociatedFile();
    valid.rawContentType = "image/png; name=sample.png";
    valid.mime = "image/png";
    PstReader.normalizeAttachmentContentType(new EmailRecord(), valid);
    Assertions.assertEquals("image/png; name=sample.png", valid.rawContentType);
    Assertions.assertEquals("image/png", valid.mime);
  }

  @Test
  void nativeDisplayAddressKeepsItsQuotedMailboxInSourceAndMetadata() throws Exception {
    var record = new SpecRepairTests().record("text/plain", "Body");
    record.toAgents.clear();
    String nativeValue = "tariq qamar <\"tGdog \"@yahoo.com>";
    PstReader.addMissingRecipient(record.toAgents, nativeValue, nativeValue);
    record.rawTo = Common.rfc822AddrList(record.toAgents);
    Assertions.assertEquals("\"tGdog \"@yahoo.com", record.toAgents.get(0).email);
    Assertions.assertEquals(nativeValue, record.toAgents.get(0).name);
    var exported = new MimeMessage(Session.getInstance(new Properties()),
        new ByteArrayInputStream(Common.synthesizeEml(record)));
    var address = (jakarta.mail.internet.InternetAddress) exported.getRecipients(jakarta.mail.Message.RecipientType.TO)[0];
    Assertions.assertEquals(record.toAgents.get(0).email, address.getAddress());
    Assertions.assertTrue(PdfMailWriter.buildXmpMetadata(record, "s")
        .contains("mailto:%22tGdog%20%22@yahoo.com"));
    for (String malformed : List.of("recipient@example.org;", "Name <recipient@example.org;>",
        "First <first@example.org>, Second <second@example.org>", "Name <a@example.org>\r\nInjected: x")) {
      Assertions.assertEquals(malformed, PstReader.nativeMailbox(malformed));
    }
  }

  @Test
  void repeatedRecipientFieldsSurviveReconstructedSourceAndMetadata() throws Exception {
    String headers = "To: First <first@example.org>\r\nTo: Second <second@example.org>\r\n\r\n";
    var message = new MimeMessage(Session.getInstance(new Properties()),
        new ByteArrayInputStream(headers.getBytes(StandardCharsets.US_ASCII)));
    var record = new SpecRepairTests().record("text/plain", "Body");
    record.rawTo = PstReader.addressHeader(message, "To");
    record.toAgents = Common.internetAddressList(message.getRecipients(jakarta.mail.Message.RecipientType.TO));
    var exported = new MimeMessage(Session.getInstance(new Properties()),
        new ByteArrayInputStream(Common.synthesizeEml(record)));
    var recipients = exported.getRecipients(jakarta.mail.Message.RecipientType.TO);
    Assertions.assertEquals(2, recipients.length);
    Assertions.assertEquals("first@example.org", ((jakarta.mail.internet.InternetAddress) recipients[0]).getAddress());
    Assertions.assertEquals("second@example.org", ((jakarta.mail.internet.InternetAddress) recipients[1]).getAddress());
    Assertions.assertEquals(record.rawTo, exported.getHeader("To", null));
    String metadata = PdfMailWriter.buildXmpMetadata(record, "s");
    Assertions.assertTrue(metadata.contains("mailto:first@example.org"));
    Assertions.assertTrue(metadata.contains("mailto:second@example.org"));
  }

  @Test
  void rawAddressMetadataMatchesEncodedExportWithoutChangingVisibleNames() throws Exception {
    EmailRecord record = new EmailRecord();
    record.to = "Leoncio E. de la Peña D. <leo@example.org>";
    record.rawTo = "\"Leoncio E. de la Peña D.\" <leo@example.org>";
    String visible = record.to;
    PstReader.normalizeExportedAddressHeaders(record);
    Assertions.assertEquals(Common.encodeAddressHeader("\"Leoncio E. de la Peña D.\" <leo@example.org>"), record.rawTo);
    Assertions.assertEquals(visible, record.to);
    String once = record.rawTo;
    PstReader.normalizeExportedAddressHeaders(record);
    Assertions.assertEquals(once, record.rawTo);
  }

  @Test
  void suppliedPstReplyExportsItsNativeReferencesWhenTheTransportHeaderIsAbsent() throws Exception {
    String reference = "<3148510c2360443396a78d35e0888de9@pf.gov.br>";
    var records = PstReader.readPst(PstItemSelectionTests.SOURCE.toString(), "", List.of(), false);
    EmailRecord record = records.stream().filter(r -> r.messageId.equals(
        "<2915856a7d3449e68529f3e61b8d26bc@pf.gov.br>")).findFirst().orElseThrow();
    Assertions.assertEquals(List.of(reference), record.references);
    byte[] raw = Common.synthesizeEml(record);
    var exported = new MimeMessage(EmlReader.mimeSession(raw), new ByteArrayInputStream(raw));
    Assertions.assertEquals(reference, exported.getHeader("References", null));
    Assertions.assertEquals(7, records.size());
    // Other stored transport references retain their supplied values.
    Assertions.assertTrue(records.stream().anyMatch(r -> r.references.equals(List.of(
        "<CAJ+FrY6C_Hp_b-Pzx2VqUqnonx9Dei8kcXDV7j1wPT99mNKsuA@mail.gmail.com>"))));
  }

  @Test
  void absentPstRecipientsDoNotBecomeNulHeadersInExportedMessages() throws Exception {
    var records = PstReader.readPst(TestSupport.fixturePath("test.pst").toString(), "", List.of(), false);
    Assertions.assertEquals(71, records.size());
    EmailRecord first = records.get(0);
    Assertions.assertTrue(first.ccAgents.isEmpty());
    Assertions.assertTrue(first.bccAgents.isEmpty());
    Assertions.assertEquals("", first.rawCc, "Absent Cc must not become a NUL-valued header");
    Assertions.assertEquals("", first.rawBcc, "Absent Bcc must not become a NUL-valued header");
    for (EmailRecord record : records) {
      var exported = new MimeMessage(Session.getInstance(new Properties()),
          new ByteArrayInputStream(Common.synthesizeEml(record)));
      Assertions.assertEquals(-1, record.rawCc.indexOf(0), record.subject);
      Assertions.assertEquals(-1, record.rawBcc.indexOf(0), record.subject);
      if (record.rawCc.isEmpty()) Assertions.assertNull(exported.getHeader("Cc"), record.subject);
      if (record.rawBcc.isEmpty()) Assertions.assertNull(exported.getHeader("Bcc"), record.subject);
    }
    Assertions.assertEquals(59, records.stream().filter(record -> record.rawCc.isEmpty()).count());
    Assertions.assertEquals(71, records.stream().filter(record -> record.rawBcc.isEmpty()).count());
  }

  @Test
  void pstTransportRecipientTextAndExportStayExact() throws Exception {
    var records = PstReader.readPst(TestSupport.fixturePath("test.pst").toString(), "", List.of(), false);
    String original = "\"Lee  Dennis\" <Dennis.Lee@ENRON.com>";
    EmailRecord record = records.get(1);
    Assertions.assertEquals(original, record.rawCc);
    Assertions.assertEquals("Dennis.Lee@ENRON.com", record.ccAgents.get(0).email);
    String exported = new String(Common.synthesizeEml(record), StandardCharsets.UTF_8);
    Assertions.assertTrue(exported.contains("Cc: " + original + "\r\n"));
  }
  @Test
  void nativeTransportHeadersRemainInTheSourceSetWithoutChangingTheExportOrAttachmentCount() throws Exception {
    String headers = "From: Author <author@example.org>\r\nTo: reader@example.org\r\n"
        + "Date: Fri, 23 Oct 2026 12:00:00 +0000\r\nReceived: from relay.example.org\r\n\tby archive.example.org; Fri, 23 Oct 2026 12:00:00 +0000\r\n"
        + "Received: from origin.example.org by relay.example.org\r\nReturn-Path: <bounce@example.org>\r\nX-Archive-Note: Résumé 工作\r\n";
    try (var nativeItem = new PstItemSelectionTests.NativeItem()) {
      PstAuthorPreservationTests.strings(nativeItem.message, java.util.Map.of(0x007d, headers));
      EmailRecord record = PstReader.readMessage(nativeItem.message, "Inbox", PstItemSelectionTests.SOURCE.toString(), "", List.of(), false, 0);
      AssociatedFile companion = record.associatedFiles.stream().filter(file -> file.sourceSetMember
          && file.filename.equals("transport-headers.txt")).findFirst().orElseThrow();
      Assertions.assertArrayEquals(headers.getBytes(StandardCharsets.UTF_8), companion.data());
      Assertions.assertFalse(companion.emailAttachment);
      int count = record.attachmentCount;
      byte[] exported = Common.synthesizeEml(record);
      record.associatedFiles.remove(companion);
      Assertions.assertArrayEquals(exported, Common.synthesizeEml(record), "Source companions do not become MIME attachments or change MIME boundaries");
      record.associatedFiles.add(companion);
      java.nio.file.Path directory = java.nio.file.Files.createTempDirectory("pst-header-check-");
      try {
        for (String profile : List.of("s", "si")) {
          byte[] pdf = PdfMailWriter.buildSingleMessagePdf(directory.resolve(profile + ".pdf"), record,
              TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, profile).pdfBytes;
          try (var document = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            var files = AttachmentProfilePolicyTests.files(document);
            if (profile.equals("s")) {
              var file = files.get(companion.filename);
              Assertions.assertNotNull(file);
              Assertions.assertEquals("Supplement", file.getCOSObject().getNameAsString(org.apache.pdfbox.cos.COSName.getPDFName("AFRelationship")));
              Assertions.assertArrayEquals(companion.data(), AttachmentProfilePolicyTests.bytes(file));
              var source = files.values().stream().filter(f -> "Source".equals(f.getCOSObject().getNameAsString(
                  org.apache.pdfbox.cos.COSName.getPDFName("AFRelationship")))).findFirst().orElseThrow();
              Assertions.assertArrayEquals(exported, AttachmentProfilePolicyTests.bytes(source));
            } else {
              Assertions.assertTrue(files.isEmpty(), "Isolation omits the source set");
            }
            Assertions.assertEquals(count, record.attachmentCount);
            PdfCreationValidator.validate(document);
          }
        }
      } finally { Common.deleteTree(directory); }
    }
  }

}
