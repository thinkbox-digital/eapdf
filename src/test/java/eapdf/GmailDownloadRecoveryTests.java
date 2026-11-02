package eapdf;

import com.google.api.client.http.HttpResponseException;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.services.gmail.Gmail;
import java.io.InterruptedIOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GmailDownloadRecoveryTests {
  static Gmail service(AtomicInteger attempts, int... statuses) {
    var transport = new MockHttpTransport() {
      @Override
      public LowLevelHttpRequest buildRequest(String method, String url) {
        int index = attempts.getAndIncrement();
        int status = statuses[Math.min(index, statuses.length - 1)];
        var response = new MockLowLevelHttpResponse().setStatusCode(status).setContentType("application/json")
            .setContent(status == 200 ? "{\"id\":\"message-id\",\"raw\":\"U3ViamVjdDogVGVzdA0KDQpCb2R5\"}"
                : "{\"error\":{\"code\":" + status + ",\"message\":\"download failed\"}}");
        return new MockLowLevelHttpRequest(url).setResponse(response);
      }
    };
    return new Gmail.Builder(transport, GmailDownloadToPdf.JSON_FACTORY, request -> request.setNumberOfRetries(0))
        .setApplicationName("EmailToPDFA regression test").build();
  }

  @Test
  void temporaryFailureRetriesAndPreservesDownloadedMessage() throws Exception {
    var attempts = new AtomicInteger();
    var message = GmailDownloadToPdf.downloadMessage(service(attempts, 503, 200), "me", "message-id");
    Assertions.assertEquals(2, attempts.get());
    Assertions.assertEquals("message-id", message.getId());
    Assertions.assertEquals("Subject: Test\r\n\r\nBody", new String(java.util.Base64.getUrlDecoder().decode(message.getRaw()),
        java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void permanentFailureIsNotRetriedAndTemporaryFailuresHaveABound() {
    var permanentAttempts = new AtomicInteger();
    Assertions.assertThrows(HttpResponseException.class,
        () -> GmailDownloadToPdf.downloadMessage(service(permanentAttempts, 404), "me", "missing"));
    Assertions.assertEquals(1, permanentAttempts.get());
    var temporaryAttempts = new AtomicInteger();
    Assertions.assertThrows(HttpResponseException.class,
        () -> GmailDownloadToPdf.downloadMessage(service(temporaryAttempts, 429), "me", "limited"));
    Assertions.assertEquals(3, temporaryAttempts.get());
  }

  @Test
  void interruptionStopsRetriesAndPreservesInterruptStatus() {
    var attempts = new AtomicInteger();
    try {
      Thread.currentThread().interrupt();
      Assertions.assertThrows(InterruptedIOException.class,
          () -> GmailDownloadToPdf.downloadMessage(service(attempts, 503), "me", "cancelled"));
      Assertions.assertTrue(Thread.currentThread().isInterrupted());
      Assertions.assertEquals(1, attempts.get());
    } finally {
      Thread.interrupted();
    }
  }
}
