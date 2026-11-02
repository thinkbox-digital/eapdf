package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnfixedRegressionTests {
  @TempDir
  Path tempDir;

  @Test
  void successfulPlainTextFallbackIsReportedWithoutDebugAndKeepsSource() throws Exception {
    byte[] raw = "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Fallback\r\n\r\nOriginal body".getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "fallback.eml", "fallback.eml", "", List.of(), false);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    Path output = tempDir.resolve("fallback.pdf");
    try (Common.JobReport job = Common.beginJob(); var capture = Common.beginPerFileLog(output)) {
      byte[] rendered = PdfMailWriter.renderHtmlPdf("<html><body>Visible body<img src='cid:missing'/></body></html>",
          tempDir.toUri().toString(), TestSupport.fontPath(), record, false, (xhtml, baseUri, fontPath) -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("primary rendering failed");
            return PdfMailWriter.renderXhtmlPdf(xhtml, baseUri, fontPath);
          });
      try (PDDocument document = Loader.loadPDF(rendered)) {
        Assertions.assertTrue(new PDFTextStripper().getText(document).contains("Visible body"));
      }
      Assertions.assertEquals(2, attempts.get());
      Assertions.assertEquals(1, record.conversionWarnings.size());
      Assertions.assertTrue(record.conversionWarnings.get(0).contains("primary rendering failed"));
      Assertions.assertEquals(1, job.warnings.size());
      Assertions.assertArrayEquals(raw, record.sourceBytes);
    }
    String log = Files.readString(tempDir.resolve("logs/fallback.log"));
    Assertions.assertTrue(log.contains("Reduced rendering:"));
    Assertions.assertTrue(log.contains("Images, formatting, and links may be missing."));
  }

  @Test
  void archiveReadingHonorsOneWorkerAndStopsBeforeLaterInputsOnFailure() throws Exception {
    String missing = tempDir.resolve("missing.eml").toString();
    Path later = tempDir.resolve("later.eml");
    Files.writeString(later, "Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Later\r\n\r\nLater body");
    try (Common.JobReport job = Common.beginJob()) {
      job.workerLimit = 1;
      Common.inputState(missing, "accepted");
      Common.inputState(later.toString(), "accepted");
      Assertions.assertThrows(Exception.class,
          () -> EmailToPDFA.readManyForArchive(List.of(missing, later.toString()), "", List.of(), false));
      Assertions.assertEquals("started", job.inputs.get(missing));
      Assertions.assertEquals("accepted", job.inputs.get(later.toString()));
      Assertions.assertNull(job.payloads);
    }
  }

  @Test
  void workerShutdownWaitsForCleanupAndPreservesInterruptStatus() throws Exception {
    var started = new java.util.concurrent.CountDownLatch(1);
    var interrupted = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
    var restored = new java.util.concurrent.atomic.AtomicBoolean();
    workers.submit(() -> {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException exception) {
        interrupted.countDown();
        try {
          release.await();
        } catch (InterruptedException repeated) {
          Thread.currentThread().interrupt();
        }
      }
    });
    Thread shutdown = new Thread(() -> {
      Thread.currentThread().interrupt();
      Common.stopWorkers(workers);
      restored.set(Thread.currentThread().isInterrupted());
    });
    try {
      Assertions.assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS));
      shutdown.start();
      Assertions.assertTrue(interrupted.await(5, java.util.concurrent.TimeUnit.SECONDS));
      Assertions.assertTrue(shutdown.isAlive());
    } finally {
      release.countDown();
      shutdown.join(5000);
      Common.stopWorkers(workers);
    }
    Assertions.assertFalse(shutdown.isAlive());
    Assertions.assertTrue(workers.isTerminated());
    Assertions.assertTrue(restored.get());
  }

  @Test
  void jobAccountingAndWarningsIncludeFailedPendingAndSkippedInputs() throws Exception {
    var console = new java.io.ByteArrayOutputStream();
    var original = System.out;
    Path output = tempDir.resolve("warnings.pdf");
    try {
      System.setOut(new java.io.PrintStream(console, true, StandardCharsets.UTF_8));
      try (Common.JobReport job = Common.beginJob(); var capture = Common.beginPerFileLog(output)) {
        Common.inputState("done.eml", "completed");
        Common.inputState("failed.eml", "started");
        Common.inputState("later.eml", "accepted");
        Common.inputState("notes.txt", "skipped");
        EmailRecord record = new EmailRecord();
        record.sourcePath = "done.eml";
        record.subject = "Reduced content";
        Common.warn(record, "Reduced rendering: used plain text; images, formatting, and links may be missing.");
        Common.recordWarnings(record);
      }
    } finally {
      System.setOut(original);
    }
    String summary = console.toString(StandardCharsets.UTF_8);
    Assertions.assertTrue(summary.contains("accepted: 3 skipped: 1 completed: 1 failed: 1 not processed: 1"));
    Assertions.assertTrue(summary.contains("conversion warnings: 1"));
    Assertions.assertTrue(summary.contains("warning summary: done.eml | Reduced content | Reduced rendering:"));
    Assertions.assertTrue(Files.readString(tempDir.resolve("logs/warnings.log")).contains("Reduced rendering:"));
  }

  @Test
  void stagedPayloadsPreserveRenderingSourceBytesAndMetadataAndAreRemoved() throws Exception {
    byte[] raw = ("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nFrom: sender@example.org\r\nTo: reader@example.org\r\nSubject: Staged message\r\n"
        + "Content-Type: text/plain; charset=UTF-8\r\n\r\nPreserved body\r\n").getBytes(StandardCharsets.UTF_8);
    EmailRecord record = EmlReader.readRfc822Bytes(raw, "test.eml", "test.eml", "", List.of(), false);
    String originalXmp = PdfMailWriter.buildXmpMetadata(record);
    Path storage;
    try (PayloadStore store = new PayloadStore()) {
      storage = store.directory;
      store.stage(record);
      Assertions.assertNull(record.sourceBytes);
      Assertions.assertNull(record.htmlBody);
      Assertions.assertTrue(record.associatedFiles.stream().allMatch(file -> file.bytes == null));
      Assertions.assertArrayEquals(raw, record.associatedFiles.get(0).data());
      Assertions.assertEquals(raw.length, PdfMailWriter.messageMetadataSize(record));
      // Each generated document has its own identity and creation event. Staging
      // must preserve the email metadata, rather than repeat those output IDs.
      String generatedProperties = "<(xmp:(?:CreateDate|ModifyDate|MetadataDate)|xmpMM:(?:DocumentID|InstanceID)|stEvt:when)>.*?</\\1>";
      Assertions.assertEquals(originalXmp.replaceAll(generatedProperties, ""),
          PdfMailWriter.buildXmpMetadata(record).replaceAll(generatedProperties, ""));
      Path output = tempDir.resolve("staged.pdf");
      PdfMailWriter.writeEaPdfA3(output, record, TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, 1);
      try (PDDocument document = Loader.loadPDF(output.toFile())) {
        Assertions.assertTrue(new PDFTextStripper().getText(document).contains("Preserved body"));
        Assertions.assertArrayEquals(raw, AttachmentProfilePolicyTests.bytes(
            AttachmentProfilePolicyTests.files(document).values().iterator().next()));
      }
    }
    Assertions.assertFalse(Files.exists(storage));
  }

  @Test
  void stagedArchivesAndCollectionsPreserveMessagesAndChildPremis() throws Exception {
    boolean previousPremis = PdfMailWriter.CONFIG.premis.enabled;
    try (PayloadStore store = new PayloadStore()) {
      PdfMailWriter.CONFIG.premis.enabled = true;
      EmailRecord first = EmlReader.readRfc822Bytes("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: First\r\n\r\nFirst body".getBytes(StandardCharsets.UTF_8), "first.eml", "first.eml", "", List.of(), false);
      EmailRecord second = EmlReader.readRfc822Bytes("Date: Fri, 16 Oct 2026 12:00:00 +0000\r\nSubject: Second\r\n\r\nSecond body".getBytes(StandardCharsets.UTF_8), "second.eml", "second.eml", "", List.of(), false);
      store.stage(first);
      store.stage(second);
      for (String profile : List.of("m", "mi", "c", "ci")) {
        Path output = tempDir.resolve("staged_1" + profile + ".pdf");
        if (profile.startsWith("c")) PdfMailWriter.writeEaPdfCollection(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        else PdfMailWriter.writeEaPdfArchive(output, List.of(first, second), TestSupport.fontPath(), TestSupport.iccPath(), false, tempDir, profile);
        try (PDDocument document = Loader.loadPDF(output.toFile())) {
          Assertions.assertTrue(TestSupport.metadataXml(document).contains("<pdfmailid:conformance>" + profile + "</pdfmailid:conformance>"));
          if (profile.startsWith("c")) {
            String sidecar = Files.readString(PdfFindingAidSupport.premisSidecarPath(output));
            Assertions.assertEquals(3, sidecar.split("<premis:event>", -1).length - 1);
            Assertions.assertTrue(sidecar.contains("event:conversion:child:"));
            int children = 0;
            for (var file : AttachmentProfilePolicyTests.files(document).values()) {
              if (!"Contained EA-PDF message".equals(file.getCOSObject().getString(org.apache.pdfbox.cos.COSName.DESC))) continue;
              children++;
              try (PDDocument child = Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))) {
                Assertions.assertTrue(new PDFTextStripper().getText(child).contains("body"));
                if (profile.equals("ci")) Assertions.assertTrue(AttachmentProfilePolicyTests.files(child).isEmpty());
              }
            }
            Assertions.assertEquals(2, children);
          } else {
            String text = new PDFTextStripper().getText(document);
            Assertions.assertTrue(text.contains("First body"));
            Assertions.assertTrue(text.contains("Second body"));
          }
        }
      }
    } finally {
      PdfMailWriter.CONFIG.premis.enabled = previousPremis;
    }
  }

  @Test
  void indirectAllocationRetainsPageKeysAssignedByEarlierSave() throws Exception {
    try (PDDocument document = new PDDocument()) {
      document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      var page = document.getPage(0).getCOSObject();
      document.save(new java.io.ByteArrayOutputStream());
      var previousKey = page.getKey();
      Assertions.assertNotNull(previousKey);
      var reference = PdfDocumentParts.indirect(document, page);
      Assertions.assertEquals(previousKey, reference.getKey());
      document.getDocumentCatalog().getCOSObject().setItem("PageReference", reference);
      var output = new java.io.ByteArrayOutputStream();
      document.save(output);
      try (PDDocument reopened = Loader.loadPDF(output.toByteArray())) {
        Assertions.assertSame(reopened.getPage(0).getCOSObject(),
            reopened.getDocumentCatalog().getCOSObject().getDictionaryObject("PageReference"));
      }
    }
  }

  @Test
  void indirectAllocationReusesLoadedObjectsAndDoesNotOverwriteExistingNumbers() throws Exception {
    Path saved = tempDir.resolve("objects.pdf");
    Path initial = tempDir.resolve("original-objects.pdf");
    try (PDDocument document = new PDDocument()) {
      document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      COSDictionary original = new COSDictionary();
      original.setString("Marker", "original");
      document.getDocumentCatalog().getCOSObject().setItem("Original", new org.apache.pdfbox.cos.COSObject(original));
      document.save(initial.toFile());
    }
    try (PDDocument document = Loader.loadPDF(initial.toFile())) {
      var originalReference = (org.apache.pdfbox.cos.COSObject) document.getDocumentCatalog().getCOSObject().getItem("Original");
      COSDictionary original = (COSDictionary) originalReference.getObject();
      document.getDocument().setHighestXRefObjectNumber(900);
      Assertions.assertSame(originalReference, PdfDocumentParts.indirect(document, original));
      long last = 900;
      for (int index = 0; index < 2000; index++) {
        COSDictionary value = new COSDictionary();
        var reference = PdfDocumentParts.indirect(document, value);
        Assertions.assertTrue(reference.getObjectNumber() > last);
        Assertions.assertSame(reference, PdfDocumentParts.indirect(document, value));
        last = reference.getObjectNumber();
      }
      document.getDocumentCatalog().getCOSObject().setItem("Original", originalReference);
      document.save(saved.toFile());
    }
    try (PDDocument document = Loader.loadPDF(saved.toFile())) {
      COSDictionary original = (COSDictionary)document.getDocumentCatalog().getCOSObject().getDictionaryObject("Original");
      Assertions.assertEquals("original", original.getString("Marker"));
      var existing = PdfDocumentParts.indirect(document, original);
      Assertions.assertSame(existing, PdfDocumentParts.indirect(document, original));
      Assertions.assertNotEquals(existing.getObjectNumber(), PdfDocumentParts.indirect(document, new COSDictionary()).getObjectNumber());
    }
  }
}
