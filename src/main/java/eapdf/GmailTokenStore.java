package eapdf;

import com.google.api.client.auth.oauth2.StoredCredential;
import com.google.api.client.util.store.AbstractDataStore;
import com.google.api.client.util.store.AbstractDataStoreFactory;
import com.google.api.client.util.store.DataStore;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/** Password-protected OAuth token storage for one client and requested account. */
final class GmailTokenStore extends AbstractDataStore<StoredCredential> {
  static final String PASSWORD_ENV = "EAPDF_GMAIL_TOKEN_PASSWORD";
  static final String KEY = "credential";
  private static final byte[] MAGIC = {'E', 'A', 'G', 1};
  private static final int SALT_BYTES = 16, NONCE_BYTES = 12, TAG_BITS = 128, FILE_LIMIT = 64 * 1024;
  private static final SecureRandom RANDOM = new SecureRandom();

  private final Path file, lockFile;
  private final String scope;
  private final char[] password;

  static DataStore<StoredCredential> open(Path root, String client, String account, char[] password) throws Exception {
    String scope = Common.sha256Hex((client + "\n" + account.strip().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    Path directory = root.resolve("encrypted").resolve(scope);
    Files.createDirectories(directory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Gmail token storage is not a private directory.");
    Common.makeOwnerOnly(directory);
    return new Factory(directory, scope, password).getDataStore(StoredCredential.DEFAULT_DATA_STORE_ID);
  }

  private static final class Factory extends AbstractDataStoreFactory {
    private final Path directory;
    private final String scope;
    private final char[] password;

    Factory(Path directory, String scope, char[] password) {
      this.directory = directory;
      this.scope = scope;
      this.password = password;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <V extends Serializable> DataStore<V> createDataStore(String id) throws IOException {
      if (!StoredCredential.DEFAULT_DATA_STORE_ID.equals(id)) throw new IOException("Unexpected Gmail token store.");
      return (DataStore<V>) new GmailTokenStore(this, id, directory, scope, password);
    }
  }

  private GmailTokenStore(Factory factory, String id, Path directory, String scope, char[] password) {
    super(factory, id);
    file = directory.resolve("tokens.bin");
    lockFile = directory.resolve("tokens.lock");
    this.scope = scope;
    this.password = password.clone();
  }

  private record Token(String access, String refresh, Long expires) {}

  @Override public Set<String> keySet() throws IOException { return get(KEY) == null ? Set.of() : Set.of(KEY); }
  @Override public Collection<StoredCredential> values() throws IOException {
    StoredCredential value = get(KEY);
    return value == null ? List.of() : List.of(value);
  }

  @Override public StoredCredential get(String key) throws IOException {
    if (!KEY.equals(key)) return null;
    return locked(() -> {
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
      if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid Gmail token file.");
      byte[] encoded;
      try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { encoded = input.readNBytes(FILE_LIMIT + 1); }
      if (encoded.length > FILE_LIMIT || encoded.length < MAGIC.length + SALT_BYTES + NONCE_BYTES + TAG_BITS / 8
          || !Arrays.equals(encoded, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) throw new IOException("Invalid Gmail token file.");
      ByteBuffer header = ByteBuffer.wrap(encoded);
      header.position(MAGIC.length);
      byte[] salt = new byte[SALT_BYTES], nonce = new byte[NONCE_BYTES];
      header.get(salt).get(nonce);
      try {
        byte[] clear = cipher(Cipher.DECRYPT_MODE, salt, nonce).doFinal(encoded, header.position(), header.remaining());
        try {
          Token token = GmailResumeStore.JSON.fromJson(new String(clear, StandardCharsets.UTF_8), Token.class);
          if (token == null) throw new IOException("Invalid Gmail token file.");
          return new StoredCredential().setAccessToken(token.access()).setRefreshToken(token.refresh())
              .setExpirationTimeMilliseconds(token.expires());
        } finally { Arrays.fill(clear, (byte) 0); }
      } catch (GeneralSecurityException | com.google.gson.JsonParseException error) {
        throw new IOException("Cannot unlock the Gmail token store. Check " + PASSWORD_ENV + ".", error);
      }
    });
  }

  @Override public DataStore<StoredCredential> set(String key, StoredCredential value) throws IOException {
    if (!KEY.equals(key)) throw new IOException("Unexpected Gmail credential key.");
    return locked(() -> {
      byte[] salt = new byte[SALT_BYTES], nonce = new byte[NONCE_BYTES];
      RANDOM.nextBytes(salt);
      RANDOM.nextBytes(nonce);
      Token token = new Token(value.getAccessToken(), value.getRefreshToken(), value.getExpirationTimeMilliseconds());
      byte[] clear = GmailResumeStore.JSON.toJson(token).getBytes(StandardCharsets.UTF_8);
      try {
        byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, salt, nonce).doFinal(clear);
        int length = MAGIC.length + salt.length + nonce.length + encrypted.length;
        if (length > FILE_LIMIT) throw new IOException("Gmail token exceeds the storage limit.");
        byte[] contents = ByteBuffer.allocate(length).put(MAGIC).put(salt).put(nonce).put(encrypted).array();
        Common.writeAtomically(file, temporary -> {
          Files.write(temporary, contents);
          Common.makeOwnerOnly(temporary);
        });
      } catch (Exception error) { throw new IOException("Cannot save the encrypted Gmail token.", error); }
      finally { Arrays.fill(clear, (byte) 0); }
      return this;
    });
  }

  @Override public DataStore<StoredCredential> clear() throws IOException {
    return locked(() -> { Files.deleteIfExists(file); return this; });
  }

  @Override public DataStore<StoredCredential> delete(String key) throws IOException { return KEY.equals(key) ? clear() : this; }

  private Cipher cipher(int mode, byte[] salt, byte[] nonce) throws GeneralSecurityException {
    var spec = new PBEKeySpec(password, salt, 600_000, 256);
    byte[] key;
    try { key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
    finally { spec.clearPassword(); }
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
      cipher.updateAAD(MAGIC);
      cipher.updateAAD(scope.getBytes(StandardCharsets.US_ASCII));
      return cipher;
    } finally { Arrays.fill(key, (byte) 0); }
  }

  @FunctionalInterface private interface Operation<T> { T run() throws IOException; }

  private <T> T locked(Operation<T> operation) throws IOException {
    synchronized (GmailTokenStore.class) {
      try (var channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
           var lock = channel.lock()) {
        Common.makeOwnerOnly(lockFile);
        return operation.run();
      }
    }
  }
}
