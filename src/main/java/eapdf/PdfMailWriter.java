package eapdf;

import org.apache.pdfbox.Loader;
import java.io.*;
import java.awt.image.BufferedImage;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import javax.net.ssl.SSLException;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSUriResolver;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.parser.*;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.swing.NaiveUserAgent;
import com.openhtmltopdf.util.XRLog;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.multipdf.PDFCloneUtility;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.*;
import org.apache.pdfbox.pdmodel.common.filespecification.*;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.*;
import org.apache.pdfbox.pdmodel.interactive.viewerpreferences.PDViewerPreferences;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

class PdfMailWriter {
static final EapdfConfig CONFIG = EapdfConfig.get();
static final String EMBEDDED_LINK_PREFIX = "https://eapdf.local/attachment/";
static final String EMBEDDED_BADGE_LINK_PREFIX = EMBEDDED_LINK_PREFIX + "badge/";
static final Set<String> PDF17_STRUCTURE_ROLES = Set.of("Art", "BlockQuote", "TOC", "TOCI", "Index", "Private", "Quote", "Note", "Reference", "BibEntry", "Code");
static final ConcurrentMap<String, String> FAILED_REMOTE_ASSET_URLS = new ConcurrentHashMap<>();
static final FSUriResolver REMOTE_ASSET_URI_RESOLVER = new RemoteAssetUriResolver();
static final FSStreamFactory REMOTE_ASSET_STREAM_FACTORY = new BlacklistingHttpStreamFactory();
static final InheritableThreadLocal<EmailRecord> ACTIVE_RENDER_RECORD = new InheritableThreadLocal<>();
static final ThreadLocal<Set<String>> RENDERED_IMAGES = new ThreadLocal<>();
static final ThreadLocal<Map<String, String>> ORIENTED_IMAGE_URIS = new ThreadLocal<>();

static {
  // Lossless compression only; preserve an explicit PDFBox override.
  System.getProperties().putIfAbsent("org.apache.pdfbox.filter.deflatelevel", "9");
  configureRendererLogging();
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

static class MessageSegment {
  final EmailRecord record;
  final int startPage;
  final int endPage;
  COSDictionary semanticTree;
  MessageSegment(EmailRecord record, int startPage, int endPage) {
    this.record = record;
    this.startPage = startPage;
    this.endPage = endPage;
  }
}

static class RenderedMessagePart {
  final EmailRecord record;
  final byte[] pdfBytes;

  RenderedMessagePart(EmailRecord record, byte[] pdfBytes) {
    this.record = record;
    this.pdfBytes = pdfBytes;
  }
}

static class SingleMessageBuildResult {
  final byte[] pdfBytes;

  SingleMessageBuildResult(byte[] pdfBytes) {
    this.pdfBytes = pdfBytes;
  }
}

static class ArchiveEmbeddedFilePlan {
  final List<EmailRecord> owners = new ArrayList<>();
  final EmailRecord record;
  final AssociatedFile file;
  final String requestedName;
  final String filename;
  final String mime;
  final String relationship;
  final String checksumMd5;

  ArchiveEmbeddedFilePlan(EmailRecord record, AssociatedFile file, String requestedName, String filename, String mime, String relationship, String checksumMd5) {
    this.record = record;
    this.file = file;
    this.requestedName = requestedName;
    this.owners.add(record);
    this.filename = filename;
    this.mime = mime;
    this.relationship = relationship;
    this.checksumMd5 = checksumMd5;
  }
}

static class ArchivePendingFile {
  final EmailRecord record;
  final AssociatedFile file;
  final String requestedName;

  ArchivePendingFile(EmailRecord record, AssociatedFile file, String requestedName) {
    this.record = record;
    this.file = file;
    this.requestedName = requestedName;
  }
}

static class EmbeddedFileBindings {
  final Map<EmailRecord, PDComplexFileSpecification> sourceFiles = new IdentityHashMap<>();
  final Map<String, PDComplexFileSpecification> filesByToken = new LinkedHashMap<>();
}

static class CollectionMember {
  final String groupName;
  final String subject;
  final String childProfile;
  final List<EmailRecord> records;
  final int folderId;
  final int orderIndex;
  final String filename;
  byte[] pdfBytes;
  Path pdfPath;
  int storedPdfSize;
  final int attachmentCount;
  final String sentDisplay;

  CollectionMember(String groupName, String subject, String childProfile, List<EmailRecord> records, int folderId, int orderIndex, String filename, byte[] pdfBytes,
                   int attachmentCount, String sentDisplay) {
    this.groupName = groupName;
    this.subject = subject;
    this.childProfile = childProfile;
    this.records = records;
    this.folderId = folderId;
    this.orderIndex = orderIndex;
    this.filename = filename;
    this.pdfBytes = pdfBytes;
    this.attachmentCount = attachmentCount;
    this.sentDisplay = sentDisplay;
  }

  void stage(PayloadStore store) throws IOException {
    if (store == null || pdfBytes == null) return;
    storedPdfSize = pdfBytes.length;
    pdfPath = store.write(pdfBytes);
    pdfBytes = null;
  }

  byte[] pdfData() throws IOException {
    return pdfBytes != null ? pdfBytes : Files.readAllBytes(pdfPath);
  }

  int pdfSize() {
    return pdfBytes != null ? pdfBytes.length : storedPdfSize;
  }
}

static class CollectionFolder {
  final String name;
  final int id;
  int messageCount;
  long totalSizeBytes;

  CollectionFolder(String name, int id, int messageCount) {
    this.name = name;
    this.id = id;
    this.messageCount = messageCount;
  }
}

static class FolderSection {
  final String folderName;
  final List<EmailRecord> records;
  final List<MessageSegment> messageSegments = new ArrayList<>();
  int summaryStartPage;
  int totalSizeBytes;

  FolderSection(String folderName, List<EmailRecord> records) {
    this.folderName = folderName;
    this.records = records;
  }
}

static class PageLinkTarget {
  final int sourcePageNumber;
  final PDRectangle rect;
  int targetPageNumber;

  PageLinkTarget(int sourcePageNumber, PDRectangle rect) {
    this.sourcePageNumber = sourcePageNumber;
    this.rect = rect;
  }
}

static class CollectionRunSummary {
  final String harvestedEmails;
  final String generatedAt;
  final int folderCount;
  final int messageCount;

  CollectionRunSummary(String harvestedEmails, String generatedAt, int folderCount, int messageCount) {
    this.harvestedEmails = harvestedEmails;
    this.generatedAt = generatedAt;
    this.folderCount = folderCount;
    this.messageCount = messageCount;
  }

  String countsText() {
    return String.format(java.util.Locale.US, "%,d %s across %,d %s", messageCount,
        messageCount == 1 ? "message" : "messages", folderCount, folderCount == 1 ? "folder" : "folders");
  }
}







static boolean includeAssociatedFile(EmailRecord record, AssociatedFile file) {
  return file.embedInPdf && record.embedSourceMessage && ("Source".equals(Common.nz(file.afRelationship))
      || file.sourceSetMember && "Supplement".equals(Common.nz(file.afRelationship)));
}

static List<AssociatedFile> pdfEmbeddedFiles(EmailRecord r) {
  List<AssociatedFile> out = new ArrayList<>();
  if (r == null || r.associatedFiles == null) return out;
  for (AssociatedFile af : r.associatedFiles) {
    if (af != null && af.sourceSetMember && r.embedSourceMessage && (!af.embedInPdf || !af.hasData() || af.size() == 0
        || af.inline || af.emailAttachment || !"Supplement".equals(af.afRelationship))) {
      throw Common.fail("Required source file-set member cannot be preserved as a catalog Supplement: " + af.filename);
    }
    if (af == null || !af.hasData() || af.size() == 0) continue;
    if (!includeAssociatedFile(r, af)) continue;
    if (af.inline) continue;
    out.add(af);
  }
  return out;
}

static List<AssociatedFile> renderAssociatedFiles(EmailRecord r) {
  List<AssociatedFile> out = new ArrayList<>();
  if (r == null) return out;
  if (r.associatedFiles != null) {
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null) continue;
      if (!includeAssociatedFile(r, af)) continue;
      if (af.inline) continue;
      out.add(af);
    }
  }
  boolean hasSource = false;
  for (AssociatedFile af : out) {
    if ("Source".equals(Common.nz(af.afRelationship))) {
      hasSource = true;
      break;
    }
  }
  if (!hasSource && r.embedSourceMessage) {
    try {
      byte[] sourceBytes = r.sourceBytes == null ? Common.synthesizeEml(r) : r.sourceBytes;
      if (sourceBytes != null && sourceBytes.length > 0) {
        AssociatedFile af = new AssociatedFile();
        af.filename = Common.nz(r.sourceFilename).isBlank() ? "message.eml" : r.sourceFilename;
        af.mime = Common.nz(r.sourceMime).isBlank() ? "message/rfc822" : r.sourceMime;
        af.afRelationship = Common.nz(r.afRelationship).isBlank() ? "Source" : r.afRelationship;
        af.bytes = sourceBytes;
        af.description = Common.sourceDescription(r);
        af.ensureChecksums();
        af.launchToken = attachmentLaunchToken(af);
        out.add(0, af);
      }
    } catch (Exception ignored) {
    }
  }
  return out;
}

static List<AssociatedFile> emailAttachmentFiles(EmailRecord r) {
  List<AssociatedFile> out = new ArrayList<>();
  if (r == null || r.associatedFiles == null) return out;
  for (AssociatedFile af : r.associatedFiles) {
    if (af == null || !af.emailAttachment) continue;
    if (!af.hasData() || af.size() == 0) continue;
    if (!includeAssociatedFile(r, af) || af.inline) continue;
    out.add(af);
  }
  return out;
}

static String attachmentLaunchToken(AssociatedFile af) {
  if (af == null) return "";
  if (!Common.nz(af.launchToken).isBlank()) return af.launchToken;
  String rel = normalizeAfRelationship(af.afRelationship);
  String mime = Common.nz(af.mime).trim().toLowerCase(Locale.ROOT);
  if (Common.nz(af.checksumSha256).isBlank() && af.hasData() && af.size() > 0) {
    try { af.ensureChecksums(); }
    catch (IOException error) { throw new java.io.UncheckedIOException("Cannot fingerprint attachment: " + af.filename, error); }
  }
  String sha = Common.nz(af.checksumSha256);
  if (sha.isBlank()) sha = Common.nz(af.checksumMd5);
  if (sha.isBlank()) sha = Common.safeFileStem(Common.nz(af.filename));
  af.launchToken = rel + "|" + mime + "|" + sha;
  return af.launchToken;
}

static String attachmentLaunchHref(AssociatedFile af) {
  return attachmentLaunchHref(af, EMBEDDED_BADGE_LINK_PREFIX);
}

static String attachmentLaunchHref(AssociatedFile af, String prefix) {
  String token = attachmentLaunchToken(af);
  try {
    return prefix + URLEncoder.encode(token, StandardCharsets.UTF_8.toString());
  } catch (Exception ignored) {
    return prefix + token.replace("|", "%7C");
  }
}

static String attachmentBadgeHtml(AssociatedFile af) {
  return attachmentBadgeHtml(af, attachmentLaunchToken(af));
}

static String attachmentBadgeHtml(AssociatedFile af, String token) {
  String href = EMBEDDED_BADGE_LINK_PREFIX + URLEncoder.encode(token, StandardCharsets.UTF_8);
  String title = "Open attached file: " + Common.escapeHtml(Common.nz(af.filename));
  return "<a class=\"att-badge\" href=\"" + href + "\" title=\"" + title + "\" aria-label=\"" + title + "\">&#160;</a>";
}

static String nativeAttachmentToken(EmailRecord record, AssociatedFile file) {
  return Common.nz(record.mailGuid) + ":attachment:" + record.associatedFiles.indexOf(file)
      + ":" + attachmentLaunchToken(file);
}

static String nativeAttachmentHtml(EmailRecord record, AssociatedFile file) {
  return attachmentDisplayHtml(file);
}

static String attachmentDisplayHtml(AssociatedFile af) {
  return Common.escapeHtml(Common.nz(af.filename));
}

static String attachmentTextWithBadgeHtml(AssociatedFile af) {
  if (af == null) return "";
  return attachmentDisplayHtml(af);
}

static String pdfaComplianceLabel() {
  return "PDF/A-4f";
}

static byte[] renderHtmlPdf(String html, String baseUri, String fontPath, EmailRecord r, boolean debug) throws Exception {
  return renderHtmlPdf(html, baseUri, fontPath, r, debug, PdfMailWriter::renderXhtmlPdf);
}

interface XhtmlRenderer {
  byte[] render(String xhtml, String baseUri, String fontPath) throws Exception;
}

static byte[] renderHtmlPdf(String html, String baseUri, String fontPath, EmailRecord r, boolean debug, XhtmlRenderer renderer) throws Exception {
  Document d = Jsoup.parse(Common.nz(html), "", Parser.xmlParser());
  d.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  d.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  d.outputSettings().charset(StandardCharsets.UTF_8);
  d.outputSettings().prettyPrint(false);

  for (Element e : d.select("img")) {
    if (!e.hasAttr("alt")) {
      e.attr("alt", pdfUaFeaturesEnabled() ? "Image from the original email" : "");
      if (pdfUaFeaturesEnabled()) Common.warn(r, "An email image has no source alternative text; a generic description is used and should be reviewed for accessibility.");
    }
  }

  String finalXhtml = d.outerHtml();
  if (debug) {
    Common.dbg(r, "final xhtml len=" + finalXhtml.length());
    Common.dbg(r, "final xhtml preview=" + Common.preview(finalXhtml, 260));
  }

  EmailRecord previousRecord = ACTIVE_RENDER_RECORD.get();
  Set<String> previousImages = RENDERED_IMAGES.get();
  Map<String, String> previousOrientations = ORIENTED_IMAGE_URIS.get();
  RENDERED_IMAGES.set(new HashSet<>());
  ORIENTED_IMAGE_URIS.set(new HashMap<>());
  ACTIVE_RENDER_RECORD.set(r);
  try {
    try {
      byte[] rendered = renderer.render(finalXhtml, baseUri, fontPath);
      if (debug) Common.dbg(r, "OpenHTMLtoPDF run complete");
      return rendered;
    } catch (Exception ex) {
      for (Throwable cause = ex; cause != null && cause.getCause() != cause; cause = cause.getCause()) {
        if (cause instanceof Common.UserFacingException limit) throw limit;
      }
      String fallbackXhtml = buildPlainTextFallbackXhtml(html);
      if (debug) {
        Common.dbg(r, "OpenHTMLtoPDF primary render failed: " + ex.getClass().getSimpleName() + ": " + Common.nz(ex.getMessage()));
        Common.dbg(r, "retrying render with plain-text fallback xhtml");
      }
      try {
        byte[] rendered = renderer.render(fallbackXhtml, baseUri, fontPath);
        Common.warn(r, "Reduced rendering: HTML failed (" + ex.getClass().getSimpleName() + ": "
            + Common.nz(ex.getMessage()) + "); used plain text. Images, formatting, and links may be missing.");
        if (debug) Common.dbg(r, "OpenHTMLtoPDF fallback render complete");
        return rendered;
      } catch (Exception fallbackEx) {
        fallbackEx.addSuppressed(ex);
        throw fallbackEx;
      }
    }
  } finally {
    if (previousImages == null) RENDERED_IMAGES.remove();
    else RENDERED_IMAGES.set(previousImages);
    if (previousOrientations == null) ORIENTED_IMAGE_URIS.remove();
    else ORIENTED_IMAGE_URIS.set(previousOrientations);
    r.renderAssets.clear();
    r.renderBaseUri = "";
    if (previousRecord == null) ACTIVE_RENDER_RECORD.remove();
    else ACTIVE_RENDER_RECORD.set(previousRecord);
  }
}

static byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath) throws Exception {
  return renderXhtmlPdf(xhtml, baseUri, fontPath, true);
}

static byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath, boolean fitWideContent) throws Exception {
  ByteArrayOutputStream baos = new ByteArrayOutputStream();
  PdfRendererBuilder builder = new PdfRendererBuilder();
  builder.useFastMode();
  // The patched API calls base PDF/A-4 PDFA_4_A; it does not create PDF/A-4f.
  // Final assembly supplies EA-PDF identification, associated files and 4f XMP.
  builder.usePdfAConformance(PdfRendererBuilder.PdfAConformance.PDFA_4_A);
  builder.useColorProfile(Files.readAllBytes(Path.of(Common.resolveIccPath(null))));
  FontSupport.register(builder, new File(fontPath), "ArchiveFont");
  EmailRecord renderRecord = ACTIVE_RENDER_RECORD.get();
  builder.usePdfUaAccessibility(pdfUaFeaturesEnabled() || (renderRecord != null && !Common.nz(renderRecord.headerTagMarker).isBlank()), 2);
  xhtml = FontSupport.configure(builder, fontPath, xhtml, ACTIVE_RENDER_RECORD.get());
  builder.useUriResolver(REMOTE_ASSET_URI_RESOLVER);
  builder.useProtocolsStreamImplementation(REMOTE_ASSET_STREAM_FACTORY, "http", "https");
  builder.useProtocolsStreamImplementation(PdfMailWriter::trustedRenderAsset, "file", "eapdf-image");
  builder.useExternalResourceAccessControl((uri, type) -> isAllowedRenderResource(uri),
      ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
  builder.withHtmlContent(xhtml, baseUri);
  builder.toStream(baos);
  String fittedXhtml = null;
  try (var renderer = builder.buildPdfRenderer()) {
    renderer.layout();
    if (fitWideContent && renderRecord != null) {
      var root = renderer.getRootBox();
      var page = root.getLayer().getPages().get(0);
      var context = renderer.getSharedContext().newLayoutContextInstance();
      int paintedWidth = root.getPaintingInfo().getOuterMarginCorner().width;
      int availableWidth = page.getContentWidth(context);
      if (paintedWidth > availableWidth + 1) {
        // Preserve fixed email tables at their authored size instead of clipping columns.
        float dotsPerPoint = renderer.getDotsPerPoint();
        float width = (float) Math.ceil((page.getWidth(context) + paintedWidth - availableWidth) / dotsPerPoint) + 1f;
        float height = page.getHeight(context) / dotsPerPoint;
        Document fitted = Jsoup.parse(xhtml, "", Parser.xmlParser());
        fitted.outputSettings().syntax(Document.OutputSettings.Syntax.xml).prettyPrint(false);
        fitted.head().appendElement("style").text("@page {size:" + width + "pt " + height + "pt;}");
        fittedXhtml = fitted.outerHtml();
      }
    }
    if (fittedXhtml == null) {
      renderer.setListener(new com.openhtmltopdf.pdfboxout.PDFCreationListener() {
        private boolean counted;
        public void preOpen(com.openhtmltopdf.pdfboxout.PdfBoxRenderer r) {}
        public void preWrite(com.openhtmltopdf.pdfboxout.PdfBoxRenderer r, int count) {}
        public void onClose(com.openhtmltopdf.pdfboxout.PdfBoxRenderer r) {
          if (!counted) { counted = true; ProcessingLimits.accountPages(r.getPdfDocument().getNumberOfPages()); }
        }
      });
      renderer.createPDF();
    }
  }
  if (fittedXhtml != null) return renderXhtmlPdf(fittedXhtml, baseUri, fontPath, false);
  byte[] rendered = baos.toByteArray();
  return rendered;
}

static String buildPlainTextFallbackXhtml(String html) throws Exception {
  String text = Common.stripHtml(Common.nz(html));
  if (text.isBlank()) text = "(message content could not be rendered as HTML)";
  StringBuilder s = new StringBuilder();
  s.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
  s.append("<style>");
  s.append(messagePageStyle(ACTIVE_RENDER_RECORD.get()));
  s.append("body { margin: 8px 8px 0; font-family: ArchiveFont; font-size: 11pt; color: #222; line-height: 1.35; }");
  s.append("pre { white-space: pre-wrap; word-wrap: break-word; margin: 0; }");
  s.append("</style></head><body><pre>");
  s.append(Common.escapeHtml(text));
  s.append("</pre></body></html>");
  return s.toString();
}

static String messagePageStyle(EmailRecord record) throws Exception {
  return "@page { size: letter; margin: 0.65in; }";
}

static int detectPrimaryContentWidthPx(String emailHtml) {
  try {
    Document d = Jsoup.parseBodyFragment(Common.nz(emailHtml));
    for (Element el : d.body().children()) {
      Integer width = extractElementWidthPx(el);
      if (width != null && width >= 200 && width <= 2000) return width;
      for (Element nested : el.select("table,div")) {
        width = extractElementWidthPx(nested);
        if (width != null && width >= 200 && width <= 2000) return width;
      }
    }
  } catch (Exception ignored) {
  }
  return 0;
}

static Integer extractElementWidthPx(Element el) {
  if (el == null) return null;
  String widthAttr = Common.nz(el.attr("width")).trim();
  if (!widthAttr.isBlank()) {
    String digits = widthAttr.replaceAll("[^0-9.]", "");
    if (!digits.isBlank()) {
      try { return (int)Math.round(Double.parseDouble(digits)); } catch (Exception ignored) {}
    }
  }
  String style = Common.nz(el.attr("style")).trim();
  if (!style.isBlank()) {
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("(^|;)\\s*width\\s*:\\s*([0-9.]+)px\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(style);
    if (m.find()) {
      try { return (int)Math.round(Double.parseDouble(m.group(2))); } catch (Exception ignored) {}
    }
  }
  return null;
}

static String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir) throws Exception {
  return buildRenderHtml(r, debug, assetDir, null);
}

static String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir, String profileOverride) throws Exception {
  return buildRenderHtml(r, debug, assetDir, profileOverride, "");
}

static String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir, String profileOverride, String archiveSourceName) throws Exception {
  if (Common.nz(r.headerTagMarker).isBlank()) r.headerTagMarker = "EA-PDF-" + UUID.randomUUID() + ":";
  for (String warning : r.conversionWarnings) System.err.println("warning: " + warning);
  r.embedSourceMessage = !chooseEaPdfConformance(r, profileOverride).endsWith("i");
  String htmlBody = Common.htmlBody(r);
  String textBody = Common.textBody(r);
  ProcessingLimits.checkBody(htmlBody, r.sourcePath);
  ProcessingLimits.checkBody(textBody, r.sourcePath);
  String original = !htmlBody.isBlank() ? htmlBody : Common.plainTextToHtml(textBody);
  original = Common.stripInvalidXml(original);
  String emailHtml = canonicalizeEmailHtml(original, r, debug);
  emailHtml = rewriteCidToFileUris(emailHtml, r.associatedFiles, r, debug, assetDir);
  emailHtml = Common.stripInvalidXml(emailHtml);
  Document styledEmail = Jsoup.parse(emailHtml, "", Parser.xmlParser());
  StringBuilder emailStyles = new StringBuilder();
  for (Element style : styledEmail.select("style")) {
    emailStyles.append(style.wholeText()).append('\n');
    style.remove();
  }
  emailHtml = styledEmail.html();

  if (looksEmpty(emailHtml)) {
    if (debug) Common.dbg(r, "html body empty after canonicalization; fallback to text html");
    emailHtml = Common.plainTextToHtml(Common.textBody(r));
  }
  r.renderContentWidthPx = detectPrimaryContentWidthPx(emailHtml);
  if (debug) Common.dbg(r, "detected content width px=" + r.renderContentWidthPx);

  StringBuilder s = new StringBuilder();
  s.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
  s.append("<style>");
  s.append(messagePageStyle(r));
  s.append("html, body { font-family: ArchiveFont; }");
  s.append("body { margin: 8px 8px 0; font-size: 11pt; color: #222; line-height: 1.35; }");
  s.append(".hdr { margin-bottom: 18px; }");
  s.append(".hdr-table { border-collapse: collapse; }");
  s.append(".hdr-table td { padding: 2px 0; }");
  s.append(".hdr-table .label { width: 96px; padding-right: 8px; box-sizing: border-box; white-space: nowrap; }");
  s.append(".hdr-table .value { padding-left: 0; }");
  s.append(".label-strong { font-weight: bold; }");
  s.append(".message-body { margin-top: 12px; word-wrap: break-word; }");
  s.append(".message-body pre { white-space: pre-wrap; word-wrap: break-word; }");
  s.append(".message-body table[data-ea-table-head] { -fs-table-paginate: paginate; }");
  String[] legacySizes = {"x-small", "small", "medium", "large", "x-large", "xx-large", "48px"};
  for (int index = 0; index < legacySizes.length; index++) {
    s.append(".ea-email-body font[data-ea-font-size='").append(index + 1)
        .append("'] { font-size:").append(legacySizes[index]).append("; }");
  }
  s.append(".message-body ol[type='1'] { list-style-type: decimal; }");
  s.append(".message-body ol[type='a'] { list-style-type: lower-alpha; }");
  s.append(".message-body ol[type='A'] { list-style-type: upper-alpha; }");
  s.append(".message-body ol[type='i'] { list-style-type: lower-roman; }");
  s.append(".message-body ol[type='I'] { list-style-type: upper-roman; }");
  s.append(".message-body ul[type='disc'] { list-style-type: disc; }");
  s.append(".message-body ul[type='circle'] { list-style-type: circle; }");
  s.append(".message-body ul[type='square'] { list-style-type: square; }");
  // Leave room for ordinary email headers and source captions around a portrait image.
  s.append(".message-body img { display: inline-block; max-width: 100%; max-height: 6.5in; height: auto; page-break-inside: avoid; }");
  s.append(".attachments { clear: both; margin-top: 18px; font-size: 9.5pt; color: #444; width: 100%; word-wrap: break-word; }");
  s.append(".attachments.source-only { margin-top: 0; }");
  s.append(".att-line { margin: 3px 0 0 12px; }");
  s.append(".att-parent { margin-top: 4px; font-weight: bold; color: #3e4f63; }");
  s.append(".attachments.source-only .att-parent { margin-top: 0; }");
  s.append(".att-child { margin-left: 24px; }");
  s.append(".att-meta { color: #72675b; font-weight: normal; }");
  s.append(".message-delimiter { margin-top: 22px; page-break-inside: avoid; width: 100%; }");
  s.append(".message-delimiter-label { font-size: 8.5pt; color: #7f7462; letter-spacing: 0.08em; text-transform: uppercase; margin-bottom: 6px; }");
  s.append(".hdr-table th, .hdr-table td { vertical-align: top; }");
  s.append(".pagebreak { page-break-before: always; }");
  s.append(emailStyles);
  s.append("</style></head><body>");
  if (!Common.nz(EapdfConfig.get().validationWarning).isBlank()) {
    System.err.println("warning: VALIDATION WARNING BANNER: " + EapdfConfig.get().validationWarning);
    s.append("<div style='border:2px solid #8b6500;background:#fff5ce;padding:10px;margin-bottom:12px;color:#473500'>")
        .append("<p><strong>WARNING — Validation requires review</strong></p><p>")
        .append(Common.escapeHtml(EapdfConfig.get().validationWarning)).append("</p></div>");
  }
  if (!r.sourceProblems.isEmpty()) {
    s.append("<div style='border:2px solid #8b3500;padding:10px;margin-bottom:12px;color:#582400'>")
        .append("<p><strong>INCOMPLETE SOURCE — conversion warnings</strong></p><ul>");
    for (String problem : r.sourceProblems) s.append("<li>").append(Common.escapeHtml(problem)).append("</li>");
    s.append("</ul></div>");
  }

  s.append("<div class=\"hdr\"><table class=\"hdr-table\" data-eapdf-header=\"").append(r.headerTagMarker).append("\">");
  appendHeaderRow(s, "From", Common.displayAddressHeader(r.from, r.fromAgents));
  if (!Common.nz(r.rawReplyTo).isBlank()) {
    String replyTo = Common.addrListLenient(r.rawReplyTo);
    appendHeaderRow(s, "Reply-To", Common.displayAddressHeader(replyTo.isBlank() ? r.rawReplyTo : replyTo,
        Common.internetAddressListLenient(r.rawReplyTo)));
  }
  appendHeaderRow(s, "Date", !Common.nz(r.rawDateHeader).isBlank() ? Common.nz(r.rawDateHeader) : Common.nz(r.date));
  appendHeaderRow(s, "To", Common.displayAddressHeader(r.to, r.toAgents));
  if (!Common.nz(r.cc).isBlank()) appendHeaderRow(s, "Cc", Common.displayAddressHeader(r.cc, r.ccAgents));
  if (!Common.nz(r.bcc).isBlank()) appendHeaderRow(s, "Bcc", Common.displayAddressHeader(r.bcc, r.bccAgents));
  appendHeaderRow(s, "Subject", Common.nz(r.subject));
  if (!Common.nz(r.messageId).isBlank()) appendHeaderRow(s, "Message-ID", Common.nz(r.messageId));
  if (!Common.nz(r.folderPath).isBlank()) appendHeaderRow(s, "Folder", Common.nz(r.folderPath));
  if (!Common.nz(r.archiveGlobalId).isBlank()) appendHeaderRow(s, "Archive Global ID", Common.nz(r.archiveGlobalId));
  if (!r.archiveEmails.isEmpty()) appendHeaderRow(s, "Archive Email(s)", String.join(", ", r.archiveEmails));
  s.append("</table></div>");

  s.append("<div class=\"message-body\">").append(emailHtml).append("</div>");
  List<AssociatedFile> displayFiles = renderAssociatedFiles(r);
  boolean hasEmailAttachments = r.associatedFiles.stream().anyMatch(file -> file != null && file.emailAttachment);
  boolean hasFileList = !displayFiles.isEmpty() || hasEmailAttachments;
  if (hasFileList) {
  s.append(hasEmailAttachments ? "<div class=\"attachments\">" : "<div class=\"attachments source-only\">");
  AssociatedFile sourceDisplay = null;
  for (AssociatedFile af : displayFiles) {
    if ("Source".equals(Common.nz(af.afRelationship))) {
      sourceDisplay = af;
      break;
    }
  }
  if (sourceDisplay != null) {
    s.append("<div class=\"att-line att-parent\">")
     .append("Source file: ")
     .append(Common.escapeHtml(archiveSourceName.isBlank() ? Common.nz(sourceDisplay.filename) : archiveSourceName))
     .append(" <span class=\"att-meta\">[")
     .append(Common.escapeHtml(Common.nz(sourceDisplay.mime)))
     .append("]</span></div>");
    List<String> sourceAttachmentNames = new ArrayList<>();
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null || !af.emailAttachment) continue;
      sourceAttachmentNames.add(nativeAttachmentHtml(r, af));
    }
    if (!sourceAttachmentNames.isEmpty()) {
      s.append("<div class=\"att-line att-child\">")
       .append("Source file attachments:");
      for (String attachment : sourceAttachmentNames) {
        s.append("<div class=\"att-line\">").append(attachment).append("</div>");
      }
      s.append("</div>");
    }
  } else {
    for (AssociatedFile af : displayFiles) {
      s.append("<div class=\"att-line\">")
       .append(attachmentTextWithBadgeHtml(af))
       .append(" <span class=\"att-meta\">[")
       .append(Common.escapeHtml(Common.nz(af.mime)))
       .append("; ")
       .append(Common.escapeHtml(Common.nz(af.afRelationship).isBlank() ? "Data" : Common.nz(af.afRelationship)))
       .append("]</span></div>");
    }
    for (AssociatedFile af : r.associatedFiles) {
      if (af != null && af.emailAttachment) {
        s.append("<div class=\"att-line\">").append(nativeAttachmentHtml(r, af)).append("</div>");
      }
    }
  }
  s.append("</div>");
  }
  s.append("</body></html>");

  String out = SemanticStructure.headerHtml(Common.stripInvalidXml(s.toString()), r.headerTagMarker);
  ProcessingLimits.checkBody(out, r.sourcePath);
  if (debug) {
    Common.dbg(r, "final render html len=" + out.length());
    Common.dbg(r, "final render html preview=" + Common.preview(out, 260));
  }
  return out;
}

static String canonicalizeEmailHtml(String html, EmailRecord r, boolean debug) {
  String src = Common.stripInvalidXml(html);
  if (r != null) r.renderBaseUri = Common.nz(r.bodyBaseUri);
  if (src.isBlank()) return "";

  Document d = Jsoup.parse(src);
  Element base = d.selectFirst("base[href]");
  if (r != null && base != null && !base.attr("href").isBlank()) {
    String href = base.attr("href").trim();
    try {
      r.renderBaseUri = new URI(r.renderBaseUri).resolve(new URI(href)).toString();
    } catch (URISyntaxException invalidBase) {
      r.renderBaseUri = href;
    }
  }
  d.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  d.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  d.outputSettings().charset(StandardCharsets.UTF_8);
  d.outputSettings().prettyPrint(false);
  String preservedStyles = EmailStyles.scoped(d, r);
  for (Element e : d.select("script,style,meta[http-equiv=refresh],link[rel=stylesheet],base")) e.remove();
  // Hidden HTML inputs carry form submission data, not visible email content.
  // Rendering them creates hidden PDF widgets without appearances. Keep their
  // original markup in the embedded source, but omit them from the page.
  for (Element e : d.select("input[type=hidden]")) e.remove();
  for (Element e : d.getAllElements()) {
    if (pdfUaFeaturesEnabled() && !e.hasAttr("lang") && e.hasAttr("xml:lang")) {
      e.attr("lang", e.attr("xml:lang"));
    }
    String tag = Common.nz(e.tagName());
    if (tag.contains(":")) {
      e.tagName("span");
    }
    List<String> namespacedAttrs = new ArrayList<>();
    e.attributes().forEach(attr -> {
      if (Common.nz(attr.getKey()).contains(":")) namespacedAttrs.add(attr.getKey());
    });
    for (String key : namespacedAttrs) e.removeAttr(key);
  }

  Element body = d.body();
  BodyColors bodyColors = legacyBodyColorStyles(body);
  String inner = body != null ? body.html() : d.html();

  Document wrap = Jsoup.parse("<div id=\"email-root\"></div>", "", Parser.xmlParser());
  wrap.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  wrap.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  wrap.outputSettings().charset(StandardCharsets.UTF_8);
  wrap.outputSettings().prettyPrint(false);

  Element root = wrap.selectFirst("div#email-root");
  Element sourceBody = root.appendElement("div").addClass("ea-email-body");
  if (body != null) {
    body.attributes().forEach(attribute -> sourceBody.attr(attribute.getKey(), attribute.getValue()));
    sourceBody.addClass("ea-email-body");
  }
  if (pdfUaFeaturesEnabled() && !sourceBody.hasAttr("lang")) {
    Element sourceHtml = d.selectFirst("html[lang]");
    if (sourceHtml != null) sourceBody.attr("lang", sourceHtml.attr("lang"));
  }
  sourceBody.html(Common.stripInvalidXml(inner));
  for (Element pre : sourceBody.select("pre")) {
    int[] column = new int[1];
    pre.forEachNode(node -> {
      if (node instanceof org.jsoup.nodes.TextNode text) {
        text.text(Common.expandTabs(text.getWholeText(), column));
      } else if (node instanceof Element element && "br".equals(element.normalName())) {
        column[0] = 0;
      }
    });
  }
  if (preservedStyles.isBlank() && !bodyColors.background()) trimTrailingEmptyContent(sourceBody);
  linkifyBodyText(sourceBody);
  if (!bodyColors.css().isBlank() || !preservedStyles.isBlank()) {
    root.prependElement("style").text(bodyColors.css() + preservedStyles);
  }

  for (Element e : wrap.select("img")) {
    if (e.hasAttr("src")) e.attr("src", normalizeRemoteAssetUrl(Common.stripInvalidXml(Common.nz(e.attr("src")).trim())));
  }

  for (Element e : wrap.select("[src]")) {
    e.attr("src", normalizeRemoteAssetUrl(e.attr("src")));
  }
  for (Element e : wrap.select("[href]")) {
    String safe = safeRenderedHref(e.attr("href"), r);
    if (safe == null) e.removeAttr("href"); else e.attr("href", safe);
  }
  for (Element e : wrap.getAllElements()) {
    for (var attribute : new ArrayList<>(e.attributes().asList())) {
      String key = attribute.getKey().toLowerCase(Locale.ROOT);
      if (key.startsWith("on") || Set.of("action", "formaction").contains(key)) e.removeAttr(attribute.getKey());
    }
  }

  for (Element e : wrap.select("font[size]")) {
    var size = java.util.regex.Pattern.compile("^[\\t\\n\\f\\r ]*([+-]?)([0-9]+)")
        .matcher(e.attr("size"));
    if (!size.find()) continue;
    int value = 0;
    for (char digit : size.group(2).toCharArray()) value = Math.min(10, value * 10 + digit - '0');
    if ("+".equals(size.group(1))) value += 3;
    else if ("-".equals(size.group(1))) value = 3 - value;
    e.attr("data-ea-font-size", Integer.toString(Math.max(1, Math.min(7, value))));
  }

  for (Element table : sourceBody.select("table")) {
    if (table.children().stream().anyMatch(child -> "thead".equals(child.normalName()))) {
      table.attr("data-ea-table-head", "");
    }
  }

  StringBuilder presentationStyles = new StringBuilder();
  var colorParser = new com.openhtmltopdf.css.parser.CSSParser((uri, message) -> {});
  int presentationIndex = 0;
  for (Element e : wrap.select("[width],[height],[align],[background],font[color],hr[color],img[hspace],img[vspace],br[clear],td[nowrap],th[nowrap]")) {
    StringBuilder declarations = new StringBuilder();
    if (Set.of("td", "th").contains(e.normalName()) && e.hasAttr("nowrap")) {
      declarations.append("white-space:nowrap;");
    }
    if (e.hasAttr("background")) {
      String background = normalizeRemoteAssetUrl(e.attr("background"));
      e.removeAttr("background");
      if (!background.isBlank()) declarations.append("background-image:url('")
          .append(background.replace("'", "%27")).append("');");
    }
    if ("br".equals(e.normalName())) {
      String clear = e.attr("clear").trim().toLowerCase(Locale.ROOT);
      if ("all".equals(clear)) clear = "both";
      if (Set.of("left", "right", "both").contains(clear)) {
        // The renderer applies float clearing only to block-level breaks.
        declarations.append("display:block;clear:").append(clear).append(';');
      }
    }
    if ("img".equals(e.normalName())) {
      for (String spacing : List.of("hspace", "vspace")) {
        String value = e.attr(spacing).trim();
        if (!value.matches("\\d+")) continue;
        String[] sides = "hspace".equals(spacing) ? new String[]{"left", "right"} : new String[]{"top", "bottom"};
        for (String side : sides) declarations.append("margin-").append(side).append(':').append(value).append("px;");
      }
    }
    if (e.hasAttr("align")) {
      String alignment = e.attr("align").trim().toLowerCase(Locale.ROOT);
      String tag = e.normalName();
      if ("hr".equals(tag) && Set.of("left", "right", "center").contains(alignment)) {
        declarations.append("margin-left:").append("left".equals(alignment) ? "0" : "auto")
            .append(";margin-right:").append("right".equals(alignment) ? "0" : "auto").append(';');
      } else if ("table".equals(tag) && "center".equals(alignment)) {
        declarations.append("margin-left:auto;margin-right:auto;");
      } else if (Set.of("img", "table").contains(tag) && Set.of("left", "right").contains(alignment)) {
        declarations.append("float:").append(alignment).append(';');
      } else if ("img".equals(tag) && Set.of("top", "middle", "bottom").contains(alignment)) {
        declarations.append("vertical-align:").append(alignment).append(';');
      } else if (Set.of("left", "right", "center", "justify").contains(alignment)) {
        declarations.append("text-align:").append(alignment).append(';');
      }
      e.removeAttr("align");
    }
    if (Set.of("font", "hr").contains(e.normalName()) && e.hasAttr("color")) {
      String color = e.attr("color").trim();
      var value = color.isBlank() ? null : colorParser.parsePropertyValue(
          com.openhtmltopdf.css.constants.CSSName.COLOR, com.openhtmltopdf.css.sheet.StylesheetInfo.AUTHOR, color);
      if (value != null) {
        if ("hr".equals(e.normalName())) {
          declarations.append("border-color:").append(value.getCssText()).append(";border-style:solid;");
        } else {
          declarations.append("color:").append(value.getCssText()).append(';');
        }
      }
      e.removeAttr("color");
    }
    for (String dimension : List.of("width", "height")) {
      String value = e.attr(dimension).trim();
      if (value.matches("\\d+")) declarations.append(dimension).append(':').append(value).append("px;");
      else if (value.matches("(?:\\d+(?:\\.\\d+)?|\\.\\d+)%"))
        declarations.append(dimension).append(':').append(value).append(';');
      e.removeAttr(dimension);
    }
    if (declarations.length() == 0) continue;
    // Legacy presentation attributes are defaults; scoped author rules and inline CSS take priority.
    e.attr("data-ea-presentation", Integer.toString(presentationIndex));
    presentationStyles.append(".ea-email-body").append(e == sourceBody ? "" : " ")
        .append("[data-ea-presentation='").append(presentationIndex++)
        .append("'] {").append(declarations).append("}\n");
  }
  if (presentationStyles.length() > 0) root.prependElement("style").text(presentationStyles.toString());

  String out = Common.stripInvalidXml(root.outerHtml());
  if (debug) {
    Common.dbg(r, "canonicalized email html len=" + out.length());
    Common.dbg(r, "canonicalized email html preview=" + Common.preview(out, 220));
  }
  return out;
}

private record BodyColors(String css, boolean background) {}

static BodyColors legacyBodyColorStyles(Element body) {
  if (body == null) return new BodyColors("", false);
  var parser = new com.openhtmltopdf.css.parser.CSSParser((uri, message) -> {});
  String background = body.attr("bgcolor").trim();
  String foreground = body.attr("text").trim();
  var backgroundValue = background.isBlank() ? null : parser.parsePropertyValue(
      com.openhtmltopdf.css.constants.CSSName.BACKGROUND_COLOR, com.openhtmltopdf.css.sheet.StylesheetInfo.AUTHOR, background);
  var foregroundValue = foreground.isBlank() ? null : parser.parsePropertyValue(
      com.openhtmltopdf.css.constants.CSSName.COLOR, com.openhtmltopdf.css.sheet.StylesheetInfo.AUTHOR, foreground);
  StringBuilder declarations = new StringBuilder();
  if (backgroundValue != null) declarations.append("background-color:").append(backgroundValue.getCssText()).append(';');
  if (foregroundValue != null) declarations.append("color:").append(foregroundValue.getCssText()).append(';');
  // The low-priority class rule lets scoped author styles and inline CSS win.
  StringBuilder css = new StringBuilder();
  if (declarations.length() > 0) css.append(".ea-email-body {").append(declarations).append("}\n");
  String[] attributes = {"link", "vlink", "alink"};
  String[] states = {"link", "visited", "active"};
  for (int index = 0; index < attributes.length; index++) {
    String raw = body.attr(attributes[index]).trim();
    var value = raw.isBlank() ? null : parser.parsePropertyValue(
        com.openhtmltopdf.css.constants.CSSName.COLOR, com.openhtmltopdf.css.sheet.StylesheetInfo.AUTHOR, raw);
    if (value != null) css.append(".ea-email-body a:").append(states[index])
        .append(" {color:").append(value.getCssText()).append(";}\n");
  }
  return new BodyColors(css.toString(), backgroundValue != null);
}

static void trimTrailingEmptyContent(Element root) {
  String style = root.attr("style").toLowerCase(Locale.ROOT);
  if (style.matches("(?s).*(background|border|height|padding|margin|position)\\s*[:;-].*")) return;
  while (root.childNodeSize() > 0) {
    org.jsoup.nodes.Node node = root.childNode(root.childNodeSize() - 1);
    if (node instanceof org.jsoup.nodes.TextNode text && text.isBlank()) {
      node.remove();
      continue;
    }
    if (!(node instanceof Element last)) return;
    String tag = last.normalName();
    if ("br".equals(tag) && last.attributes().size() == 0) {
      last.remove();
      continue;
    }
    if (!Set.of("div", "p", "span", "font", "blockquote").contains(tag)) return;
    trimTrailingEmptyContent(last);
    if (last.childNodeSize() == 0 && last.attributes().size() == 0) {
      last.remove();
      continue;
    }
    return;
  }
}

static void linkifyBodyText(Element root) {
  List<org.jsoup.nodes.TextNode> nodes = new ArrayList<>();
  for (Element element : root.getAllElements()) {
    // jsoup shares an empty child list across elements; another independent
    // render can clear it while textNodes() streams it. Empty elements have
    // no text to linkify, so avoid traversing that shared list.
    if (element.childNodeSize() > 0 && element.closest("a,style,script") == null)
      nodes.addAll(element.textNodes());
  }
  var pattern = java.util.regex.Pattern.compile("(?i)\\b(?:https?://|mailto:|www\\.)[^\\s<>()\\[\\]{}]+");
  for (var node : nodes) {
    String text = node.getWholeText();
    var matches = pattern.matcher(text);
    int previous = 0;
    boolean replaced = false;
    while (matches.find()) {
      String url = matches.group().replaceFirst("[.,;:!?\\]}>\\\"']+$", "");
      if (url.isBlank()) continue;
      if (matches.start() > previous) node.before(new org.jsoup.nodes.TextNode(text.substring(previous, matches.start())));
      String target = url.regionMatches(true, 0, "www.", 0, 4) ? "http://" + url : url;
      node.before(new Element("a").attr("href", target).attr("style", "color:inherit;text-decoration:none").text(url));
      previous = matches.start() + url.length();
      replaced = true;
    }
    if (replaced) {
      if (previous < text.length()) node.before(new org.jsoup.nodes.TextNode(text.substring(previous)));
      node.remove();
    }
  }
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

static byte[] readAllBytes(Reader reader) throws IOException {
  StringBuilder out = new StringBuilder();
  char[] buf = new char[4096];
  for (int n; (n = reader.read(buf)) != -1;) out.append(buf, 0, n);
  return out.toString().getBytes(StandardCharsets.UTF_8);
}

// This affects rendered links only. The original source and its headers/body stay intact.
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
  FAILED_REMOTE_ASSET_URLS.merge(key, normalizedReason, PdfMailWriter::preferRemoteAssetReason);
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

static void writeEaPdfA3(Path out, EmailRecord r, String fontPath, String iccPath, boolean debug, Path debugDir, int idx) throws Exception {
  try (Common.PerFileLogCapture ignored = Common.beginPerFileLog(out)) {
  ConversionProgress.stage("rendering");
  SingleMessageBuildResult built = buildSingleMessagePdf(out, r, fontPath, iccPath, debug, debugDir, idx, null, out, true);
  ConversionProgress.completed();
  Common.recordWarnings(r);

  if (debug) Common.dbg(r, "wrote pdf=" + out);
  }
}

static SingleMessageBuildResult buildSingleMessagePdf(Path logicalOut, EmailRecord r, String fontPath, String iccPath, boolean debug, Path debugDir, int idx) throws Exception {
  return buildSingleMessagePdf(logicalOut, r, fontPath, iccPath, debug, debugDir, idx, null);
}

static SingleMessageBuildResult buildSingleMessagePdf(Path logicalOut, EmailRecord r, String fontPath, String iccPath, boolean debug, Path debugDir, int idx, String profileOverride) throws Exception {
  return buildSingleMessagePdf(logicalOut, r, fontPath, iccPath, debug, debugDir, idx, profileOverride, null);
}

static SingleMessageBuildResult buildSingleMessagePdf(Path logicalOut, EmailRecord r, String fontPath, String iccPath, boolean debug, Path debugDir, int idx, String profileOverride, Path destination) throws Exception {
  return buildSingleMessagePdf(logicalOut, r, fontPath, iccPath, debug, debugDir, idx, profileOverride, destination, false);
}

private static SingleMessageBuildResult buildSingleMessagePdf(Path logicalOut, EmailRecord r, String fontPath, String iccPath,
    boolean debug, Path debugDir, int idx, String profileOverride, Path destination, boolean outermost) throws Exception {
  Common.finalizeRecordMetadata(r);
  Common.requireCreationDate(r);
  PreservationInventory expected = PreservationInventory.capture(List.of(r), chooseEaPdfConformance(r, profileOverride));
  ProcessingLimits.checkBody(Common.textBody(r), r.sourcePath);
  ProcessingLimits.checkBody(Common.htmlBody(r), r.sourcePath);
  String stem = Common.safeFileStem((r.subject != null && !r.subject.isBlank()) ? r.subject : "message") + "_" + String.format("%06d", idx);
  Path assetDir = debug ? debugDir.resolve(stem + "_assets") : Files.createTempDirectory("emailtopdfa-assets-");
  Files.createDirectories(assetDir);

  try {
    String html = buildRenderHtml(r, debug, assetDir, profileOverride);
    if (debug) {
      Path htmlFile = debugDir.resolve(stem + ".rendered.html");
      Path txtFile = debugDir.resolve(stem + ".body.txt");
      Path dbgFile = debugDir.resolve(stem + ".debug.txt");
      Files.writeString(htmlFile, html, StandardCharsets.UTF_8);
      Files.writeString(txtFile, Common.textBody(r), StandardCharsets.UTF_8);
      Files.writeString(dbgFile, String.join("\n", r.debugLog), StandardCharsets.UTF_8);
      Common.dbg(r, "wrote debug html=" + htmlFile);
      Common.dbg(r, "wrote debug txt=" + txtFile);
      Common.dbg(r, "wrote debug log=" + dbgFile);
    }

    byte[] rendered = renderHtmlPdf(html, assetDir.toUri().toString(), fontPath, r, debug);
    if (debug) Common.dbg(r, "rendered pdf bytes=" + rendered.length);

    PDDocument doc = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(rendered), org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
    try {
      doc.setVersion(2.0f);
      doc.getDocument().setVersion(2.0f);
      drawInlineImageFallback(doc, r, debug);
      addOmittedAttachmentAnnotations(doc, r, fontPath);
      clearImageInterpolation(doc);
      drawPageContext(doc, fontPath);
      setDocumentInfo(doc, r);
      configureCatalog(doc, r, profileOverride);
      applyPageLabels(doc, 0, true);
      addOutlines(doc, r);
      addDPartStructure(doc, r);

      Map<String, PDComplexFileSpecification> embeddedFiles = embedAssociatedFiles(doc, r, debug);
      PdfAttachmentSupport.rewriteAttachmentLaunchLinks(doc, embeddedFiles);
      addLogicalStructure(doc, r);
      setPdfA3Metadata(doc, iccPath, r, profileOverride);
      normalizePageContentStreams(doc);
      SemanticStructure.balanceParentTree(doc);
      if (destination != null) {
        if (outermost) Common.writeOuterPdfAtomically(destination, temporary -> PdfFontMetadata.save(doc, temporary.toFile()), expected);
        else Common.writePdfAtomically(destination, temporary -> PdfFontMetadata.save(doc, temporary.toFile()), expected);
        return new SingleMessageBuildResult(null);
      }
      ByteArrayOutputStream finalized = new ByteArrayOutputStream();
      PdfFontMetadata.save(doc, finalized);
      byte[] bytes = finalized.toByteArray();
      if (EapdfConfig.get().validateOnCreation) {
        try (PDDocument serialized = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(bytes),
            org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
          PdfCreationValidator.validate(serialized, expected);
        }
      }
      return new SingleMessageBuildResult(bytes);
    } finally {
      try { doc.close(); } catch (Exception ignored) {}
    }
  } finally {
    if (!debug) Common.deleteTree(assetDir);
  }
}

static byte[] buildArchiveMessagePdfBytes(EmailRecord r, String fontPath, boolean debug, Path debugDir, int idx) throws Exception {
  return buildArchiveMessagePdfBytes(r, fontPath, debug, debugDir, idx, null);
}

static byte[] buildArchiveMessagePdfBytes(EmailRecord r, String fontPath, boolean debug, Path debugDir, int idx, String profileOverride) throws Exception {
  return buildArchiveMessagePdfBytes(r, fontPath, debug, debugDir, idx, profileOverride, "");
}

static byte[] buildArchiveMessagePdfBytes(EmailRecord r, String fontPath, boolean debug, Path debugDir, int idx, String profileOverride, String archiveSourceName) throws Exception {
  String stem = Common.safeFileStem((r.subject != null && !r.subject.isBlank()) ? r.subject : "message") + "_" + String.format("%06d", idx);
  Path assetDir = debug ? debugDir.resolve(stem + "_assets") : Files.createTempDirectory("emailtopdfa-assets-");
  Files.createDirectories(assetDir);

  try {
    String html = buildRenderHtml(r, debug, assetDir, profileOverride, archiveSourceName);
    if (debug) {
      Path htmlFile = debugDir.resolve(stem + ".rendered.html");
      Path txtFile = debugDir.resolve(stem + ".body.txt");
      Path dbgFile = debugDir.resolve(stem + ".debug.txt");
      Files.writeString(htmlFile, html, StandardCharsets.UTF_8);
      Files.writeString(txtFile, Common.textBody(r), StandardCharsets.UTF_8);
      Files.writeString(dbgFile, String.join("\n", r.debugLog), StandardCharsets.UTF_8);
      Common.dbg(r, "wrote debug html=" + htmlFile);
      Common.dbg(r, "wrote debug txt=" + txtFile);
      Common.dbg(r, "wrote debug log=" + dbgFile);
    }

    byte[] rendered;
    FontSupport.ARCHIVE_FONTS.set(true);
    try { rendered = renderHtmlPdf(html, assetDir.toUri().toString(), fontPath, r, debug); }
    finally { FontSupport.ARCHIVE_FONTS.remove(); }
    if (debug) Common.dbg(r, "rendered pdf bytes=" + rendered.length);
    try (PDDocument doc = Loader.loadPDF(rendered)) {
      SemanticStructure.headerFields(doc, r.headerTagMarker);
      drawInlineImageFallback(doc, r, debug);
      addOmittedAttachmentAnnotations(doc, r, fontPath);
      clearImageInterpolation(doc);

      ByteArrayOutputStream finalized = new ByteArrayOutputStream();
      PdfFontMetadata.save(doc, finalized);
      return finalized.toByteArray();
    }
  } finally {
    if (!debug) Common.deleteTree(assetDir);
  }
}

static int renderParallelism(int workItems) {
  if (workItems <= 1) return 1;
  int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
  int memoryWorkers = (int)Math.max(1, Math.min(Integer.MAX_VALUE, Runtime.getRuntime().maxMemory() / (ProcessingLimits.messageBytes() * 16)));
  Common.JobReport report = Common.ACTIVE_JOB.get();
  return Math.max(1, Math.min(memoryWorkers, Math.min(workItems, Math.min(cpu, report == null ? 4 : report.workerLimit))));
}

interface RenderedPartConsumer {
  void accept(RenderedMessagePart part) throws Exception;
}

static void forEachArchiveMessagePart(List<EmailRecord> records, String fontPath, boolean debug, Path debugDir, String archiveProfile, RenderedPartConsumer consumer) throws Exception {
  List<EmailRecord> source = records == null ? List.of() : records;
  if (source.isEmpty()) return;
  // Archive names must be settled before rendering; preserve original source names
  // in the record because collection children have their own filename scope.
  for (EmailRecord record : source) Common.finalizeRecordMetadata(record);
  Map<EmailRecord, String> sourceNames = new IdentityHashMap<>();
  for (ArchiveEmbeddedFilePlan plan : buildArchiveEmbeddedFilePlans(source))
    if ("Source".equals(plan.relationship)) sourceNames.putIfAbsent(plan.record, Common.portablePdfFilename(plan.filename));
  ConversionProgress.stage("rendering");
  int parallelism = renderParallelism(source.size());
  if (parallelism <= 1) {
    int idx = 0;
    for (EmailRecord record : source) {
      idx++;
      Common.finalizeRecordMetadata(record);
      consumer.accept(new RenderedMessagePart(record, buildArchiveMessagePdfBytes(record, fontPath, debug, debugDir, idx, archiveProfile, sourceNames.getOrDefault(record, ""))));
      ConversionProgress.completed();
    }
    return;
  }

  ExecutorService pool = Common.newWorkers(parallelism);
  try {
    Map<Integer, Future<RenderedMessagePart>> inFlight = new LinkedHashMap<>();
    int submitted = 0;
    for (int expected = 1; expected <= source.size(); expected++) {
      while (submitted < source.size() && inFlight.size() < parallelism) {
        final int idx = submitted + 1;
        final EmailRecord record = source.get(submitted);
        inFlight.put(idx, pool.submit(() -> {
          Common.finalizeRecordMetadata(record);
          return new RenderedMessagePart(record, buildArchiveMessagePdfBytes(record, fontPath, debug, debugDir, idx, archiveProfile, sourceNames.getOrDefault(record, "")));
        }));
        submitted++;
      }
      consumer.accept(Common.awaitWorker(inFlight.remove(expected)));
      ConversionProgress.completed();
    }
  } catch (ExecutionException e) {
    Throwable cause = e.getCause();
    if (cause instanceof Exception ex) throw ex;
    throw new RuntimeException(cause);
  } finally {
    Common.stopWorkers(pool);
  }
}

static void writeEaPdfArchive(Path out, List<EmailRecord> records, String fontPath, String iccPath, boolean debug, Path debugDir, String archiveProfile) throws Exception {
  try (Common.PerFileLogCapture ignored = Common.beginPerFileLog(out)) {
  if (records == null || records.size() < 2) throw Common.fail("PDF/mail-1m and PDF/mail-1mi require at least two emails; use a single-message profile for one email.");
  ProcessingLimits.checkCount(records.size());
  Set<String> identifiers = new HashSet<>();
  for (EmailRecord record : records) {
    record.embedSourceMessage = !archiveProfile.endsWith("i");
    Common.finalizeRecordMetadata(record);
    if (!identifiers.add(record.mailGuid)) throw Common.fail("Each email in an archive must have a unique internal Mail_GUID.");
  }
  PreservationInventory expected = PreservationInventory.capture(records, archiveProfile);
  // Return from the writer with all PDF graphs out of scope before the saved
  // archive is reopened for the unchanged creation/preservation checks.
  ConversionProgress.stage("preparing_archive");
  Common.writeOuterPdfAtomically(out, temporary -> saveArchiveDocument(
      temporary, out, records, fontPath, iccPath, debug, debugDir, archiveProfile), expected);
  for (EmailRecord record : records) Common.recordWarnings(record);
  }
}


private static void saveArchiveDocument(Path temporary, Path out, List<EmailRecord> records,
    String fontPath, String iccPath, boolean debug, Path debugDir, String archiveProfile) throws Exception {
  List<MessageSegment> segments = new ArrayList<>();
  try (PDDocument archive = new PDDocument(CompactStreamCache::new)) {
    try {
      archive.setVersion(2.0f);
      archive.getDocument().setVersion(2.0f);
      ArchiveResourcePool resources = new ArchiveResourcePool();
      forEachArchiveMessagePart(records, fontPath, debug, debugDir, archiveProfile, rendered -> {
        try (PDDocument part = Loader.loadPDF(rendered.pdfBytes)) {
          int startPage = archive.getNumberOfPages() + 1;
          MessageSegment segment = new MessageSegment(rendered.record, startPage, startPage + part.getNumberOfPages() - 1);
          appendClonedPagesWithLast(archive, part, segment, resources);
          segments.add(segment);
        }
      });
      ConversionProgress.stage("assembling");
      setDocumentInfo(archive, records.get(0));
      configureArchiveCatalog(archive, archiveProfile);
      applyPageLabels(archive, 0, false);
      ConversionProgress.begin("bookmarks", segments.size(), "emails");
      addArchiveOutlines(archive, segments, 0);
      ConversionProgress.begin("document_parts", segments.size(), "emails");
      addArchiveDPartStructure(archive, segments, 0);

      ConversionProgress.stage("embedding");
      EmbeddedFileBindings embeddedFiles = embedAssociatedFiles(archive, records, debug);
      PdfAttachmentSupport.rewriteAttachmentLaunchLinks(archive, embeddedFiles.filesByToken);
      ConversionProgress.begin("page_information", archive.getNumberOfPages() < 2 || !CONFIG.accessibility.pageLabels.enabled ? 0 : archive.getNumberOfPages(), "pages");
      drawPageContext(archive, fontPath);
      ConversionProgress.begin("tagging", segments.size(), "emails");
      addArchiveLogicalStructure(archive, segments, 0);
      ConversionProgress.stage("metadata");
      setPdfA3Metadata(archive, iccPath, records, archiveProfile);
      ConversionProgress.begin("page_streams", archive.getNumberOfPages(), "pages");
      normalizePageContentStreams(archive);
      Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
      SemanticStructure.balanceParentTree(archive);
      ArchiveFontSubsets.apply(archive);
      ConversionProgress.saving(temporary);
      // Larger bounded groups improve lossless compression of repeated archive structures.
      PdfFontMetadata.save(archive, temporary.toFile(), new org.apache.pdfbox.pdfwriter.compress.CompressParameters(2000));
    } finally {
      PdfDocumentParts.releaseAllocator(archive);
    }
  }
}

static int appendClonedPages(PDDocument target, PDDocument source) throws Exception {
  return appendClonedPages(target, source, null);
}

static int appendClonedPages(PDDocument target, PDDocument source, MessageSegment segment) throws Exception {
  return appendClonedPages(target, source, segment, null);
}

private record AppendedPages(int count, PDPage lastPage) {}

static int appendClonedPages(PDDocument target, PDDocument source, MessageSegment segment, ArchiveResourcePool resources) throws Exception {
  return appendClonedPagesWithLast(target, source, segment, resources).count();
}

private static AppendedPages appendClonedPagesWithLast(PDDocument target, PDDocument source, MessageSegment segment, ArchiveResourcePool resources) throws Exception {
  if (target == null || source == null) return new AppendedPages(0, null);
  PDFCloneUtility cloner = resources == null ? new PDFCloneUtility(target) {} : resources.cloner(target);
  int count = 0;
  PDPage lastPage = null;
  for (PDPage page : source.getPages()) {
    COSDictionary cloned = (COSDictionary) cloner.cloneForNewDocument(page.getCOSObject());
    cloned.removeItem(COSName.PARENT);
    PDPage imported = new PDPage(cloned);
    target.addPage(imported);
    lastPage = imported;
    count++;
  }
  if (segment != null) segment.semanticTree = SemanticStructure.importedTree(target, source, cloner);
  return new AppendedPages(count, lastPage);
}

static void writeEaPdfCollection(Path out, List<EmailRecord> records, String fontPath, String iccPath, boolean debug, Path debugDir, String collectionProfile) throws Exception {
  try (Common.PerFileLogCapture ignored = Common.beginPerFileLog(out); PayloadStore memberStore = new PayloadStore()) {
  if (records == null || records.isEmpty()) throw new IllegalArgumentException("records must not be empty");
  ProcessingLimits.checkCount(records.size());
  PreservationInventory expected = PreservationInventory.capture(records, collectionProfile);
  List<FolderSection> sections = PdfCollectionSupport.buildFolderSections(records);
  if (sections.isEmpty()) throw new IllegalArgumentException("collection requires at least one folder section");
  List<CollectionFolder> folders = PdfCollectionSupport.buildCollectionFolders(sections);
  CollectionRunSummary runSummary = buildCollectionRunSummary(records, sections);
  ConversionProgress.stage("rendering");
  List<CollectionMember> members = PdfCollectionSupport.buildFolderCollectionMembers(sections, folders, fontPath, iccPath, debug, debugDir, collectionProfile, memberStore);
  ConversionProgress.stage("assembling");
  if (members.isEmpty()) throw new IllegalArgumentException("collection requires at least one embedded EA-PDF member");
  try (PDDocument archive = PdfAttachmentSupport.buildCollectionFrontMatter(collectionProfile, runSummary,
      fontPath, records.stream().anyMatch(record -> !record.sourceProblems.isEmpty()))) {
    archive.setVersion(2.0f);
    archive.getDocument().setVersion(2.0f);
    clearImageInterpolation(archive);
    setDocumentInfo(archive, records.get(0));
    configureArchiveCatalog(archive, collectionProfile);
    applyPageLabels(archive, archive.getNumberOfPages(), false);
    PdfCollectionSupport.addTopLevelCollectionDictionary(archive, folders);
    addOutlines(archive, PdfCollectionSupport.buildCollectionFrontRecord(records, collectionProfile, members));
    PdfCollectionSupport.addCollectionDPartStructure(archive, records, collectionProfile);
    setPdfA3Metadata(archive, iccPath, records, collectionProfile);
    PdfCollectionSupport.embedCollectionFiles(archive, records, members, debug, true);
    // Embedded streams now own their copied bytes. Release child working PDFs before saving the collection.
    memberStore.close();
    PdfCollectionSupport.addCollectionLogicalStructure(archive);
    PdfCollectionSupport.addTopLevelCollectionDictionary(archive, folders);
    normalizePageContentStreams(archive);
    Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
    Common.writeOuterPdfAtomically(out, temporary -> {
      SemanticStructure.balanceParentTree(archive);
      ConversionProgress.saving(temporary);
      PdfFontMetadata.save(archive, temporary.toFile());
      ConversionProgress.stage(EapdfConfig.get().validateOnCreation ? "checking" : "writing");
      // Release the collection scratch streams before reopening and checking the saved PDF.
      archive.close();
      PdfCollectionSupport.repairSavedTopLevelCollection(temporary, folders, members, debug, expected);
    }, expected);
  }
  for (EmailRecord record : records) Common.recordWarnings(record);
  }
}

static String ROOT_URI() {
  return Paths.get(".").toAbsolutePath().normalize().toUri().toString();
}

static void configureLinkAnnotation(PDAnnotationLink annotation, PDRectangle rect) {
  PdfAttachmentSupport.configureLinkAnnotation(annotation, rect);
}

static PDType0Font loadUiFont(PDDocument doc, String fontPath) throws Exception {
  return FontSupport.loadUiFont(doc, fontPath);
}

static CollectionRunSummary buildCollectionRunSummary(List<EmailRecord> records, List<FolderSection> sections) {
  LinkedHashSet<String> emails = new LinkedHashSet<>();
  for (EmailRecord record : records) {
    if (record == null || record.archiveEmails == null) continue;
    for (String email : record.archiveEmails) {
      String value = Common.nz(email).trim();
      if (!value.isBlank()) emails.add(value);
    }
  }
  String harvested = emails.isEmpty() ? "Not recorded" : String.join(", ", emails);
  String generatedAt = ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));
  return new CollectionRunSummary(harvested, generatedAt, sections.size(), records.size());
}

static void drawInlineImageFallback(PDDocument doc, EmailRecord r, boolean debug) throws Exception {
  if (doc == null || doc.getNumberOfPages() == 0 || r == null || r.associatedFiles == null) return;
  if (documentHasImageXObjects(doc)) return;

  AssociatedFile imageFile = null;
  for (AssociatedFile af : r.associatedFiles) {
    if (af == null || !af.inline || !af.hasData() || af.size() == 0) continue;
    String mime = Common.detectMimeType(af.filename, af.mime, af.head()).toLowerCase(Locale.ROOT);
    if (mime.startsWith("image/")) {
      imageFile = af;
      break;
    }
  }
  if (imageFile == null) return;
  if (debug) Common.dbg(r, "inline image fallback skipped: no source layout position can be established for " + Common.nz(imageFile.filename));
}

static boolean pdfUaFeaturesEnabled() {
  return CONFIG.accessibility != null && CONFIG.accessibility.addPdfUaFeatures;
}

static void appendSourceProblems(StringBuilder x, List<EmailRecord> records) {
  if (records.stream().noneMatch(r -> !r.sourceProblems.isEmpty())) return;
  x.append("<rdf:Description rdf:about=\"\" xmlns:conversion=\"https://thinkbox.digital/ns/eapdf/conversion/1.0/\">")
      .append("<conversion:status>INCOMPLETE</conversion:status><conversion:problems><rdf:Seq>");
  for (EmailRecord record : records) for (String problem : record.sourceProblems)
    x.append("<rdf:li>").append(Common.xml(record.mailGuid + " | " + record.subject + " | " + problem)).append("</rdf:li>");
  x.append("</rdf:Seq></conversion:problems></rdf:Description>");
}

static String pdfUaIdentification() {
  if (!pdfUaFeaturesEnabled()) return "";
  return "<rdf:Description rdf:about=\"\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\">"
      + "<pdfuaid:part>2</pdfuaid:part><pdfuaid:rev>2024</pdfuaid:rev></rdf:Description>";
}

static void drawPageContext(PDDocument document, String fontPath) throws Exception {
  if (document.getNumberOfPages() < 2 || !CONFIG.accessibility.pageLabels.enabled) return;
  PDType0Font font = loadUiFont(document, fontPath);
  int pageCount = document.getNumberOfPages();
  int index = 0;
  for (PDPage page : document.getPages()) {
    String label = "Page " + (++index) + " of " + pageCount;
    PDRectangle box = page.getCropBox();
    float positionX = Math.max(box.getLowerLeftX() + 10f, box.getUpperRightX() - 46.8f - textWidth(font, 8f, label));
    try (PDPageContentStream content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
      beginArtifact(content);
      drawText(content, font, 8f, positionX, box.getLowerLeftY() + 14f, label);
      endArtifact(content);
    }
    ConversionProgress.advance("page_information");
  }
}

static void beginArtifact(PDPageContentStream cs) throws IOException {
  cs.beginMarkedContent(COSName.ARTIFACT);
}

static void endArtifact(PDPageContentStream cs) throws IOException {
  cs.endMarkedContent();
}



static boolean documentHasImageXObjects(PDDocument doc) {
  for (PDPage page : doc.getPages()) {
    if (pageHasImageXObjects(page.getCOSObject())) return true;
  }
  return false;
}



static String buildMessageCollectionRenderHtml(List<CollectionMember> members, String collectionProfile, String folderName) {
  return PdfCollectionSupport.buildMessageCollectionRenderHtml(members, collectionProfile, folderName);
}

static boolean pageHasImageXObjects(COSDictionary page) {
  COSDictionary resources = (COSDictionary)page.getDictionaryObject(COSName.RESOURCES);
  return resourcesHasImageXObjects(resources);
}

static boolean resourcesHasImageXObjects(COSDictionary resources) {
  if (resources == null) return false;
  COSDictionary xobjects = (COSDictionary)resources.getDictionaryObject(COSName.XOBJECT);
  if (xobjects == null) return false;
  for (COSName name : xobjects.keySet()) {
    COSBase base = xobjects.getDictionaryObject(name);
    if (!(base instanceof COSStream stream)) continue;
    COSName subtype = stream.getCOSName(COSName.SUBTYPE);
    if (COSName.IMAGE.equals(subtype)) return true;
    if (COSName.FORM.equals(subtype)) {
      COSDictionary nested = (COSDictionary)stream.getDictionaryObject(COSName.RESOURCES);
      if (resourcesHasImageXObjects(nested)) return true;
    }
  }
  return false;
}

static void clearImageInterpolation(PDDocument doc) {
  for (PDPage page : doc.getPages()) {
    clearImageInterpolationFromResources((COSDictionary)page.getCOSObject().getDictionaryObject(COSName.RESOURCES));
  }
}

static void clearImageInterpolationFromResources(COSDictionary resources) {
  if (resources == null) return;
  COSDictionary xobjects = (COSDictionary)resources.getDictionaryObject(COSName.XOBJECT);
  if (xobjects == null) return;
  for (COSName name : xobjects.keySet()) {
    COSBase base = xobjects.getDictionaryObject(name);
    if (!(base instanceof COSStream stream)) continue;
    COSName subtype = stream.getCOSName(COSName.SUBTYPE);
    if (COSName.IMAGE.equals(subtype)) {
      stream.setBoolean(COSName.INTERPOLATE, false);
    } else if (COSName.FORM.equals(subtype)) {
      clearImageInterpolationFromResources((COSDictionary)stream.getDictionaryObject(COSName.RESOURCES));
    }
  }
}

static void setDocumentInfo(PDDocument doc, EmailRecord r) {
  doc.getDocument().getTrailer().removeItem(COSName.INFO);
}

static void configureCatalog(PDDocument doc, EmailRecord r) {
  configureCatalog(doc, r, null);
}

static void configureCatalog(PDDocument doc, EmailRecord r, String profileOverride) {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  COSDictionary catd = cat.getCOSObject();
  catd.setName(COSName.VERSION, "2.0");
  // The renderer can leave an empty form dictionary requesting viewer-created
  // appearances even when all controls were painted as static page content.
  // There are no fields to preserve in that case; signatures are added later.
  if (catd.getDictionaryObject(COSName.ACRO_FORM) instanceof COSDictionary form
      && form.getDictionaryObject(COSName.FIELDS) instanceof COSArray fields && fields.size() == 0) {
    catd.removeItem(COSName.ACRO_FORM);
  }

  String pageMode = choosePageMode(r);
  String nfsm = chooseNonFullScreenPageMode(r, pageMode, profileOverride);

  catd.setName(COSName.PAGE_MODE, pageMode);

  PDViewerPreferences vp = cat.getViewerPreferences();
  if (vp == null) {
    vp = new PDViewerPreferences(new COSDictionary());
    cat.setViewerPreferences(vp);
  }
  vp.setDisplayDocTitle(true);
  applyCatalogAccessibility(cat, vp);
  vp.getCOSObject().setName(COSName.getPDFName("NonFullScreenPageMode"), nfsm);
}

static void configureArchiveCatalog(PDDocument doc, String conformance) {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  COSDictionary catd = cat.getCOSObject();
  catd.setName(COSName.VERSION, "2.0");
  String pageMode = chooseArchivePageMode(conformance);
  String nfsm = chooseArchiveNonFullScreenPageMode(conformance, pageMode);
  catd.setName(COSName.PAGE_MODE, pageMode);
  PDViewerPreferences vp = cat.getViewerPreferences();
  if (vp == null) {
    vp = new PDViewerPreferences(new COSDictionary());
    cat.setViewerPreferences(vp);
  }
  vp.setDisplayDocTitle(true);
  applyCatalogAccessibility(cat, vp);
  vp.getCOSObject().setName(COSName.getPDFName("NonFullScreenPageMode"), nfsm);
}

static void applyCatalogAccessibility(PDDocumentCatalog cat, PDViewerPreferences vp) {
  EapdfConfig.AccessibilityConfig accessibility = CONFIG.accessibility;
  if (accessibility == null || !accessibility.addPdfUaFeatures) return;
  if (accessibility.markInfoMarked) {
    COSDictionary markInfo = (COSDictionary)cat.getCOSObject().getDictionaryObject(COSName.MARK_INFO);
    if (markInfo == null) {
      markInfo = new COSDictionary();
      cat.getCOSObject().setItem(COSName.MARK_INFO, markInfo);
    }
    markInfo.setBoolean(COSName.getPDFName("Marked"), true);
  }
  if (accessibility.language != null && !accessibility.language.isBlank()) {
    cat.setLanguage(accessibility.language);
  }
  String readingDirection = Common.nz(accessibility.readingDirection);
  if ("L2R".equalsIgnoreCase(readingDirection)) {
    vp.setReadingDirection(PDViewerPreferences.READING_DIRECTION.L2R);
  } else if ("R2L".equalsIgnoreCase(readingDirection)) {
    vp.setReadingDirection(PDViewerPreferences.READING_DIRECTION.R2L);
  }
}

static void applyPageLabels(PDDocument doc, int frontMatterPages, boolean singleMessage) {
  if (!pdfUaFeaturesEnabled()) return;
  EapdfConfig.PageLabelsConfig pageLabels = CONFIG.accessibility.pageLabels;
  if (!pageLabels.enabled) return;
  if (singleMessage && !pageLabels.singleMessage) return;
  if (!singleMessage && !pageLabels.archiveAndCollection) return;

  COSDictionary labels = new COSDictionary();
  COSArray nums = new COSArray();
  if (!singleMessage && frontMatterPages > 0) {
    COSDictionary front = new COSDictionary();
    front.setString(COSName.P, pageLabels.frontMatterPrefix);
    front.setName(COSName.S, "D");
    front.setInt(COSName.ST, 1);
    nums.add(COSInteger.ZERO);
    nums.add(front);

    if (frontMatterPages < doc.getNumberOfPages()) {
      COSDictionary rest = new COSDictionary();
      rest.setName(COSName.S, "D");
      rest.setInt(COSName.ST, 1);
      nums.add(COSInteger.get(frontMatterPages));
      nums.add(rest);
    }
  } else {
    COSDictionary all = new COSDictionary();
    all.setName(COSName.S, "D");
    all.setInt(COSName.ST, 1);
    nums.add(COSInteger.ZERO);
    nums.add(all);
  }
  labels.setItem(COSName.NUMS, nums);
  doc.getDocumentCatalog().getCOSObject().setItem(COSName.PAGE_LABELS, labels);
}

static void addTopLevelCollectionDictionary(PDDocument doc, List<CollectionFolder> folders) throws IOException {
  PdfCollectionSupport.addTopLevelCollectionDictionary(doc, folders);
}

static void addMessageCollectionDictionary(PDDocument doc) {
  PdfCollectionSupport.addMessageCollectionDictionary(doc);
}



static String pdfDateNow() {
  Calendar now = Calendar.getInstance();
  int offsetMinutes = now.getTimeZone().getOffset(now.getTimeInMillis()) / (60 * 1000);
  char sign = offsetMinutes >= 0 ? '+' : '-';
  int abs = Math.abs(offsetMinutes);
  int hours = abs / 60;
  int minutes = abs % 60;
  return String.format(Locale.ROOT,
      "D:%04d%02d%02d%02d%02d%02d%c%02d'%02d'",
      now.get(Calendar.YEAR),
      now.get(Calendar.MONTH) + 1,
      now.get(Calendar.DAY_OF_MONTH),
      now.get(Calendar.HOUR_OF_DAY),
      now.get(Calendar.MINUTE),
      now.get(Calendar.SECOND),
      sign,
      hours,
      minutes);
}


static String choosePageMode(EmailRecord r) {
  return sourceEmailHasAttachments(r) ? "UseAttachments" : "UseOutlines";
}

static String chooseNonFullScreenPageMode(EmailRecord r, String pageMode) {
  return chooseNonFullScreenPageMode(r, pageMode, null);
}

static String chooseNonFullScreenPageMode(EmailRecord r, String pageMode, String profileOverride) {
  String conf = chooseEaPdfConformance(r, profileOverride);
  if ("s".equals(conf) || "si".equals(conf)) {
    return sourceEmailHasAttachments(r) ? "UseAttachments" : "UseOutlines";
  }
  return pageMode;
}

static boolean sourceEmailHasAttachments(EmailRecord r) {
  return r != null && r.attachmentCount > 0;
}

static String chooseEaPdfConformance(EmailRecord r) {
  return chooseEaPdfConformance(r, null);
}

static String chooseEaPdfConformance(EmailRecord r, String profileOverride) {
  if (!Common.nz(profileOverride).isBlank()) return profileOverride;
  if (r != null && !r.embedSourceMessage) return "si";
  return "s";
}

static String chooseArchivePageMode(String conformance) {
  if ("c".equals(conformance) || "ci".equals(conformance)) return "UseAttachments";
  return ("m".equals(conformance) || "mi".equals(conformance)) ? "UseOutlines" : "UseAttachments";
}

static String chooseArchiveNonFullScreenPageMode(String conformance, String pageMode) {
  if ("c".equals(conformance) || "ci".equals(conformance)) return "UseAttachments";
  return ("m".equals(conformance) || "mi".equals(conformance)) ? "UseOutlines" : pageMode;
}

static EmailRecord buildCollectionFrontRecord(List<EmailRecord> records, String collectionProfile, List<CollectionMember> members) {
  return PdfCollectionSupport.buildCollectionFrontRecord(records, collectionProfile, members);
}

static void addCollectionDPartStructure(PDDocument doc, List<EmailRecord> records, String collectionProfile) throws Exception {
  PdfCollectionSupport.addCollectionDPartStructure(doc, records, collectionProfile);
}

static void addCollectionLogicalStructure(PDDocument doc) throws IOException {
  PdfCollectionSupport.addCollectionLogicalStructure(doc);
}

static void setCompressedMetadata(PDDocument doc, String xmp) throws IOException {
  PDMetadata metadata = new PDMetadata(doc);
  try (OutputStream output = metadata.createOutputStream(COSName.FLATE_DECODE)) {
    output.write(xmp.getBytes(StandardCharsets.UTF_8));
  }
  doc.getDocumentCatalog().setMetadata(metadata);
}

static void setPdfA3Metadata(PDDocument doc, String iccPath, EmailRecord r) throws Exception {
  setPdfA3Metadata(doc, iccPath, r, null);
}

static void setPdfA3Metadata(PDDocument doc, String iccPath, EmailRecord r, String profileOverride) throws Exception {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  String xmp = buildXmpMetadata(r, profileOverride);
  setCompressedMetadata(doc, xmp);

  try (InputStream icc = Files.newInputStream(Paths.get(iccPath))) {
    PDOutputIntent oi = new PDOutputIntent(doc, icc);
    oi.setInfo("sRGB IEC61966-2.1");
    oi.setOutputCondition("sRGB IEC61966-2.1");
    oi.setOutputConditionIdentifier("sRGB IEC61966-2.1");
    oi.setRegistryName("http://www.color.org");
    // Replace the intermediate intent so all final ICC references remain consistent.
    cat.setOutputIntents(List.of(oi));
  }
}



static void setPdfA3Metadata(PDDocument doc, String iccPath, List<EmailRecord> records, String archiveProfile) throws Exception {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  String xmp = buildXmpMetadata(records, archiveProfile);
  setCompressedMetadata(doc, xmp);

  try (InputStream icc = Files.newInputStream(Paths.get(iccPath))) {
    PDOutputIntent oi = new PDOutputIntent(doc, icc);
    oi.setInfo("sRGB IEC61966-2.1");
    oi.setOutputCondition("sRGB IEC61966-2.1");
    oi.setOutputConditionIdentifier("sRGB IEC61966-2.1");
    oi.setRegistryName("http://www.color.org");
    // Replace the intermediate intent so all final ICC references remain consistent.
    cat.setOutputIntents(List.of(oi));
  }
}

static String buildXmpMetadata(EmailRecord r) throws Exception {
  return buildXmpMetadata(r, null);
}

static String buildXmpMetadata(EmailRecord r, String profileOverride) throws Exception {
  Common.finalizeRecordMetadata(r);
  Common.requireCreationDate(r);
  String now = Common.xmpDateNowUtc();
  String claimDate = LocalDate.now(ZoneOffset.UTC).toString();
  String title = Common.stripInvalidXml(Common.nz(r.subject).isBlank() ? "Email" : Common.nz(r.subject));
  String description = buildDescription(r);
  String producer = Common.productLabel() + " via OpenHTMLtoPDF + Apache PDFBox";
  String creatorTool = Common.productLabel();
  String conformance = chooseEaPdfConformance(r, profileOverride);
  String mailGuid = Common.nz(r.mailGuid).isBlank() ? buildMailGuid(r) : Common.nz(r.mailGuid);
  StringBuilder x = new StringBuilder();

  x.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>");
  x.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">");
  x.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"");
  x.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"");
  x.append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"");
  x.append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"");
  x.append(" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"");
  x.append(" xmlns:pdfd=\"http://pdfa.org/declarations/\"");
  x.append(" xmlns:foaf=\"http://xmlns.com/foaf/0.1/\"");
  x.append(" xmlns:pdfmail=\"http://www.pdfa.org/eapdf/\"");
  x.append(" xmlns:pdfmailid=\"http://www.pdfa.org/eapdf/ns/id/\"");
  x.append(" xmlns:pdfmailmeta=\"http://www.pdfa.org/eapdf/ns/meta/\">");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<dc:creator><rdf:Seq><rdf:li>").append(Common.xml(CONFIG.requireCreator())).append("</rdf:li></rdf:Seq></dc:creator>");
  x.append("<dc:format>application/pdf</dc:format>");
  x.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(title)).append("</rdf:li></rdf:Alt></dc:title>");
  x.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(description)).append("</rdf:li></rdf:Alt></dc:description>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdf:Producer>").append(Common.xml(producer)).append("</pdf:Producer>");
  x.append("<pdf:Keywords>EA-PDF</pdf:Keywords>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<xmp:CreateDate>").append(Common.xml(now)).append("</xmp:CreateDate>");
  x.append("<xmp:ModifyDate>").append(Common.xml(now)).append("</xmp:ModifyDate>");
  x.append("<xmp:MetadataDate>").append(Common.xml(now)).append("</xmp:MetadataDate>");
  x.append("<xmp:CreatorTool>").append(Common.xml(creatorTool)).append("</xmp:CreatorTool>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfaid:part>4</pdfaid:part>");
  x.append("<pdfaid:rev>2020</pdfaid:rev>");
  x.append("<pdfaid:conformance>F</pdfaid:conformance>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailid:version>1</pdfmailid:version>");
  x.append("<pdfmailid:rev>2024</pdfmailid:rev>");
  x.append("<pdfmailid:conformance>").append(Common.xml(conformance)).append("</pdfmailid:conformance>");
  x.append("</rdf:Description>");

  appendSourceProblems(x, List.of(r));
  x.append(pdfUaIdentification());
  appendIsoTs32005Declaration(x, creatorTool, claimDate);
  appendConversionHistory(x, creatorTool, now, conformance, 1);

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailmeta:email><rdf:Bag><rdf:li rdf:parseType=\"Resource\">");
  appendStringProp(x, "Subject", r.subject);
  appendStringProp(x, "Raw-From", r.rawFrom);
  appendStringProp(x, "Raw-To", r.rawTo);
  appendStringProp(x, "Raw-Cc", r.rawCc);
  appendStringProp(x, "Raw-Bcc", r.rawBcc);
  if (!Common.nz(r.sentXmp).isBlank()) appendStringProp(x, "Sent", r.sentXmp);
  if (shouldEmitRawSent(r)) appendStringProp(x, "Raw-Sent", !Common.nz(r.rawDateHeader).isBlank() ? r.rawDateHeader : r.date);
  appendStringProp(x, "SizeInBytes", Integer.toString(messageMetadataSize(r)));
  appendStringProp(x, "NumberAttachments", Integer.toString(Math.max(r.attachmentCount, 0)));
  appendStringProp(x, "Mail_GUID", mailGuid);
  appendStringProp(x, "messageid", r.messageId);
  appendReplyMetadata(x, r);
  appendFoafSeq(x, "From", r.fromAgents);
  appendFoafSeq(x, "To", r.toAgents);
  appendFoafSeq(x, "Cc", r.ccAgents);
  appendFoafSeq(x, "Bcc", r.bccAgents);
  x.append("</rdf:li></rdf:Bag></pdfmailmeta:email>");

  x.append("<pdfmailmeta:assets><rdf:Bag>");
  for (AssociatedFile af : r.associatedFiles) {
    if (!includeAssociatedFile(r, af)) continue;
    x.append("<rdf:li rdf:parseType=\"Resource\">");
    appendStringProp(x, "Filename", Common.nz(af.filename));
    appendStringProp(x, "SizeInBytes", Integer.toString(storedAttachmentSize(r, af)));
    appendStringProp(x, "ContentType", embeddedAssetContentType(af, af.filename));
    appendStringProp(x, "Checksum", Common.nz(af.checksumMd5));
    x.append("</rdf:li>");
  }
  x.append("</rdf:Bag></pdfmailmeta:assets>");

  x.append("<pdfmailmeta:attachments><rdf:Bag>");
  for (AssociatedFile af : r.associatedFiles) {
    if (!af.emailAttachment) continue;
    x.append("<rdf:li rdf:parseType=\"Resource\">");
    appendStringProp(x, "Filename", Common.nz(af.filename));
    appendStringProp(x, "SizeInBytes", Integer.toString(storedAttachmentSize(r, af)));
    appendStringProp(x, "Content-Type", Common.sourceContentType(af));
    if (r.embedSourceMessage && !Common.nz(af.checksumMd5).isBlank()) appendStringProp(x, "CheckSum", Common.nz(af.checksumMd5));
    appendStringProp(x, "Mail_GUID", mailGuid);
    appendStringProp(x, "Description", attachmentDescription(r, af));
    x.append("</rdf:li>");
  }
  x.append("</rdf:Bag></pdfmailmeta:attachments>");

  x.append("</rdf:Description>");
  x.append("</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>");
  return x.toString();
}



static String buildXmpMetadata(List<EmailRecord> records, String archiveProfile) throws Exception {
  for (EmailRecord r : records) {
    Common.finalizeRecordMetadata(r);
    Common.requireCreationDate(r);
  }
  String now = Common.xmpDateNowUtc();
  String claimDate = LocalDate.now(ZoneOffset.UTC).toString();
  String title = Common.stripInvalidXml(buildArchiveTitle(records));
  String description = buildArchiveDescription(records, archiveProfile);
  String producer = Common.productLabel() + " via OpenHTMLtoPDF + Apache PDFBox";
  String creatorTool = Common.productLabel();
  StringBuilder x = new StringBuilder();

  x.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>");
  x.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">");
  x.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"");
  x.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"");
  x.append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"");
  x.append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"");
  x.append(" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"");
  x.append(" xmlns:pdfd=\"http://pdfa.org/declarations/\"");
  x.append(" xmlns:foaf=\"http://xmlns.com/foaf/0.1/\"");
  x.append(" xmlns:pdfmail=\"http://www.pdfa.org/eapdf/\"");
  x.append(" xmlns:pdfmailid=\"http://www.pdfa.org/eapdf/ns/id/\"");
  x.append(" xmlns:pdfmailmeta=\"http://www.pdfa.org/eapdf/ns/meta/\">");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<dc:creator><rdf:Seq><rdf:li>").append(Common.xml(CONFIG.requireCreator())).append("</rdf:li></rdf:Seq></dc:creator>");
  x.append("<dc:format>application/pdf</dc:format>");
  x.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(title)).append("</rdf:li></rdf:Alt></dc:title>");
  x.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(description)).append("</rdf:li></rdf:Alt></dc:description>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdf:Producer>").append(Common.xml(producer)).append("</pdf:Producer>");
  x.append("<pdf:Keywords>EA-PDF</pdf:Keywords>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<xmp:CreateDate>").append(Common.xml(now)).append("</xmp:CreateDate>");
  x.append("<xmp:ModifyDate>").append(Common.xml(now)).append("</xmp:ModifyDate>");
  x.append("<xmp:MetadataDate>").append(Common.xml(now)).append("</xmp:MetadataDate>");
  x.append("<xmp:CreatorTool>").append(Common.xml(creatorTool)).append("</xmp:CreatorTool>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfaid:part>4</pdfaid:part>");
  x.append("<pdfaid:rev>2020</pdfaid:rev>");
  x.append("<pdfaid:conformance>F</pdfaid:conformance>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailid:version>1</pdfmailid:version>");
  x.append("<pdfmailid:rev>2024</pdfmailid:rev>");
  x.append("<pdfmailid:conformance>").append(Common.xml(archiveProfile)).append("</pdfmailid:conformance>");
  x.append("</rdf:Description>");

  appendSourceProblems(x, records);
  x.append(pdfUaIdentification());
  appendIsoTs32005Declaration(x, creatorTool, claimDate);
  appendConversionHistory(x, creatorTool, now, archiveProfile, records.size());

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailmeta:Mail_GUID><rdf:Seq>");
  for (EmailRecord r : records) {
    if (!Common.nz(r.mailGuid).isBlank()) {
      x.append("<rdf:li>").append(Common.xml(r.mailGuid)).append("</rdf:li>");
    }
  }
  x.append("</rdf:Seq></pdfmailmeta:Mail_GUID>");
  x.append("<pdfmailmeta:messageid><rdf:Seq>");
  for (EmailRecord r : records) {
    if (!Common.nz(r.messageId).isBlank()) {
      x.append("<rdf:li>").append(Common.xml(r.messageId)).append("</rdf:li>");
    }
  }
  x.append("</rdf:Seq></pdfmailmeta:messageid>");

  x.append("<pdfmailmeta:email><rdf:Bag>");
  for (EmailRecord r : records) {
    x.append("<rdf:li rdf:parseType=\"Resource\">");
    appendStringProp(x, "Subject", r.subject);
    appendStringProp(x, "Raw-From", r.rawFrom);
    appendStringProp(x, "Raw-To", r.rawTo);
    appendStringProp(x, "Raw-Cc", r.rawCc);
    appendStringProp(x, "Raw-Bcc", r.rawBcc);
    if (!Common.nz(r.sentXmp).isBlank()) appendStringProp(x, "Sent", r.sentXmp);
    if (shouldEmitRawSent(r)) appendStringProp(x, "Raw-Sent", !Common.nz(r.rawDateHeader).isBlank() ? r.rawDateHeader : r.date);
    appendStringProp(x, "SizeInBytes", Integer.toString(messageMetadataSize(r)));
    appendStringProp(x, "NumberAttachments", Integer.toString(Math.max(r.attachmentCount, 0)));
    appendStringProp(x, "Mail_GUID", r.mailGuid);
    appendStringProp(x, "messageid", r.messageId);
    appendReplyMetadata(x, r);
    appendFoafSeq(x, "From", r.fromAgents);
    appendFoafSeq(x, "To", r.toAgents);
    appendFoafSeq(x, "Cc", r.ccAgents);
    appendFoafSeq(x, "Bcc", r.bccAgents);
    x.append("</rdf:li>");
  }
  x.append("</rdf:Bag></pdfmailmeta:email>");

  x.append("<pdfmailmeta:assets><rdf:Bag>");
  if (!"ci".equals(archiveProfile)) {
    for (ArchiveEmbeddedFilePlan plan : buildArchiveEmbeddedFilePlans(records)) {
      if (!"Source".equals(plan.relationship) && !plan.file.sourceSetMember) continue;
      x.append("<rdf:li rdf:parseType=\"Resource\">");
      appendStringProp(x, "Filename", plan.filename);
      appendStringProp(x, "SizeInBytes", Integer.toString(plan.file.size()));
      appendStringProp(x, "ContentType", plan.mime);
      appendStringProp(x, "Checksum", plan.checksumMd5);
      if ("Source".equals(plan.relationship)) appendStringProp(x, "NumberMessages", "1");
      x.append("</rdf:li>");
    }
  }
  x.append("</rdf:Bag></pdfmailmeta:assets>");

  x.append("<pdfmailmeta:attachments><rdf:Bag>");
  for (EmailRecord r : records) {
    for (AssociatedFile af : r.associatedFiles) {
      if (!af.emailAttachment) continue;
      x.append("<rdf:li rdf:parseType=\"Resource\">");
      appendStringProp(x, "Filename", Common.nz(af.filename));
      appendStringProp(x, "SizeInBytes", Integer.toString(storedAttachmentSize(r, af)));
      appendStringProp(x, "Content-Type", Common.sourceContentType(af));
      if (r.embedSourceMessage && !Common.nz(af.checksumMd5).isBlank()) appendStringProp(x, "CheckSum", Common.nz(af.checksumMd5));
      appendStringProp(x, "Mail_GUID", Common.nz(r.mailGuid));
      appendStringProp(x, "Description", attachmentDescription(r, af));
      x.append("</rdf:li>");
    }
  }
  x.append("</rdf:Bag></pdfmailmeta:attachments>");

  x.append("</rdf:Description>");
  x.append("</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>");
  return x.toString();
}

static String buildDescription(EmailRecord r) {
  StringBuilder d = new StringBuilder("EA-PDF email archive");
  if (!Common.nz(r.archiveGlobalId).isBlank()) d.append("; archiveGlobalId=").append(Common.nz(r.archiveGlobalId));
  if (!Common.nz(r.messageId).isBlank()) d.append("; messageId=").append(Common.nz(r.messageId));
  if (!Common.nz(r.mailGuid).isBlank()) d.append("; mailGuid=").append(Common.nz(r.mailGuid));
  if (!r.archiveEmails.isEmpty()) d.append("; archiveEmails=").append(String.join(", ", r.archiveEmails));
  if (!Common.sourceDerivation(r).isBlank()) d.append("; ").append(Common.sourceDerivation(r));
  return d.toString();
}

static void appendReplyMetadata(StringBuilder metadata, EmailRecord record) {
  if (record.inReplyTo.isEmpty()) return;
  metadata.append("<pdfmailmeta:In-Reply-To><rdf:Bag>");
  for (String identifier : record.inReplyTo) metadata.append("<rdf:li>").append(Common.xml(identifier)).append("</rdf:li>");
  metadata.append("</rdf:Bag></pdfmailmeta:In-Reply-To>");
}

static String buildArchiveDescription(List<EmailRecord> records, String archiveProfile) {
  StringBuilder d = new StringBuilder("EA-PDF email archive");
  d.append("; messages=").append(records.size());
  d.append("; conformance=").append(archiveProfile);
  String archiveGlobalId = "";
  List<String> archiveEmails = new ArrayList<>();
  for (EmailRecord r : records) {
    if (archiveGlobalId.isBlank() && !Common.nz(r.archiveGlobalId).isBlank()) archiveGlobalId = Common.nz(r.archiveGlobalId);
    for (String email : r.archiveEmails) {
      if (!archiveEmails.contains(email)) archiveEmails.add(email);
    }
  }
  if (!archiveGlobalId.isBlank()) d.append("; archiveGlobalId=").append(archiveGlobalId);
  if (!archiveEmails.isEmpty()) d.append("; archiveEmails=").append(String.join(", ", archiveEmails));
  for (EmailRecord record : records) {
    if (!Common.sourceDerivation(record).isBlank()) d.append("; Mail_GUID=").append(record.mailGuid).append(": ").append(Common.sourceDerivation(record));
  }
  return d.toString();
}

static String buildArchiveTitle(List<EmailRecord> records) {
  if (records.size() == 1) {
    String subject = Common.nz(records.get(0).subject);
    return subject.isBlank() ? "Email Archive" : subject;
  }
  return "Email Archive (" + records.size() + " messages)";
}

static String buildMailGuid(EmailRecord r) throws Exception {
  Common.finalizeRecordMetadata(r);
  return Common.nz(r.mailGuid);
}

static void appendStringProp(StringBuilder x, String prop, String value) {
  if (Common.nz(value).isBlank()) return;
  x.append("<pdfmailmeta:").append(prop).append(">")
   .append(Common.xml(value))
   .append("</pdfmailmeta:").append(prop).append(">");
}

static void appendFoafSeq(StringBuilder x, String prop, List<MailAgent> agents) {
  if (agents == null || agents.isEmpty()) return;
  agents = agents.stream().filter(agent -> Common.smtpMailbox(agent.email)).toList();
  if (agents.isEmpty()) return;
  x.append("<pdfmailmeta:").append(prop).append("><rdf:Seq>");
  for (MailAgent a : agents) {
    String email = Common.nz(a.email);
    if (email.isBlank()) continue;
    x.append("<rdf:li><rdf:Description");
    x.append(" foaf:mbox=\"").append(Common.xmlAttr(Common.mailto(email))).append("\"");
    if (!Common.nz(a.name).isBlank()) x.append(" foaf:name=\"").append(Common.xmlAttr(a.name)).append("\"");
    x.append("/></rdf:li>");
  }
  x.append("</rdf:Seq></pdfmailmeta:").append(prop).append(">");
}

static boolean shouldEmitRawSent(EmailRecord r) {
  String raw = Common.nz(r.rawDateHeader).trim();
  if (raw.isBlank()) return false;
  return Common.sourceDateNeedsRaw(raw);
}

static int messageMetadataSize(EmailRecord r) {
  if (r == null) return 0;
  for (AssociatedFile af : r.associatedFiles) {
    if (!"Source".equals(Common.nz(af.afRelationship))) continue;
    if ("message/rfc822".equalsIgnoreCase(Common.nz(af.mime)) || Common.nz(af.filename).toLowerCase(Locale.ROOT).endsWith(".eml")) {
      if (af.hasData() && af.size() > 0) return af.size();
    }
  }
  if (r.sourceBytes != null && r.sourceBytes.length > 0) return r.sourceBytes.length;
  if (r.payloadStaged) {
    for (AssociatedFile file : r.associatedFiles) {
      if ("Source".equals(file.afRelationship)) return file.size();
    }
  }
  try {
    byte[] synthesized = Common.synthesizeEml(r);
    return synthesized == null ? 0 : synthesized.length;
  } catch (Exception ignored) {
    return 0;
  }
}

static void addOutlines(PDDocument doc, EmailRecord r) {
  if (doc.getNumberOfPages() == 0) return;
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  PDDocumentOutline outline = new PDDocumentOutline();
  cat.setDocumentOutline(outline);
  PDOutlineItem item = new PDOutlineItem();
  item.setTitle(Common.nz(r.subject).isBlank() ? "Email" : Common.nz(r.subject));
  setOutlineDestination(doc, item, 1);
  outline.addLast(item);
  addMessageSectionOutlines(doc, item, r, 1, doc.getNumberOfPages());
  outline.openNode();
}

static void addMessageSectionOutlines(PDDocument doc, PDOutlineItem message, EmailRecord record,
                                     int firstPage, int lastPage) {
  addMessageSectionOutlines(doc::getPage, message, record, firstPage, lastPage);
}

private static void addMessageSectionOutlines(java.util.function.IntFunction<PDPage> pages, PDOutlineItem message,
    EmailRecord record, int firstPage, int lastPage) {
  int bodyPages = PdfDocumentParts.bodyPageCount(record, lastPage - firstPage + 1);
  int attachmentsPage = firstPage + bodyPages;
  if (attachmentsPage > lastPage) return;
  PDOutlineItem body = new PDOutlineItem();
  body.setTitle("Body");
  setOutlineDestination(body, pages.apply(firstPage - 1));
  message.addLast(body);
  PDOutlineItem attachments = new PDOutlineItem();
  attachments.setTitle("Attachments");
  setOutlineDestination(attachments, pages.apply(attachmentsPage - 1));
  message.addLast(attachments);
  message.openNode();
}

static void addArchiveOutlines(PDDocument doc, List<MessageSegment> segments, int frontMatterPages) {
  if (doc.getNumberOfPages() == 0 || segments.isEmpty()) return;
  List<PDPage> pages = new ArrayList<>(doc.getNumberOfPages());
  for (PDPage page : doc.getPages()) pages.add(page);
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  PDDocumentOutline outline = new PDDocumentOutline();
  cat.setDocumentOutline(outline);
  if (frontMatterPages > 0) {
    PDOutlineItem frontMatter = new PDOutlineItem();
    frontMatter.setTitle("Archive front matter");
    setOutlineDestination(frontMatter, pages.get(0));
    outline.addLast(frontMatter);
  }
  for (MessageSegment segment : segments) {
    PDOutlineItem item = new PDOutlineItem();
    item.setTitle(Common.nz(segment.record.subject).isBlank() ? "Email" : Common.nz(segment.record.subject));
    setOutlineDestination(item, pages.get(segment.startPage - 1));
    outline.addLast(item);
    addMessageSectionOutlines(pages::get, item, segment.record, segment.startPage, segment.endPage);
    ConversionProgress.advance("bookmarks");
  }
  outline.openNode();
}

static void addCollectionNavigationOutlines(PDDocument doc, List<FolderSection> sections) {
  if (doc.getNumberOfPages() == 0) return;
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  PDDocumentOutline outline = new PDDocumentOutline();
  cat.setDocumentOutline(outline);

  PDOutlineItem home = new PDOutlineItem();
  home.setTitle("Folders");
  setOutlineDestination(doc, home, 1);
  outline.addLast(home);

  for (FolderSection section : sections) {
    PDOutlineItem folder = new PDOutlineItem();
    folder.setTitle(section.folderName);
    setOutlineDestination(doc, folder, section.summaryStartPage);
    outline.addLast(folder);
    for (MessageSegment segment : section.messageSegments) {
      PDOutlineItem message = new PDOutlineItem();
      message.setTitle(Common.nz(segment.record.subject).isBlank() ? "Email" : Common.nz(segment.record.subject));
      setOutlineDestination(doc, message, segment.startPage);
      folder.addLast(message);
    }
    if (!section.messageSegments.isEmpty()) folder.openNode();
  }
  outline.openNode();
}

static void setOutlineDestination(PDDocument doc, PDOutlineItem item, int pageNumber) {
  setOutlineDestination(item, doc.getPage(Math.max(0, pageNumber - 1)));
}

private static void setOutlineDestination(PDOutlineItem item, PDPage page) {
  COSArray dest = new COSArray();
  dest.add(page.getCOSObject());
  dest.add(COSName.getPDFName("FitH"));
  dest.add(COSNull.NULL);
  item.getCOSObject().setItem(COSName.DEST, dest);
  item.getCOSObject().removeItem(COSName.A);
}

static void addDPartStructure(PDDocument doc, EmailRecord r) throws Exception {
  if (doc.getNumberOfPages() == 0) return;
  new PdfDocumentParts(doc).addMessage(r, 0, doc.getNumberOfPages() - 1);
}

static void addArchiveDPartStructure(PDDocument doc, List<MessageSegment> segments, int frontMatterPages) throws Exception {
  if (doc.getNumberOfPages() == 0 || segments.isEmpty()) return;
  PdfDocumentParts parts = new PdfDocumentParts(doc);
  if (frontMatterPages > 0) parts.addFrontMatter(0, frontMatterPages - 1, "Archive front matter");
  for (MessageSegment segment : segments) {
    parts.addMessage(segment.record, segment.startPage - 1, segment.endPage - 1);
    ConversionProgress.advance("document_parts");
  }
}

static COSDictionary structElem(String type, COSBase parent, PDPage page) {
  COSDictionary elem = new COSDictionary();
  elem.setName(COSName.TYPE, "StructElem");
  elem.setName(COSName.S, type);
  if (parent != null) elem.setItem(COSName.P, parent);
  if (page != null) elem.setItem(COSName.PG, page.getCOSObject());
  return elem;
}




static void addLogicalStructureNamespaces(PDDocument doc, COSDictionary structTreeRoot) throws IOException {
  if (doc.getVersion() < 2.0f) return;
  COSDictionary catalog = doc.getDocumentCatalog().getCOSObject();
  COSDictionary markInfo = (COSDictionary) catalog.getDictionaryObject(COSName.MARK_INFO);
  if (markInfo == null) {
    markInfo = new COSDictionary();
    catalog.setItem(COSName.MARK_INFO, markInfo);
  }
  markInfo.setBoolean(COSName.getPDFName("UserProperties"), true);
  COSDictionary standard = new COSDictionary();
  standard.setName(COSName.TYPE, "Namespace");
  standard.setString(COSName.getPDFName("NS"), "http://iso.org/pdf2/ssn");
  COSObject standardReference = PdfDocumentParts.indirect(doc, standard);
  COSDictionary legacy = new COSDictionary();
  legacy.setName(COSName.TYPE, "Namespace");
  legacy.setString(COSName.getPDFName("NS"), "http://iso.org/pdf/ssn");
  COSObject legacyReference = PdfDocumentParts.indirect(doc, legacy);
  COSDictionary mail = new COSDictionary();
  mail.setName(COSName.TYPE, "Namespace");
  mail.setString(COSName.getPDFName("NS"), "https://pdfa.org/ns/ea-pdf/mail-1");
  COSObject mailReference = PdfDocumentParts.indirect(doc, mail);
  COSDictionary namespaceMap = new COSDictionary();
  COSDictionary roles = (COSDictionary) structTreeRoot.getDictionaryObject(COSName.ROLE_MAP);
  for (COSName role : roles.keySet()) {
    COSArray mapping = new COSArray();
    mapping.add(roles.getDictionaryObject(role));
    String target = roles.getNameAsString(role);
    mapping.add(PDF17_STRUCTURE_ROLES.contains(target) ? legacyReference : standardReference);
    namespaceMap.setItem(role, mapping);
  }
  mail.setItem(COSName.getPDFName("RoleMapNS"), namespaceMap);
  COSArray namespaces = new COSArray();
  namespaces.add(standardReference);
  namespaces.add(legacyReference);
  namespaces.add(mailReference);
  structTreeRoot.setItem(COSName.getPDFName("Namespaces"), namespaces);
  bindStructureNamespaces(structTreeRoot.getDictionaryObject(COSName.K), standardReference, legacyReference, mailReference);
}

static void bindStructureNamespaces(COSBase value, COSObject standard, COSObject legacy, COSObject mail) {
  if (value instanceof COSObject reference) value = reference.getObject();
  if (value instanceof COSArray array) {
    for (COSBase child : array) bindStructureNamespaces(child, standard, legacy, mail);
  } else if (value instanceof COSDictionary element && element.containsKey(COSName.S)) {
    String tag = element.getNameAsString(COSName.S);
    element.setItem(COSName.getPDFName("NS"), tag.startsWith("Mail_") ? mail : PDF17_STRUCTURE_ROLES.contains(tag) ? legacy : standard);
    bindStructureNamespaces(element.getDictionaryObject(COSName.K), standard, legacy, mail);
  }
}

static void bindContentSetPages(PDDocument doc, COSDictionary tree, COSDictionary element, int firstPage, int lastPage) throws IOException {
  COSObject elementReference = PdfDocumentParts.indirect(doc, element);
  COSDictionary paragraph = structElem("P", elementReference, doc.getPage(firstPage));
  COSObject paragraphReference = PdfDocumentParts.indirect(doc, paragraph);
  COSArray content = new COSArray();
  COSDictionary parentTree = (COSDictionary) tree.getDictionaryObject(COSName.getPDFName("ParentTree"));
  if (parentTree == null) {
    parentTree = new COSDictionary();
    parentTree.setItem(COSName.NUMS, new COSArray());
    tree.setItem(COSName.getPDFName("ParentTree"), PdfDocumentParts.indirect(doc, parentTree));
  }
  COSArray numbers = (COSArray) parentTree.getDictionaryObject(COSName.NUMS);
  for (int index = firstPage; index <= lastPage; index++) {
    PDPage page = doc.getPage(index);
    COSDictionary reference = new COSDictionary();
    reference.setName(COSName.TYPE, "MCR");
    reference.setItem(COSName.PG, PdfDocumentParts.indirect(doc, page.getCOSObject()));
    reference.setInt(COSName.MCID, 0);
    content.add(reference);
    COSArray parents = new COSArray();
    parents.add(paragraphReference);
    numbers.add(COSInteger.get(index));
    numbers.add(parents);
    page.getCOSObject().setInt(COSName.getPDFName("StructParents"), index);
    ByteArrayOutputStream wrapped = new ByteArrayOutputStream();
    wrapped.write("/P << /MCID 0 >> BDC\n".getBytes(StandardCharsets.US_ASCII));
    try (InputStream original = page.getContents()) {
      original.transferTo(wrapped);
    }
    wrapped.write("\nEMC\n".getBytes(StandardCharsets.US_ASCII));
    page.setContents(new PDStream(doc, new ByteArrayInputStream(wrapped.toByteArray()), COSName.FLATE_DECODE));
  }
  paragraph.setItem(COSName.K, content);
  element.setItem(COSName.K, paragraphReference);
  tree.setInt(COSName.getPDFName("ParentTreeNextKey"), doc.getNumberOfPages());
}

static COSDictionary ensureUserPropertiesAttribute(COSDictionary elem) {
  COSBase attrsBase = elem.getDictionaryObject(COSName.A);
  COSDictionary effective = null;
  int directIndex = -1;
  if (attrsBase instanceof COSArray attributes) {
    for (int index = 0; index < attributes.size(); index++) {
      if (attributes.getObject(index) instanceof COSDictionary attr
          && "UserProperties".equals(attr.getNameAsString(COSName.O))) {
        effective = attr;
        directIndex = index;
      }
    }
  } else if (attrsBase instanceof COSDictionary attr && "UserProperties".equals(attr.getNameAsString(COSName.O))) {
    effective = attr;
  }
  if (effective == null) effective = classUserProperties(elem);

  // Attribute objects, their P arrays and individual properties may be shared.
  // Copy before updating this element; P is one whole attribute, not an N merge.
  COSDictionary attr = effective == null ? new COSDictionary() : new COSDictionary(effective);
  COSArray values = new COSArray();
  if (effective != null) {
    if (effective.getDictionaryObject(COSName.getPDFName("NS")) != null)
      throw new IllegalArgumentException("UserProperties cannot have an NS entry");
    if (!(effective.getDictionaryObject(COSName.P) instanceof COSArray existing))
      throw new IllegalArgumentException("UserProperties P must be an array");
    for (int index = 0; index < existing.size(); index++) {
      if (!(existing.getObject(index) instanceof COSDictionary property))
        throw new IllegalArgumentException("UserProperties entries must be dictionaries");
      values.add(new COSDictionary(property));
    }
  }
  attr.setName(COSName.O, "UserProperties");
  attr.setItem(COSName.P, values);
  COSBase revisionBase = elem.getDictionaryObject(COSName.R);
  if (revisionBase != null && !(revisionBase instanceof COSInteger))
    throw new IllegalArgumentException("Structure revision must be an integer");
  int revision = elem.getInt(COSName.R, 0);
  if (revision < 0) throw new IllegalArgumentException("Structure revision must be nonnegative");
  if (attrsBase instanceof COSArray attributes) {
    COSArray local = new COSArray();
    for (int index = 0; index < attributes.size(); index++) local.add(attributes.get(index));
    if (directIndex >= 0) {
      local.set(directIndex, attr);
      if (directIndex + 1 < local.size() && local.getObject(directIndex + 1) instanceof COSInteger) {
        local.set(directIndex + 1, COSInteger.get(revision));
      } else if (revision != 0) local.add(directIndex + 1, COSInteger.get(revision));
    } else {
      local.add(attr);
      if (revision != 0) local.add(COSInteger.get(revision));
    }
    elem.setItem(COSName.A, local);
  } else if (attrsBase instanceof COSDictionary direct && "UserProperties".equals(direct.getNameAsString(COSName.O))) {
    if (revision == 0) elem.setItem(COSName.A, attr);
    else {
      COSArray local = new COSArray();
      local.add(attr);
      local.add(COSInteger.get(revision));
      elem.setItem(COSName.A, local);
    }
  } else if (attrsBase != null) {
    COSArray attributes = new COSArray();
    attributes.add(attrsBase);
    attributes.add(attr);
    if (revision != 0) attributes.add(COSInteger.get(revision));
    elem.setItem(COSName.A, attributes);
  } else {
    if (revision == 0) elem.setItem(COSName.A, attr);
    else {
      COSArray local = new COSArray();
      local.add(attr);
      local.add(COSInteger.get(revision));
      elem.setItem(COSName.A, local);
    }
  }
  return attr;
}

static COSDictionary classUserProperties(COSDictionary elem) {
  COSBase classes = elem.getDictionaryObject(COSName.C);
  if (classes == null) return null;
  COSDictionary current = elem;
  COSDictionary map = null;
  Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
  for (int depth = 0; current != null && depth < 256 && seen.add(current); depth++) {
    if ("StructTreeRoot".equals(current.getNameAsString(COSName.TYPE))) {
      COSBase value = current.getDictionaryObject(COSName.getPDFName("ClassMap"));
      if (value != null && !(value instanceof COSDictionary))
        throw new IllegalArgumentException("ClassMap must be a dictionary");
      map = (COSDictionary) value;
      break;
    }
    COSBase parent = current.getDictionaryObject(COSName.P);
    current = parent instanceof COSDictionary dictionary ? dictionary : null;
  }
  COSArray names = classes instanceof COSArray array ? array : new COSArray();
  if (!(classes instanceof COSArray)) names.add(classes);
  COSDictionary effective = null;
  for (int index = 0; index < names.size(); index++) {
    if (!(names.getObject(index) instanceof COSName name))
      throw new IllegalArgumentException("Attribute classes must be names");
    COSBase value = map == null ? null : map.getDictionaryObject(name);
    COSArray objects = value instanceof COSArray array ? array : new COSArray();
    if (value != null && !(value instanceof COSArray)) objects.add(value);
    for (int objectIndex = 0; objectIndex < objects.size(); objectIndex++) {
      if (!(objects.getObject(objectIndex) instanceof COSDictionary attribute))
        throw new IllegalArgumentException("Class attributes must be dictionaries");
      if ("UserProperties".equals(attribute.getNameAsString(COSName.O))) effective = attribute;
    }
    if (index + 1 < names.size() && names.getObject(index + 1) instanceof COSInteger) index++;
  }
  return effective;
}

static void addStringUserProperty(COSDictionary elem, String name, String value) {
  if (elem == null || Common.nz(name).isBlank() || Common.nz(value).isBlank()) return;
  COSDictionary attr = ensureUserPropertiesAttribute(elem);
  COSArray props = (COSArray) attr.getDictionaryObject(COSName.P);
  for (int index = 0; index < props.size(); index++) {
    if (!(props.getObject(index) instanceof COSDictionary existing)) continue;
    if (name.equals(existing.getString(COSName.N))) {
      existing.setString(COSName.V, value);
      return;
    }
  }
  COSDictionary prop = new COSDictionary();
  prop.setString(COSName.N, name);
  prop.setString(COSName.V, value);
  props.add(prop);
}

static void addMessageUserProperties(COSDictionary elem, EmailRecord r) {
  if (elem == null || r == null) return;
  addStringUserProperty(elem, "Mail_GUID", Common.nz(r.mailGuid));
  if (!Common.nz(r.messageId).isBlank()) addStringUserProperty(elem, "Mail_MessageID", Common.nz(r.messageId));
}

static void appendIsoTs32005Declaration(StringBuilder x, String claimBy, String claimDate) {
  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfd:declarations><rdf:Bag><rdf:li rdf:parseType=\"Resource\">");
  x.append("<pdfd:conformsTo>https://pdfa.org/declarations#iso32005</pdfd:conformsTo>");
  x.append("<pdfd:claimData><rdf:Bag><rdf:li rdf:parseType=\"Resource\">");
  x.append("<pdfd:claimBy>").append(Common.xml(claimBy)).append("</pdfd:claimBy>");
  x.append("<pdfd:claimDate>").append(Common.xml(claimDate)).append("</pdfd:claimDate>");
  x.append("</rdf:li></rdf:Bag></pdfd:claimData>");
  x.append("</rdf:li></rdf:Bag></pdfd:declarations>");
  x.append("</rdf:Description>");
}

static void appendConversionHistory(StringBuilder x, String tool, String timestamp, String profile, int messages) {
  String documentId = "uuid:" + UUID.randomUUID();
  String instanceId = "uuid:" + UUID.randomUUID();
  x.append("<rdf:Description rdf:about=\"\" xmlns:xmpMM=\"http://ns.adobe.com/xap/1.0/mm/\"");
  x.append(" xmlns:stEvt=\"http://ns.adobe.com/xap/1.0/sType/ResourceEvent#\">");
  x.append("<xmpMM:DocumentID>").append(documentId).append("</xmpMM:DocumentID>");
  x.append("<xmpMM:InstanceID>").append(instanceId).append("</xmpMM:InstanceID>");
  x.append("<xmpMM:History><rdf:Seq><rdf:li rdf:parseType=\"Resource\">");
  x.append("<stEvt:action>converted</stEvt:action>");
  x.append("<stEvt:softwareAgent>").append(Common.xml(tool)).append("</stEvt:softwareAgent>");
  x.append("<stEvt:when>").append(Common.xml(timestamp)).append("</stEvt:when>");
  x.append("<stEvt:parameters>Converted ").append(messages).append(messages == 1 ? " email" : " emails");
  x.append(" to PDF/mail-1").append(Common.xml(profile)).append(".</stEvt:parameters>");
  x.append("</rdf:li></rdf:Seq></xmpMM:History></rdf:Description>");
}

static void addLogicalStructure(PDDocument doc, EmailRecord r) throws IOException {
  if (doc.getNumberOfPages() == 0) return;
  SemanticStructure.headerFields(doc, r.headerTagMarker);
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  COSDictionary renderedTree = (COSDictionary)cat.getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT);
  COSDictionary tree = new COSDictionary();
  tree.setName(COSName.TYPE, "StructTreeRoot");
  COSDictionary roles = new COSDictionary();
  roles.setName(COSName.getPDFName("Mail_Message"), "Document");
  roles.setName(COSName.getPDFName("Mail_ContentSet"), "Art");
  tree.setItem(COSName.ROLE_MAP, roles);
  COSDictionary root = structElem("Document", tree, doc.getPage(0));
  COSDictionary message = structElem("Mail_Message", root, doc.getPage(0));
  addMessageUserProperties(message, r);
  COSDictionary contentSet = structElem("Mail_ContentSet", message, doc.getPage(0));
  addStringUserProperty(contentSet, "Mail_ContentSetType", "BodyRendering");
  addStringUserProperty(contentSet, "Mail_Subtype", Common.contentSetSubtype(r));
  int bodyPages = PdfDocumentParts.bodyPageCount(r, doc.getNumberOfPages());
  SemanticStructure.attach(doc, tree, contentSet, renderedTree, 0, bodyPages - 1);
  COSArray sets = new COSArray();
  sets.add(PdfDocumentParts.indirect(doc, contentSet));
  if (bodyPages < doc.getNumberOfPages()) {
    sets.add(PdfDocumentParts.indirect(doc, attachmentListStructure(doc, tree, message,
        bodyPages, doc.getNumberOfPages() - 1)));
  }
  message.setItem(COSName.K, sets.size() == 1 ? sets.get(0) : sets);
  root.setItem(COSName.K, PdfDocumentParts.indirect(doc, message));
  tree.setItem(COSName.K, PdfDocumentParts.indirect(doc, root));
  addLogicalStructureNamespaces(doc, tree);
  if (pdfUaFeaturesEnabled()) SemanticStructure.addDestinations(doc, tree);
  cat.getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(doc, tree));
}

static void addArchiveLogicalStructure(PDDocument doc, List<MessageSegment> segments, int frontMatterPages) throws IOException {
  if (doc.getNumberOfPages() == 0 || segments.isEmpty()) return;
  // Page order is final here. Share one index across every message and content
  // set, instead of rescanning the page tree for each structure association.
  List<PDPage> indexedPages = new ArrayList<>(doc.getNumberOfPages());
  for (PDPage page : doc.getPages()) indexedPages.add(page);
  java.util.function.IntFunction<PDPage> pageAt = indexedPages::get;
  COSDictionary tree = new COSDictionary();
  tree.setName(COSName.TYPE, "StructTreeRoot");
  COSDictionary roles = new COSDictionary();
  roles.setName(COSName.getPDFName("Mail_Message"), "Document");
  roles.setName(COSName.getPDFName("Mail_ContentGroup"), "Part");
  roles.setName(COSName.getPDFName("Mail_ContentSet"), "Art");
  tree.setItem(COSName.ROLE_MAP, roles);
  COSDictionary root = structElem("Document", tree, pageAt.apply(0));
  COSArray children = new COSArray();
  if (frontMatterPages > 0) {
    COSDictionary front = structElem("Mail_ContentSet", root, pageAt.apply(0));
    addStringUserProperty(front, "Mail_ContentSetType", "FrontMatter");
    SemanticStructure.attach(doc, tree, front, null, 0, frontMatterPages - 1, pageAt);
    children.add(PdfDocumentParts.indirect(doc, front));
  }
  for (MessageSegment segment : segments) {
    PDPage firstPage = pageAt.apply(segment.startPage - 1);
    COSDictionary message = structElem("Mail_Message", root, firstPage);
    addMessageUserProperties(message, segment.record);
    COSArray content = new COSArray();
    COSDictionary group = structElem("Mail_ContentGroup", message, firstPage);
    addStringUserProperty(group, "Mail_ContentSetType", "BodyRendering");
    COSDictionary contentSet = structElem("Mail_ContentSet", group, firstPage);
    addStringUserProperty(contentSet, "Mail_ContentSetType", "BodyRendering");
    addStringUserProperty(contentSet, "Mail_Subtype", Common.contentSetSubtype(segment.record));
    int bodyEnd = segment.startPage - 1 + PdfDocumentParts.bodyPageCount(segment.record,
        segment.endPage - segment.startPage + 1);
    SemanticStructure.attach(doc, tree, contentSet, segment.semanticTree, segment.startPage - 1, bodyEnd - 1, pageAt);
    group.setItem(COSName.K, PdfDocumentParts.indirect(doc, contentSet));
    content.add(PdfDocumentParts.indirect(doc, group));
    if (bodyEnd < segment.endPage) {
      content.add(PdfDocumentParts.indirect(doc, attachmentListStructure(doc, tree, message,
          bodyEnd, segment.endPage - 1, pageAt)));
    }
    message.setItem(COSName.K, content.size() == 1 ? content.get(0) : content);
    children.add(PdfDocumentParts.indirect(doc, message));
    ConversionProgress.advance("tagging");
  }
  root.setItem(COSName.K, children);
  tree.setItem(COSName.K, PdfDocumentParts.indirect(doc, root));
  addLogicalStructureNamespaces(doc, tree);
  if (pdfUaFeaturesEnabled()) SemanticStructure.addDestinations(doc, tree);
  doc.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(doc, tree));
}

static COSDictionary attachmentListStructure(PDDocument doc, COSDictionary tree, COSDictionary message,
                                             int firstPage, int lastPage) throws IOException {
  return attachmentListStructure(doc, tree, message, firstPage, lastPage, doc::getPage);
}

private static COSDictionary attachmentListStructure(PDDocument doc, COSDictionary tree, COSDictionary message,
    int firstPage, int lastPage, java.util.function.IntFunction<PDPage> pageAt) throws IOException {
  COSDictionary list = structElem("Mail_ContentSet", message, pageAt.apply(firstPage));
  addStringUserProperty(list, "Mail_ContentSetType", "AttachmentList");
  SemanticStructure.attach(doc, tree, list, null, firstPage, lastPage, pageAt);
  return list;
}

static Map<String, PDComplexFileSpecification> attachmentAnnotationBindings(PDDocument doc) throws IOException {
  Map<String, PDComplexFileSpecification> bindings = new LinkedHashMap<>();
  for (PDPage page : doc.getPages()) for (PDAnnotation annotation : page.getAnnotations()) {
    if (annotation instanceof PDAnnotationFileAttachment attachment
        && attachment.getFile() instanceof PDComplexFileSpecification file) {
      String token = Common.nz(annotation.getAnnotationName());
      if (!token.isBlank()) bindings.put(token, file);
    }
  }
  return bindings;
}

// PDF 2.0 DPart /AF records ownership independently of filename and embedding location.
static Map<String, COSDictionary> messageFileOwners(PDDocument doc) {
  Map<String, COSDictionary> owners = new LinkedHashMap<>();
  for (PDPage page : doc.getPages()) {
    COSBase value = page.getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
    Set<COSDictionary> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    while (value instanceof COSDictionary part && visited.add(part)) {
      COSBase metadata = part.getDictionaryObject(COSName.getPDFName("DPM"));
      if (metadata instanceof COSDictionary dpm) {
        String guid = dpm.getString(COSName.getPDFName("Mail_GUID"));
        if (guid != null && !guid.isBlank()) { owners.putIfAbsent(guid, part); break; }
      }
      value = part.getDictionaryObject(COSName.getPDFName("Parent"));
    }
  }
  return owners;
}

static void associateMessageFile(Map<String, COSDictionary> owners, EmailRecord record, PDComplexFileSpecification file) {
  COSDictionary part = owners.get(record.mailGuid);
  if (part == null) throw new IllegalStateException("Missing message document part for embedded file ownership.");
  COSName key = COSName.getPDFName("AF");
  COSArray files = (COSArray) part.getDictionaryObject(key);
  if (files == null) { files = new COSArray(); part.setItem(key, files); }
  for (int i = 0; i < files.size(); i++) if (files.getObject(i) == file.getCOSObject()) return;
  files.add(file.getCOSObject());
}

static Map<String, PDComplexFileSpecification> embedAssociatedFiles(PDDocument doc, EmailRecord r, boolean debug) throws Exception {
  Map<String, COSDictionary> messageOwners = messageFileOwners(doc);
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  COSDictionary catd = cat.getCOSObject();

  COSArray afArray = (COSArray)catd.getDictionaryObject(COSName.getPDFName("AF"));
  if (afArray == null) {
    afArray = new COSArray();
    catd.setItem(COSName.getPDFName("AF"), afArray);
  }

  PDDocumentNameDictionary names = cat.getNames();
  if (names == null) {
    names = new PDDocumentNameDictionary(cat);
    cat.setNames(names);
  }

  PDEmbeddedFilesNameTreeNode efs = names.getEmbeddedFiles();
  Map<String, PDComplexFileSpecification> nameMap = efs != null && efs.getNames() != null ? new LinkedHashMap<>(efs.getNames()) : new LinkedHashMap<>();
  if (efs == null) efs = new PDEmbeddedFilesNameTreeNode();
  Map<String, PDComplexFileSpecification> filesByToken = new LinkedHashMap<>();

  List<AssociatedFile> files = pdfEmbeddedFiles(r);
  if (r.embedSourceMessage && files.stream().noneMatch(file -> "Source".equals(file.afRelationship))) {
    AssociatedFile af = new AssociatedFile();
    af.filename = Common.nz(r.sourceFilename).isBlank() ? "message.eml" : r.sourceFilename;
    af.mime = Common.nz(r.sourceMime).isBlank() ? "message/rfc822" : r.sourceMime;
    af.afRelationship = Common.nz(r.afRelationship).isBlank() ? "Source" : r.afRelationship;
    af.bytes = r.sourceBytes == null ? Common.synthesizeEml(r) : r.sourceBytes;
    af.description = Common.sourceDescription(r);
    af.ensureChecksums();
    files.add(af);
    r.associatedFiles.add(af);
  }

  int n = 0;
  for (AssociatedFile af : files) {
    if (af == null || !af.hasData() || af.size() == 0) continue;

    String fname = Common.nz(af.filename).isBlank() ? ("attachment_" + String.format("%03d", ++n) + Common.extensionForMime(af.mime, af.filename)) : Common.nz(af.filename);
    String mime = embeddedAssetContentType(af, fname);
    String rel = normalizeAfRelationship(af.afRelationship);
    af.ensureChecksums();
    String md5 = Common.nz(af.checksumMd5);
    String token = attachmentLaunchToken(af);

    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(Common.portablePdfFilename(fname));
    fs.setFileUnicode(Common.portablePdfFilename(fname));

    PDEmbeddedFile ef;
    try (InputStream data = af.openData()) {
      ef = new PDEmbeddedFile(doc, data, COSName.FLATE_DECODE);
    }
    setEmbeddedMediaType(ef, mime);
    ef.setSize(af.size());
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    setEmbeddedChecksum(ef, md5);

    COSDictionary efd = ef.getCOSObject();


    COSDictionary params = (COSDictionary)efd.getDictionaryObject(COSName.PARAMS);
    if (params == null) {
      params = new COSDictionary();
      efd.setItem(COSName.PARAMS, params);
    }
    params.setInt(COSName.SIZE, af.size());

    fs.setEmbeddedFile(ef);
    fs.setEmbeddedFileUnicode(ef);

    COSDictionary fsd = fs.getCOSObject();
    fsd.setName(COSName.getPDFName("AFRelationship"), rel);
    if (!Common.nz(af.description).isBlank()) fsd.setString(COSName.DESC, af.description);

    associateMessageFile(messageOwners, r, fs);
    String key = uniqueAttachmentKey(nameMap, fname);
    nameMap.put(key, fs);
    afArray.add(fs.getCOSObject());
    if (!token.isBlank()) filesByToken.put(token, fs);

    if (debug) Common.dbg(r, "embedded file filename=" + fname + " mime=" + mime + " afRelationship=" + rel + " bytes=" + af.size());
  }

  if (afArray.size() == 0) catd.removeItem(COSName.getPDFName("AF"));
  efs.setNames(nameMap);
  names.setEmbeddedFiles(efs);
  cat.setNames(names);
  filesByToken.putAll(attachmentAnnotationBindings(doc));
  return filesByToken;
}

static EmbeddedFileBindings embedAssociatedFiles(PDDocument doc, List<EmailRecord> records, boolean debug) throws Exception {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  COSDictionary catd = cat.getCOSObject();
  EmbeddedFileBindings bindings = new EmbeddedFileBindings();
  Map<String, COSDictionary> messageOwners = messageFileOwners(doc);

  COSArray afArray = (COSArray)catd.getDictionaryObject(COSName.getPDFName("AF"));
  if (afArray == null) {
    afArray = new COSArray();
    catd.setItem(COSName.getPDFName("AF"), afArray);
  }

  PDDocumentNameDictionary names = cat.getNames();
  if (names == null) {
    names = new PDDocumentNameDictionary(cat);
    cat.setNames(names);
  }

  PDEmbeddedFilesNameTreeNode efs = names.getEmbeddedFiles();
  Map<String, PDComplexFileSpecification> nameMap = efs != null && efs.getNames() != null ? new LinkedHashMap<>(efs.getNames()) : new LinkedHashMap<>();
  if (efs == null) efs = new PDEmbeddedFilesNameTreeNode();
  Set<String> normalizedNames = new HashSet<>();
  for (String name : nameMap.keySet()) normalizedNames.add(Common.filenameCaseKey(name));

  List<ArchiveEmbeddedFilePlan> plans = buildArchiveEmbeddedFilePlans(records);
  ConversionProgress.begin("embedding", plans.size(), "files");
  for (ArchiveEmbeddedFilePlan plan : plans) {
    AssociatedFile af = plan.file;
    if (af == null || !af.hasData() || af.size() == 0) continue;

    String fname = plan.filename;
    String mime = plan.mime;
    String rel = plan.relationship;
    String md5 = plan.checksumMd5;

    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(Common.portablePdfFilename(fname));
    fs.setFileUnicode(Common.portablePdfFilename(fname));

    PDEmbeddedFile ef;
    try (InputStream data = af.openData()) {
      ef = new PDEmbeddedFile(doc, data, COSName.FLATE_DECODE);
    }
    setEmbeddedMediaType(ef, mime);
    ef.setSize(af.size());
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    setEmbeddedChecksum(ef, md5);

    COSDictionary efd = ef.getCOSObject();


    COSDictionary params = (COSDictionary)efd.getDictionaryObject(COSName.PARAMS);
    if (params == null) {
      params = new COSDictionary();
      efd.setItem(COSName.PARAMS, params);
    }
    params.setInt(COSName.SIZE, af.size());

    fs.setEmbeddedFile(ef);
    fs.setEmbeddedFileUnicode(ef);

    COSDictionary fsd = fs.getCOSObject();
    fsd.setName(COSName.getPDFName("AFRelationship"), rel);
    if (!Common.nz(af.description).isBlank()) fsd.setString(COSName.DESC, af.description);

    for (EmailRecord owner : plan.owners) associateMessageFile(messageOwners, owner, fs);
    nameMap.put(reserveAttachmentKey(normalizedNames, fname), fs);
    afArray.add(fs.getCOSObject());
    if ("Source".equals(rel) && plan.record != null && !bindings.sourceFiles.containsKey(plan.record)) {
      bindings.sourceFiles.put(plan.record, fs);
    }
    String token = attachmentLaunchToken(af);
    if (!token.isBlank()) bindings.filesByToken.put(token, fs);
    ConversionProgress.advance("embedding");
  }

  if (afArray.size() == 0) catd.removeItem(COSName.getPDFName("AF"));
  efs.setNames(nameMap);
  names.setEmbeddedFiles(efs);
  cat.setNames(names);
  bindings.filesByToken.putAll(attachmentAnnotationBindings(doc));
  return bindings;
}

// Preserve declared parameter spelling and case; sniffing is only a fallback
// when the conversion source supplies no media type.
static String embeddedAssetContentType(AssociatedFile file, String filename) throws IOException {
  String declared = Common.sourceContentType(file);
  return declared.isBlank() ? Common.detectMimeType(filename, file.mime, file.head()) : declared;
}

static void setEmbeddedMediaType(PDEmbeddedFile file, String contentType) {
  file.setSubtype(Common.baseMimeType(contentType));
  COSName parameters = COSName.getPDFName("Mail_MediaTypeParameters");
  int separator = contentType.indexOf(';');
  if (separator >= 0) file.getCOSObject().setString(parameters, contentType.substring(separator + 1).trim());
  else file.getCOSObject().removeItem(parameters);
}

static int storedAttachmentSize(EmailRecord record, AssociatedFile file) {
  return record.embedSourceMessage ? file.size() : 0;
}

static void setEmbeddedChecksum(PDEmbeddedFile file, String hexadecimal) {
  if (Common.nz(hexadecimal).isBlank()) return;
  byte[] checksum = HexFormat.of().parseHex(hexadecimal);
  if (checksum.length != 16) throw new IllegalArgumentException("Embedded file MD5 must contain 16 bytes");
  COSDictionary parameters = (COSDictionary) file.getCOSObject().getDictionaryObject(COSName.PARAMS);
  if (parameters == null) {
    parameters = new COSDictionary();
    file.getCOSObject().setItem(COSName.PARAMS, parameters);
  }
  COSString value = new COSString(checksum);
  value.setForceHexForm(true);
  parameters.setItem(COSName.getPDFName("CheckSum"), value);
}

static String attachmentDescription(EmailRecord record, AssociatedFile file) {
  if (record.embedSourceMessage) return Common.nz(file.description);
  return Common.nz(file.description) + "; contents omitted by policy; original size=" + file.size()
      + "; original MD5=" + Common.nz(file.checksumMd5);
}

static void addOmittedAttachmentAnnotations(PDDocument doc, EmailRecord record, String fontPath) throws Exception {
  List<AssociatedFile> attachments = new ArrayList<>();
  for (AssociatedFile file : record.associatedFiles) {
    if (file != null && file.emailAttachment) attachments.add(file);
  }
  if (attachments.isEmpty()) return;
  PDType0Font font = loadUiFont(doc, fontPath);
  PDPage page = null;
  float positionY = 0;
  float margin = 46.8f;
  float bottomLimit = margin;
  for (AssociatedFile file : attachments) {
    String filename = Common.portablePdfFilename(file.filename);
    List<String> filenameLines = wrapAttachmentLabel(font, filename, PDRectangle.LETTER.getWidth() - 2 * margin - 20f);
    float rowHeight = filenameLines.size() * 13f + 9f;
    if (page == null || positionY - rowHeight < bottomLimit) {
      page = new PDPage(PDRectangle.LETTER);
      doc.addPage(page);
      positionY = page.getMediaBox().getHeight() - margin;
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        drawText(content, font, 18f, margin, positionY, "Attachments");
        positionY -= 28f;
        String subject = Common.nz(record.subject).isBlank() ? "(no subject)" : record.subject;
        String sent = !Common.nz(record.rawDateHeader).isBlank() ? record.rawDateHeader : Common.nz(record.date);
        for (String context : List.of("Email: " + subject, "Sent: " + sent)) {
          for (String line : wrapAttachmentLabel(font, context, PDRectangle.LETTER.getWidth() - 2 * margin)) {
            drawText(content, font, 9f, margin, positionY, line);
            positionY -= 13f;
          }
        }
        positionY -= 6f;
        if (!record.embedSourceMessage) {
          drawText(content, font, 9f, margin, positionY,
              "These placeholders identify attachments; their native bytes are not embedded separately.");
          positionY -= 16f;
        }
        drawText(content, font, 9f, margin, positionY, record.embedSourceMessage
            ? "The original source email is also retained in the document attachments panel."
            : "This isolated PDF does not preserve the original attachment contents.");
        positionY -= 28f;
      }
    }
    PDRectangle rectangle = new PDRectangle(margin, positionY - 3f, 13f, 10f);
    PDAnnotation annotation;
    {
      PDComplexFileSpecification specification = new PDComplexFileSpecification();
      specification.setFile(filename);
      specification.setFileUnicode(filename);
      specification.getCOSObject().setString(COSName.DESC, attachmentDescription(record, file));
      specification.getCOSObject().setName(COSName.getPDFName("AFRelationship"), "Data");
      PDEmbeddedFile placeholder;
      try (InputStream input = record.embedSourceMessage ? file.openData() : new ByteArrayInputStream(new byte[0])) {
        placeholder = new PDEmbeddedFile(doc, input, COSName.FLATE_DECODE);
      }
      String contentType = Common.sourceContentType(file);
      setEmbeddedMediaType(placeholder, contentType);
      placeholder.setSize(storedAttachmentSize(record, file));
      placeholder.setModDate(Calendar.getInstance(TimeZone.getTimeZone("UTC")));
      setEmbeddedChecksum(placeholder, record.embedSourceMessage ? file.checksumMd5 : Common.md5Hex(new byte[0]));
      specification.setEmbeddedFile(placeholder);
      specification.setEmbeddedFileUnicode(placeholder);
      annotation = inlineAttachmentBadgeAnnotation(doc, rectangle, specification, filename);
      annotation.setContents((record.embedSourceMessage ? "Original email attachment: " : "Attachment contents omitted: ") + filename);
    }
    page.getAnnotations().add(annotation);
    annotation.setAnnotationName(nativeAttachmentToken(record, file));
    try (PDPageContentStream content = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
      for (String line : filenameLines) {
        drawText(content, font, 9f, margin + 20f, positionY, line);
        positionY -= 13f;
      }
    }
    positionY -= 9f;
  }
  // The per-message attachment continuation belongs to the same body rendering.
  // Its visible native annotations satisfy EA-PDF 9.3 without duplicate badges
  // on the original email pages. It is not a separate document-wide index.
  record.renderedBodyPages = doc.getNumberOfPages();
}

static List<String> wrapAttachmentLabel(PDType0Font font, String filename, float maxWidth) throws Exception {
  List<String> lines = new ArrayList<>();
  String value = sanitizePdfText(font, filename);
  StringBuilder line = new StringBuilder();
  for (int offset = 0; offset < value.length();) {
    int point = value.codePointAt(offset);
    String next = new String(Character.toChars(point));
    while (!line.isEmpty() && textWidth(font, 9f, line + next) > maxWidth) {
      int space = line.lastIndexOf(" ");
      if (space > 0) {
        lines.add(line.substring(0, space));
        line.delete(0, space + 1);
      } else {
        lines.add(line.toString());
        line.setLength(0);
      }
    }
    line.append(next);
    offset += Character.charCount(point);
  }
  if (!line.isEmpty() || lines.isEmpty()) lines.add(line.toString());
  return lines;
}

static PDAnnotationFileAttachment inlineAttachmentBadgeAnnotation(PDDocument doc, PDRectangle rect,
                                                                  PDComplexFileSpecification fileSpec,
                                                                  String contents) throws Exception {
  PDAnnotationFileAttachment annotation = new PDAnnotationFileAttachment();
  annotation.setFile(fileSpec);
  annotation.setAttachmentName(PDAnnotationFileAttachment.ATTACHMENT_NAME_TAG);
  annotation.setPrinted(true);
  annotation.setRectangle(rect);
  annotation.setAppearance(buildAttachmentBadgeAppearance(doc, rect.getWidth(), rect.getHeight()));
  annotation.setContents(contents);
  return annotation;
}

static PDAppearanceDictionary buildAttachmentBadgeAppearance(PDDocument doc, float width, float height) throws Exception {
  float w = Math.max(8f, Math.min(13f, width));
  float h = Math.max(6f, Math.min(10f, height));
  PDAppearanceStream appearance = new PDAppearanceStream(doc);
  appearance.setResources(new PDResources());
  appearance.setBBox(new PDRectangle(width, height));
  try (PDPageContentStream cs = new PDPageContentStream(doc, appearance)) {
    cs.transform(org.apache.pdfbox.util.Matrix.getTranslateInstance(0, Math.max(0f, height - h)));
    float left = 0.75f;
    float right = w - 0.75f;
    float top = h - 0.75f;
    float bottom = 0.75f;
    float notch = Math.min(Math.max(2.6f, h * 0.32f), Math.max(2.6f, w * 0.22f));
    float holeRadius = Math.max(0.8f, Math.min(1.4f, h * 0.16f));
    float holeCenterX = left + notch + holeRadius + 0.9f;
    float holeCenterY = bottom + (h / 2f);

    cs.setNonStrokingColor(new java.awt.Color(237, 244, 250));
    cs.moveTo(left + notch, top);
    cs.lineTo(right, top);
    cs.lineTo(right, bottom);
    cs.lineTo(left + notch, bottom);
    cs.lineTo(left, bottom + (h / 2f));
    cs.closePath();
    cs.fill();

    cs.setStrokingColor(new java.awt.Color(109, 130, 151));
    cs.setLineWidth(0.9f);
    cs.moveTo(left + notch, top);
    cs.lineTo(right, top);
    cs.lineTo(right, bottom);
    cs.lineTo(left + notch, bottom);
    cs.lineTo(left, bottom + (h / 2f));
    cs.closePath();
    cs.stroke();

    float c = 0.5522847498f * holeRadius;
    cs.setNonStrokingColor(new java.awt.Color(109, 130, 151));
    cs.moveTo(holeCenterX + holeRadius, holeCenterY);
    cs.curveTo(holeCenterX + holeRadius, holeCenterY + c, holeCenterX + c, holeCenterY + holeRadius, holeCenterX, holeCenterY + holeRadius);
    cs.curveTo(holeCenterX - c, holeCenterY + holeRadius, holeCenterX - holeRadius, holeCenterY + c, holeCenterX - holeRadius, holeCenterY);
    cs.curveTo(holeCenterX - holeRadius, holeCenterY - c, holeCenterX - c, holeCenterY - holeRadius, holeCenterX, holeCenterY - holeRadius);
    cs.curveTo(holeCenterX + c, holeCenterY - holeRadius, holeCenterX + holeRadius, holeCenterY - c, holeCenterX + holeRadius, holeCenterY);
    cs.fill();
  }
  PDAppearanceDictionary dict = new PDAppearanceDictionary();
  dict.setNormalAppearance(appearance);
  return dict;
}

static void normalizePageContentStreams(PDDocument doc) throws Exception {
  if (doc == null) return;
  for (PDPage page : doc.getPages()) {
    try (InputStream in = page.getContents()) {
      byte[] merged = in == null ? new byte[0] : in.readAllBytes();
      if (merged.length > 0) {
        PDStream stream = new PDStream(doc);
        try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
          out.write(merged);
        }
        page.setContents(stream);
      }
    }
    ConversionProgress.advance("page_streams");
  }
}

static List<ArchiveEmbeddedFilePlan> buildArchiveEmbeddedFilePlans(List<EmailRecord> records) throws Exception {
  List<ArchivePendingFile> pending = new ArrayList<>();
  int syntheticCounter = 0;

  for (EmailRecord r : records) {
    for (AssociatedFile af : pdfEmbeddedFiles(r)) {
      if (af == null || !af.hasData() || af.size() == 0) continue;
      String requestedName = Common.nz(af.filename).isBlank()
          ? ("attachment_" + String.format("%03d", ++syntheticCounter) + Common.extensionForMime(af.mime, af.filename))
          : Common.nz(af.filename);
      pending.add(new ArchivePendingFile(r, af, requestedName));
    }

    boolean hasSource = false;
    for (AssociatedFile af : r.associatedFiles) {
      if ("Source".equals(Common.nz(af.afRelationship))) {
        hasSource = true;
        break;
      }
    }
    if (!hasSource && r.embedSourceMessage) {
      AssociatedFile af = new AssociatedFile();
      af.filename = Common.nz(r.sourceFilename).isBlank() ? "message.eml" : r.sourceFilename;
      af.mime = Common.nz(r.sourceMime).isBlank() ? "message/rfc822" : r.sourceMime;
      af.afRelationship = Common.nz(r.afRelationship).isBlank() ? "Source" : r.afRelationship;
      af.bytes = r.sourceBytes == null ? Common.synthesizeEml(r) : r.sourceBytes;
      af.description = Common.sourceDescription(r);
      af.ensureChecksums();
      pending.add(new ArchivePendingFile(r, af, af.filename));
    }
  }

  Map<String, Integer> requestedTotals = new LinkedHashMap<>();
  for (ArchivePendingFile item : pending) {
    requestedTotals.put(item.requestedName, requestedTotals.getOrDefault(item.requestedName, 0) + 1);
  }
  Map<String, Integer> requestedSeen = new LinkedHashMap<>();
  Map<String, Boolean> usedNames = new LinkedHashMap<>();
  Set<String> usedCaseNames = new HashSet<>();
  Map<String, ArchiveEmbeddedFilePlan> embeddedContentKeys = new LinkedHashMap<>();
  List<ArchiveEmbeddedFilePlan> out = new ArrayList<>();
  for (ArchivePendingFile item : pending) {
    int total = requestedTotals.getOrDefault(item.requestedName, 1);
    int seq = requestedSeen.getOrDefault(item.requestedName, 0) + 1;
    requestedSeen.put(item.requestedName, seq);
    String uniqueName = total > 1
        ? numberedAttachmentKey(usedNames, item.requestedName, seq, total)
        : reserveAttachmentKey(usedCaseNames, item.requestedName);
    usedNames.put(uniqueName, Boolean.TRUE);
    usedCaseNames.add(Common.filenameCaseKey(uniqueName));
    String mime = embeddedAssetContentType(item.file, uniqueName);
    String rel = normalizeAfRelationship(item.file.afRelationship);
    item.file.ensureChecksums();
    String checksumMd5 = item.file.checksumMd5;
    String checksumSha256 = item.file.checksumSha256;
    if (!"Source".equals(rel)) {
      String contentKey = rel + "|" + mime + "|" + checksumSha256;
      ArchiveEmbeddedFilePlan existing = embeddedContentKeys.get(contentKey);
      if (existing != null) { existing.owners.add(item.record); continue; }
    }
    ArchiveEmbeddedFilePlan plan = new ArchiveEmbeddedFilePlan(item.record, item.file, item.requestedName, uniqueName, mime, rel, checksumMd5);
    out.add(plan);
    if (!"Source".equals(rel)) embeddedContentKeys.put(rel + "|" + mime + "|" + checksumSha256, plan);
  }
  return out;
}

static List<CollectionFolder> buildCollectionFolders(List<FolderSection> sections) {
  return PdfCollectionSupport.buildCollectionFolders(sections);
}


static List<FolderSection> buildFolderSections(List<EmailRecord> records) {
  return PdfCollectionSupport.buildFolderSections(records);
}

static Map<String, PageLinkTarget> addFolderIndexPages(PDDocument doc, PDType0Font font, List<FolderSection> sections, String collectionProfile, CollectionRunSummary runSummary) throws Exception {
  Map<String, PageLinkTarget> links = new LinkedHashMap<>();
  if (sections.isEmpty()) return links;

  final int rowsPerPage = 32;
  int pageCount = Math.max(1, (sections.size() + rowsPerPage - 1) / rowsPerPage);
  int sectionIndex = 0;
  for (int pageNo = 0; pageNo < pageCount; pageNo++) {
    PDPage page = new PDPage(PDRectangle.LETTER);
    doc.addPage(page);
    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
      float pageWidth = page.getMediaBox().getWidth();
      float pageHeight = page.getMediaBox().getHeight();
      float margin = 46f;
      float top = pageHeight - margin;
      float rowHeight = 18f;
      float y = top;

      y = drawText(cs, font, 20f, margin, y, "Email Archive");
      y = drawText(cs, font, 10.5f, margin, y - 8f,
          "EA-PDF 1" + collectionProfile + " single-document navigation. Select a folder to jump to its in-document email list.");
      y = drawText(cs, font, 10f, margin, y - 4f, "Harvested email(s): " + runSummary.harvestedEmails);
      y = drawText(cs, font, 10f, margin, y - 2f,
          "Run stats: " + runSummary.countsText());
      y = drawText(cs, font, 10f, margin, y - 2f, "Generated: " + runSummary.generatedAt);
      y -= 12f;
      drawRule(cs, margin, y, pageWidth - margin, y);
      y -= 16f;

      float folderX = margin;
      float messagesX = margin + 250f;
      float sizeX = margin + 330f;
      drawText(cs, font, 10f, folderX, y, "Folder");
      drawText(cs, font, 10f, messagesX, y, "Messages");
      drawText(cs, font, 10f, sizeX, y, "Size");
      y -= 8f;
      drawRule(cs, margin, y, pageWidth - margin, y);
      y -= 14f;

      for (int row = 0; row < rowsPerPage && sectionIndex < sections.size(); row++, sectionIndex++) {
        FolderSection section = sections.get(sectionIndex);
        float baseline = y;
        drawText(cs, font, 10.5f, folderX, baseline, ellipsize(section.folderName, 34));
        drawText(cs, font, 10.5f, messagesX, baseline, Integer.toString(section.records.size()));
        drawText(cs, font, 10.5f, sizeX, baseline, humanReadableSize(section.totalSizeBytes));
        links.put(section.folderName, new PageLinkTarget(doc.getNumberOfPages(),
            new PDRectangle(folderX - 2f, baseline - 3f, 210f, 12f)));
        y -= rowHeight;
      }

      y = 52f;
      drawRule(cs, margin, y + 10f, pageWidth - margin, y + 10f);
      drawText(cs, font, 9.5f, margin, y, "Folders are sorted alphabetically. Each folder section lists messages in chronological order.");
    }
  }
  return links;
}

static Map<EmailRecord, PageLinkTarget> addFolderSummaryPages(PDDocument doc, PDType0Font font, FolderSection section, String collectionProfile) throws Exception {
  Map<EmailRecord, PageLinkTarget> links = new IdentityHashMap<>();
  if (section.records.isEmpty()) return links;

  final int rowsPerPage = 24;
  int recordIndex = 0;
  int pageNumber = 0;
  while (recordIndex < section.records.size()) {
    pageNumber++;
    PDPage page = new PDPage(PDRectangle.LETTER);
    doc.addPage(page);
    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
      float pageWidth = page.getMediaBox().getWidth();
      float pageHeight = page.getMediaBox().getHeight();
      float margin = 36f;
      float top = pageHeight - margin;
      float rowHeight = 24f;
      float y = top;

      y = drawText(cs, font, 18f, margin, y, section.folderName);
      y = drawText(cs, font, 10.5f, margin, y - 8f,
          "EA-PDF 1" + collectionProfile + " folder section. Messages below are ordered chronologically.");
      y -= 16f;
      drawRule(cs, margin, y, pageWidth - margin, y);
      y -= 16f;

      float subjectX = margin;
      float fromX = margin + 245f;
      float toX = margin + 390f;
      float sizeX = margin + 520f;
      drawText(cs, font, 9.5f, subjectX, y, "Subject");
      drawText(cs, font, 9.5f, fromX, y, "From");
      drawText(cs, font, 9.5f, toX, y, "To");
      drawText(cs, font, 9.5f, sizeX, y, "Size");
      y -= 8f;
      drawRule(cs, margin, y, pageWidth - margin, y);
      y -= 14f;

      for (int row = 0; row < rowsPerPage && recordIndex < section.records.size(); row++, recordIndex++) {
        EmailRecord record = section.records.get(recordIndex);
        float baseline = y;
        drawText(cs, font, 9.75f, subjectX, baseline, ellipsize(collectionMemberSubject(record, recordIndex + 1), 40));
        drawText(cs, font, 9.75f, fromX, baseline, ellipsize(Common.nz(record.from), 22));
        drawText(cs, font, 9.75f, toX, baseline, ellipsize(Common.nz(record.to), 18));
        drawText(cs, font, 9.75f, sizeX, baseline, humanReadableSize(messageMetadataSize(record)));
        links.put(record, new PageLinkTarget(doc.getNumberOfPages(),
            new PDRectangle(subjectX - 2f, baseline - 3f, sizeX - subjectX + 55f, 12f)));
        y -= rowHeight;
      }

      drawRule(cs, margin, 54f, pageWidth - margin, 54f);
      String footer = "Folder " + section.folderName + "  " + pageNumber + "/" + ((section.records.size() + rowsPerPage - 1) / rowsPerPage);
      drawText(cs, font, 9.5f, margin, 40f, footer);
    }
    addPageNavigationChrome(doc, font, page, "Folders", 1, null, 0);
  }
  return links;
}

static float drawText(PDPageContentStream cs, PDType0Font font, float fontSize, float x, float y, String text) throws Exception {
  FontSupport.draw(cs, font, fontSize, x, y, text);
  return y - fontSize;
}

static void drawRule(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws Exception {
  SemanticStructure.beginGroup(cs, "Artifact");
  cs.moveTo(x1, y1);
  cs.lineTo(x2, y2);
  cs.stroke();
  SemanticStructure.endGroup(cs);
}

static String ellipsize(String s, int maxChars) {
  String value = Common.nz(s).trim();
  if (value.length() <= maxChars) return value;
  if (maxChars <= 3) return value.substring(0, Math.max(0, maxChars));
  return value.substring(0, maxChars - 3) + "...";
}

static String humanReadableSize(long bytes) {
  if (bytes < 1024) return bytes + " B";
  double value = bytes;
  String[] units = { "KB", "MB", "GB", "TB" };
  int unitIndex = -1;
  while (value >= 1024 && unitIndex + 1 < units.length) {
    value /= 1024.0;
    unitIndex++;
  }
  return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.2f %s", value, units[Math.max(0, unitIndex)]);
}

static String sanitizePdfText(PDType0Font font, String text) throws Exception {
  String value = FontSupport.pdfText(text).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
  StringBuilder out = new StringBuilder(value.length());
  for (int i = 0; i < value.length();) {
    int codePoint = value.codePointAt(i);
    i += Character.charCount(codePoint);
    if (Character.isISOControl(codePoint)) continue;
    if (FontSupport.select(font, codePoint) != null) {
      out.appendCodePoint(codePoint);
    } else {
      out.append('?');
      Common.warn(ACTIVE_RENDER_RECORD.get(), "Missing font glyph in generated text: " + String.format("U+%04X", codePoint)
          + "; visible text uses '?'. Configure EAPDF_FALLBACK_FONTS for additional coverage.");
    }
  }
  return out.toString();
}

static boolean fontCanEncode(PDType0Font font, int codePoint) {
  if (font == null) return false;
  try {
    // hasGlyph takes an encoded PDF character code, not a Unicode code point.
    // encode performs the Unicode-to-glyph lookup and rejects missing glyphs.
    font.encode(new String(Character.toChars(codePoint)));
    return true;
  } catch (Exception ex) {
    return false;
  }
}

static void applyPageLinks(PDDocument doc, Collection<PageLinkTarget> links) throws Exception {
  if (links == null) return;
  for (PageLinkTarget link : links) {
    if (link == null || link.targetPageNumber <= 0) continue;
    PDPage source = doc.getPage(Math.max(0, link.sourcePageNumber - 1));
    PDPage target = doc.getPage(Math.max(0, link.targetPageNumber - 1));
    PDAnnotationLink annotation = new PDAnnotationLink();
    configureLinkAnnotation(annotation, link.rect);
    PDActionGoTo action = new PDActionGoTo();
    PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
    dest.setPage(target);
    action.setDestination(dest);
    annotation.setAction(action);
    source.getAnnotations().add(annotation);
  }
}

static void addPageNavigationChrome(PDDocument doc, PDType0Font font, PDPage page,
                                    String primaryLabel, int primaryTargetPage,
                                    String secondaryLabel, int secondaryTargetPage) throws Exception {
  if (page == null) return;
  String primary = sanitizePdfText(font, primaryLabel);
  String secondary = sanitizePdfText(font, secondaryLabel);
  float fontSize = 9f;
  float rightMargin = 36f;
  float topY = page.getMediaBox().getHeight() - 18f;
  float separatorWidth = textWidth(font, fontSize, " | ");
  float secondaryWidth = secondary.isBlank() ? 0f : textWidth(font, fontSize, secondary);
  float primaryWidth = primary.isBlank() ? 0f : textWidth(font, fontSize, primary);
  float totalWidth = primaryWidth + (secondary.isBlank() ? 0f : separatorWidth + secondaryWidth);
  float startX = page.getMediaBox().getWidth() - rightMargin - totalWidth;

  try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
    FontSupport.draw(cs, font, fontSize, startX, topY, primary + (secondary.isBlank() ? "" : " | " + secondary));
  }

  if (!primary.isBlank() && primaryTargetPage > 0) {
    addDirectPageLink(doc, page, new PDRectangle(startX - 1f, topY - 2f, primaryWidth + 2f, 11f), primaryTargetPage);
  }
  if (!secondary.isBlank() && secondaryTargetPage > 0) {
    float secondaryX = startX + primaryWidth + separatorWidth;
    addDirectPageLink(doc, page, new PDRectangle(secondaryX - 1f, topY - 2f, secondaryWidth + 2f, 11f), secondaryTargetPage);
  }
}

static float textWidth(PDType0Font font, float fontSize, String text) throws Exception {
  return FontSupport.width(font, fontSize, text);
}

static void addDirectPageLink(PDDocument doc, PDPage sourcePage, PDRectangle rect, int targetPageNumber) throws Exception {
  PDPage target = doc.getPage(Math.max(0, targetPageNumber - 1));
  PDAnnotationLink annotation = new PDAnnotationLink();
  configureLinkAnnotation(annotation, rect);
  PDActionGoTo action = new PDActionGoTo();
  PDPageFitWidthDestination dest = new PDPageFitWidthDestination();
  dest.setPage(target);
  action.setDestination(dest);
  annotation.setAction(action);
  sourcePage.getAnnotations().add(annotation);
}

static void writeFolderMessageCollection(Path out, List<EmailRecord> records, String fontPath, String iccPath, boolean debug, Path debugDir,
                                         String collectionProfile, String folderName) throws Exception {
  PdfCollectionSupport.writeFolderMessageCollection(out, records, fontPath, iccPath, debug, debugDir, collectionProfile, folderName);
}

static List<CollectionMember> buildMessageCollectionMembers(List<EmailRecord> records, String fontPath, String iccPath, boolean debug, Path debugDir, String collectionProfile) throws Exception {
  return PdfCollectionSupport.buildMessageCollectionMembers(records, fontPath, iccPath, debug, debugDir, collectionProfile);
}

static String childProfileForCollection(String collectionProfile, int count) {
  return PdfCollectionSupport.childProfileForCollection(collectionProfile, count);
}

static String collectionMemberFilename(String groupName, String childProfile, int index) {
  return PdfCollectionSupport.collectionMemberFilename(groupName, childProfile, index);
}

static String collectionFolderDisplay(EmailRecord record) {
  return PdfCollectionSupport.collectionFolderDisplay(record);
}

static String collectionMemberSubject(EmailRecord record, int index) {
  return PdfCollectionSupport.collectionMemberSubject(record, index);
}

static List<EmailRecord> sortRecordsChronologically(List<EmailRecord> records) {
  return PdfCollectionSupport.sortRecordsChronologically(records);
}

static List<EmailRecord> sortRecordsReverseChronologically(List<EmailRecord> records) {
  return PdfCollectionSupport.sortRecordsReverseChronologically(records);
}


static List<String> collectionGroupNames(EmailRecord record) {
  return PdfCollectionSupport.collectionGroupNames(record);
}


static void embedCollectionFiles(PDDocument doc, List<EmailRecord> records, List<CollectionMember> members, boolean debug, boolean topLevelFolders) throws Exception {
  PdfCollectionSupport.embedCollectionFiles(doc, records, members, debug, topLevelFolders);
}

static void addEmbeddedPlan(PDDocument doc, COSArray afArray, Map<String, PDComplexFileSpecification> nameMap,
                            String nameTreeKey, String filename, String mime, String relationship, String checksumMd5, byte[] bytes,
                            String description) throws Exception {
  addEmbeddedPlan(doc, afArray, nameMap, nameTreeKey, filename, mime, relationship, checksumMd5, bytes, description, null);
}

static void addEmbeddedPlan(PDDocument doc, COSArray afArray, Map<String, PDComplexFileSpecification> nameMap,
                            String nameTreeKey, String filename, String mime, String relationship, String checksumMd5, byte[] bytes,
                            String description, COSDictionary collectionItem) throws Exception {
  if (bytes == null || bytes.length == 0) return;
  String key = uniqueAttachmentKey(nameMap, nameTreeKey);
  PDComplexFileSpecification fs = new PDComplexFileSpecification();
  fs.setFile(Common.portablePdfFilename(filename));
  fs.setFileUnicode(Common.portablePdfFilename(filename));

  PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(bytes), COSName.FLATE_DECODE);
  ef.setSubtype(mime);
  ef.setSize(bytes.length);
  Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  ef.setCreationDate(now);
  ef.setModDate(now);
  setEmbeddedChecksum(ef, checksumMd5);

  COSDictionary efd = ef.getCOSObject();
  efd.setName(COSName.SUBTYPE, mime);

  COSDictionary params = (COSDictionary)efd.getDictionaryObject(COSName.PARAMS);
  if (params == null) {
    params = new COSDictionary();
    efd.setItem(COSName.PARAMS, params);
  }
  params.setInt(COSName.SIZE, bytes.length);

  fs.setEmbeddedFile(ef);
  fs.setEmbeddedFileUnicode(ef);

  COSDictionary fsd = fs.getCOSObject();
  fsd.setName(COSName.getPDFName("AFRelationship"), relationship);
  if (!Common.nz(description).isBlank()) fsd.setString(COSName.DESC, description);
  if (collectionItem != null) fsd.setItem(COSName.getPDFName("CI"), collectionItem);

  nameMap.put(key, fs);
  afArray.add(fs.getCOSObject());
}

static COSDictionary topLevelCollectionItemDictionary(CollectionMember member) {
  return PdfCollectionSupport.topLevelCollectionItemDictionary(member);
}

static void repairSavedTopLevelCollection(Path pdfPath, List<CollectionFolder> folders, List<CollectionMember> members, boolean debug) throws Exception {
  PdfCollectionSupport.repairSavedTopLevelCollection(pdfPath, folders, members, debug);
}

static void rewriteTopLevelCollectionItems(PDDocument doc, List<CollectionMember> members) throws Exception {
  PdfCollectionSupport.rewriteTopLevelCollectionItems(doc, members);
}

static COSDictionary messageCollectionItemDictionary(CollectionMember member) {
  return PdfCollectionSupport.messageCollectionItemDictionary(member);
}

static String reserveAttachmentKey(Set<String> normalizedNames, String name) {
  name = Common.portablePdfFilename(name);
  if (normalizedNames.add(Common.filenameCaseKey(name))) return name;
  int dot = name.lastIndexOf('.');
  String base = dot > 0 ? name.substring(0, dot) : name;
  String extension = dot > 0 ? name.substring(dot) : "";
  for (int suffix = 2; ; suffix++) {
    String candidate = base + "_" + suffix + extension;
    if (normalizedNames.add(Common.filenameCaseKey(candidate))) return candidate;
  }
}

static String uniqueAttachmentKey(Map<String, ?> names, String name) {
  name = Common.portablePdfFilename(name);
  String normalizedName = name;
  if (names.keySet().stream().noneMatch(key -> Common.filenameCaseKey(key).equals(Common.filenameCaseKey(normalizedName)))) return name;
  String base = name, ext = "";
  int dot = name.lastIndexOf('.');
  if (dot > 0) {
    base = name.substring(0, dot);
    ext = name.substring(dot);
  }
  int n = 2;
  while (true) {
    String candidate = base + "_" + n + ext;
    if (names.keySet().stream().noneMatch(key -> Common.filenameCaseKey(key).equals(Common.filenameCaseKey(candidate)))) return candidate;
    n++;
  }
}

static String numberedAttachmentKey(Map<String, ?> names, String name, int seq, int total) {
  name = Common.portablePdfFilename(name);
  String base = name, ext = "";
  int dot = name.lastIndexOf('.');
  if (dot > 0) {
    base = name.substring(0, dot);
    ext = name.substring(dot);
  }
  int width = Math.max(2, Integer.toString(total).length());
  String candidate = base + "_" + String.format("%0" + width + "d", seq) + ext;
  if (!names.containsKey(candidate)) return candidate;
  int n = seq;
  while (true) {
    n++;
    candidate = base + "_" + String.format("%0" + width + "d", n) + ext;
    if (!names.containsKey(candidate)) return candidate;
  }
}

static String normalizeAfRelationship(String s) {
  String t = Common.nz(s).trim();
  if (t.equals("Source") || t.equals("Data") || t.equals("Alternative") || t.equals("Supplement") || t.equals("EncryptedPayload") || t.equals("FormData") || t.equals("Schema") || t.equals("Unspecified")) return t;
  return "Data";
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

static String rewriteCidToFileUris(String html, List<AssociatedFile> files, EmailRecord r, boolean debug, Path assetDir) throws Exception {
  Document d = Jsoup.parse("<html><body>" + Common.stripInvalidXml(html) + "</body></html>", "", Parser.xmlParser());
  d.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  d.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  d.outputSettings().charset(StandardCharsets.UTF_8);
  d.outputSettings().prettyPrint(false);

  Map<AssociatedFile, byte[]> loaded = new IdentityHashMap<>();
  for (Element e : d.select("[src],[href]")) {
    String attr = e.hasAttr("src") ? "src" : "href";
    String val = Common.nz(e.attr(attr)).trim();
    if (val.isBlank()) continue;

    String cidKey = "";
    if (val.regionMatches(true, 0, "cid:", 0, 4)) cidKey = val.substring(4).trim();
    if (cidKey.isBlank()) continue;

    String normCidKey = Common.cidUrlKey(cidKey);
    AssociatedFile hit = findCidAsset(cidKey, files);
    if (hit == null || !hit.hasData() || hit.size() == 0) continue;

    byte[] data = loaded.computeIfAbsent(hit, AssociatedFile::data);
    String mime = Common.detectMimeType(hit.filename, hit.mime, data);
    String outName = "cid-" + Common.sha256Hex((normCidKey + "|" + Common.sha256Hex(data)).getBytes(StandardCharsets.UTF_8))
        + Common.extensionForMime(mime, hit.filename);
    Path img = assetDir.resolve(outName);
    Files.write(img, data);
    String uri = img.toUri().toString();
    r.renderAssets.put(uri, data);
    e.attr(attr, uri);
    // Preserve authored display styles and dimensions; the renderer supplies
    // the natural image aspect ratio when no explicit height is provided.
    if (debug) {
      Common.dbg(r, "rewrote inline CID " + cidKey + " using file=" + Common.nz(hit.filename) + " mime=" + mime + " uri=" + uri);
      Common.dbg(r, "asset exists=" + Files.exists(img) + " size=" + Files.size(img) + " path=" + img);
    }
  }

  return d.body().html();
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
  }
  return new int[]{0, 0};
}

static boolean looksEmpty(String html) {
  Document document = Jsoup.parse(Common.nz(html));
  return document.text().isBlank() && document.select("img,svg,object,video,audio,canvas,[background],[style*=background]").isEmpty();
}

static boolean shouldBoldHeaderLabel(String label) {
  String normalized = Common.nz(label).trim();
  return "From".equals(normalized)
      || "To".equals(normalized)
      || "Reply-To".equals(normalized)
      || "Cc".equals(normalized)
      || "Bcc".equals(normalized)
      || "Subject".equals(normalized)
      || "Date".equals(normalized)
      || "Message-ID".equals(normalized)
      || "MessageID".equals(normalized);
}

static void appendHeaderRow(StringBuilder s, String label, String value) {
  String headerTag = pdfUaFeaturesEnabled() ? "th" : "td";
  s.append("<tr><").append(headerTag);
  if (pdfUaFeaturesEnabled()) s.append(" scope=\"row\"");
  s.append(" class=\"label");
  if (shouldBoldHeaderLabel(label)) s.append(" label-strong");
  s.append("\">")
   .append(Common.escapeHtml(label))
   .append(":</").append(headerTag).append("><td class=\"value\">")
   .append(Common.escapeHtml(Common.nz(value)))
   .append("</td></tr>");
}

static String appendStyle(String existing, String add) {
  String e = Common.nz(existing).trim();
  if (e.isBlank()) return add;
  if (!e.endsWith(";")) e += ";";
  return e + add;
}

static String chooseOutputAssetName(String filename, String mime, String cid) {
  if (filename != null && !filename.isBlank()) return safeAssetFilename(filename);
  return safeAssetFilename("cid-" + cid + Common.extensionForMime(mime, filename));
}

static String safeAssetFilename(String s) {
  String t = Common.nz(s).trim();
  if (t.isEmpty()) t = "asset.bin";
  t = t.replaceAll("[\\\\/:*?\"<>|]+", "_");
  t = t.replaceAll("\\s+", " ").trim();
  return t.isEmpty() ? "asset.bin" : t;
}

}
