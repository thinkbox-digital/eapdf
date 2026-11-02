package eapdf;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EmailContentRegressionTests {
  @TempDir
  Path tempDir;

  static EmailRecord parse(String contentType, String body) throws Exception {
    String message = "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nFrom: author@example.org\r\nSubject: Body test\r\nMIME-Version: 1.0\r\n"
        + "Content-Type: " + contentType + "\r\n\r\n" + body;
    return EmlReader.readRfc822Bytes(message.getBytes(StandardCharsets.UTF_8), "body.eml", "body.eml", "", List.of(), false);
  }

  static String section(String boundary, String contentType, String headers, String body) {
    return "--" + boundary + "\r\nContent-Type: " + contentType + "\r\n" + headers + "\r\n" + body + "\r\n";
  }

  @Test
  void mixedBodySectionsAreKeptInOrderWithoutIncludingTextAttachments() throws Exception {
    String body = section("mixed", "text/plain; charset=UTF-8", "", "First section")
        + section("mixed", "text/html; charset=UTF-8", "", "<p>Second section</p>")
        + section("mixed", "text/plain; charset=UTF-8", "", "Third section")
        + section("mixed", "text/plain", "Content-Disposition: attachment; filename=notes.txt\r\n", "Attachment only")
        + "--mixed--\r\n";
    EmailRecord record = parse("multipart/mixed; boundary=mixed", body);
    for (String rendered : List.of(record.textBody, record.htmlBody)) {
      Assertions.assertTrue(rendered.contains("First section"));
      Assertions.assertTrue(rendered.contains("Second section"));
      Assertions.assertTrue(rendered.contains("Third section"));
      Assertions.assertTrue(rendered.indexOf("First section") < rendered.indexOf("Second section"));
      Assertions.assertTrue(rendered.indexOf("Second section") < rendered.indexOf("Third section"));
      Assertions.assertFalse(rendered.contains("Attachment only"));
    }
    Assertions.assertTrue(record.associatedFiles.stream().anyMatch(file -> "notes.txt".equals(file.filename)));
  }

  @Test
  void alternativeBodySelectionFollowsOrderNotLengthAndDoesNotDuplicateSections() throws Exception {
    String alternative = section("alternatives", "text/plain", "", "Plain alternative")
        + section("alternatives", "text/html", "", "<p>Longer obsolete HTML alternative</p>")
        + section("alternatives", "text/html", "", "<p>Chosen HTML</p>")
        + "--alternatives--\r\n";
    String body = section("mixed", "multipart/alternative; boundary=alternatives", "", alternative)
        + section("mixed", "text/plain", "", "Separate section") + "--mixed--\r\n";
    EmailRecord record = parse("multipart/mixed; boundary=mixed", body);
    Assertions.assertTrue(record.htmlBody.contains("Chosen HTML"));
    Assertions.assertTrue(record.htmlBody.contains("Separate section"));
    Assertions.assertFalse(record.htmlBody.contains("obsolete"));
    Assertions.assertFalse(record.htmlBody.contains("Plain alternative"));
    Assertions.assertTrue(record.textBody.contains("Plain alternative"));
    Assertions.assertTrue(record.textBody.contains("Separate section"));
    EmailRecord reversed = parse("multipart/alternative; boundary=alternatives",
        section("alternatives", "text/html", "", "<p>Not selected</p>")
            + section("alternatives", "text/plain", "", "Final supported version") + "--alternatives--\r\n");
    Assertions.assertTrue(reversed.htmlBody.contains("Final supported version"));
    Assertions.assertFalse(reversed.htmlBody.contains("Not selected"));
  }

  @Test
  void meetingInvitationUsesItsReadableAlternativeWithoutAnUnreadableBodyWarning() throws Exception {
    String calendar = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Example//Meeting//EN\r\nMETHOD:REQUEST\r\n"
        + "BEGIN:VEVENT\r\nUID:meeting@example.org\r\nDTSTAMP:20261023T120000Z\r\n"
        + "DTSTART:20261026T140000Z\r\nDTEND:20261026T150000Z\r\nSUMMARY:Project meeting\r\n"
        + "ORGANIZER:mailto:author@example.org\r\nEND:VEVENT\r\nEND:VCALENDAR";
    EmailRecord record = parse("multipart/alternative; boundary=invitation",
        section("invitation", "text/plain; charset=UTF-8", "", "Project meeting, Monday at 14:00 UTC.")
            + section("invitation", "text/html; charset=UTF-8", "", "<p>Project meeting, Monday at <b>14:00 UTC</b>.</p>")
            + section("invitation", "text/calendar; charset=UTF-8; method=REQUEST", "", calendar)
            + "--invitation--\r\n");
    Assertions.assertEquals("text/html", Common.baseMimeType(record.bodyMime));
    Assertions.assertTrue(record.htmlBody.contains("14:00 UTC"));
    Assertions.assertFalse(record.htmlBody.contains("could not be rendered"));
    Assertions.assertTrue(record.conversionWarnings.isEmpty());
    Assertions.assertTrue(new String(record.sourceBytes, StandardCharsets.UTF_8).contains(calendar));
  }

  @Test
  void relatedRootSelectionUsesStartOrFirstPartInsteadOfLargestResource() throws Exception {
    String resource = section("related", "text/html", "Content-ID: <resource>\r\n", "<p>Long unrelated HTML resource</p>");
    String root = section("related", "text/html", "Content-ID: <root>\r\n", "<p>Root body</p>");
    EmailRecord explicit = parse("multipart/related; boundary=related; start=\"<root>\"", resource + root + "--related--\r\n");
    EmailRecord implicit = parse("multipart/related; boundary=related", root + resource + "--related--\r\n");
    Assertions.assertEquals("<p>Root body</p>", explicit.htmlBody.trim());
    Assertions.assertEquals("<p>Root body</p>", implicit.htmlBody.trim());
    Assertions.assertThrows(jakarta.mail.MessagingException.class,
        () -> parse("multipart/related; boundary=related; start=\"<missing>\"", root + "--related--\r\n"));
  }

  @Test
  void synthesizedSourcePreservesInlineBytesAndCidWithoutTopLevelAttachments() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("Inline image");
    record.htmlBody = "<p>Body</p><img src=\"cid:picture@example.org\" />";
    AssociatedFile image = new AssociatedFile();
    image.filename = "picture.png";
    image.mime = "image/png";
    image.inline = true;
    image.contentId = "<picture@example.org>";
    image.afRelationship = "Supplement";
    ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
    image.bytes = imageBytes.toByteArray();
    record.associatedFiles.add(image);
    byte[] source = Common.synthesizeEml(record);
    Assertions.assertArrayEquals(source, Common.synthesizeEml(record));
    EmailRecord decoded = EmlReader.readRfc822Bytes(source, "source.eml", "source.eml", "", List.of(), false);
    Assertions.assertTrue(decoded.htmlBody.contains("cid:picture@example.org"));
    Assertions.assertTrue(decoded.associatedFiles.stream().anyMatch(file -> file.inline
        && Common.normalizeCidKey(file.contentId).equals("picture@example.org") && Arrays.equals(image.bytes, file.bytes)));
    Assertions.assertEquals(1, decoded.associatedFiles.stream().filter(file -> file.emailAttachment && !file.inline).count());
    byte[] pdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("inline.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "s").pdfBytes;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      var files = AttachmentProfilePolicyTests.files(document);
      Assertions.assertEquals(1, files.size());
      Assertions.assertArrayEquals(source, AttachmentProfilePolicyTests.bytes(files.values().iterator().next()));
    }
    Assertions.assertEquals(1, record.attachmentCount);
    byte[] isolatedPdf = PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("inline_isolated.pdf"), record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, "si").pdfBytes;
    try (PDDocument document = Loader.loadPDF(isolatedPdf)) {
      Assertions.assertTrue(AttachmentProfilePolicyTests.files(document).isEmpty());
      boolean foundImage = false;
      for (var page : document.getPages()) {
        for (COSName name : page.getResources().getXObjectNames()) {
          foundImage |= page.getResources().getXObject(name) instanceof org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
        }
      }
      Assertions.assertTrue(foundImage);
    }
    record.associatedFiles.removeIf(file -> "Source".equals(file.afRelationship) || file.emailAttachment);
    EmailRecord inlineOnly = EmlReader.readRfc822Bytes(Common.synthesizeEml(record), "inline.eml", "inline.eml", "", List.of(), false);
    Assertions.assertTrue(inlineOnly.associatedFiles.stream().anyMatch(file -> file.inline && Arrays.equals(image.bytes, file.bytes)));
    Assertions.assertTrue(inlineOnly.associatedFiles.stream().noneMatch(file -> file.emailAttachment && !file.inline));
  }

  @Test
  void synthesizedEmailRoundTripsUnicodeHeadersAndFilenamesWithoutChangingBytes() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("Résumé 中文 📎");
    record.rawFrom = "\"José 中文\" <author@example.org>";
    record.rawTo = "\"Zoë\" <recipient@example.org>";
    AssociatedFile attachment = record.associatedFiles.get(0);
    attachment.filename = "Résumé \"报价\".txt";
    byte[] bytes = Common.synthesizeEml(record);
    EmailRecord decoded = EmlReader.readRfc822Bytes(bytes, "unicode.eml", "unicode.eml", "", List.of(), false);
    Assertions.assertEquals(record.subject, decoded.subject);
    Assertions.assertEquals("José 中文", decoded.fromAgents.get(0).name);
    Assertions.assertEquals("Zoë", decoded.toAgents.get(0).name);
    AssociatedFile preserved = decoded.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertEquals(attachment.filename, preserved.filename);
    Assertions.assertArrayEquals(attachment.bytes, preserved.bytes);
    attachment.filename = "中文".repeat(100) + ".txt";
    decoded = EmlReader.readRfc822Bytes(Common.synthesizeEml(record), "long.eml", "long.eml", "", List.of(), false);
    Assertions.assertEquals(attachment.filename, decoded.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow().filename);
    MimeMessage message = new MimeMessage(Session.getInstance(new java.util.Properties()), new ByteArrayInputStream(bytes));
    Assertions.assertNull(message.getHeader("Message-ID"));
    Assertions.assertEquals(record.rawDateHeader, message.getHeader("Date", null));
  }

  @Test
  void rtfOnlyPstBodiesAreConvertedAndMalformedRtfIsReported() {
    EmailRecord record = new EmailRecord();
    record.subject = "RTF email";
    PstReader.populatePstBody(record, "", "", "{\\rtf1\\ansi Hello \\b rich text\\b0\\par Second paragraph}");
    Assertions.assertTrue(record.textBody.contains("Hello"));
    Assertions.assertTrue(record.textBody.contains("rich text"));
    Assertions.assertTrue(record.textBody.contains("Second paragraph"));
    Assertions.assertTrue(record.htmlBody.contains("rich text"));
    Assertions.assertEquals("text/rtf; charset=UTF-8", record.bodyMime);
    Common.UserFacingException error = Assertions.assertThrows(Common.UserFacingException.class,
        () -> PstReader.populatePstBody(record, "", "", "Not valid RTF"));
    Assertions.assertTrue(error.getMessage().contains("RTF email"));
    PstReader.populatePstBody(record, "Original plain text", "", "Not valid RTF");
    Assertions.assertEquals("Original plain text", record.textBody);
    Assertions.assertEquals("text/plain; charset=UTF-8", record.bodyMime);
    PstReader.populatePstBody(record, "", "<p>Original HTML</p>", "Not valid RTF");
    Assertions.assertEquals("<p>Original HTML</p>", record.htmlBody);
    Assertions.assertEquals("text/html; charset=UTF-8", record.bodyMime);
  }

  @Test
  void collectionCoreSchemaAndPerMessageValuesAreCorrectForBothProfiles() throws Exception {
    Map<String, String> required = Map.of("To", "S", "From", "S", "Sent", "D", "Subject", "S", "Message-ID", "S", "Cc", "S", "Bcc", "S", "Size", "Size", "Attachments", "N");
    for (String profile : List.of("c", "ci")) {
      EmailRecord record = AttachmentProfilePolicyTests.record("Core metadata");
      record.from = "author@example.org";
      record.to = "recipient@example.org";
      record.cc = "copy@example.org";
      record.bcc = "blind@example.org";
      record.messageId = "<core@example.org>";
      Path output = tempDir.resolve("core_1" + profile + ".pdf");
      PdfMailWriter.writeEaPdfCollection(output, List.of(record), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        COSDictionary schema = TestSupport.derefDict(TestSupport.collectionDictionary(document).getDictionaryObject(COSName.getPDFName("Schema")));
        for (Map.Entry<String, String> field : required.entrySet()) {
          COSDictionary definition = TestSupport.derefDict(schema.getDictionaryObject(COSName.getPDFName(field.getKey())));
          Assertions.assertNotNull(definition, field.getKey());
          Assertions.assertEquals(field.getValue(), definition.getNameAsString(COSName.getPDFName("Subtype")));
          Assertions.assertFalse(definition.getBoolean(COSName.getPDFName("E"), false));
        }
        int inspected = 0;
        for (var file : AttachmentProfilePolicyTests.files(document).values()) {
          if (!"Contained EA-PDF message".equals(file.getCOSObject().getString(COSName.DESC))) continue;
          COSDictionary item = TestSupport.derefDict(file.getCOSObject().getDictionaryObject(COSName.getPDFName("CI")));
          Assertions.assertEquals(record.subject, item.getString(COSName.getPDFName("Subject")));
          Assertions.assertEquals(record.from, item.getString(COSName.getPDFName("From")));
          Assertions.assertEquals(record.to, item.getString(COSName.getPDFName("To")));
          Assertions.assertEquals(record.cc, item.getString(COSName.getPDFName("Cc")));
          Assertions.assertEquals(record.bcc, item.getString(COSName.getPDFName("Bcc")));
          Assertions.assertEquals(record.messageId, item.getString(COSName.getPDFName("Message-ID")));
          Assertions.assertEquals("D:20261013120000+00'00'", item.getString(COSName.getPDFName("Sent")));
          Assertions.assertEquals(PdfMailWriter.messageMetadataSize(record), item.getInt(COSName.getPDFName("Size")));
          Assertions.assertEquals(1, item.getInt(COSName.getPDFName("Attachments")));
          try (PDDocument child = Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))) {
            Assertions.assertEquals(profile.equals("ci") ? 0 : 1, AttachmentProfilePolicyTests.files(child).size());
          }
          inspected++;
        }
        Assertions.assertEquals(1, inspected);
      }
    }
  }

  @Test
  void offlineAlternativeRetainsMimeParametersAndReadableGeneratedHtml() throws Exception {
    String imageHtml = "<html><body><img src=\"https://images.example.invalid/status.png\"></body></html>";
    String plainPart = section("offline", "text/plain; charset=UTF-8; format=flowed", "", "Provided plain version");
    EmailRecord fallback = parse("multipart/alternative; boundary=offline", plainPart
        + section("offline", "text/html; charset=UTF-8", "", imageHtml) + "--offline--\r\n");
    var plainType = new jakarta.mail.internet.ContentType(fallback.bodyMime);
    Assertions.assertEquals("text/plain", plainType.getBaseType());
    Assertions.assertEquals("flowed", plainType.getParameter("format"));
    Assertions.assertTrue(fallback.htmlBody.contains("Provided plain version"));
    Assertions.assertTrue(new String(fallback.sourceBytes, StandardCharsets.UTF_8).contains(imageHtml));

    String styledHtml = imageHtml.replace("<body>", "<head><style>.status:before{content:'Approved'}</style></head>"
        + "<body><p class=\"status\"></p>");
    EmailRecord styled = parse("multipart/alternative; boundary=offline", plainPart
        + section("offline", "text/html; charset=UTF-8", "", styledHtml) + "--offline--\r\n");
    Assertions.assertEquals("text/html", new jakarta.mail.internet.ContentType(styled.bodyMime).getBaseType());
    Assertions.assertTrue(styled.htmlBody.contains("content:'Approved'"));
    Assertions.assertFalse(styled.htmlBody.contains("Provided plain version"));
  }
}
