package eapdf;

import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/** Desktop consent receiver bound only to an OS-selected IPv4 loopback port. */
final class GmailOAuthReceiver implements VerificationCodeReceiver {
  private static final String HOST = "127.0.0.1", CALLBACK = "/oauth2callback";
  final String state = UUID.randomUUID().toString();
  private final CompletableFuture<String> result = new CompletableFuture<>();
  private HttpServer server;

  @Override public String getRedirectUri() throws IOException {
    server = HttpServer.create(new InetSocketAddress(HOST, 0), 0);
    server.createContext(CALLBACK, this::receive);
    server.start();
    return "http://" + HOST + ":" + server.getAddress().getPort() + CALLBACK;
  }

  private void receive(HttpExchange request) throws IOException {
    String raw = request.getRequestURI().getRawQuery();
    Map<String, String> values = Map.of();
    if ("GET".equals(request.getRequestMethod()) && CALLBACK.equals(request.getRequestURI().getPath())
        && raw != null && raw.length() <= 16_384) {
      try { values = parameters(raw); }
      catch (IllegalArgumentException invalid) { /* Invalid callbacks do not complete consent. */ }
    }
    boolean matched = state.equals(values.get("state"));
    String code = matched ? values.get("code") : null;
    boolean success = code != null && !code.isBlank();
    boolean denied = matched && values.containsKey("error");
    String message = success ? "Gmail authorization received. You may close this tab."
        : denied ? "Gmail authorization was denied." : "Unrecognized authorization response.";
    byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
    try (request) {
      request.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
      request.getResponseHeaders().set("Cache-Control", "no-store");
      request.sendResponseHeaders(success ? 200 : 400, bytes.length);
      request.getResponseBody().write(bytes);
    } finally {
      if (success) result.complete(code);
      else if (denied) result.completeExceptionally(new IOException("Gmail authorization was denied."));
    }
  }

  private static Map<String, String> parameters(String query) {
    var values = new HashMap<String, String>();
    for (String field : query.split("&")) {
      int separator = field.indexOf('=');
      if (separator < 0) continue;
      String key = URLDecoder.decode(field.substring(0, separator), StandardCharsets.UTF_8);
      String value = URLDecoder.decode(field.substring(separator + 1), StandardCharsets.UTF_8);
      if (values.putIfAbsent(key, value) != null) throw new IllegalArgumentException("Duplicate callback parameter");
    }
    return values;
  }

  @Override public String waitForCode() throws IOException {
    try { return result.get(); }
    catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      var cancelled = new InterruptedIOException("Gmail authorization was interrupted.");
      cancelled.initCause(error);
      throw cancelled;
    } catch (ExecutionException error) {
      throw new IOException("Gmail authorization could not complete.", error.getCause());
    }
  }

  @Override public void stop() {
    if (server != null) server.stop(0);
  }
}
