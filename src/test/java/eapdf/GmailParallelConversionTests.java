package eapdf;

import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Message;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GmailParallelConversionTests {
  @TempDir Path temporary;

  static final String SOURCE = "From: sender@example.org\r\nTo: archive@example.org\r\n"
      + "Subject: Same subject\r\nDate: Thu, 19 Nov 2026 12:00:00 +0000\r\n"
      + "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nBody\r\n";

  static List<Message> messages(int count) {
    return java.util.stream.IntStream.rangeClosed(1, count)
        .mapToObj(i -> new Message().setId("message-" + i)).toList();
  }

  static Gmail service(AtomicInteger downloads, Thread caller) {
    var transport = new MockHttpTransport() {
      @Override public LowLevelHttpRequest buildRequest(String method, String url) {
        assertSame(caller, Thread.currentThread(), "Gmail requests must remain serialized on the caller");
        downloads.incrementAndGet();
        String id = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
        Message message = new Message().setId(id).setLabelIds(List.of("Label_1"))
            .setRaw(Base64.getUrlEncoder().withoutPadding().encodeToString(SOURCE.getBytes(StandardCharsets.UTF_8)));
        return new MockLowLevelHttpRequest(url).setResponse(new MockLowLevelHttpResponse()
            .setStatusCode(200).setContentType("application/json").setContent(GmailResumeStore.JSON.toJson(
                Map.of("id", id, "raw", message.getRaw(), "labelIds", message.getLabelIds()))));
      }
    };
    return new Gmail.Builder(transport, GmailDownloadToPdf.JSON_FACTORY, request -> request.setNumberOfRetries(0))
        .setApplicationName("EA-PDF concurrency regression").build();
  }

  static void requireTwoWorkers() {
    assumeTrue(Runtime.getRuntime().availableProcessors() >= 2);
    assumeTrue(Runtime.getRuntime().maxMemory() >= 256L * 1024 * 1024);
  }

  @Test void overlapsConversionsWithBoundedDownloadsAndSafeResume() throws Exception {
    requireTwoWorkers();
    Path output = Files.createDirectory(temporary.resolve("output"));
    AtomicInteger downloads = new AtomicInteger(), active = new AtomicInteger(), peak = new AtomicInteger();
    CountDownLatch firstPair = new CountDownLatch(2), firstPairChecked = new CountDownLatch(2);
    Thread caller = Thread.currentThread();
    var selected = messages(4);
    try (var job = Common.beginJob();
         var resume = new GmailResumeStore(temporary.resolve("resume"), "parallel", selected.stream().map(Message::getId).toList())) {
      job.workerLimit = 2;
      GmailDownloadToPdf.convertMessages(service(downloads, caller), "me", selected, Map.of("Label_1", "Projects"),
          resume, Map.of("out-pdf", output.toString(), "omit-source", true, "global-id", "urn:test:archive",
              "email", List.of("archive@example.org")), (path, record, sequence) -> {
            assertFalse(record.embedSourceMessage);
            assertEquals("urn:test:archive", record.archiveGlobalId);
            assertEquals(List.of("archive@example.org"), record.archiveEmails);
            assertNotSame(caller, Thread.currentThread());
            assertSame(job, Common.ACTIVE_JOB.get());
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
              if (sequence <= 2) {
                firstPair.countDown();
                assertTrue(firstPair.await(5, TimeUnit.SECONDS), "Two PDFs must be converted concurrently");
                assertEquals(2, downloads.get(), "Do not download an unbounded queue while workers are busy");
                firstPairChecked.countDown();
                assertTrue(firstPairChecked.await(5, TimeUnit.SECONDS));
              }
              assertEquals("gmail", record.sourceType);
              assertEquals(List.of("Projects"), record.folderMemberships);
              assertEquals("Body", Common.textBody(record).trim());
              Files.writeString(path, record.sourcePath);
            } finally { active.decrementAndGet(); }
          });
      assertEquals(2, peak.get());
      assertEquals(0, active.get());
      assertEquals(4, downloads.get());
      for (Message message : selected) assertTrue(resume.completed(message.getId(), output));
      try (var files = Files.list(output)) { assertEquals(4, files.count(), "Repeated subjects must not collide"); }
      GmailDownloadToPdf.convertMessages(service(downloads, caller), "me", selected, Map.of(), resume,
          Map.of("out-pdf", output.toString()), (path, record, sequence) -> fail("Completed PDFs must be skipped"));
      assertEquals(4, downloads.get());
    }
  }

  @Test void failedConversionCancelsOtherWorkersWithoutCheckpointingFailure() throws Exception {
    requireTwoWorkers();
    Path output = Files.createDirectory(temporary.resolve("output"));
    AtomicInteger downloads = new AtomicInteger();
    CountDownLatch otherStarted = new CountDownLatch(1), otherStopped = new CountDownLatch(1);
    var selected = messages(3);
    try (var job = Common.beginJob();
         var resume = new GmailResumeStore(temporary.resolve("resume"), "failure", selected.stream().map(Message::getId).toList())) {
      job.workerLimit = 2;
      IOException error = assertThrows(IOException.class, () -> GmailDownloadToPdf.convertMessages(
          service(downloads, Thread.currentThread()), "me", selected, Map.of(), resume,
          Map.of("out-pdf", output.toString()), (path, record, sequence) -> {
            if (sequence == 1) {
              assertTrue(otherStarted.await(5, TimeUnit.SECONDS));
              throw new IOException("conversion failed");
            }
            otherStarted.countDown();
            try { new CountDownLatch(1).await(); }
            finally { otherStopped.countDown(); }
          }));
      assertEquals("conversion failed", error.getMessage());
      assertTrue(otherStopped.await(1, TimeUnit.SECONDS));
      assertEquals(2, downloads.get());
      assertFalse(resume.completed("message-1", output));
      assertFalse(resume.completed("message-2", output));
    }
  }

  @Test void callerInterruptionStopsConversionWithoutDownloading() throws Exception {
    AtomicInteger downloads = new AtomicInteger();
    try (var job = Common.beginJob(); var resume = new GmailResumeStore(null, "", List.of("message-1"))) {
      Thread.currentThread().interrupt();
      assertThrows(Common.UserFacingException.class, () -> GmailDownloadToPdf.convertMessages(
          service(downloads, Thread.currentThread()), "me", messages(1), Map.of(), resume,
          Map.of("out-pdf", temporary.toString()), (path, record, sequence) -> fail("Cancelled")));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, downloads.get());
    } finally { Thread.interrupted(); }
  }

  @Test void archivePreparationRetainsSelectedOrderWithoutIndividualPdfs() throws Exception {
    requireTwoWorkers();
    AtomicInteger downloads = new AtomicInteger();
    try (var job = Common.beginJob(); var resume = new GmailResumeStore(null, "", List.of("message-1", "message-2"))) {
      job.workerLimit = 2;
      var records = GmailDownloadToPdf.convertMessages(service(downloads, Thread.currentThread()), "me", messages(2),
          Map.of("Label_1", "Projects"), resume, Map.of("archive-profile", "m", "out-pdf", temporary.toString()),
          (path, record, sequence) -> fail("Archive preparation must not create standalone PDFs"));
      assertEquals(List.of("gmail://message-1", "gmail://message-2"), records.stream().map(r -> r.sourcePath).toList());
      assertTrue(records.stream().allMatch(r -> r.payloadStaged));
      assertEquals(SOURCE.getBytes(StandardCharsets.UTF_8).length, job.largestSourceMessageBytes);
      assertEquals(2, PdfMailWriter.renderParallelism(records.size()), "Small Gmail archive records must render concurrently");
      job.largestSourceMessageBytes = Runtime.getRuntime().maxMemory() / 16;
      assertEquals(1, PdfMailWriter.renderParallelism(records.size()), "Large sources must retain their conservative memory bound");
      assertEquals(2, downloads.get());
    }
  }
}
