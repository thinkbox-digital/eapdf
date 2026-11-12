package eapdf;
import eapdf.PdfAttachmentSupport.*;
import eapdf.PdfCollectionSupport.*;


import org.apache.pdfbox.Loader;
import java.io.*;
import java.awt.image.BufferedImage;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import com.openhtmltopdf.extend.FSStream;
import com.openhtmltopdf.extend.FSStreamFactory;
import com.openhtmltopdf.extend.FSUriResolver;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.parser.*;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
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
static final PdfMetadataWriter METADATA = new PdfMetadataWriter(CONFIG, new PdfMetadataWriter.Assets() {
  public boolean include(EmailRecord r, AssociatedFile f) { return includeAssociatedFile(r,f); }
  public int size(EmailRecord r, AssociatedFile f) { return storedAttachmentSize(r,f); }
  public String mime(AssociatedFile f, String name) throws Exception { return embeddedAssetContentType(f,name); }
  public String description(EmailRecord r, AssociatedFile f) { return attachmentDescription(r,f); }
  public int folders(List<EmailRecord> records) { return PdfCollectionSupport.buildFolderSections(records).size(); }
  public List<PdfMetadataWriter.Asset> archive(List<EmailRecord> records) throws Exception {
    return buildArchiveEmbeddedFilePlans(records).stream().map(p -> new PdfMetadataWriter.Asset(p.filename,p.file.size(),p.mime,p.checksumMd5,p.relationship,p.file.sourceSetMember)).toList();
  }
});
static final MailHtmlRenderer HTML = new MailHtmlRenderer(PdfMailWriter::pdfUaFeaturesEnabled, PdfMailWriter::chooseEaPdfConformance, PdfMailWriter::renderAssociatedFiles);
static final String EMBEDDED_LINK_PREFIX = PdfAttachmentSupport.EMBEDDED_LINK_PREFIX;
static final String EMBEDDED_BADGE_LINK_PREFIX = PdfAttachmentSupport.EMBEDDED_BADGE_LINK_PREFIX;
static final Set<String> PDF17_STRUCTURE_ROLES = Set.of("Art", "BlockQuote", "TOC", "TOCI", "Index", "Private", "Quote", "Note", "Reference", "BibEntry", "Code");
static final ConcurrentMap<String, String> FAILED_REMOTE_ASSET_URLS = MailRenderPolicy.FAILED_REMOTE_ASSET_URLS;
static final FSUriResolver REMOTE_ASSET_URI_RESOLVER = MailRenderPolicy.REMOTE_ASSET_URI_RESOLVER;
static final FSStreamFactory REMOTE_ASSET_STREAM_FACTORY = MailRenderPolicy.REMOTE_ASSET_STREAM_FACTORY;
static final InheritableThreadLocal<EmailRecord> ACTIVE_RENDER_RECORD = MailRenderPolicy.ACTIVE_RENDER_RECORD;
static final ThreadLocal<Set<String>> RENDERED_IMAGES = MailRenderPolicy.RENDERED_IMAGES;
static final ThreadLocal<Map<String, String>> ORIENTED_IMAGE_URIS = MailRenderPolicy.ORIENTED_IMAGE_URIS;

static {
  // Lossless compression only; preserve an explicit PDFBox override.
  System.getProperties().putIfAbsent("org.apache.pdfbox.filter.deflatelevel", "9");
  configureRendererLogging();
}

static boolean isAllowedRenderResource(String uri) {
  return MailRenderPolicy.isAllowedRenderResource(uri);
}

static byte[] decodeRenderImageUri(String uri) {
  return MailRenderPolicy.decodeRenderImageUri(uri);
}

static String orientRenderImage(String uri) {
  return MailRenderPolicy.orientRenderImage(uri);
}

static void checkRenderImage(byte[] bytes) {
  MailRenderPolicy.checkRenderImage(bytes);
}

static FSStream trustedRenderAsset(String uri) {
  return MailRenderPolicy.trustedRenderAsset(uri);
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
      if (sourceBytes.length == 0) throw new IllegalStateException("Source message is empty");
      {
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
    } catch (Exception error) {
      throw new IllegalStateException("Could not prepare source email for PDF rendering: " + error.getMessage(), error);
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
  return HTML.nativeAttachmentHtml(record, file);
}

static String attachmentDisplayHtml(AssociatedFile af) {
  return HTML.attachmentDisplayHtml(af);
}

static String attachmentTextWithBadgeHtml(AssociatedFile af) {
  return HTML.attachmentTextWithBadgeHtml(af);
}

static String pdfaComplianceLabel() {
  return "PDF/A-4f";
}

static byte[] renderHtmlPdf(String html, String baseUri, String fontPath, EmailRecord r, boolean debug) throws Exception {
  return HTML.renderHtmlPdf(html, baseUri, fontPath, r, debug);
}

interface XhtmlRenderer extends MailHtmlRenderer.XhtmlRenderer {}

static byte[] renderHtmlPdf(String html, String baseUri, String fontPath, EmailRecord r, boolean debug, XhtmlRenderer renderer) throws Exception {
  return HTML.renderHtmlPdf(html, baseUri, fontPath, r, debug, renderer);
}

static byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath) throws Exception {
  return HTML.renderXhtmlPdf(xhtml, baseUri, fontPath);
}

static byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath, boolean fitWideContent) throws Exception {
  return HTML.renderXhtmlPdf(xhtml, baseUri, fontPath, fitWideContent);
}

static String buildPlainTextFallbackXhtml(String html) throws Exception {
  return HTML.buildPlainTextFallbackXhtml(html);
}

static String messagePageStyle(EmailRecord record) throws Exception {
  return HTML.messagePageStyle(record);
}

static int detectPrimaryContentWidthPx(String emailHtml) {
  return HTML.detectPrimaryContentWidthPx(emailHtml);
}

static Integer extractElementWidthPx(Element el) {
  return HTML.extractElementWidthPx(el);
}

static String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir) throws Exception {
  return HTML.buildRenderHtml(r, debug, assetDir);
}

static String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir, String profileOverride) throws Exception {
  return HTML.buildRenderHtml(r, debug, assetDir, profileOverride);
}

static String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir, String profileOverride, String archiveSourceName) throws Exception {
  return HTML.buildRenderHtml(r, debug, assetDir, profileOverride, archiveSourceName);
}

static String canonicalizeEmailHtml(String html, EmailRecord r, boolean debug) {
  return HTML.canonicalizeEmailHtml(html, r, debug);
}



static MailHtmlRenderer.BodyColors legacyBodyColorStyles(Element body) {
  return HTML.legacyBodyColorStyles(body);
}

static void trimTrailingEmptyContent(Element root) {
  HTML.trimTrailingEmptyContent(root);
}

static void linkifyBodyText(Element root) {
  HTML.linkifyBodyText(root);
}

static String normalizeRemoteAssetUrl(String raw) {
  return MailRenderPolicy.normalizeRemoteAssetUrl(raw);
}

static String remoteAssetKey(String raw) {
  return MailRenderPolicy.remoteAssetKey(raw);
}

static byte[] readAllBytes(Reader reader) throws IOException {
  StringBuilder out = new StringBuilder();
  char[] buf = new char[4096];
  for (int n; (n = reader.read(buf)) != -1;) out.append(buf, 0, n);
  return out.toString().getBytes(StandardCharsets.UTF_8);
}

// This affects rendered links only. The original source and its headers/body stay intact.
static String safeRenderedHref(String original, EmailRecord record) {
  return MailRenderPolicy.safeRenderedHref(original, record);
}

static boolean shouldBlacklistRemoteAssetResponse(String key, byte[] bytes) {
  return MailRenderPolicy.shouldBlacklistRemoteAssetResponse(key, bytes);
}

static String describeRemoteAssetResponse(String key, byte[] bytes) {
  return MailRenderPolicy.describeRemoteAssetResponse(key, bytes);
}

static boolean looksLikeHtmlResponse(byte[] bytes) {
  return MailRenderPolicy.looksLikeHtmlResponse(bytes);
}

static boolean isLikelyRemoteImageUrl(String key) {
  return MailRenderPolicy.isLikelyRemoteImageUrl(key);
}

static boolean looksLikeSvg(byte[] bytes) {
  return MailRenderPolicy.looksLikeSvg(bytes);
}

static boolean looksLikeSupportedImage(byte[] bytes) {
  return MailRenderPolicy.looksLikeSupportedImage(bytes);
}

static boolean shouldBlacklistRemoteAssetFailure(Throwable error) {
  return MailRenderPolicy.shouldBlacklistRemoteAssetFailure(error);
}

static String describeRemoteAssetFailure(Throwable error) {
  return MailRenderPolicy.describeRemoteAssetFailure(error);
}

static void configureRendererLogging() {
  MailRenderPolicy.configureRendererLogging();
}

static void blacklistRemoteAsset(String key, String reason) {
  MailRenderPolicy.blacklistRemoteAsset(key, reason);
}

static void recordBlacklistedRemoteAssetEncounter(String key) {
  MailRenderPolicy.recordBlacklistedRemoteAssetEncounter(key);
}

static String preferRemoteAssetReason(String existing, String replacement) {
  return MailRenderPolicy.preferRemoteAssetReason(existing, replacement);
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
  ConversionProgress.stage("rendering");
  List<CollectionMember> members = PdfCollectionSupport.buildFolderCollectionMembers(sections, folders, fontPath, iccPath, debug, debugDir, collectionProfile, memberStore);
  ConversionProgress.stage("assembling");
  if (members.isEmpty()) throw new IllegalArgumentException("collection requires at least one embedded EA-PDF member");
  try (PDDocument archive = PdfCollectionSupport.buildCollectionStartPage(records,
      collectionProfile, fontPath, debug)) {
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
  return METADATA.pdfUaFeaturesEnabled();
}

static void appendSourceProblems(StringBuilder x, List<EmailRecord> records) {
  METADATA.appendSourceProblems(x, records);
}

static String pdfUaIdentification() {
  return METADATA.pdfUaIdentification();
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
  return METADATA.chooseEaPdfConformance(r);
}

static String chooseEaPdfConformance(EmailRecord r, String profileOverride) {
  return METADATA.chooseEaPdfConformance(r, profileOverride);
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
  return METADATA.buildXmpMetadata(r);
}

static String buildXmpMetadata(EmailRecord r, String profileOverride) throws Exception {
  return METADATA.buildXmpMetadata(r, profileOverride);
}



static String buildXmpMetadata(List<EmailRecord> records, String archiveProfile) throws Exception {
  return METADATA.buildXmpMetadata(records, archiveProfile);
}

static String buildDescription(EmailRecord r) {
  return METADATA.buildDescription(r);
}

static void appendReplyMetadata(StringBuilder metadata, EmailRecord record) {
  METADATA.appendReplyMetadata(metadata, record);
}

static String buildArchiveDescription(List<EmailRecord> records, String archiveProfile) {
  return METADATA.buildArchiveDescription(records, archiveProfile);
}

static String buildArchiveTitle(List<EmailRecord> records) {
  return METADATA.buildArchiveTitle(records);
}

static String buildMailGuid(EmailRecord r) throws Exception {
  return METADATA.buildMailGuid(r);
}

static void appendStringProp(StringBuilder x, String prop, String value) {
  METADATA.appendStringProp(x, prop, value);
}

static void appendFoafSeq(StringBuilder x, String prop, List<MailAgent> agents) {
  METADATA.appendFoafSeq(x, prop, agents);
}

static boolean shouldEmitRawSent(EmailRecord r) {
  return METADATA.shouldEmitRawSent(r);
}

static int messageMetadataSize(EmailRecord r) {
  return METADATA.messageMetadataSize(r);
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
  METADATA.appendIsoTs32005Declaration(x, claimBy, claimDate);
}

static void appendConversionHistory(StringBuilder x, String tool, String timestamp, String profile, int messages) {
  METADATA.appendConversionHistory(x, tool, timestamp, profile, messages);
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
  return PdfAttachmentSupport.embeddedAssetContentType(file, filename);
}

static void setEmbeddedMediaType(PDEmbeddedFile file, String contentType) {
  PdfAttachmentSupport.setEmbeddedMediaType(file, contentType);
}

static int storedAttachmentSize(EmailRecord record, AssociatedFile file) {
  return record.embedSourceMessage ? file.size() : 0;
}

static void setEmbeddedChecksum(PDEmbeddedFile file, String hexadecimal) {
  PdfAttachmentSupport.setEmbeddedChecksum(file, hexadecimal);
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
  return PdfAttachmentSupport.inlineAttachmentBadgeAnnotation(doc, rect, fileSpec, contents);
}

static PDAppearanceDictionary buildAttachmentBadgeAppearance(PDDocument doc, float width, float height) throws Exception {
  return PdfAttachmentSupport.buildAttachmentBadgeAppearance(doc, width, height);
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
  return PdfAttachmentSupport.buildArchiveEmbeddedFilePlans(records, PdfMailWriter::pdfEmbeddedFiles);
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
  return PdfAttachmentSupport.reserveAttachmentKey(normalizedNames, name);
}

static String uniqueAttachmentKey(Map<String, ?> names, String name) {
  return PdfAttachmentSupport.uniqueAttachmentKey(names, name);
}

static String numberedAttachmentKey(Map<String, ?> names, String name, int seq, int total) {
  return PdfAttachmentSupport.numberedAttachmentKey(names, name, seq, total);
}

static String normalizeAfRelationship(String s) {
  return PdfAttachmentSupport.normalizeAfRelationship(s);
}

static AssociatedFile findLocationAsset(String uri, EmailRecord record) {
  return MailRenderPolicy.findLocationAsset(uri, record);
}

static boolean sameEmbeddedLocation(String value, String location, String base) {
  return MailRenderPolicy.sameEmbeddedLocation(value, location, base);
}

static AssociatedFile findCidAsset(String value, List<AssociatedFile> files) {
  return MailRenderPolicy.findCidAsset(value, files);
}

static String rewriteCidToFileUris(String html, List<AssociatedFile> files, EmailRecord r, boolean debug, Path assetDir) throws Exception {
  return HTML.rewriteCidToFileUris(html, files, r, debug, assetDir);
}

static int[] sniffImageSize(byte[] bytes) {
  return MailRenderPolicy.sniffImageSize(bytes);
}

static boolean looksEmpty(String html) {
  return HTML.looksEmpty(html);
}

static boolean shouldBoldHeaderLabel(String label) {
  return HTML.shouldBoldHeaderLabel(label);
}

static void appendHeaderRow(StringBuilder s, String label, String value) {
  HTML.appendHeaderRow(s, label, value);
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
