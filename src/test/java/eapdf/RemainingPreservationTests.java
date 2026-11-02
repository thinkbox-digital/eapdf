package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemainingPreservationTests {
  @TempDir
  Path tempDir;

  @Test
  void unnamedCidImagesAreCollectedWithoutChangingAttachmentCounts() throws Exception {
    String body = EmailContentRegressionTests.section("related", "text/html", "", "<p>Body</p><img src=\"cid:picture\"/>")
        + EmailContentRegressionTests.section("related", "image/png", "Content-ID: <picture>\r\nContent-Transfer-Encoding: base64\r\n", "AQID")
        + "--related--\r\n";
    EmailRecord record = EmailContentRegressionTests.parse("multipart/related; boundary=related", body);
    AssociatedFile image = record.associatedFiles.stream().filter(file -> file.inline).findFirst().orElseThrow();
    Assertions.assertArrayEquals(new byte[]{1, 2, 3}, image.bytes);
    Assertions.assertEquals("<picture>", image.contentId);
    Assertions.assertFalse(image.emailAttachment);
    Assertions.assertEquals(0, record.attachmentCount);
    Assertions.assertEquals("Body", Jsoup.parse(record.htmlBody).text());
  }

  @Test
  void inlineImagesSharingFilenameKeepDistinctUrisAndBytes() throws Exception {
    EmailRecord record = new EmailRecord();
    for (int index = 0; index < 2; index++) {
      AssociatedFile image = new AssociatedFile();
      image.filename = "image.png";
      image.mime = "image/png";
      image.contentId = "picture" + index;
      image.bytes = new byte[]{(byte)index, 2, 3};
      image.inline = true;
      record.associatedFiles.add(image);
    }
    String rewritten = PdfMailWriter.rewriteCidToFileUris("<img src=\"cid:picture0\"/><img src=\"cid:picture1\"/>", record.associatedFiles, record, false, tempDir);
    var images = Jsoup.parse(rewritten).select("img");
    Assertions.assertNotEquals(images.get(0).attr("src"), images.get(1).attr("src"));
    for (int index = 0; index < 2; index++) {
      String uri = images.get(index).attr("src");
      Assertions.assertArrayEquals(record.associatedFiles.get(index).bytes, record.renderAssets.get(uri));
      Assertions.assertArrayEquals(record.associatedFiles.get(index).bytes, Files.readAllBytes(Path.of(java.net.URI.create(uri))));
    }
    Assertions.assertEquals("image.png", record.associatedFiles.get(0).filename);
  }

  @Test
  void emptyAttachmentsRoundTripWithZeroSizeAndChecksum() throws Exception {
    String body = EmailContentRegressionTests.section("mixed", "text/plain", "", "Body")
        + EmailContentRegressionTests.section("mixed", "application/octet-stream", "Content-Disposition: attachment; filename=empty.bin\r\nContent-Transfer-Encoding: base64\r\n", "")
        + "--mixed--\r\n";
    EmailRecord original = EmailContentRegressionTests.parse("multipart/mixed; boundary=mixed", body);
    Assertions.assertEquals(1, original.attachmentCount);
    AssociatedFile attachment = original.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals(0, attachment.bytes.length);
    Assertions.assertEquals("d41d8cd98f00b204e9800998ecf8427e", attachment.checksumMd5);
    EmailRecord decoded = EmlReader.readRfc822Bytes(Common.synthesizeEml(original), "empty.eml", "empty.eml", "", List.of(), false);
    Assertions.assertEquals(1, decoded.attachmentCount);
    Assertions.assertEquals(0, decoded.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow().bytes.length);
    Assertions.assertTrue(PdfMailWriter.buildXmpMetadata(original).contains("<pdfmailmeta:SizeInBytes>0</pdfmailmeta:SizeInBytes>"));
  }

  @Test
  void pstRecipientsRecoverOnlyMissingGroupsAndRemoveDuplicates() {
    EmailRecord record = new EmailRecord();
    record.rawTo = "Original Header <header@example.org>";
    Common.addAgent(record.toAgents, "Original Header", "header@example.org");
    EmailRecord recovered = new EmailRecord();
    PstReader.addMissingRecipient(recovered.toAgents, "Other", "other@example.org");
    PstReader.addMissingRecipient(recovered.ccAgents, "Copy", "copy@example.org");
    PstReader.addMissingRecipient(recovered.bccAgents, "Hidden", "hidden@example.org");
    PstReader.addMissingRecipient(recovered.bccAgents, "Hidden again", "HIDDEN@example.org");
    PstReader.recoverMissingRecipientGroups(record, recovered);
    Assertions.assertEquals("header@example.org", record.toAgents.get(0).email);
    Assertions.assertEquals("copy@example.org", record.ccAgents.get(0).email);
    Assertions.assertEquals(1, record.bccAgents.size());
    Assertions.assertEquals("hidden@example.org", record.bccAgents.get(0).email);
    Assertions.assertEquals("Original Header <header@example.org>", record.rawTo);
  }

  @Test
  void pstSentDateUsesOriginalThenSubmissionThenDelivery() {
    Date original = Date.from(Instant.parse("2026-10-13T12:00:00Z"));
    Date submitted = Date.from(Instant.parse("2026-10-13T13:00:00Z"));
    Date delivered = Date.from(Instant.parse("2026-10-13T14:00:00Z"));
    EmailRecord record = new EmailRecord();
    record.rawDateHeader = "Tue, 13 Oct 2026 08:00:00 -0400";
    PstReader.populatePstSentDate(record, submitted, delivered);
    Assertions.assertEquals(original, Common.parseMessageDate(record.sentXmp));
    Assertions.assertEquals(Common.fmtDate(original), record.date);
    Assertions.assertEquals("Tue, 13 Oct 2026 08:00:00 -0400", record.rawDateHeader);
    record.rawDateHeader = "";
    PstReader.populatePstSentDate(record, submitted, delivered);
    Assertions.assertEquals(submitted, Common.parseMessageDate(record.sentXmp));
    record.rawDateHeader = "";
    PstReader.populatePstSentDate(record, null, delivered);
    Assertions.assertEquals(delivered, Common.parseMessageDate(record.sentXmp));
    record.rawDateHeader = "not a date";
    PstReader.populatePstSentDate(record, submitted, delivered);
    Assertions.assertEquals(submitted, Common.parseMessageDate(record.sentXmp));
    Assertions.assertEquals("not a date", record.rawDateHeader);
  }

  @Test
  void pstMessageIdFallbackKeepsRecoveredHeaderAndReportsConflicts() {
    EmailRecord record = new EmailRecord();
    record.messageId = "<header@example.org>";
    PstReader.preservePstMessageId(record, "  ");
    Assertions.assertEquals("<header@example.org>", record.messageId);
    PstReader.preservePstMessageId(record, "<dedicated@example.org>");
    Assertions.assertEquals("<dedicated@example.org>", record.messageId);
    Assertions.assertEquals(1, record.conversionWarnings.size());
    Assertions.assertTrue(record.conversionWarnings.get(0).contains("<header@example.org>"));
  }

  @Test
  void chronologyUsesInstantsAcrossFormatsAndPutsUnknownDatesLast() {
    EmailRecord earlier = new EmailRecord();
    earlier.rawDateHeader = "Tue, 13 Oct 2026 08:30:00 -0400";
    EmailRecord later = new EmailRecord();
    later.sentXmp = "2026-10-13T14:00:00+01:00";
    EmailRecord missing = new EmailRecord();
    missing.date = "invalid";
    List<EmailRecord> expected = List.of(earlier, later, missing);
    Assertions.assertEquals(expected, PdfCollectionSupport.sortRecordsChronologically(List.of(missing, later, earlier)));
    Assertions.assertEquals(expected, PdfMailWriter.sortRecordsChronologically(List.of(missing, later, earlier)));
    Assertions.assertNull(Common.parseMessageDate("Thu, 31 Feb 2026 12:00:00 +0000"));
  }

  @Test
  void replyHeadersSurviveReadingReconstructionAndBothXmpPaths() throws Exception {
    byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nFrom: author@example.org\r\nMessage-ID: <reply@example.org>\r\n"
        + "In-Reply-To: <parent@example.org> <other@example.org>\r\n"
        + "References: <root@example.org>\r\n <parent@example.org>\r\n\r\nReply").getBytes(StandardCharsets.UTF_8);
    EmailRecord original = EmlReader.readRfc822Bytes(raw, "reply.eml", "reply.eml", "", List.of(), false);
    EmailRecord decoded = EmlReader.readRfc822Bytes(Common.synthesizeEml(original), "reply.eml", "reply.eml", "", List.of(), false);
    Assertions.assertEquals(original.inReplyTo, decoded.inReplyTo);
    Assertions.assertEquals(original.references, decoded.references);
    Assertions.assertEquals("<reply@example.org>", decoded.messageId);
    for (String metadata : List.of(PdfMailWriter.buildXmpMetadata(original), PdfMailWriter.buildXmpMetadata(List.of(original), "m"))) {
      Assertions.assertTrue(metadata.contains("<pdfmailmeta:In-Reply-To><rdf:Bag><rdf:li>&lt;parent@example.org&gt; &lt;other@example.org&gt;</rdf:li>"));
    }
  }

  @Test
  void gmailSelectionResolvesNamesIdsAndAllAliasesWithoutAcceptingTypos() {
    Map<String, String> labels = Map.of("INBOX", "Inbox", "Label_1", "Projects", "Label_2", "Team, Finance");
    Assertions.assertEquals(List.of("Label_1"), GmailDownloadToPdf.resolveLabelIds("Projects", labels));
    Assertions.assertEquals(List.of("Label_1"), GmailDownloadToPdf.resolveLabelIds("Label_1", labels));
    Assertions.assertEquals(List.of("INBOX", "Label_1"), GmailDownloadToPdf.resolveLabelIds("INBOX, Projects", labels));
    Assertions.assertEquals(List.of("Label_2"), GmailDownloadToPdf.resolveLabelIds("Team, Finance", labels));
    for (String alias : List.of("ALL", "all_mail", "*", "")) Assertions.assertEquals(List.of(), GmailDownloadToPdf.resolveLabelIds(alias, labels));
    Assertions.assertThrows(Common.UserFacingException.class, () -> GmailDownloadToPdf.resolveLabelIds("Project", labels));
    Assertions.assertThrows(Common.UserFacingException.class, () -> GmailDownloadToPdf.resolveLabelIds("Projects", Map.of("Label_1", "Projects", "Label_2", "Projects")));
  }

  @Test
  void premisObjectsAndEventLinksUseDistinctInternalEmailIdentities() throws Exception {
    EmailRecord first = AttachmentProfilePolicyTests.record("Duplicate");
    EmailRecord second = AttachmentProfilePolicyTests.record("Duplicate");
    first.messageId = second.messageId = "<duplicate@example.org>";
    for (EmailRecord record : List.of(first, second)) {
      AssociatedFile source = new AssociatedFile();
      source.filename = "same.eml";
      source.mime = "message/rfc822";
      source.afRelationship = "Source";
      source.bytes = Common.synthesizeEml(record);
      record.associatedFiles.add(source);
    }
    String xml = new String(PremisBuilder.buildAggregate(tempDir.resolve("archive.pdf"), List.of(first, second), "1m"), StandardCharsets.UTF_8);
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    var identifiers = document.getElementsByTagNameNS("http://www.loc.gov/premis/v3", "objectIdentifierValue");
    Set<String> unique = new HashSet<>();
    int originalIds = 0;
    for (int index = 0; index < identifiers.getLength(); index++) {
      String value = identifiers.item(index).getTextContent();
      if (value.equals("<duplicate@example.org>")) originalIds++;
      else Assertions.assertTrue(unique.add(value));
    }
    Assertions.assertEquals(2, originalIds);
    Assertions.assertTrue(unique.contains("record:" + first.mailGuid));
    Assertions.assertTrue(unique.contains("record:" + second.mailGuid));
    Assertions.assertEquals(2, unique.stream().filter(value -> value.startsWith("embedded:")).count());
    var links = document.getElementsByTagNameNS("http://www.loc.gov/premis/v3", "linkingObjectIdentifierValue");
    for (int index = 0; index < links.getLength(); index++) Assertions.assertTrue(unique.contains(links.item(index).getTextContent()));
  }

  @Test
  void encryptedBodyShowsAnExplicitNoticeAndPreservesOriginalSource() throws Exception {
    for (String type : List.of("application/pkcs7-mime; smime-type=enveloped-data", "application/x-pkcs7-mime", "multipart/encrypted; boundary=encrypted")) {
      String payload = type.startsWith("multipart/")
          ? EmailContentRegressionTests.section("encrypted", "application/pgp-encrypted", "", "Version: 1")
              + EmailContentRegressionTests.section("encrypted", "application/octet-stream", "", "Unreadable encrypted payload") + "--encrypted--\r\n"
          : "Unreadable encrypted payload";
      EmailRecord record = EmailContentRegressionTests.parse(type, payload);
      Assertions.assertTrue(record.textBody.contains("Encrypted email body could not be rendered"));
      Assertions.assertEquals(1, record.conversionWarnings.size());
      Assertions.assertTrue(new String(record.sourceBytes, StandardCharsets.UTF_8).contains("Unreadable encrypted payload"));
    }
    EmailRecord record = EmailContentRegressionTests.parse("application/pkcs7-mime; smime-type=enveloped-data", "encrypted payload");
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("encrypted.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      Assertions.assertTrue(new PDFTextStripper().getText(document).contains("Encrypted email body could not be rendered"));
      Assertions.assertArrayEquals(record.sourceBytes, AttachmentProfilePolicyTests.bytes(AttachmentProfilePolicyTests.files(document).values().iterator().next()));
    }
    EmailRecord empty = EmailContentRegressionTests.parse("text/plain", "");
    Assertions.assertTrue(empty.conversionWarnings.isEmpty());
    EmailRecord unsupported = EmailContentRegressionTests.parse("application/unknown-body", "Unreadable body");
    Assertions.assertTrue(unsupported.textBody.contains("Unsupported email body could not be rendered"));
    Assertions.assertEquals(1, unsupported.conversionWarnings.size());
    EmailRecord alternative = EmailContentRegressionTests.parse("multipart/alternative; boundary=choices",
        EmailContentRegressionTests.section("choices", "text/plain", "", "Supported readable alternative")
            + EmailContentRegressionTests.section("choices", "application/unknown-body", "", "Unreadable alternative") + "--choices--\r\n");
    Assertions.assertTrue(alternative.textBody.contains("Supported readable alternative"));
    Assertions.assertFalse(alternative.textBody.contains("could not be rendered"));
    Assertions.assertTrue(alternative.conversionWarnings.isEmpty(), "A discarded alternative is not the rendered email body");
    EmailRecord mixed = EmailContentRegressionTests.parse("multipart/mixed; boundary=sections",
        EmailContentRegressionTests.section("sections", "text/plain", "", "Readable section")
            + EmailContentRegressionTests.section("sections", "application/unknown-body", "", "Unreadable section") + "--sections--\r\n");
    Assertions.assertTrue(mixed.textBody.contains("could not be rendered"));
    Assertions.assertEquals(1, mixed.conversionWarnings.size(), "An unsupported section actually selected for rendering still needs a warning");
  }

  @Test
  void emptyMsgAttachmentIsNotDiscardedByTheReader() throws Exception {
    Path input = tempDir.resolve("empty.msg");
    try (var storage = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
      PreservationRegressionTests.writeMessageStorage(storage.getRoot(), 32, "Empty attachment", "Body", 1);
      var attachment = (org.apache.poi.poifs.filesystem.DirectoryNode)storage.getRoot().createDirectory("__attach_version1.0_#00000000");
      attachment.createDocument("__properties_version1.0", new java.io.ByteArrayInputStream(new byte[8]));
      attachment.createDocument("__substg1.0_3707001F", new java.io.ByteArrayInputStream("empty.bin\0".getBytes(StandardCharsets.UTF_16LE)));
      attachment.createDocument("__substg1.0_37010102", new java.io.ByteArrayInputStream(new byte[0]));
      try (var output = Files.newOutputStream(input)) {
        storage.writeFilesystem(output);
      }
    }
    EmailRecord record = MsgReader.readMsg(input.toString(), "", List.of(), false);
    Assertions.assertEquals(1, record.attachmentCount);
    AssociatedFile attachment = record.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals("empty.bin", attachment.filename);
    Assertions.assertEquals(0, attachment.bytes.length);
    Assertions.assertEquals("d41d8cd98f00b204e9800998ecf8427e", attachment.checksumMd5);
  }
}
