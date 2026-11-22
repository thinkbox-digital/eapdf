package eapdf;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Level;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.swing.NaiveUserAgent;
import com.openhtmltopdf.util.XRLog;

/** Renderer resource resolution and budgets; no PDF construction or writer callbacks. */
final class MailRenderPolicy {
private MailRenderPolicy() {}
static final FSUriResolver REMOTE_ASSET_URI_RESOLVER = new RemoteAssetUriResolver();
static final FSStreamFactory REMOTE_ASSET_STREAM_FACTORY = new BlacklistingHttpStreamFactory();
static final InheritableThreadLocal<EmailRecord> ACTIVE_RENDER_RECORD = new InheritableThreadLocal<>();
static final ThreadLocal<RenderedImages> RENDERED_IMAGES = new ThreadLocal<>();
static final ThreadLocal<Map<String, String>> ORIENTED_IMAGE_URIS = new ThreadLocal<>();

static final class RenderedImages {
  final Set<String> digests = new HashSet<>();
  long pixels;
}

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
    if (key != null) Common.recordBlockedRemoteAsset(key);
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
    String resolved = delegate.resolveURI(baseUri, uri);
    if (isAllowedRenderResource(resolved)) return orientRenderImage(resolved);
    String key = remoteAssetKey(resolved == null ? uri : resolved);
    if (key != null) Common.recordBlockedRemoteAsset(key);
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
      if (bytes == null) return false;
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
  try {
    int comma = uri.indexOf(',');
    if (comma < 0) throw new IllegalArgumentException("Missing data URI separator");
    boolean base64 = uri.substring(0, comma).toLowerCase(Locale.ROOT).endsWith(";base64");
    long units = 0;
    for (int index = comma + 1; index < uri.length(); index++) {
      char c = uri.charAt(index);
      if (base64) {
        if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/') units++;
      } else {
        units++;
        if (c == '%') {
          if (index + 2 >= uri.length() || Character.digit(uri.charAt(index + 1), 16) < 0
              || Character.digit(uri.charAt(index + 2), 16) < 0) throw new IllegalArgumentException("Malformed percent escape");
          index += 2;
        }
      }
    }
    ProcessingLimits.checkMessage(base64 ? units * 6 / 8 : units, "inline data image");
    return base64 ? Base64.getMimeDecoder().decode(uri.substring(comma + 1))
        : java.net.URLDecoder.decode(uri.substring(comma + 1).replace("+", "%2B"), StandardCharsets.ISO_8859_1)
            .getBytes(StandardCharsets.ISO_8859_1);
  } catch (IllegalArgumentException malformed) {
    Common.warn(ACTIVE_RENDER_RECORD.get(), "Skipped malformed inline data image.");
    return null;
  }
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
  if (bytes == null) return null;
  if (bytes.length < 2 || (bytes[0] & 0xff) != 0xff || (bytes[1] & 0xff) != 0xd8) return uri;
  try {
    byte[] display = ImageOrientation.orientedPng(bytes);
    if (display == null) return uri;
    // The original JPEG's pixels were already charged by the resource check.
    RenderedImages images = RENDERED_IMAGES.get();
    if (images != null) images.digests.add(Common.sha256Hex(display));
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
  RenderedImages images = RENDERED_IMAGES.get();
  String digest = null;
  if (images != null) try {
    digest = Common.sha256Hex(bytes);
  } catch (Exception failure) {
    throw new IllegalStateException("Could not fingerprint a render image", failure);
  }
  if (images != null && images.digests.contains(digest)) return;
  int[] dimensions = sniffImageSize(bytes);
  if (images == null || dimensions[0] == 0) return;
  long pixels = (long) dimensions[0] * dimensions[1];
  ProcessingLimits.checkRenderedPixels(images.pixels + pixels);
  images.digests.add(digest);
  images.pixels += pixels;
  ProcessingLimits.accountImage(pixels);
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


static void configureRendererLogging() {
  XRLog.setLevel(XRLog.CSS_PARSE, Level.SEVERE);
  var delegate = XRLog.getLoggerImpl();
  XRLog.setLoggerImpl(new com.openhtmltopdf.util.XRLogger() {
    @Override public void log(String where, Level level, String message) {
      delegate.log(where, level, ConversionDiagnostics.redactIdentifiers(ACTIVE_RENDER_RECORD.get(), message));
    }
    @Override public void log(String where, Level level, String message, Throwable error) {
      log(where, level, error == null ? message : message + " (" + error.getClass().getSimpleName() + ")");
    }
    @Override public void setLevel(String logger, Level level) { delegate.setLevel(logger, level); }
    @Override public void log(com.openhtmltopdf.util.Diagnostic diagnostic) {
      log(diagnostic.getLogMessageId().getWhere(), diagnostic.getLevel(), diagnostic.getFormattedMessage(), diagnostic.getError());
    }
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
