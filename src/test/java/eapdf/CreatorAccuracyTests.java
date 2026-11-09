package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class CreatorAccuracyTests {
  @TempDir
  Path tempDir;

  @Test
  void unsafeLinkActionsAreAbsentButSourceBytesAndSafeLinksRemain() throws Exception {
    String html = "<p><a href='javascript:alert(1)' onclick='alert(1)'>Script</a></p>"
        + "<p><a href='file:///private.txt'>Local</a></p><p><a href='data:text/html,example'>Data</a></p>"
        + "<p><a href='https://example.org/'>Web</a></p><p><a href='mailto:a@example.org'>Mail</a></p>";
    var record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/html", html, "unsafe-links.eml");
    byte[] original = record.sourceBytes.clone();
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      var uris = new java.util.HashSet<String>();
      for (var page : document.getPages()) for (var annotation : page.getAnnotations()) {
        var action = annotation.getCOSObject().getCOSDictionary(COSName.A);
        if (action != null && action.containsKey(COSName.URI)) uris.add(action.getString(COSName.URI));
      }
      Assertions.assertTrue(uris.contains("https://example.org/"));
      Assertions.assertTrue(uris.contains("mailto:a@example.org"));
      Assertions.assertTrue(uris.stream().noneMatch(u -> u.startsWith("javascript:") || u.startsWith("file:") || u.startsWith("data:")));
      boolean preserved = false;
      for (var spec : document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames().values()) {
        var embedded = spec.getEmbeddedFileUnicode() == null ? spec.getEmbeddedFile() : spec.getEmbeddedFileUnicode();
        preserved |= java.util.Arrays.equals(original, embedded.toByteArray());
      }
      Assertions.assertTrue(preserved, "Safety policy must not rewrite the original source");
    }
    for (String unsafe : List.of("JaVaScRiPt:alert(1)", "java\nscript:alert(1)", "vbscript:example", "//host/file", "file:/tmp/a", "data:text/plain,example"))
      Assertions.assertNull(PdfMailWriter.safeRenderedHref(unsafe, null));
    Assertions.assertEquals("#part", PdfMailWriter.safeRenderedHref("#part", null));
  }

  @Test
  void utf7EmailBodyIsDecodedAndRenderedWithoutLosingAccentsOrPlusSigns() throws Exception {
    var record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain; charset=UTF-7",
        "Caf+AOk +- r+AOk-sum+AOk-", "utf7.eml");
    Assertions.assertEquals("Café + résumé", record.textBody);
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      Assertions.assertTrue(new PDFTextStripper().getText(document).contains("Café + résumé"));
    }
  }

  @Test
  void hiddenFormInputsStayInSourceWithoutCreatingHiddenPdfWidgets() throws Exception {
    var record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/html",
        "<form action='https://example.org/search'><p>Search archive</p>"
            + "<INPUT TYPE=HIDDEN name=config value=private-token>"
            + "<input name=query value='Visible query'></form>", "form.eml");
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      Assertions.assertTrue(new PDFTextStripper().getText(document).contains("Search archive"));
      Assertions.assertNull(document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.ACRO_FORM),
          "Static form content must not leave an empty interactive form requiring new appearances");
      for (var page : document.getPages()) {
        for (var annotation : page.getAnnotations()) {
          Assertions.assertFalse(annotation.isHidden(), "Hidden form data must not create PDF annotations");
        }
      }
      boolean originalPreserved = false;
      for (var spec : document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames().values()) {
        var file = spec.getEmbeddedFileUnicode() == null ? spec.getEmbeddedFile() : spec.getEmbeddedFileUnicode();
        originalPreserved |= new String(file.toByteArray(), StandardCharsets.UTF_8).contains("value=private-token");
      }
      Assertions.assertTrue(originalPreserved);
    }
    Assertions.assertTrue(PdfMailWriter.canonicalizeEmailHtml(record.htmlBody, record, false).contains("Visible query"));
  }

  @Test
  void mailboxUrisPreserveLiteralPercentAndReservedCharacters() throws Exception {
    var addresses = java.util.Map.of(
        "user%20@example.org", "mailto:user%2520@example.org",
        "user%route@example.org", "mailto:user%25route@example.org",
        "a+b&c=d?e#f@example.org", "mailto:a+b%26c%3Dd%3Fe%23f@example.org",
        "\"a b,c\"@example.org", "mailto:%22a%20b%2Cc%22@example.org",
        "jörg@example.org", "mailto:j%C3%B6rg@example.org");
    for (var entry : addresses.entrySet()) {
      String uri = Common.mailto(entry.getKey());
      Assertions.assertEquals(entry.getValue(), uri);
      Assertions.assertEquals(entry.getKey(), java.net.URLDecoder.decode(
          uri.substring(7).replace("+", "%2B"), StandardCharsets.UTF_8));
    }
    EmailRecord record = parse("Fri, 29 Dec 2000 03:49:00 -0800", "text/plain", "Body", "percent.eml");
    record.fromAgents = List.of(Common.agent("Literal percent", "user%20@example.org"));
    record.rawFrom = "Literal percent <user%20@example.org>";
    String xmp = PdfMailWriter.buildXmpMetadata(record, "s");
    var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xmp.getBytes(StandardCharsets.UTF_8)));
    var from = (Element) xml.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/meta/", "From").item(0);
    var agent = (Element) from.getElementsByTagNameNS("http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Description").item(0);
    Assertions.assertEquals("mailto:user%2520@example.org", agent.getAttributeNS("http://xmlns.com/foaf/0.1/", "mbox"));
    Assertions.assertEquals(record.rawFrom, xml.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/meta/", "Raw-From").item(0).getTextContent());
  }

  EmailRecord parse(String date, String type, String body, String filename) throws Exception {
    String raw = "From: Sender <sender@example.org>\r\nTo: Recipient <recipient@example.org>\r\n"
        + "Subject: Accurate archive\r\n" + (date.isEmpty() ? "" : "Date: " + date + "\r\n")
        + "MIME-Version: 1.0\r\nContent-Type: " + type + "\r\n\r\n" + body;
    return EmlReader.readRfc822Bytes(raw.getBytes(StandardCharsets.UTF_8), filename, filename, "", List.of(), false);
  }

  byte[] create(EmailRecord record, String profile) throws Exception {
    return PdfMailWriter.buildSingleMessagePdf(tempDir.resolve("accuracy-" + profile + ".pdf"), record,
        TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1, profile).pdfBytes;
  }

  Element attachmentMetadata(PDDocument document) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document.getDocumentCatalog().getMetadata().toByteArray()));
    return (Element) xml.getElementsByTagNameNS("http://www.pdfa.org/eapdf/ns/meta/", "attachments").item(0);
  }

  @Test
  void validDateCommentsDoNotProduceUnnecessaryRawSent() throws Exception {
    for (String date : List.of("Thu, 30 Sep 2010 13:24:56 GMT", "Fri, 29 Dec 2000 03:49:00 -0800 (PST)")) {
      EmailRecord record = parse(date, "text/plain", "Body", "date.eml");
      Assertions.assertNotNull(Common.parseMessageDate(record.rawDateHeader));
      Assertions.assertEquals(Common.parseMessageDate(record.sentXmp), Common.parseMessageDate(date));
      try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
        Assertions.assertFalse(TestSupport.metadataXml(document).contains("<pdfmailmeta:Raw-Sent>"));
        Assertions.assertTrue(record.conversionWarnings.stream().noneMatch(w -> w.contains("Invalid source sent date")));
        var roles = new java.util.HashSet<String>();
        collectStructureRoles(document.getDocumentCatalog().getStructureTreeRoot().getCOSObject(), roles);
        Assertions.assertTrue(roles.contains("Mail_FieldName"), roles.toString());
        Assertions.assertTrue(roles.contains("Mail_FieldValue"), roles.toString());
      }
    }
  }

  @Test
  void trailingDateCommentsDoNotHideInvalidDateText() {
    String date = "Fri, 29 Dec 2000 03:49:00 -0800";
    var expected = java.time.Instant.parse("2000-12-29T11:49:00Z");
    for (String suffix : List.of(" (PST)", " (Pacific (standard) time)", "\r\n\t(PST) (archived)")) {
      Assertions.assertEquals(expected, Common.parseMessageDate(date + suffix).toInstant());
    }
    for (String invalid : List.of(date + " garbage", date + " (PST) trailing", date + " (unclosed",
        date + " (PST))", "Thu, 31 Feb 2026 12:00:00 +0000 (UTC)")) {
      Assertions.assertNull(Common.parseMessageDate(invalid), invalid);
    }
  }

  @Test
  void rtfBodySurvivesExportAndPayloadStaging() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain", "Body", "rtf.eml");
    record.sourceBytes = null;
    record.associatedFiles.clear();
    String original = "{\\rtf1\\ansi Original RTF body}";
    PstReader.populatePstBody(record, "", "", original);
    Assertions.assertEquals("text/rtf; charset=UTF-8", record.bodyMime);
    try (PayloadStore store = new PayloadStore()) {
      store.stage(record);
      Assertions.assertNull(record.rtfBody);
      Assertions.assertEquals(original, Common.rtfBody(record));
      var message = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
          new ByteArrayInputStream(Common.synthesizeEml(record)));
      var parts = (jakarta.mail.Multipart) message.getContent();
      boolean found = false;
      for (int index = 0; index < parts.getCount(); index++) {
        var part = parts.getBodyPart(index);
        if (!part.isMimeType("text/rtf")) continue;
        Assertions.assertEquals(original, new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        found = true;
      }
      Assertions.assertTrue(found);
    }
  }

  void collectStructureRoles(org.apache.pdfbox.cos.COSBase value, java.util.Set<String> roles) {
    if (value instanceof org.apache.pdfbox.cos.COSObject reference) value = reference.getObject();
    if (value instanceof org.apache.pdfbox.cos.COSArray array) {
      for (var child : array) collectStructureRoles(child, roles);
    } else if (value instanceof org.apache.pdfbox.cos.COSDictionary dictionary) {
      String role = dictionary.getNameAsString(COSName.S);
      if (role != null) roles.add(role);
      collectStructureRoles(dictionary.getDictionaryObject(COSName.K), roles);
    }
  }

  @Test
  void parallelLinkificationDoesNotIterateJsoupsSharedEmptyChildList() throws Exception {
    var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
    var start = new java.util.concurrent.CountDownLatch(1);
    var readers = new java.util.concurrent.atomic.AtomicInteger(2);
    var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
    try {
      for (int worker = 0; worker < 4; worker++) {
        boolean mutateEmpty = worker < 2;
        futures.add(pool.submit(() -> {
          start.await();
          if (mutateEmpty) {
            var empty = new org.jsoup.nodes.Element("span");
            while (readers.get() > 0 && !Thread.currentThread().isInterrupted()) empty.empty();
          } else {
            try {
              for (int iteration = 0; iteration < 2000; iteration++) {
                var body = org.jsoup.Jsoup.parseBodyFragment(
                    "<div><span></span><br>Visit https://example.org/test.</div>").body();
                PdfMailWriter.linkifyBodyText(body);
                Assertions.assertEquals("Visit https://example.org/test.", body.text());
                Assertions.assertEquals(1, body.select("a").size());
                Assertions.assertEquals("https://example.org/test", body.selectFirst("a").attr("href"));
              }
            } finally { readers.decrementAndGet(); }
          }
          return null;
        }));
      }
      start.countDown();
      for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
      Assertions.assertTrue(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  void visibleUrlsAreLinkedWithoutChangingDisplayedTextOrExistingLinks() {
    var body = org.jsoup.Jsoup.parseBodyFragment("Visit https://example.org/path, www.example.org. "
        + "<a href='https://existing.example.org'>https://existing.example.org</a><script>https://ignored.example.org</script>").body();
    String original = body.text();
    PdfMailWriter.linkifyBodyText(body);
    Assertions.assertEquals(original, body.text());
    Assertions.assertEquals(3, body.select("a").size());
    Assertions.assertEquals("https://example.org/path", body.select("a").get(0).attr("href"));
    Assertions.assertEquals("http://www.example.org", body.select("a").get(1).attr("href"));
  }

  @Test
  void canonicalizationDoesNotUpgradeOrRetargetBodyHyperlinks() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/html",
        "<p>http://example.org/plain</p><a href='http://example.org/explicit'>http://example.org/explicit</a>", "links.eml");
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      var targets = new java.util.HashSet<String>();
      for (var page : document.getPages()) {
        for (var annotation : page.getAnnotations()) {
          if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink link
              && link.getAction() instanceof org.apache.pdfbox.pdmodel.interactive.action.PDActionURI action) {
            targets.add(action.getURI());
          }
        }
      }
      Assertions.assertTrue(targets.contains("http://example.org/plain"), targets.toString());
      Assertions.assertTrue(targets.contains("http://example.org/explicit"), targets.toString());
    }
  }

  @Test
  void multipageOutputsHaveVisiblePageContext() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/html",
        "<p>First</p><p style='page-break-before:always'>Second</p>", "pages.eml");
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      Assertions.assertTrue(document.getNumberOfPages() >= 2);
      String text = new PDFTextStripper().getText(document);
      Assertions.assertTrue(text.contains("Page 1 of " + document.getNumberOfPages()));
      Assertions.assertTrue(text.contains("Page 2 of " + document.getNumberOfPages()));
    }
  }

  @Test
  void validationWarningIsVisibleAndDistinctFromMissingSourceData() throws Exception {
    String previous = EapdfConfig.get().validationWarning;
    try {
      EapdfConfig.get().validationWarning = "Validation could not be completed. Review <initial> and final reports.";
      EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain", "Original body", "warning.eml");
      try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
        String text = new PDFTextStripper().getText(document);
        Assertions.assertTrue(text.contains("WARNING — Validation requires review"));
        Assertions.assertTrue(text.contains("Review <initial> and final reports."));
        Assertions.assertTrue(text.contains("Original body"));
        Assertions.assertFalse(text.contains("INCOMPLETE SOURCE"));
        Assertions.assertFalse(TestSupport.metadataXml(document).contains("<conversion:status>INCOMPLETE</conversion:status>"));
      }
      Assertions.assertTrue(record.sourceProblems.isEmpty());
    } finally { EapdfConfig.get().validationWarning = previous; }
  }

  @Test
  void collectionValidationWarningDoesNotMislabelFailedChecksAsUnfinished() throws Exception {
    String previous = EapdfConfig.get().validationWarning;
    try {
      EapdfConfig.get().validationWarning = "One or more validation checks failed.";
      var summary = new PdfMailWriter.CollectionRunSummary("1", "", 1, 1);
      try (var document = PdfAttachmentSupport.buildCollectionFrontMatter("c", summary, TestSupport.fontPath(), false)) {
        String text = new PDFTextStripper().getText(document);
        Assertions.assertTrue(text.contains("WARNING — Validation requires review"));
        Assertions.assertTrue(text.contains("Conformance was not established"));
        Assertions.assertFalse(text.contains("could not be completed"));
      }
    } finally { EapdfConfig.get().validationWarning = previous; }
  }

  @Test
  void missingSourceDateProducesMarkedOutputWithoutInventingADate() throws Exception {
    for (String profile : List.of("s", "si")) {
      EmailRecord record = parse("", "text/plain", "Original body", "missing.eml");
      byte[] bytes = create(record, profile);
      try (PDDocument document = Loader.loadPDF(bytes)) {
        String text = new PDFTextStripper().getText(document);
        Assertions.assertTrue(text.contains("INCOMPLETE SOURCE"));
        Assertions.assertTrue(text.contains("Original body"));
        String xml = TestSupport.metadataXml(document);
        Assertions.assertFalse(xml.contains("<pdfmailmeta:Sent>"));
        Assertions.assertTrue(xml.contains("<conversion:status>INCOMPLETE</conversion:status>"));
      }
      Assertions.assertEquals("", record.sentXmp);
      Assertions.assertEquals(1, record.sourceProblems.size());
      Path saved = tempDir.resolve("missing-" + profile + ".pdf");
      Files.write(saved, bytes);
      Assertions.assertThrows(Exception.class, () -> PdfCreationValidator.validate(saved));
      PdfCreationValidator.validate(saved, PreservationInventory.capture(List.of(record), profile));
    }
  }

  @Test
  void unsupportedHeaderCharactersAreDisclosedWithoutChangingOriginalBytes() throws Exception {
    byte[] original = ("From: Sender <sender@example.org>\r\nTo: recipient@example.org\r\n"
        + "Subject: Original\u0012subject\r\nDate: Fri, 16 Oct 2026 12:00:00 +0000\r\n"
        + "Content-Type: text/plain\r\n\r\nOriginal body").getBytes(StandardCharsets.UTF_8);
    var record = EmlReader.readRfc822Bytes(original, "control.eml", "control.eml", "", List.of(), false);
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      String text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
      Assertions.assertTrue(text.contains("INCOMPLETE SOURCE"));
      Assertions.assertTrue(text.contains("unsupported header characters"));
      Assertions.assertTrue(TestSupport.metadataXml(document).contains("<conversion:status>INCOMPLETE</conversion:status>"));
      boolean preserved = false;
      for (var spec : document.getDocumentCatalog().getNames().getEmbeddedFiles().getNames().values()) {
        var file = spec.getEmbeddedFileUnicode() == null ? spec.getEmbeddedFile() : spec.getEmbeddedFileUnicode();
        preserved |= java.util.Arrays.equals(original, file.toByteArray());
      }
      Assertions.assertTrue(preserved, "The original header octets must remain embedded unchanged");
    }
  }

  @Test
  void archivesAndCollectionsRetainMessagesWithMissingDates() throws Exception {
    for (String profile : List.of("m", "mi", "c", "ci")) {
      var missing = parse("", "text/plain", "Missing-date body", "missing.eml");
      var complete = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain", "Complete body", "complete.eml");
      missing.folderPath = "Drafts";
      complete.folderPath = "Inbox";
      var records = List.of(missing, complete);
      Path output = tempDir.resolve("incomplete-" + profile + ".pdf");
      if (profile.startsWith("c")) PdfMailWriter.writeEaPdfCollection(output, records,
          TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      else PdfMailWriter.writeEaPdfArchive(output, records,
          TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
      var expected = PreservationInventory.capture(records, profile);
      PdfCreationValidator.validate(output, expected);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        String xml = TestSupport.metadataXml(document);
        Assertions.assertTrue(xml.contains("<conversion:status>INCOMPLETE</conversion:status>"));
        Assertions.assertTrue(new PDFTextStripper().getText(document).contains("INCOMPLETE"));
        Assertions.assertTrue(xml.contains("<pdfmailmeta:Sent>" + complete.sentXmp + "</pdfmailmeta:Sent>"));
        xml = xml.replace("<conversion:status>INCOMPLETE</conversion:status>", "");
        document.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        Assertions.assertThrows(Exception.class, () -> PdfCreationValidator.validate(document, expected));
      }
    }
  }

  @Test
  void sourceDateLossIsStillRejectedWithCapturedInventory() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain", "Body", "valid.eml");
    byte[] bytes = create(record, "s");
    var inventory = PreservationInventory.capture(List.of(record), "s");
    Path damaged = tempDir.resolve("lost-date.pdf");
    try (PDDocument document = Loader.loadPDF(bytes)) {
      String xml = TestSupport.metadataXml(document).replaceAll("<pdfmailmeta:Sent>[^<]*</pdfmailmeta:Sent>", "");
      document.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
      document.save(damaged.toFile());
    }
    Assertions.assertThrows(Exception.class, () -> PdfCreationValidator.validate(damaged, inventory));
  }

  @Test
  void recoveredShortClockFieldsKeepTheOriginalDateText() throws Exception {
    for (String raw : List.of("Thu, 10 Jul 2003 14:3:46 -0400", "Tue, 20 May 2003 6:49:32 -0400")) {
      EmailRecord record = parse(raw, "text/plain", "Original body", "short-clock.eml");
      Assertions.assertNotNull(Common.parseMessageDate(raw));
      try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
        Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfmailmeta:Raw-Sent>" + raw + "</pdfmailmeta:Raw-Sent>"));
      }
    }
    Assertions.assertEquals(java.time.Instant.parse("2003-04-14T17:17:55Z"),
        Common.parseMessageDate("14 Apr 03 13:17:55 -0400").toInstant());
  }

  @Test
  void invalidCalendarDateRetainsRawSentAndReportsNormalization() throws Exception {
    String rawDate = "Tue, 31 Feb 2026 12:00:00 +0000";
    EmailRecord record = parse(rawDate, "text/plain", "Original body", "invalid.eml");
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfmailmeta:Raw-Sent>" + rawDate + "</pdfmailmeta:Raw-Sent>"));
      Assertions.assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("normalized Sent=")));
      Assertions.assertDoesNotThrow(() -> java.time.OffsetDateTime.parse(record.sentXmp));
    }
  }

  @Test
  void plainBodyKeepsItsOriginalMediaTypeAndSourceFilesUsePortableNames() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain; charset=UTF-8", "Plain original", "folder\\source.eml");
    Assertions.assertEquals("text/plain; charset=UTF-8", record.bodyMime);
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      boolean plainBody = false;
      for (var page : document.getPages()) {
        var part = (org.apache.pdfbox.cos.COSDictionary) page.getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
        var properties = (org.apache.pdfbox.cos.COSDictionary) part.getDictionaryObject(COSName.getPDFName("DPM"));
        if (properties != null) plainBody |= "text/plain; charset=UTF-8".equals(properties.getNameAsString(COSName.getPDFName("Mail_Subtype")));
      }
      Assertions.assertTrue(plainBody);
      for (var file : AttachmentProfilePolicyTests.files(document).values()) {
        Assertions.assertEquals("source.eml", file.getFileUnicode());
        var parameters = (org.apache.pdfbox.cos.COSDictionary) file.getEmbeddedFile().getCOSObject().getDictionaryObject(COSName.PARAMS);
        var checksum = (org.apache.pdfbox.cos.COSString) parameters.getDictionaryObject(COSName.getPDFName("CheckSum"));
        Assertions.assertEquals(Common.md5Hex(AttachmentProfilePolicyTests.bytes(file)), java.util.HexFormat.of().formatHex(checksum.getBytes()));
        Assertions.assertEquals(16, checksum.getBytes().length);
      }
      Assertions.assertEquals("source.eml", Common.portablePdfFilename("folder\\source.eml"));
      Assertions.assertEquals("notes_txt", Common.portablePdfFilename("../notes..txt"));
      Assertions.assertEquals("source.eml", Common.portablePdfFilename("C:source.eml"));
      Assertions.assertEquals("_source.eml", Common.portablePdfFilename("~source.eml"));
      Assertions.assertTrue(AttachmentProfilePolicyTests.files(document).containsKey("source.eml"));
    }
  }

  @Test
  void nativeAndIsolatedAttachmentsDescribeTheirStoredBytesAndRetainMimeParameters() throws Exception {
    String body = "--parts\r\nContent-Type: text/plain\r\n\r\nBody\r\n"
        + "--parts\r\nContent-Type: text/plain; charset=UTF-8; format=flowed\r\n"
        + "Content-Disposition: attachment; filename=notes.txt\r\n\r\npayload\r\n--parts--\r\n";
    for (String profile : List.of("s", "si")) {
      EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "multipart/mixed; boundary=parts", body, "native.eml");
      try (PDDocument document = Loader.loadPDF(create(record, profile))) {
        Element metadata = attachmentMetadata(document);
        String namespace = "http://www.pdfa.org/eapdf/ns/meta/";
        Assertions.assertEquals(profile.equals("s") ? "7" : "0", metadata.getElementsByTagNameNS(namespace, "SizeInBytes").item(0).getTextContent());
        Assertions.assertEquals("text/plain; charset=UTF-8; format=flowed", metadata.getElementsByTagNameNS(namespace, "Content-Type").item(0).getTextContent());
        Assertions.assertEquals(profile.equals("s") ? 1 : 0, metadata.getElementsByTagNameNS(namespace, "CheckSum").getLength());
        if (profile.equals("si")) Assertions.assertTrue(metadata.getTextContent().contains("original size=7"));
        boolean found = false;
        for (var page : document.getPages()) {
          for (var annotation : page.getAnnotations()) {
            if (!(annotation instanceof PDAnnotationFileAttachment attachment)) continue;
            var file = (PDComplexFileSpecification) attachment.getFile();
            if (!"notes.txt".equals(file.getFilename())) continue;
            found = true;
            Assertions.assertArrayEquals(profile.equals("s") ? "payload".getBytes(StandardCharsets.UTF_8) : new byte[0], AttachmentProfilePolicyTests.bytes(file));
            Assertions.assertEquals("charset=UTF-8; format=flowed", file.getEmbeddedFile().getCOSObject().getString(COSName.getPDFName("Mail_MediaTypeParameters")));
            var parameters = (org.apache.pdfbox.cos.COSDictionary) file.getEmbeddedFile().getCOSObject().getDictionaryObject(COSName.PARAMS);
            var checksum = (org.apache.pdfbox.cos.COSString) parameters.getDictionaryObject(COSName.getPDFName("CheckSum"));
            Assertions.assertEquals(16, checksum.getBytes().length);
            Assertions.assertEquals(Common.md5Hex(AttachmentProfilePolicyTests.bytes(file)), java.util.HexFormat.of().formatHex(checksum.getBytes()));
          }
        }
        Assertions.assertTrue(found);
        PdfCreationValidator.validate(document);
      }
    }
  }

  @Test
  void stylesheetVisibilityIsPreservedWithoutHidingGeneratedHeaders() throws Exception {
    String html = "<html><head><style>.hidden {display:none} .hdr {display:none} body {color:#123456}</style></head>"
        + "<body><p class=hidden>HIDDENMARKER</p><p>VISIBLEMARKER</p></body></html>";
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/html; charset=UTF-8", html, "styled.eml");
    try (PDDocument document = Loader.loadPDF(create(record, "s"))) {
      String text = new PDFTextStripper().getText(document);
      Assertions.assertFalse(text.contains("HIDDENMARKER"), PdfMailWriter.canonicalizeEmailHtml(html, record, false));
      Assertions.assertTrue(text.contains("VISIBLEMARKER"));
      Assertions.assertTrue(text.contains("sender@example.org"));
      Assertions.assertTrue(text.contains("Accurate archive"));
    }
  }

  @Test
  void omittedExternalStylesProduceAnExplicitFidelityWarning() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/html",
        "<style>@import url('https://example.invalid/mail.css');</style><link rel=stylesheet href='https://example.invalid/other.css'><p>Offline body</p>", "offline.eml");
    String rendered = PdfMailWriter.buildRenderHtml(record, false, tempDir, "s");
    Assertions.assertFalse(rendered.contains("https://example.invalid"));
    Assertions.assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("Reduced CSS rendering")));
  }

  @Test
  void exportedSourceRetainsAttachmentMediaTypeParametersWithoutInventingNewOnes() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("Exported source");
    long originalMetadataSize = ProcessingLimits.metadataSize(record);
    record.associatedFiles.get(0).rawContentType = "text/plain; charset=UTF-8; format=flowed";
    Assertions.assertEquals(originalMetadataSize + record.associatedFiles.get(0).rawContentType.length() * 2L,
        ProcessingLimits.metadataSize(record));
    EmailRecord decoded = EmlReader.readRfc822Bytes(Common.synthesizeEml(record), "export.eml", "export.eml", "", List.of(), false);
    AssociatedFile attachment = decoded.associatedFiles.stream().filter(file -> file.emailAttachment).findFirst().orElseThrow();
    Assertions.assertTrue(Common.sameContentType(record.associatedFiles.get(0).rawContentType, attachment.rawContentType));
  }

  @Test
  void unscopableSelectorsAreReportedInsteadOfBroadeningTheirScope() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("Sibling stylesheet");
    String result = PdfMailWriter.canonicalizeEmailHtml("<style>.previous + p {display:none}</style><p>First</p><p>Second</p>", record, false);
    Assertions.assertFalse(result.contains("display: none"));
    Assertions.assertTrue(record.conversionWarnings.stream().anyMatch(warning -> warning.contains("sibling")));
  }

  @Test
  void serializedAcceptanceCheckRejectsCorruptionBeforeAtomicReplacement() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain", "Body", "valid.eml");
    byte[] valid = create(record, "s");
    Path output = tempDir.resolve("protected.pdf");
    Files.write(output, valid);
    Assertions.assertThrows(java.io.IOException.class, () -> Common.writePdfAtomically(output, candidate -> {
      try (PDDocument document = Loader.loadPDF(valid)) {
        String xml = TestSupport.metadataXml(document).replaceAll("<pdfmailmeta:Sent>[^<]*</pdfmailmeta:Sent>", "");
        document.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        document.save(candidate.toFile());
      }
    }));
    Assertions.assertArrayEquals(valid, Files.readAllBytes(output));
  }

  @Test
  void acceptanceCheckRejectsBadSourceChecksumsBeforeReplacingAnExistingPdf() throws Exception {
    EmailRecord record = parse("Fri, 16 Oct 2026 12:00:00 +0000", "text/plain", "Source body", "source.eml");
    byte[] valid = create(record, "s");
    Path output = tempDir.resolve("protected-source.pdf");
    Files.write(output, valid);
    for (String corruption : List.of("hexadecimal", "short", "wrong-digest", "integer", "unicode-stream")) {
      java.io.IOException failure = Assertions.assertThrows(java.io.IOException.class, () -> Common.writePdfAtomically(output, candidate -> {
        try (PDDocument document = Loader.loadPDF(valid)) {
          var file = AttachmentProfilePolicyTests.files(document).get("source.eml");
          var stream = file.getEmbeddedFile();
          if (corruption.equals("unicode-stream")) {
            stream = new org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile(document,
                new ByteArrayInputStream(AttachmentProfilePolicyTests.bytes(file)));
            file.setEmbeddedFileUnicode(stream);
          }
          var parameters = new org.apache.pdfbox.cos.COSDictionary();
          stream.getCOSObject().setItem(COSName.PARAMS, parameters);
          org.apache.pdfbox.cos.COSBase checksum = switch (corruption) {
            case "short" -> new org.apache.pdfbox.cos.COSString(new byte[15]);
            case "wrong-digest" -> new org.apache.pdfbox.cos.COSString(new byte[16]);
            case "integer" -> org.apache.pdfbox.cos.COSInteger.get(16);
            default -> new org.apache.pdfbox.cos.COSString(Common.md5Hex(record.sourceBytes));
          };
          parameters.setItem(COSName.getPDFName("CheckSum"), checksum);
          document.save(candidate.toFile());
        }
      }), corruption);
      Assertions.assertTrue(failure.getMessage().contains("checksum"), failure.getMessage());
      Assertions.assertArrayEquals(valid, Files.readAllBytes(output), corruption);
    }
  }

  @Test
  void acceptanceCheckRejectsBadAttachmentMetadataVisibilityAndDeclarations() throws Exception {
    EmailRecord record = AttachmentProfilePolicyTests.record("Acceptance boundary");
    record.associatedFiles.get(0).checksumMd5 = Common.md5Hex(AttachmentProfilePolicyTests.PAYLOAD);
    byte[] valid = create(record, "s");
    for (String corruption : List.of("checksum", "checksum-parameter", "size", "visibility", "declaration", "identification", "metadata")) {
      try (PDDocument document = Loader.loadPDF(valid)) {
        if (corruption.equals("metadata")) {
          document.getDocumentCatalog().setMetadata(null);
        } else if (corruption.equals("identification")) {
          String xml = TestSupport.metadataXml(document).replaceAll("<pdfmailid:conformance>[^<]*</pdfmailid:conformance>", "");
          document.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        } else if (corruption.equals("declaration")) {
          String xml = TestSupport.metadataXml(document).replace("<pdfd:conformsTo>", "<pdfd:notADeclaration>")
              .replace("</pdfd:conformsTo>", "</pdfd:notADeclaration>");
          document.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        } else {
          boolean changed = false;
          for (var page : document.getPages()) {
            for (var annotation : page.getAnnotations()) {
              if (!(annotation instanceof PDAnnotationFileAttachment attachment)) continue;
              var file = (PDComplexFileSpecification) attachment.getFile();
              if (!"native.txt".equals(file.getFilename())) continue;
              changed = true;
              if (corruption.equals("size")) file.getEmbeddedFile().setSize(999);
              if (corruption.equals("visibility")) attachment.setHidden(true);
              if (corruption.equals("checksum-parameter")) file.getEmbeddedFile().setCheckSum("incorrect checksum");
              if (corruption.equals("checksum")) {
                try (var stream = file.getEmbeddedFileUnicode().getCOSObject().createOutputStream()) {
                  stream.write(new byte[AttachmentProfilePolicyTests.PAYLOAD.length]);
                }
              }
            }
          }
          Assertions.assertTrue(changed, corruption);
        }
        Assertions.assertThrows(java.io.IOException.class, () -> PdfCreationValidator.validate(document), corruption);
      }
    }
  }
}
