package eapdf;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import javax.net.ssl.SSLException;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.swing.NaiveUserAgent;
import com.openhtmltopdf.util.XRLog;

/** Renderer resource resolution and budgets; no PDF construction or writer callbacks. */
final class MailRenderPolicy {
private MailRenderPolicy() {}
static final ConcurrentMap<String, String> FAILED_REMOTE_ASSET_URLS = new ConcurrentHashMap<>();
static final FSUriResolver REMOTE_ASSET_URI_RESOLVER = new RemoteAssetUriResolver();
static final FSStreamFactory REMOTE_ASSET_STREAM_FACTORY = new BlacklistingHttpStreamFactory();
static final InheritableThreadLocal<EmailRecord> ACTIVE_RENDER_RECORD = new InheritableThreadLocal<>();
static final ThreadLocal<Set<String>> RENDERED_IMAGES = new ThreadLocal<>();
static final ThreadLocal<Map<String, String>> ORIENTED_IMAGE_URIS = new ThreadLocal<>();

static final class NullFsStream implements FSStream {
  @Override
  public InputStream getStream() {
    return null;
  }

  @Override
  public Reader getReader() {
    return null;
  }
}


static final class BlacklistingHttpStreamFactory implements FSStreamFactory {
  @Override
  public FSStream getUrl(String url) {
    String key = remoteAssetKey(url);
    if (key == null) return new NullFsStream();
    blacklistRemoteAsset(key, "External resource loading is disabled");
    return new NullFsStream();
  }
}

static final class RemoteAssetUriResolver implements FSUriResolver {
  final FSUriResolver delegate = new NaiveUserAgent.DefaultUriResolver();

  @Override
  public String resolveURI(String baseUri, String uri) {
    EmailRecord record = ACTIVE_RENDER_RECORD.get();
    boolean cid = uri != null && uri.regionMatches(true, 0, "cid:", 0, 4);
    AssociatedFile image = null;
    if (record != null && uri != null) {
      if (cid) image = findCidAsset(uri, record.associatedFiles);
      else image = findLocationAsset(uri, record);
    }
    if (image != null && image.hasData() && image.size() > 0) {
      byte[] bytes = image.data();
      String mime = Common.detectMimeType(image.filename, image.mime, bytes);
      if (!mime.startsWith("image/")) return null;
      ProcessingLimits.checkMessage(bytes.length, "inline CID image");
      checkRenderImage(bytes);
      return orientRenderImage("data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes));
    }
    if (cid) return null;
    String rawKey = remoteAssetKey(uri);
    if (rawKey != null && FAILED_REMOTE_ASSET_URLS.containsKey(rawKey)) {
      recordBlacklistedRemoteAssetEncounter(rawKey);
      return null;
    }
    String resolved = delegate.resolveURI(baseUri, uri);
    String resolvedKey = remoteAssetKey(resolved);
    if (resolvedKey != null && FAILED_REMOTE_ASSET_URLS.containsKey(resolvedKey)) {
      recordBlacklistedRemoteAssetEncounter(resolvedKey);
      return null;
    }
    if (resolved == null && rawKey != null) blacklistRemoteAsset(rawKey, "Remote asset URI resolution failed");
    if (isAllowedRenderResource(resolved)) return orientRenderImage(resolved);
    if (resolvedKey != null) blacklistRemoteAsset(resolvedKey, "External resource loading is disabled");
    return null;
  }
}

static boolean isAllowedRenderResource(String uri) {
  if (uri == null) return false;
  if (uri.regionMatches(true, 0, "data:", 0, 5)) {
    int comma = uri.indexOf(',');
    if (comma < 0) return false;
    String header = uri.substring(0, comma).toLowerCase(Locale.ROOT);
    if (header.startsWith("data:image/")) {
      byte[] bytes = decodeRenderImageUri(uri);
      ProcessingLimits.checkMessage(bytes.length, "inline data image");
      checkRenderImage(bytes);
    }
    return true;
  }
  EmailRecord record = ACTIVE_RENDER_RECORD.get();
  if (record == null || !record.renderAssets.containsKey(uri)) return false;
  checkRenderImage(record.renderAssets.get(uri));
  return true;
}

static byte[] decodeRenderImageUri(String uri) {
  int comma = uri.indexOf(',');
  String header = uri.substring(0, comma).toLowerCase(Locale.ROOT);
  return header.endsWith(";base64") ? Base64.getMimeDecoder().decode(uri.substring(comma + 1))
      : java.net.URLDecoder.decode(uri.substring(comma + 1).replace("+", "%2B"), StandardCharsets.ISO_8859_1)
          .getBytes(StandardCharsets.ISO_8859_1);
}

static String orientRenderImage(String uri) {
  EmailRecord record = ACTIVE_RENDER_RECORD.get();
  if (record == null || uri == null) return uri;
  Map<String, String> cache = ORIENTED_IMAGE_URIS.get();
  if (cache != null && cache.containsKey(uri)) return cache.get(uri);
  byte[] bytes = record.renderAssets.get(uri);
  if (bytes == null) {
    if (!uri.regionMatches(true, 0, "data:image/jpeg", 0, 15)
        && !uri.regionMatches(true, 0, "data:image/jpg", 0, 14)) return uri;
    bytes = decodeRenderImageUri(uri);
  }
  if (bytes.length < 2 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) return uri;
  try {
    byte[] display = ImageOrientation.orientedPng(bytes);
    if (display == null) return uri;
    // The original JPEG's pixels were already charged by the resource check.
    Set<String> images = RENDERED_IMAGES.get();
    if (images != null) images.add(HexFormat.of().formatHex(
        java.security.MessageDigest.getInstance("SHA-256").digest(display)));
    String oriented = "eapdf-image:" + Common.sha256Hex(display);
    record.renderAssets.put(oriented, display);
    if (cache != null) cache.put(uri, oriented);
    return oriented;
  } catch (Common.UserFacingException limit) {
    throw limit;
  } catch (Exception error) {
    Common.warn(ACTIVE_RENDER_RECORD.get(), "Could not apply photo orientation; retained the original image display.");
    return uri;
  }
}

static void checkRenderImage(byte[] bytes) {
  int[] dimensions = sniffImageSize(bytes);
  Set<String> images = RENDERED_IMAGES.get();
  if (images == null || dimensions[0] == 0) return;
  try {
    if (images.add(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))) {
      ProcessingLimits.accountImage((long) dimensions[0] * dimensions[1]);
    }
  } catch (java.security.NoSuchAlgorithmException error) {
    throw new IllegalStateException(error);
  }
}

static FSStream trustedRenderAsset(String uri) {
  EmailRecord record = ACTIVE_RENDER_RECORD.get();
  byte[] bytes = record == null ? null : record.renderAssets.get(uri);
  if (bytes == null) return new NullFsStream();
  return new FSStream() {
    public InputStream getStream() { return new ByteArrayInputStream(bytes); }
    public Reader getReader() { return new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8); }
  };
}

static String normalizeRemoteAssetUrl(String raw) {
  return Common.stripInvalidXml(Common.nz(raw).trim());
}

static String remoteAssetKey(String raw) {
  String value = normalizeRemoteAssetUrl(raw);
  if (value.isBlank()) return null;
  if (!(value.regionMatches(true, 0, "https://", 0, 8) || value.regionMatches(true, 0, "http://", 0, 7))) return null;
  try {
    return new URI(value).normalize().toString();
  } catch (URISyntaxException ignored) {
    try {
      return new URI(value.replace("[", "%5B").replace("]", "%5D")).normalize().toString();
    } catch (URISyntaxException ignoredAgain) {
      return value;
    }
  }
}

static String safeRenderedHref(String original, EmailRecord record) {
  String value = Common.nz(original).trim();
  if (value.isEmpty() || value.chars().anyMatch(c -> c < 32 || c == 127)) return null;
  try {
    URI uri = new URI(value);
    if (value.startsWith("#")) return value;
    if (uri.isAbsolute()) {
      if (Set.of("http", "https", "mailto").contains(uri.getScheme().toLowerCase(Locale.ROOT))) return value;
      if ("cid".equalsIgnoreCase(uri.getScheme()) && record != null
          && findCidAsset(value, record.associatedFiles) != null) return value;
      return null;
    }
    if (record != null && findLocationAsset(value, record) != null) return value;
    if (record == null || Common.nz(record.renderBaseUri).isBlank()) return null;
    URI resolved = new URI(record.renderBaseUri).resolve(uri);
    return resolved.isAbsolute() && Set.of("http", "https").contains(resolved.getScheme().toLowerCase(Locale.ROOT))
        ? resolved.toString() : null;
  } catch (URISyntaxException invalid) { return null; }
}

static boolean shouldBlacklistRemoteAssetResponse(String key, byte[] bytes) {
  return describeRemoteAssetResponse(key, bytes) != null;
}

static String describeRemoteAssetResponse(String key, byte[] bytes) {
  if (key == null) return "Remote asset URL was invalid";
  if (bytes == null || bytes.length == 0) return "Remote asset returned no data";
  if (looksLikeHtmlResponse(bytes)) return "Remote asset returned HTML instead of binary content";
  if (!isLikelyRemoteImageUrl(key)) return null;
  if (looksLikeSupportedImage(bytes) || looksLikeSvg(bytes)) return null;
  return "Remote asset response was not a supported image format";
}

static boolean looksLikeHtmlResponse(byte[] bytes) {
  if (bytes == null || bytes.length == 0) return false;
  String prefix = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8)
      .trim()
      .toLowerCase(Locale.ROOT);
  return prefix.startsWith("<!doctype html")
      || prefix.startsWith("<html")
      || prefix.startsWith("<head")
      || prefix.startsWith("<body")
      || prefix.contains("<html");
}

static boolean isLikelyRemoteImageUrl(String key) {
  String value = Common.nz(key).toLowerCase(Locale.ROOT);
  return value.contains(".png")
      || value.contains(".jpg")
      || value.contains(".jpeg")
      || value.contains(".gif")
      || value.contains(".bmp")
      || value.contains(".webp")
      || value.contains(".svg")
      || value.contains(".ico")
      || value.contains(".tif")
      || value.contains(".tiff");
}

static boolean looksLikeSvg(byte[] bytes) {
  if (bytes == null || bytes.length == 0) return false;
  String prefix = new String(bytes, 0, Math.min(bytes.length, 512), StandardCharsets.UTF_8)
      .trim()
      .toLowerCase(Locale.ROOT);
  return prefix.startsWith("<svg")
      || (prefix.startsWith("<?xml") && prefix.contains("<svg"));
}

static boolean looksLikeSupportedImage(byte[] bytes) {
  if (bytes == null || bytes.length < 4) return false;
  if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') return true;
  if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) return true;
  if (bytes.length >= 6) {
    String header6 = new String(bytes, 0, 6, StandardCharsets.US_ASCII);
    if ("GIF87a".equals(header6) || "GIF89a".equals(header6)) return true;
  }
  if (bytes[0] == 'B' && bytes[1] == 'M') return true;
  if (bytes[0] == 0x00 && bytes[1] == 0x00 && bytes[2] == 0x01 && bytes[3] == 0x00) return true;
  if (bytes.length >= 12) {
    String riff = new String(bytes, 0, 4, StandardCharsets.US_ASCII);
    String webp = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
    if ("RIFF".equals(riff) && "WEBP".equals(webp)) return true;
  }
  return ((bytes[0] == 'I' && bytes[1] == 'I' && bytes[2] == 42 && bytes[3] == 0)
      || (bytes[0] == 'M' && bytes[1] == 'M' && bytes[2] == 0 && bytes[3] == 42));
}

static boolean shouldBlacklistRemoteAssetFailure(Throwable error) {
  return describeRemoteAssetFailure(error) != null;
}

static String describeRemoteAssetFailure(Throwable error) {
  Throwable current = error;
  while (current != null) {
    if (current instanceof UnknownHostException) return "Unknown host";
    if (current instanceof ConnectException) return "Connection refused";
    if (current instanceof SocketTimeoutException) return "Connection timed out";
    if (current instanceof SSLException) return "SSL handshake failed";
    if (current instanceof EOFException) return "Remote host closed the connection unexpectedly";
    if (current instanceof IOException) {
      String message = Common.nz(current.getMessage()).toLowerCase(Locale.ROOT);
      if (message.contains("connection refused")) return "Connection refused";
      if (message.contains("timed out")) return "Connection timed out";
      if (message.contains("unrecognized image format")) return "Remote asset response was not a supported image format";
    }
    current = current.getCause();
  }
  return null;
}

static void configureRendererLogging() {
  XRLog.setLevel(XRLog.CSS_PARSE, Level.SEVERE);
  var delegate = XRLog.getLoggerImpl();
  XRLog.setLoggerImpl(new com.openhtmltopdf.util.XRLogger() {
    @Override public void log(String where, Level level, String message) { delegate.log(where, level, message); }
    @Override public void log(String where, Level level, String message, Throwable error) { delegate.log(where, level, message, error); }
    @Override public void setLevel(String logger, Level level) { delegate.setLevel(logger, level); }
    @Override public void log(com.openhtmltopdf.util.Diagnostic diagnostic) { delegate.log(diagnostic); }
    @Override public boolean isLogLevelEnabled(com.openhtmltopdf.util.Diagnostic diagnostic) {
      var id = diagnostic.getLogMessageId();
      // The renderer produces an intermediate PDF. Final title/description metadata
      // is supplied by this creator after rendering; these two warnings are premature.
      if (diagnostic.getLevel() == Level.WARNING && !diagnostic.hasError()
          && (id == com.openhtmltopdf.util.LogMessageId.LogMessageId0Param.GENERAL_PDF_ACCESSIBILITY_NO_DOCUMENT_TITLE_PROVIDED
              || id == com.openhtmltopdf.util.LogMessageId.LogMessageId0Param.GENERAL_PDF_ACCESSIBILITY_NO_DOCUMENT_DESCRIPTION_PROVIDED)) return false;
      return delegate.isLogLevelEnabled(diagnostic);
    }
  });
}

static void blacklistRemoteAsset(String key, String reason) {
  if (key == null) return;
  String normalizedReason = Common.nz(reason).trim();
  FAILED_REMOTE_ASSET_URLS.merge(key, normalizedReason, MailRenderPolicy::preferRemoteAssetReason);
  Common.recordBlacklistedRemoteAsset(key, FAILED_REMOTE_ASSET_URLS.get(key), ACTIVE_RENDER_RECORD.get());
}

static void recordBlacklistedRemoteAssetEncounter(String key) {
  if (key == null) return;
  Common.recordBlacklistedRemoteAsset(key, FAILED_REMOTE_ASSET_URLS.get(key), ACTIVE_RENDER_RECORD.get());
}

static String preferRemoteAssetReason(String existing, String replacement) {
  String current = Common.nz(existing).trim();
  String candidate = Common.nz(replacement).trim();
  if (candidate.isBlank()) return current;
  if (current.isBlank()) return candidate;
  if ("previously blacklisted".equalsIgnoreCase(current)) return candidate;
  return current;
}

static AssociatedFile findLocationAsset(String uri, EmailRecord record) {
  for (Map.Entry<String, AssociatedFile> resource : record.nativeLocationResources.entrySet()) {
    if (sameEmbeddedLocation(uri, resource.getKey(), record.renderBaseUri)) return resource.getValue();
  }
  List<String> scopes = record.bodyLocationScopes.isEmpty() ? Collections.singletonList(null) : record.bodyLocationScopes;
  for (String scope : scopes) {
    for (AssociatedFile file : record.associatedFiles) {
      if (scope != null && !scope.equals(file.locationScope)) continue;
      String location = Common.nz(file.resolvedContentLocation).isBlank() ? file.contentLocation : file.resolvedContentLocation;
      if (sameEmbeddedLocation(uri, location, record.renderBaseUri)) return file;
    }
  }
  return null;
}

static boolean sameEmbeddedLocation(String value, String location, String base) {
  String target = Common.nz(location).trim();
  if (target.isBlank()) return false;
  try {
    URI fallback = new URI("thismessage:/");
    URI root = Common.nz(base).isBlank() ? fallback : new URI(base.trim());
    URI reference = root.resolve(new URI(value));
    if (!reference.isAbsolute()) reference = fallback.resolve(reference);
    return reference.toString().equals(fallback.resolve(new URI(target)).toString());
  } catch (URISyntaxException invalidLocation) {
    return false;
  }
}

static AssociatedFile findCidAsset(String value, List<AssociatedFile> files) {
  String key = Common.cidUrlKey(value);
  if (key.isBlank()) return null;
  for (AssociatedFile file : files) {
    String cid = Common.normalizeCidKey(file.contentId);
    if (!cid.isBlank() && cid.equalsIgnoreCase(key)) return file;
  }
  for (AssociatedFile file : files) {
    String filename = Common.normalizeCidKey(file.filename);
    if (!filename.isBlank() && filename.equalsIgnoreCase(key)) return file;
  }
  return null;
}

static int[] sniffImageSize(byte[] bytes) {
  try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
    var readers = javax.imageio.ImageIO.getImageReaders(input);
    if (!readers.hasNext()) return new int[]{0, 0};
    var reader = readers.next();
    try {
      reader.setInput(input, true, true);
      int width = reader.getWidth(0);
      int height = reader.getHeight(0);
      ProcessingLimits.checkImage(width, height);
      return new int[]{width, height};
    } finally {
      reader.dispose();
    }
  } catch (IOException error) {
    // A failed image probe keeps the existing unknown-size fallback. Message
    // and decode budgets still apply; processing-limit exceptions propagate.
  }
  return new int[]{0, 0};
}
}
