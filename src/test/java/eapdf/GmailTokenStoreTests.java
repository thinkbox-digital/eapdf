package eapdf;

import com.google.api.client.auth.oauth2.StoredCredential;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GmailTokenStoreTests {
  @TempDir Path directory;

  @Test void storesAreAuthenticatedAndScopedToClientAndAccount() throws Exception {
    Path legacy = Files.writeString(directory.resolve("StoredCredential"), "old credential cache");
    char[] password = "synthetic test token password".toCharArray();
    var store = GmailTokenStore.open(directory, "client-first", "owner@example.test", password);
    store.set(GmailTokenStore.KEY, new StoredCredential().setAccessToken("sample-access-token")
        .setRefreshToken("sample-refresh-token").setExpirationTimeMilliseconds(60000L));
    var reopened = GmailTokenStore.open(directory, "client-first", "OWNER@example.test", password);
    assertEquals("sample-refresh-token", reopened.get(GmailTokenStore.KEY).getRefreshToken());
    assertNull(GmailTokenStore.open(directory, "client-second", "owner@example.test", password).get(GmailTokenStore.KEY));
    assertNull(GmailTokenStore.open(directory, "client-first", "other@example.test", password).get(GmailTokenStore.KEY));
    var wrong = GmailTokenStore.open(directory, "client-first", "owner@example.test", "different password".toCharArray());
    assertThrows(IOException.class, () -> wrong.get(GmailTokenStore.KEY));
    try (var paths = Files.walk(directory.resolve("encrypted"))) {
      var token = paths.filter(path -> path.getFileName().toString().equals("tokens.bin")).findFirst().orElseThrow();
      assertFalse(new String(Files.readAllBytes(token), StandardCharsets.ISO_8859_1).contains("sample-refresh-token"));
    }
    assertEquals("old credential cache", Files.readString(legacy));
  }

  @Test void callbackUsesASeparateEphemeralLoopbackPort() throws Exception {
    var first = new GmailOAuthReceiver();
    var second = new GmailOAuthReceiver();
    try {
      URI one = URI.create(first.getRedirectUri()), two = URI.create(second.getRedirectUri());
      assertEquals("127.0.0.1", one.getHost());
      assertTrue(one.getPort() > 0);
      assertNotEquals(one.getPort(), two.getPort());
      assertNotEquals(first.state, second.state);
    } finally {
      first.stop();
      second.stop();
    }
  }
}
