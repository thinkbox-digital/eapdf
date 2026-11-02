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

static {
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
    if (isAllowedRenderResource(resolved)) return resolved;
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
      byte[] bytes = header.endsWith(";base64") ? Base64.getMimeDecoder().decode(uri.substring(comma + 1))
          : java.net.URLDecoder.decode(uri.substring(comma + 1).replace("+", "%2B"), StandardCharsets.ISO_8859_1).getBytes(StandardCharsets.ISO_8859_1);
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
  final byte[] premisBytes;

  SingleMessageBuildResult(byte[] pdfBytes, byte[] premisBytes) {
    this.pdfBytes = pdfBytes;
    this.premisBytes = premisBytes;
  }
}

static class ArchiveEmbeddedFilePlan {
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
  byte[] premisBytes;
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
}

static class FindingAidRow {
  final int ordinal;
  final String folder;
  final String sent;
  final String from;
  final String to;
  final String subject;
  final String messageId;
  final int attachmentCount;

  FindingAidRow(int ordinal, String folder, String sent, String from, String to, String subject, String messageId, int attachmentCount) {
    this.ordinal = ordinal;
    this.folder = folder;
    this.sent = sent;
    this.from = from;
    this.to = to;
    this.subject = subject;
    this.messageId = messageId;
    this.attachmentCount = attachmentCount;
  }
}

static class FindingAidPlacement {
  final int rowOrdinal;
  final int pageNumber;
  final PDRectangle numberRect;
  final PDRectangle subjectRect;

  FindingAidPlacement(int rowOrdinal, int pageNumber, PDRectangle numberRect, PDRectangle subjectRect) {
    this.rowOrdinal = rowOrdinal;
    this.pageNumber = pageNumber;
    this.numberRect = numberRect;
    this.subjectRect = subjectRect;
  }
}

static class FindingAidDocument {
  final PDDocument document;
  final List<FindingAidPlacement> placements;
  final PDRectangle excelLinkRect;

  FindingAidDocument(PDDocument document, List<FindingAidPlacement> placements, PDRectangle excelLinkRect) {
    this.document = document;
    this.placements = placements;
    this.excelLinkRect = excelLinkRect;
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
        af.checksumMd5 = Common.md5Hex(sourceBytes);
        af.checksumSha256 = Common.sha256Hex(sourceBytes);
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
  String sha = Common.nz(af.checksumSha256);
  if (sha.isBlank() && af.hasData() && af.size() > 0) {
    try { sha = Common.sha256Hex(af.data()); } catch (Exception ignored) {}
  }
  if (sha.isBlank()) {
    String md5 = Common.nz(af.checksumMd5);
    if (md5.isBlank() && af.hasData() && af.size() > 0) {
      try { md5 = Common.md5Hex(af.data()); } catch (Exception ignored) {}
    }
    sha = md5;
  }
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
  String href = attachmentLaunchHref(af);
  String title = "Open attached file: " + Common.escapeHtml(Common.nz(af.filename));
  return "<a class=\"att-badge\" href=\"" + href + "\" title=\"" + title + "\" aria-label=\"" + title + "\">&#160;</a>";
}

static String attachmentDisplayHtml(AssociatedFile af) {
  return Common.escapeHtml(Common.nz(af.filename));
}

static String attachmentTextWithBadgeHtml(AssociatedFile af) {
  if (af == null) return "";
  if (af.emailAttachment) return attachmentBadgeHtml(af) + attachmentDisplayHtml(af);
  return attachmentDisplayHtml(af);
}

static String projectBrandingLogoUri() {
  try {
    Path optimized = Common.resolveExistingPath("assets/thinkbox_footer_optimized.png", "thinkbox_footer_optimized.png");
    if (optimized != null) return optimized.toAbsolutePath().toUri().toString();
    Path p = Common.resolveExistingPath("assets/thinkbox_footer.png", "thinkbox_footer.png");
    if (p != null) return p.toAbsolutePath().toUri().toString();
    Path scaled = Common.resolveExistingPath("assets/thinkbox_footer_scaled.png", "thinkbox_footer_scaled.png");
    if (scaled != null) return scaled.toAbsolutePath().toUri().toString();
  } catch (Exception ignored) {}
  String bundledFooter = Common.bundledFooterUri();
  if (!bundledFooter.isBlank()) return bundledFooter;
  return "";
}

static String pdfaComplianceLabel() {
  return "PDF/A-4f";
}

static void appendBrandingBlock(StringBuilder s, String eaPdfProfile) {
  // Footer is drawn with PDFBox after HTML rendering so it always uses the
  // actual page width and the same @page margins, independent of email HTML.
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
    e.attr("style", appendStyle(e.attr("style"), "display:inline-block"));
  }

  String finalXhtml = d.outerHtml();
  if (debug) {
    Common.dbg(r, "final xhtml len=" + finalXhtml.length());
    Common.dbg(r, "final xhtml preview=" + Common.preview(finalXhtml, 260));
  }

  EmailRecord previousRecord = ACTIVE_RENDER_RECORD.get();
  Set<String> previousImages = RENDERED_IMAGES.get();
  RENDERED_IMAGES.set(new HashSet<>());
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
    r.renderAssets.clear();
    if (previousRecord == null) ACTIVE_RENDER_RECORD.remove();
    else ACTIVE_RENDER_RECORD.set(previousRecord);
  }
}

static byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath) throws Exception {
  ByteArrayOutputStream baos = new ByteArrayOutputStream();
  PdfRendererBuilder builder = new PdfRendererBuilder();
  builder.useFastMode();
  builder.useFont(new File(fontPath), "ArchiveFont");
  EmailRecord renderRecord = ACTIVE_RENDER_RECORD.get();
  builder.usePdfUaAccessbility(pdfUaFeaturesEnabled() || (renderRecord != null && !Common.nz(renderRecord.headerTagMarker).isBlank()));
  xhtml = FontSupport.configure(builder, fontPath, xhtml, ACTIVE_RENDER_RECORD.get());
  builder.useUriResolver(REMOTE_ASSET_URI_RESOLVER);
  builder.useProtocolsStreamImplementation(REMOTE_ASSET_STREAM_FACTORY, "http", "https");
  builder.useProtocolsStreamImplementation(PdfMailWriter::trustedRenderAsset, "file");
  builder.useExternalResourceAccessControl((uri, type) -> isAllowedRenderResource(uri),
      ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
  builder.withHtmlContent(xhtml, baseUri);
  builder.toStream(baos);
  builder.run();
  byte[] rendered = baos.toByteArray();
  try (PDDocument document = Loader.loadPDF(rendered)) {
    ProcessingLimits.accountPages(document.getNumberOfPages());
  }
  return rendered;
}

static String buildPlainTextFallbackXhtml(String html) {
  String text = Common.stripHtml(Common.nz(html));
  if (text.isBlank()) text = "(message content could not be rendered as HTML)";
  StringBuilder s = new StringBuilder();
  s.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>");
  s.append("<style>");
  s.append("@page { size: letter; margin: 0.65in; }");
  s.append("body { margin: 8px; font-family: ArchiveFont; font-size: 11pt; color: #222; line-height: 1.35; }");
  s.append("pre { white-space: pre-wrap; word-break: break-word; margin: 0; }");
  s.append("</style></head><body><pre>");
  s.append(Common.escapeHtml(text));
  s.append("</pre></body></html>");
  return s.toString();
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
  s.append("@page { size: letter; margin: 0.65in; }");
  s.append("html, body, body * { font-family: ArchiveFont !important; }");
  s.append("body { margin: 8px; font-size: 11pt; color: #222; line-height: 1.35; }");
  s.append(".hdr { margin-bottom: 18px; }");
  s.append(".hdr-table { border-collapse: collapse; }");
  s.append(".hdr-table td { padding: 2px 0; }");
  s.append(".hdr-table .label { width: 96px; padding-right: 8px; box-sizing: border-box; white-space: nowrap; }");
  s.append(".hdr-table .value { padding-left: 0; }");
  s.append(".label-strong { font-weight: bold; }");
  s.append(".message-body { margin-top: 12px; }");
  s.append(".message-body img { display: inline-block; max-width: 100%; height: auto; }");
  s.append(".attachments { margin-top: 18px; font-size: 9.5pt; color: #444; width: 100%; }");
  s.append(".att-line { margin: 3px 0 0 12px; }");
  s.append(".att-parent { margin-top: 4px; font-weight: bold; color: #3e4f63; }");
  s.append(".att-child { margin-left: 24px; }");
  s.append(".att-meta { color: #72675b; font-weight: normal; }");
  s.append(".att-badge { display: inline-block; width: 13px; height: 10px; margin-right: 6px; vertical-align: middle; ");
  s.append("border: 1px solid #6d8297; border-radius: 999px; background: #edf4fa; text-decoration: none; box-sizing: border-box; }");
  s.append(".message-delimiter { margin-top: 22px; page-break-inside: avoid; width: 100%; }");
  s.append(".message-delimiter-label { font-size: 8.5pt; color: #7f7462; letter-spacing: 0.08em; text-transform: uppercase; margin-bottom: 6px; }");
  s.append(".branding-box { border: 1px solid #cdbd9f; overflow: hidden; position: relative; width: 100%; box-sizing: border-box; }");
  s.append(".branding-image { display: block; width: 100%; height: auto; }");
  s.append(".branding-overlay { position: absolute; left: 24px; top: 14px; max-width: 72%; }");
  s.append(".branding-overlay-inner { background: #ffffff; border: 1px solid #cdbd9f; padding: 7px 10px; }");
  s.append(".branding-line { margin: 0; }");
  s.append(".branding-title { font-size: 10pt; font-weight: bold; color: #1f3f5b; }");
  s.append(".branding-subtitle { font-size: 8.5pt; color: #5f5544; margin-top: 2px; }");
  s.append("table { border-collapse: collapse; }");
  s.append("th, td { vertical-align: top; }");
  s.append(".pagebreak { page-break-before: always; }");
  s.append(emailStyles);
  s.append("</style></head><body>");

  s.append("<div class=\"hdr\"><table class=\"hdr-table\" data-eapdf-header=\"").append(r.headerTagMarker).append("\">");
  appendHeaderRow(s, "From", Common.nz(r.from));
  appendHeaderRow(s, "Date", !Common.nz(r.rawDateHeader).isBlank() ? Common.nz(r.rawDateHeader) : Common.nz(r.date));
  appendHeaderRow(s, "To", Common.nz(r.to));
  if (!Common.nz(r.cc).isBlank()) appendHeaderRow(s, "Cc", Common.nz(r.cc));
  if (!Common.nz(r.bcc).isBlank()) appendHeaderRow(s, "Bcc", Common.nz(r.bcc));
  appendHeaderRow(s, "Subject", Common.nz(r.subject));
  if (!Common.nz(r.messageId).isBlank()) appendHeaderRow(s, "Message-ID", Common.nz(r.messageId));
  if (!Common.nz(r.folderPath).isBlank()) appendHeaderRow(s, "Folder", Common.nz(r.folderPath));
  if (!Common.nz(r.archiveGlobalId).isBlank()) appendHeaderRow(s, "Archive Global ID", Common.nz(r.archiveGlobalId));
  if (!r.archiveEmails.isEmpty()) appendHeaderRow(s, "Archive Email(s)", String.join(", ", r.archiveEmails));
  s.append("</table></div>");

  s.append("<div class=\"message-body\">").append(emailHtml).append("</div>");
  List<AssociatedFile> displayFiles = renderAssociatedFiles(r);
  s.append("<div class=\"attachments\">");
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
     .append(attachmentBadgeHtml(sourceDisplay))
     .append(attachmentDisplayHtml(sourceDisplay))
     .append(" <span class=\"att-meta\">[")
     .append(Common.escapeHtml(Common.nz(sourceDisplay.mime)))
     .append("]</span></div>");
    List<String> sourceAttachmentNames = new ArrayList<>();
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null || !af.emailAttachment) continue;
      sourceAttachmentNames.add(attachmentDisplayHtml(af));
    }
    if (!sourceAttachmentNames.isEmpty()) {
      s.append("<div class=\"att-line att-child\">")
       .append("Source file attachments: ")
       .append(String.join(", ", sourceAttachmentNames))
       .append("</div>");
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
  }
  s.append("</div>");
  if (CONFIG.branding.enabled) appendBrandingBlock(s, "1" + chooseEaPdfConformance(r, profileOverride));
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
  if (src.isBlank()) return "";

  Document d = Jsoup.parse(src);
  d.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  d.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  d.outputSettings().charset(StandardCharsets.UTF_8);
  d.outputSettings().prettyPrint(false);
  String preservedStyles = EmailStyles.scoped(d, r);
  for (Element e : d.select("script,style,meta[http-equiv=refresh],link[rel=stylesheet],base")) e.remove();
  for (Element e : d.getAllElements()) {
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
  sourceBody.html(Common.stripInvalidXml(inner));
  linkifyBodyText(sourceBody);
  if (!preservedStyles.isBlank()) root.prependElement("style").text(preservedStyles);

  for (Element e : wrap.select("img")) {
    if (e.hasAttr("src")) e.attr("src", normalizeRemoteAssetUrl(Common.stripInvalidXml(Common.nz(e.attr("src")).trim())));
  }

  for (Element e : wrap.select("[background]")) {
    String bg = normalizeRemoteAssetUrl(e.attr("background"));
    e.removeAttr("background");
    if (!bg.isBlank()) e.attr("style", appendStyle(e.attr("style"), "background-image:url('" + bg.replace("'", "%27") + "')"));
  }

  for (Element e : wrap.select("[src]")) {
    e.attr("src", normalizeRemoteAssetUrl(e.attr("src")));
  }
  for (Element e : wrap.select("[href]")) {
    e.attr("href", Common.stripInvalidXml(e.attr("href").trim()));
  }

  for (Element e : wrap.select("[align]")) {
    String a = e.attr("align");
    e.removeAttr("align");
    if (!a.isBlank()) e.attr("style", appendStyle(e.attr("style"), "text-align:" + a));
  }

  for (Element e : wrap.select("font[color]")) {
    String c = e.attr("color");
    e.removeAttr("color");
    if (!c.isBlank()) e.attr("style", appendStyle(e.attr("style"), "color:" + c));
  }

  for (Element e : wrap.select("[width]")) {
    String w = e.attr("width");
    if (w.matches("\\d+")) e.attr("style", appendStyle(e.attr("style"), "width:" + w + "px"));
    e.removeAttr("width");
  }

  for (Element e : wrap.select("[height]")) {
    String h = e.attr("height");
    if (h.matches("\\d+")) e.attr("style", appendStyle(e.attr("style"), "height:" + h + "px"));
    e.removeAttr("height");
  }

  String out = Common.stripInvalidXml(root.outerHtml());
  if (debug) {
    Common.dbg(r, "canonicalized email html len=" + out.length());
    Common.dbg(r, "canonicalized email html preview=" + Common.preview(out, 220));
  }
  return out;
}

static void linkifyBodyText(Element root) {
  List<org.jsoup.nodes.TextNode> nodes = new ArrayList<>();
  for (Element element : root.getAllElements()) {
    if (element.closest("a,style,script") == null) nodes.addAll(element.textNodes());
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
  String value = Common.stripInvalidXml(Common.nz(raw).trim());
  if (value.isBlank()) return value;
  if (value.startsWith("cid:") || value.startsWith("data:") || value.startsWith("file:")) return value;
  if (value.regionMatches(true, 0, "http://", 0, 7)) {
    return "https://" + value.substring(7);
  }
  return value;
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
  SingleMessageBuildResult built = buildSingleMessagePdf(out, r, fontPath, iccPath, debug, debugDir, idx, null, out);
  if (built.premisBytes != null) PdfFindingAidSupport.writePremisSidecar(out, built.premisBytes);
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
    byte[] premisBytes = CONFIG.premis.enabled
        ? PremisBuilder.buildSingle(logicalOut, r, "1" + chooseEaPdfConformance(r, profileOverride))
        : null;

    PDDocument doc = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(rendered), org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
    try {
      doc.setVersion(2.0f);
      doc.getDocument().setVersion(2.0f);
      drawInlineImageFallback(doc, r, debug);
      addOmittedAttachmentAnnotations(doc, r, fontPath);
      if (CONFIG.branding.enabled) drawBrandingFooter(doc, fontPath, "1" + chooseEaPdfConformance(r, profileOverride), r.renderContentWidthPx);
      clearImageInterpolation(doc);
      drawPageContext(doc, fontPath);
      setDocumentInfo(doc, r);
      configureCatalog(doc, r, profileOverride);
      applyPageLabels(doc, 0, true);
      addOutlines(doc, r);
      addDPartStructure(doc, r);
      if (premisBytes != null && r.embedSourceMessage) {
        PdfFindingAidSupport.embedStandaloneCatalogBytes(doc, PdfFindingAidSupport.premisFilename(logicalOut), premisBytes,
            "PREMIS preservation metadata", "application/xml", "Data", false);
      }
      Map<String, PDComplexFileSpecification> embeddedFiles = embedAssociatedFiles(doc, r, debug);
      PdfFindingAidSupport.rewriteAttachmentLaunchLinks(doc, embeddedFiles);
      addLogicalStructure(doc, r);
      setPdfA3Metadata(doc, iccPath, r, profileOverride);
      normalizePageContentStreams(doc);
      if (destination != null) {
        Common.writePdfAtomically(destination, temporary -> doc.save(temporary.toFile()), expected);
        return new SingleMessageBuildResult(null, premisBytes);
      }
      ByteArrayOutputStream finalized = new ByteArrayOutputStream();
      doc.save(finalized);
      byte[] bytes = finalized.toByteArray();
      try (PDDocument serialized = Loader.loadPDF(new org.apache.pdfbox.io.RandomAccessReadBuffer(bytes),
          org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
        PdfCreationValidator.validate(serialized, expected);
      }
      return new SingleMessageBuildResult(bytes, premisBytes);
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
    try (PDDocument doc = Loader.loadPDF(rendered)) {
      SemanticStructure.headerFields(doc, r.headerTagMarker);
      drawInlineImageFallback(doc, r, debug);
      addOmittedAttachmentAnnotations(doc, r, fontPath);
      if (CONFIG.branding.enabled) drawBrandingFooter(doc, fontPath, "1" + chooseEaPdfConformance(r, profileOverride), r.renderContentWidthPx);
      clearImageInterpolation(doc);

      ByteArrayOutputStream finalized = new ByteArrayOutputStream();
      doc.save(finalized);
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
  int parallelism = renderParallelism(source.size());
  if (parallelism <= 1) {
    int idx = 0;
    for (EmailRecord record : source) {
      idx++;
      Common.finalizeRecordMetadata(record);
      consumer.accept(new RenderedMessagePart(record, buildArchiveMessagePdfBytes(record, fontPath, debug, debugDir, idx, archiveProfile)));
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
          return new RenderedMessagePart(record, buildArchiveMessagePdfBytes(record, fontPath, debug, debugDir, idx, archiveProfile));
        }));
        submitted++;
      }
      consumer.accept(Common.awaitWorker(inFlight.remove(expected)));
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
  List<MessageSegment> segments = new ArrayList<>();
  PDDocument findingAidDoc = null;
  FindingAidDocument findingAid = null;
  List<FolderSection> sections = PdfCollectionSupport.buildFolderSections(records);
  CollectionRunSummary runSummary = buildCollectionRunSummary(records, sections);
  boolean findingAidEnabled = CONFIG.findingAid != null && CONFIG.findingAid.pdfEnabled;
  List<FindingAidRow> findingAidRows = findingAidEnabled ? PdfFindingAidSupport.buildFindingAidRows(records) : Collections.emptyList();
  String excelFilename = out.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + "_index.xlsx";
  byte[] premisBytes = CONFIG.premis.enabled
      ? PremisBuilder.buildAggregate(out, records, "1" + archiveProfile)
      : null;
  ByteArrayOutputStream findingAidBytes = null;
  if (findingAidEnabled) {
    findingAid = PdfFindingAidSupport.buildFindingAidPdf(findingAidRows, archiveProfile, runSummary, fontPath, false, excelFilename);
    findingAidBytes = new ByteArrayOutputStream();
    findingAid.document.save(findingAidBytes);
  }
  try (PDDocument archive = new PDDocument(org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
    archive.setVersion(2.0f);
    archive.getDocument().setVersion(2.0f);
    int pageCursor = 1;
    int findingAidPages = 0;
    if (findingAidEnabled && findingAidBytes != null) {
      findingAidDoc = Loader.loadPDF(findingAidBytes.toByteArray());
      for (PDPage page : findingAidDoc.getPages()) {
        archive.importPage(page);
        pageCursor++;
      }
      findingAidPages = pageCursor - 1;
    }
    forEachArchiveMessagePart(records, fontPath, debug, debugDir, archiveProfile, rendered -> {
      try (PDDocument part = Loader.loadPDF(rendered.pdfBytes)) {
        int startPage = archive.getNumberOfPages() + 1;
        MessageSegment segment = new MessageSegment(rendered.record, startPage, startPage + part.getNumberOfPages() - 1);
        appendClonedPages(archive, part, segment);
        segments.add(segment);
      }
    });
    setDocumentInfo(archive, records.get(0));
    configureArchiveCatalog(archive, archiveProfile);
    applyPageLabels(archive, findingAidPages, false);
    addArchiveOutlines(archive, segments, findingAidPages);
    addArchiveDPartStructure(archive, segments, findingAidPages);
    if (premisBytes != null && !archiveProfile.endsWith("i")) {
      PdfFindingAidSupport.embedStandaloneCatalogBytes(archive, PdfFindingAidSupport.premisFilename(out), premisBytes,
          "PREMIS preservation metadata", "application/xml", "Data", false);
    }
    EmbeddedFileBindings embeddedFiles = embedAssociatedFiles(archive, records, debug);
    PdfFindingAidSupport.rewriteAttachmentLaunchLinks(archive, embeddedFiles.filesByToken);
    PDComplexFileSpecification excelFile = findingAidEnabled && CONFIG.findingAid.embedExcel
        ? PdfFindingAidSupport.embedSpreadsheetFile(archive, out, findingAidRows, archiveProfile, true)
        : null;
    if (findingAidEnabled && findingAid != null) {
      PdfFindingAidSupport.addFindingAidArchiveLinks(archive, findingAid.placements, segments, excelFile, findingAid.excelLinkRect);
    }
    drawPageContext(archive, fontPath);
    addArchiveLogicalStructure(archive, segments, findingAidPages);
    setPdfA3Metadata(archive, iccPath, records, archiveProfile, findingAidPages > 0);
    normalizePageContentStreams(archive);
    Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
    Common.writePdfAtomically(out, temporary -> archive.save(temporary.toFile()), expected);
  } finally {
    if (findingAidDoc != null) {
      try { findingAidDoc.close(); } catch (Exception closeErr) {}
    }
    if (findingAid != null) {
      try { findingAid.document.close(); } catch (Exception closeErr) {}
    }
  }
  if (premisBytes != null) PdfFindingAidSupport.writePremisSidecar(out, premisBytes);
  if (findingAidEnabled && CONFIG.findingAid.excelEnabled) PdfFindingAidSupport.writeFindingAidSpreadsheet(out, findingAidRows, archiveProfile);
  for (EmailRecord record : records) Common.recordWarnings(record);
  }
}

static int appendClonedPages(PDDocument target, PDDocument source) throws Exception {
  return appendClonedPages(target, source, null);
}

static int appendClonedPages(PDDocument target, PDDocument source, MessageSegment segment) throws Exception {
  if (target == null || source == null) return 0;
  PDFCloneUtility cloner = new PDFCloneUtility(target) {};
  int count = 0;
  for (PDPage page : source.getPages()) {
    COSDictionary cloned = (COSDictionary) cloner.cloneForNewDocument(page.getCOSObject());
    cloned.removeItem(COSName.PARENT);
    PDPage imported = new PDPage(cloned);
    target.addPage(imported);
    count++;
  }
  if (segment != null) segment.semanticTree = SemanticStructure.importedTree(target, source, cloner);
  return count;
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
  List<CollectionMember> members = PdfCollectionSupport.buildFolderCollectionMembers(sections, folders, fontPath, iccPath, debug, debugDir, collectionProfile, memberStore);
  if (members.isEmpty()) throw new IllegalArgumentException("collection requires at least one embedded EA-PDF member");
  List<FindingAidRow> findingAidRows = PdfFindingAidSupport.buildFindingAidRowsFromMembers(members);
  byte[] premisBytes = CONFIG.premis.enabled
      ? PremisBuilder.buildCollection(out, records, "1" + collectionProfile, members)
      : null;

  String excelFilename = out.getFileName().toString().replaceFirst("(?i)\\.pdf$", "") + "_index.xlsx";
  boolean findingAidEnabled = CONFIG.findingAid.pdfEnabled;
  FindingAidDocument findingAid = findingAidEnabled
      ? PdfFindingAidSupport.buildFindingAidPdf(findingAidRows, collectionProfile, runSummary, fontPath, true, excelFilename)
      : PdfFindingAidSupport.buildCollectionFrontMatter(collectionProfile, runSummary, fontPath);
  try (PDDocument archive = findingAid.document) {
    archive.setVersion(2.0f);
    archive.getDocument().setVersion(2.0f);
    if (CONFIG.branding.enabled) drawBrandingFooter(archive, fontPath, "1" + collectionProfile, 0);
    clearImageInterpolation(archive);
    setDocumentInfo(archive, records.get(0));
    configureArchiveCatalog(archive, collectionProfile);
    applyPageLabels(archive, archive.getNumberOfPages(), false);
    PdfCollectionSupport.addTopLevelCollectionDictionary(archive, folders);
    addOutlines(archive, PdfCollectionSupport.buildCollectionFrontRecord(records, collectionProfile, members));
    PdfCollectionSupport.addCollectionDPartStructure(archive, records, collectionProfile);
    PdfCollectionSupport.embedCollectionFiles(archive, records, members, debug, true);
    PdfFindingAidSupport.addFindingAidCollectionLinks(archive, findingAid.placements, members, null, findingAid.excelLinkRect);
    PdfCollectionSupport.addCollectionLogicalStructure(archive);
    setPdfA3Metadata(archive, iccPath, records, collectionProfile);
    PdfCollectionSupport.addTopLevelCollectionDictionary(archive, folders);
    normalizePageContentStreams(archive);
    Files.createDirectories(out.getParent() == null ? Paths.get(".") : out.getParent());
    Common.writePdfAtomically(out, temporary -> {
      archive.save(temporary.toFile());
      PdfCollectionSupport.repairSavedTopLevelCollection(temporary, folders, members, debug);
    }, expected);
  }
  if (findingAidEnabled) {
    if (CONFIG.findingAid.embedPdfInCollections) {
      byte[] findingAidBytes = PdfFindingAidSupport.buildFindingAidSidecar(out, findingAidRows, collectionProfile, runSummary, fontPath, iccPath);
      OutputTransaction.write(out.resolveSibling(PdfFindingAidSupport.findingAidPdfFilename(out)),
          temporary -> Files.write(temporary, findingAidBytes));
    }
    PdfFindingAidSupport.writeFindingAidSpreadsheet(out, findingAidRows, collectionProfile);
  }
  if (premisBytes != null) PdfFindingAidSupport.writePremisSidecar(out, premisBytes);
  if (premisBytes != null) {
    Common.warn(records.get(0), "Collection PREMIS and requested finding-aid PDF/XLSX files are published as sidecars (section 7.6).");
  }
  for (EmailRecord record : records) Common.recordWarnings(record);
  }
}

static String ROOT_URI() {
  return Paths.get(".").toAbsolutePath().normalize().toUri().toString();
}

static void configureLinkAnnotation(PDAnnotationLink annotation, PDRectangle rect) {
  PdfFindingAidSupport.configureLinkAnnotation(annotation, rect);
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

static void drawPageContext(PDDocument document, String fontPath) throws Exception {
  if (document.getNumberOfPages() < 2 || !CONFIG.accessibility.pageLabels.enabled) return;
  PDType0Font font = loadUiFont(document, fontPath);
  for (int index = 0; index < document.getNumberOfPages(); index++) {
    PDPage page = document.getPage(index);
    String label = "Page " + (index + 1) + " of " + document.getNumberOfPages();
    PDRectangle box = page.getCropBox();
    float positionX = Math.max(box.getLowerLeftX() + 10f, box.getUpperRightX() - 46.8f - textWidth(font, 8f, label));
    try (PDPageContentStream content = new PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
      beginArtifact(content);
      drawText(content, font, 8f, positionX, box.getLowerLeftY() + 14f, label);
      endArtifact(content);
    }
  }
}

static void beginArtifact(PDPageContentStream cs) throws IOException {
  if (!pdfUaFeaturesEnabled()) return;
  cs.beginMarkedContent(COSName.ARTIFACT);
}

static void endArtifact(PDPageContentStream cs) throws IOException {
  if (!pdfUaFeaturesEnabled()) return;
  cs.endMarkedContent();
}

static void drawBrandingFooter(PDDocument doc, String fontPath, String eaPdfProfile, int contentWidthPx) throws Exception {
  if (doc == null || doc.getNumberOfPages() == 0) return;
  String logoUri = projectBrandingLogoUri();
  if (Common.nz(logoUri).isBlank()) return;

  String safeProfile = Common.nz(eaPdfProfile).isBlank() ? "1s" : Common.nz(eaPdfProfile);
  PDPage page = doc.getPage(doc.getNumberOfPages() - 1);
  PDRectangle box = page.getMediaBox();
  float margin = 46.8f; // matches HTML @page margin: 0.65in
  float bodyInset = 8f * 72f / 96f; // explicit body margin: 8px
  float pageContentWidth = Math.max(120f, box.getWidth() - (margin * 2));
  float contentLeft = margin + bodyInset;
  float maxContentWidth = Math.max(120f, pageContentWidth - (bodyInset * 2));
  float contentWidth = contentWidthPx > 0 ? ((float)contentWidthPx * 72f / 96f) : maxContentWidth;
  contentWidth = Math.min(maxContentWidth, Math.max(120f, contentWidth));

  BufferedImage img;
  try (InputStream in = new BufferedInputStream(new java.net.URI(logoUri).toURL().openStream())) {
    img = javax.imageio.ImageIO.read(in);
  }
  if (img == null) return;

  float imgWidthPt = contentWidth;
  float imgHeightPt = imgWidthPt * ((float) img.getHeight() / Math.max(1f, (float) img.getWidth()));
  float x = contentLeft;
  float y = margin;

  PDImageXObject pdImage = LosslessFactory.createFromImage(doc, img);
  PDType0Font font = loadUiFont(doc, fontPath);

  try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
    beginArtifact(cs);
    cs.drawImage(pdImage, x, y, imgWidthPt, imgHeightPt);

    float panelHeight = 36f;
    float titleSize = 10f;
    float subtitleSize = 8.5f;
    String title = "Email converted by ThinkBox EMailToPDF";
    String subtitle = Common.productLabel() + "; EA-PDF 1.0 " + safeProfile + " and " + pdfaComplianceLabel() + ".";
    float titleWidth = textWidth(font, titleSize, title);
    float subtitleWidth = textWidth(font, subtitleSize, subtitle);
    float panelWidth = Math.max(titleWidth, subtitleWidth) + 24f;
    float panelX = x + Math.min(77f, Math.max(12f, imgWidthPt * 0.16f));
    float panelY = y + Math.min(53f, Math.max(10f, imgHeightPt * 0.43f));

    cs.setNonStrokingColor(new java.awt.Color(255, 255, 255));
    cs.addRect(panelX, panelY, panelWidth, panelHeight);
    cs.fill();
    cs.setStrokingColor(new java.awt.Color(205, 189, 159));
    cs.addRect(panelX, panelY, panelWidth, panelHeight);
    cs.stroke();

    cs.setNonStrokingColor(new java.awt.Color(31, 63, 91));
    FontSupport.draw(cs, font, titleSize, panelX + 12f, panelY + 22f, title);

    cs.setNonStrokingColor(new java.awt.Color(95, 85, 68));
    FontSupport.draw(cs, font, subtitleSize, panelX + 12f, panelY + 8f, subtitle);
    endArtifact(cs);
  }
}

static boolean documentHasImageXObjects(PDDocument doc) {
  for (PDPage page : doc.getPages()) {
    if (pageHasImageXObjects(page.getCOSObject())) return true;
  }
  return false;
}

static String buildFolderCollectionRenderHtml(List<CollectionMember> members, String collectionProfile, CollectionRunSummary runSummary, List<FindingAidRow> findingAidRows) {
  return PdfCollectionSupport.buildFolderCollectionRenderHtml(members, collectionProfile, runSummary, findingAidRows);
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
  if (r != null && "pst".equalsIgnoreCase(Common.nz(r.sourceType))) return "si";
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

static void setPdfA3Metadata(PDDocument doc, String iccPath, EmailRecord r) throws Exception {
  setPdfA3Metadata(doc, iccPath, r, null);
}

static void setPdfA3Metadata(PDDocument doc, String iccPath, EmailRecord r, String profileOverride) throws Exception {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  String xmp = buildXmpMetadata(r, profileOverride);
  PDMetadata meta = new PDMetadata(doc);
  meta.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
  cat.setMetadata(meta);

  try (InputStream icc = Files.newInputStream(Paths.get(iccPath))) {
    PDOutputIntent oi = new PDOutputIntent(doc, icc);
    oi.setInfo("sRGB IEC61966-2.1");
    oi.setOutputCondition("sRGB IEC61966-2.1");
    oi.setOutputConditionIdentifier("sRGB IEC61966-2.1");
    oi.setRegistryName("http://www.color.org");
    cat.addOutputIntent(oi);
  }
}

static void setPdfA3Metadata(PDDocument doc, String iccPath, List<EmailRecord> records, String archiveProfile) throws Exception {
  setPdfA3Metadata(doc, iccPath, records, archiveProfile, false);
}

static void setPdfA3Metadata(PDDocument doc, String iccPath, List<EmailRecord> records, String archiveProfile, boolean includeFindingAidIdentity) throws Exception {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  String xmp = buildXmpMetadata(records, archiveProfile, includeFindingAidIdentity);
  PDMetadata meta = new PDMetadata(doc);
  meta.importXMPMetadata(xmp.getBytes(StandardCharsets.UTF_8));
  cat.setMetadata(meta);

  try (InputStream icc = Files.newInputStream(Paths.get(iccPath))) {
    PDOutputIntent oi = new PDOutputIntent(doc, icc);
    oi.setInfo("sRGB IEC61966-2.1");
    oi.setOutputCondition("sRGB IEC61966-2.1");
    oi.setOutputConditionIdentifier("sRGB IEC61966-2.1");
    oi.setRegistryName("http://www.color.org");
    cat.addOutputIntent(oi);
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
  x.append("<dc:creator><rdf:Seq><rdf:li>").append(Common.xml(creatorTool)).append("</rdf:li></rdf:Seq></dc:creator>");
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

  appendIsoTs32005Declaration(x, creatorTool, claimDate);

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
    appendStringProp(x, "ContentType", Common.nz(af.mime));
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
  return buildXmpMetadata(records, archiveProfile, false);
}

static String buildXmpMetadata(List<EmailRecord> records, String archiveProfile, boolean includeFindingAidIdentity) throws Exception {
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
  x.append("<dc:creator><rdf:Seq><rdf:li>").append(Common.xml(creatorTool)).append("</rdf:li></rdf:Seq></dc:creator>");
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

  appendIsoTs32005Declaration(x, creatorTool, claimDate);

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
      appendStringProp(x, "NumberMessages", "1");
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
  return Common.parseMessageDate(raw) == null;
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
  outline.openNode();
}

static void addArchiveOutlines(PDDocument doc, List<MessageSegment> segments, int findingAidPages) {
  if (doc.getNumberOfPages() == 0 || segments.isEmpty()) return;
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  PDDocumentOutline outline = new PDDocumentOutline();
  cat.setDocumentOutline(outline);
  if (findingAidPages > 0) {
    PDOutlineItem findingAid = new PDOutlineItem();
    findingAid.setTitle("Finding Aid");
    setOutlineDestination(doc, findingAid, 1);
    outline.addLast(findingAid);
  }
  for (MessageSegment segment : segments) {
    PDOutlineItem item = new PDOutlineItem();
    item.setTitle(Common.nz(segment.record.subject).isBlank() ? "Email" : Common.nz(segment.record.subject));
    setOutlineDestination(doc, item, segment.startPage);
    outline.addLast(item);
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
  PDPage page = doc.getPage(Math.max(0, pageNumber - 1));
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

static void addArchiveDPartStructure(PDDocument doc, List<MessageSegment> segments, int findingAidPages) throws Exception {
  if (doc.getNumberOfPages() == 0 || segments.isEmpty()) return;
  PdfDocumentParts parts = new PdfDocumentParts(doc);
  for (MessageSegment segment : segments) {
    parts.addMessage(segment.record, segment.startPage - 1, segment.endPage - 1,
        segment == segments.getFirst() ? findingAidPages : 0);
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
  SemanticStructure.attach(doc, tree, contentSet, renderedTree, 0, doc.getNumberOfPages() - 1);
  message.setItem(COSName.K, PdfDocumentParts.indirect(doc, contentSet));
  root.setItem(COSName.K, PdfDocumentParts.indirect(doc, message));
  tree.setItem(COSName.K, PdfDocumentParts.indirect(doc, root));
  addLogicalStructureNamespaces(doc, tree);
  if (pdfUaFeaturesEnabled()) SemanticStructure.addDestinations(doc, tree);
  cat.getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(doc, tree));
}

static void addArchiveLogicalStructure(PDDocument doc, List<MessageSegment> segments, int findingAidPages) throws IOException {
  if (doc.getNumberOfPages() == 0 || segments.isEmpty()) return;
  COSDictionary tree = new COSDictionary();
  tree.setName(COSName.TYPE, "StructTreeRoot");
  COSDictionary roles = new COSDictionary();
  roles.setName(COSName.getPDFName("Mail_Message"), "Document");
  roles.setName(COSName.getPDFName("Mail_ContentGroup"), "Part");
  roles.setName(COSName.getPDFName("Mail_ContentSet"), "Art");
  tree.setItem(COSName.ROLE_MAP, roles);
  COSDictionary root = structElem("Document", tree, doc.getPage(0));
  COSArray children = new COSArray();
  for (MessageSegment segment : segments) {
    COSDictionary message = structElem("Mail_Message", root, doc.getPage(segment.startPage - 1));
    addMessageUserProperties(message, segment.record);
    COSArray content = new COSArray();
    if (findingAidPages > 0 && segment == segments.getFirst()) {
      // Keep the finding aid under the same email owner in both structure trees.
      COSDictionary front = structElem("Mail_ContentSet", message, doc.getPage(0));
      addStringUserProperty(front, "Mail_ContentSetType", "FrontMatter");
      SemanticStructure.attach(doc, tree, front, null, 0, findingAidPages - 1);
      content.add(PdfDocumentParts.indirect(doc, front));
    }
    COSDictionary group = structElem("Mail_ContentGroup", message, doc.getPage(segment.startPage - 1));
    addStringUserProperty(group, "Mail_ContentSetType", "BodyRendering");
    COSDictionary contentSet = structElem("Mail_ContentSet", group, doc.getPage(segment.startPage - 1));
    addStringUserProperty(contentSet, "Mail_ContentSetType", "BodyRendering");
    addStringUserProperty(contentSet, "Mail_Subtype", Common.contentSetSubtype(segment.record));
    SemanticStructure.attach(doc, tree, contentSet, segment.semanticTree, segment.startPage - 1, segment.endPage - 1);
    group.setItem(COSName.K, PdfDocumentParts.indirect(doc, contentSet));
    content.add(PdfDocumentParts.indirect(doc, group));
    message.setItem(COSName.K, content.size() == 1 ? content.get(0) : content);
    children.add(PdfDocumentParts.indirect(doc, message));
  }
  root.setItem(COSName.K, children);
  tree.setItem(COSName.K, PdfDocumentParts.indirect(doc, root));
  addLogicalStructureNamespaces(doc, tree);
  if (pdfUaFeaturesEnabled()) SemanticStructure.addDestinations(doc, tree);
  doc.getDocumentCatalog().getCOSObject().setItem(COSName.STRUCT_TREE_ROOT, PdfDocumentParts.indirect(doc, tree));
}

static Map<String, PDComplexFileSpecification> embedAssociatedFiles(PDDocument doc, EmailRecord r, boolean debug) throws Exception {
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
  if (files.isEmpty() && r.embedSourceMessage) {
    AssociatedFile af = new AssociatedFile();
    af.filename = Common.nz(r.sourceFilename).isBlank() ? "message.eml" : r.sourceFilename;
    af.mime = Common.nz(r.sourceMime).isBlank() ? "message/rfc822" : r.sourceMime;
    af.afRelationship = Common.nz(r.afRelationship).isBlank() ? "Source" : r.afRelationship;
    af.bytes = r.sourceBytes == null ? Common.synthesizeEml(r) : r.sourceBytes;
    af.description = Common.sourceDescription(r);
    af.checksumMd5 = Common.md5Hex(af.data());
    af.checksumSha256 = Common.sha256Hex(af.data());
    files.add(af);
    r.associatedFiles.add(af);
  }

  int n = 0;
  for (AssociatedFile af : files) {
    if (af == null || !af.hasData() || af.size() == 0) continue;

    String fname = Common.nz(af.filename).isBlank() ? ("attachment_" + String.format("%03d", ++n) + Common.extensionForMime(af.mime, af.filename)) : Common.nz(af.filename);
    String mime = Common.detectMimeType(fname, af.mime, af.head());
    String rel = normalizeAfRelationship(af.afRelationship);
    String md5 = Common.nz(af.checksumMd5).isBlank() ? Common.md5Hex(af.data()) : Common.nz(af.checksumMd5);
    String token = attachmentLaunchToken(af);

    PDComplexFileSpecification fs = new PDComplexFileSpecification();
    fs.setFile(Common.portablePdfFilename(fname));
    fs.setFileUnicode(Common.portablePdfFilename(fname));

    PDEmbeddedFile ef;
    try (InputStream data = af.openData()) {
      ef = new PDEmbeddedFile(doc, data);
    }
    ef.setSubtype(mime);
    ef.setSize(af.size());
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    setEmbeddedChecksum(ef, md5);

    COSDictionary efd = ef.getCOSObject();
    efd.setName(COSName.SUBTYPE, mime);
    efd.setInt(COSName.SIZE, af.size());

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

    String key = uniqueAttachmentKey(nameMap, fname);
    nameMap.put(key, fs);
    afArray.add(fs.getCOSObject());
    if (!token.isBlank()) filesByToken.put(token, fs);

    if (debug) Common.dbg(r, "embedded file filename=" + fname + " mime=" + mime + " afRelationship=" + rel + " bytes=" + af.size());
  }

  efs.setNames(nameMap);
  names.setEmbeddedFiles(efs);
  cat.setNames(names);
  return filesByToken;
}

static EmbeddedFileBindings embedAssociatedFiles(PDDocument doc, List<EmailRecord> records, boolean debug) throws Exception {
  PDDocumentCatalog cat = doc.getDocumentCatalog();
  COSDictionary catd = cat.getCOSObject();
  EmbeddedFileBindings bindings = new EmbeddedFileBindings();

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

  for (ArchiveEmbeddedFilePlan plan : buildArchiveEmbeddedFilePlans(records)) {
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
      ef = new PDEmbeddedFile(doc, data);
    }
    ef.setSubtype(mime);
    ef.setSize(af.size());
    Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    ef.setCreationDate(now);
    ef.setModDate(now);
    setEmbeddedChecksum(ef, md5);

    COSDictionary efd = ef.getCOSObject();
    efd.setName(COSName.SUBTYPE, mime);
    efd.setInt(COSName.SIZE, af.size());

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

    nameMap.put(uniqueAttachmentKey(nameMap, fname), fs);
    afArray.add(fs.getCOSObject());
    if ("Source".equals(rel) && plan.record != null && !bindings.sourceFiles.containsKey(plan.record)) {
      bindings.sourceFiles.put(plan.record, fs);
    }
    String token = attachmentLaunchToken(af);
    if (!token.isBlank()) bindings.filesByToken.put(token, fs);
  }

  efs.setNames(nameMap);
  names.setEmbeddedFiles(efs);
  cat.setNames(names);
  return bindings;
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
  for (AssociatedFile file : attachments) {
    if (page == null || positionY < margin + 30f) {
      page = new PDPage(PDRectangle.LETTER);
      doc.addPage(page);
      positionY = page.getMediaBox().getHeight() - margin;
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        drawText(content, font, 9f, margin, positionY, record.embedSourceMessage
            ? "Original email attachments are available from the file annotations below."
            : "These placeholders identify attachments; their native bytes are not embedded separately.");
        positionY -= 16f;
        drawText(content, font, 9f, margin, positionY, record.embedSourceMessage
            ? "The original source email is also retained in the document attachments panel."
            : "This isolated PDF does not preserve the original attachment contents.");
        positionY -= 28f;
      }
    }
    String filename = Common.portablePdfFilename(file.filename);
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
        placeholder = new PDEmbeddedFile(doc, input);
      }
      String contentType = Common.sourceContentType(file);
      placeholder.setSubtype(Common.baseMimeType(contentType));
      int parameters = contentType.indexOf(';');
      if (parameters >= 0) placeholder.getCOSObject().setString(COSName.getPDFName("Mail_MediaTypeParameters"), contentType.substring(parameters + 1).trim());
      placeholder.setSize(storedAttachmentSize(record, file));
      placeholder.setModDate(Calendar.getInstance(TimeZone.getTimeZone("UTC")));
      setEmbeddedChecksum(placeholder, record.embedSourceMessage ? file.checksumMd5 : Common.md5Hex(new byte[0]));
      specification.setEmbeddedFile(placeholder);
      specification.setEmbeddedFileUnicode(placeholder);
      annotation = inlineAttachmentBadgeAnnotation(doc, rectangle, specification, filename);
      annotation.setContents((record.embedSourceMessage ? "Original email attachment: " : "Attachment contents omitted: ") + filename);
    }
    page.getAnnotations().add(annotation);
    try (PDPageContentStream content = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
      drawText(content, font, 9f, margin + 20f, positionY, ellipsize(filename, 85));
    }
    positionY -= 22f;
  }
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
  annotation.getCOSObject().removeItem(COSName.CONTENTS);
  return annotation;
}

static PDAppearanceDictionary buildAttachmentBadgeAppearance(PDDocument doc, float width, float height) throws Exception {
  float w = Math.max(8f, width);
  float h = Math.max(6f, height);
  PDAppearanceStream appearance = new PDAppearanceStream(doc);
  appearance.setResources(new PDResources());
  appearance.setBBox(new PDRectangle(w, h));
  try (PDPageContentStream cs = new PDPageContentStream(doc, appearance)) {
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
      if (in == null) continue;
      byte[] merged = in.readAllBytes();
      if (merged.length == 0) continue;
      PDStream stream = new PDStream(doc);
      try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
        out.write(merged);
      }
      page.setContents(stream);
    }
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
      af.checksumMd5 = Common.md5Hex(af.data());
      af.checksumSha256 = Common.sha256Hex(af.data());
      pending.add(new ArchivePendingFile(r, af, af.filename));
    }
  }

  Map<String, Integer> requestedTotals = new LinkedHashMap<>();
  for (ArchivePendingFile item : pending) {
    requestedTotals.put(item.requestedName, requestedTotals.getOrDefault(item.requestedName, 0) + 1);
  }
  Map<String, Integer> requestedSeen = new LinkedHashMap<>();
  Map<String, Boolean> usedNames = new LinkedHashMap<>();
  Set<String> embeddedContentKeys = new LinkedHashSet<>();
  List<ArchiveEmbeddedFilePlan> out = new ArrayList<>();
  for (ArchivePendingFile item : pending) {
    int total = requestedTotals.getOrDefault(item.requestedName, 1);
    int seq = requestedSeen.getOrDefault(item.requestedName, 0) + 1;
    requestedSeen.put(item.requestedName, seq);
    String uniqueName = total > 1
        ? numberedAttachmentKey(usedNames, item.requestedName, seq, total)
        : uniqueAttachmentKey(usedNames, item.requestedName);
    usedNames.put(uniqueName, Boolean.TRUE);
    String mime = Common.detectMimeType(uniqueName, item.file.mime, item.file.head());
    String rel = normalizeAfRelationship(item.file.afRelationship);
    String checksumMd5 = Common.nz(item.file.checksumMd5).isBlank() ? Common.md5Hex(item.file.data()) : item.file.checksumMd5;
    String checksumSha256 = Common.nz(item.file.checksumSha256).isBlank() ? Common.sha256Hex(item.file.data()) : Common.nz(item.file.checksumSha256);
    if (!"Source".equals(rel)) {
      String contentKey = rel + "|" + mime + "|" + checksumSha256;
      if (!embeddedContentKeys.add(contentKey)) continue;
    }
    out.add(new ArchiveEmbeddedFilePlan(item.record, item.file, item.requestedName, uniqueName, mime, rel, checksumMd5));
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
          "Run stats: " + runSummary.messageCount + " messages across " + runSummary.folderCount + " folders");
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
  cs.moveTo(x1, y1);
  cs.lineTo(x2, y2);
  cs.stroke();
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
  String value = Common.nz(text).replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
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
    if (!font.hasGlyph(codePoint)) return false;
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

  PDEmbeddedFile ef = new PDEmbeddedFile(doc, new ByteArrayInputStream(bytes));
  ef.setSubtype(mime);
  ef.setSize(bytes.length);
  Calendar now = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
  ef.setCreationDate(now);
  ef.setModDate(now);
  setEmbeddedChecksum(ef, checksumMd5);

  COSDictionary efd = ef.getCOSObject();
  efd.setName(COSName.SUBTYPE, mime);
  efd.setInt(COSName.SIZE, bytes.length);
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

static String rewriteCidToFileUris(String html, List<AssociatedFile> files, EmailRecord r, boolean debug, Path assetDir) throws Exception {
  Document d = Jsoup.parse("<html><body>" + Common.stripInvalidXml(html) + "</body></html>", "", Parser.xmlParser());
  d.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  d.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  d.outputSettings().charset(StandardCharsets.UTF_8);
  d.outputSettings().prettyPrint(false);

  for (Element e : d.select("[src],[href]")) {
    String attr = e.hasAttr("src") ? "src" : "href";
    String val = Common.nz(e.attr(attr)).trim();
    if (val.isBlank()) continue;

    String cidKey = "";
    if (val.regionMatches(true, 0, "cid:", 0, 4)) cidKey = val.substring(4).trim();
    if (cidKey.isBlank()) continue;

    String normCidKey = Common.normalizeCidKey(cidKey);
    AssociatedFile hit = null;
    for (AssociatedFile af : files) {
      String cid = Common.normalizeCidKey(af.contentId);
      String fn = Common.normalizeCidKey(af.filename);
      if (!cid.isBlank() && cid.equalsIgnoreCase(normCidKey)) { hit = af; break; }
      if (!fn.isBlank() && fn.equalsIgnoreCase(normCidKey)) { hit = af; break; }
    }
    if (hit == null || !hit.hasData() || hit.size() == 0) continue;

    String mime = Common.detectMimeType(hit.filename, hit.mime, hit.data());
    String outName = "cid-" + Common.sha256Hex((normCidKey + "|" + Common.sha256Hex(hit.data())).getBytes(StandardCharsets.UTF_8))
        + Common.extensionForMime(mime, hit.filename);
    Path img = assetDir.resolve(outName);
    Files.write(img, hit.data());
    String uri = img.toUri().toString();
    r.renderAssets.put(uri, hit.data());
    e.attr(attr, uri);
    if (e.tagName().equalsIgnoreCase("img")) {
      e.attr("style", appendStyle(e.attr("style"), "display:inline-block"));
      int[] wh = sniffImageSize(hit.data());
      if (wh[0] > 0 && !e.hasAttr("width")) e.attr("width", Integer.toString(wh[0]));
      if (wh[1] > 0 && !e.hasAttr("height")) e.attr("height", Integer.toString(wh[1]));
    }
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
