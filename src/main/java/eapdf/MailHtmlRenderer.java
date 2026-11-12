package eapdf;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;
import org.jsoup.*;
import org.jsoup.nodes.*;
import org.jsoup.parser.*;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import static eapdf.MailRenderPolicy.*;

/** HTML preparation and rendering; document assembly and publication stay with the writer. */
final class MailHtmlRenderer {
 private final BooleanSupplier accessibility;
 private final BiFunction<EmailRecord,String,String> profile;
 private final Function<EmailRecord,List<AssociatedFile>> displayFiles;
 MailHtmlRenderer(BooleanSupplier accessibility, BiFunction<EmailRecord,String,String> profile,
     Function<EmailRecord,List<AssociatedFile>> displayFiles) {
   this.accessibility=accessibility;this.profile=profile;this.displayFiles=displayFiles;
 }
 private boolean pdfUaFeaturesEnabled(){return accessibility.getAsBoolean();}
 interface XhtmlRenderer {byte[] render(String xhtml,String baseUri,String fontPath)throws Exception;}
 record BodyColors(String css,boolean background){}
String nativeAttachmentHtml(EmailRecord record, AssociatedFile file) {
  return attachmentDisplayHtml(file);
}

String attachmentDisplayHtml(AssociatedFile af) {
  return Common.escapeHtml(Common.nz(af.filename));
}

String attachmentTextWithBadgeHtml(AssociatedFile af) {
  if (af == null) return "";
  return attachmentDisplayHtml(af);
}

byte[] renderHtmlPdf(String html, String baseUri, String fontPath, EmailRecord r, boolean debug) throws Exception {
  return renderHtmlPdf(html, baseUri, fontPath, r, debug, this::renderXhtmlPdf);
}

byte[] renderHtmlPdf(String html, String baseUri, String fontPath, EmailRecord r, boolean debug, XhtmlRenderer renderer) throws Exception {
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

byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath) throws Exception {
  return renderXhtmlPdf(xhtml, baseUri, fontPath, true);
}

byte[] renderXhtmlPdf(String xhtml, String baseUri, String fontPath, boolean fitWideContent) throws Exception {
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
  builder.useProtocolsStreamImplementation(MailRenderPolicy::trustedRenderAsset, "file", "eapdf-image");
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

String buildPlainTextFallbackXhtml(String html) throws Exception {
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

String messagePageStyle(EmailRecord record) throws Exception {
  return "@page { size: letter; margin: 0.65in; }";
}

int detectPrimaryContentWidthPx(String emailHtml) {
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

Integer extractElementWidthPx(Element el) {
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

String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir) throws Exception {
  return buildRenderHtml(r, debug, assetDir, null);
}

String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir, String profileOverride) throws Exception {
  return buildRenderHtml(r, debug, assetDir, profileOverride, "");
}

String buildRenderHtml(EmailRecord r, boolean debug, Path assetDir, String profileOverride, String archiveSourceName) throws Exception {
  if (Common.nz(r.headerTagMarker).isBlank()) r.headerTagMarker = "EA-PDF-" + UUID.randomUUID() + ":";
  for (String warning : r.conversionWarnings) System.err.println("warning: " + warning);
  r.embedSourceMessage = !profile.apply(r, profileOverride).endsWith("i");
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
  List<AssociatedFile> displayFiles = this.displayFiles.apply(r);
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

String canonicalizeEmailHtml(String html, EmailRecord r, boolean debug) {
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
  String inner = body.html();

  Document wrap = Jsoup.parse("", "", Parser.xmlParser());
  wrap.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
  wrap.outputSettings().escapeMode(Entities.EscapeMode.xhtml);
  wrap.outputSettings().charset(StandardCharsets.UTF_8);
  wrap.outputSettings().prettyPrint(false);

  Element root = new Element("div").attr("id", "email-root");
  wrap.appendChild(root);
  Element sourceBody = root.appendElement("div").addClass("ea-email-body");
  body.attributes().forEach(attribute -> sourceBody.attr(attribute.getKey(), attribute.getValue()));
  sourceBody.addClass("ea-email-body");
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

BodyColors legacyBodyColorStyles(Element body) {
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

void trimTrailingEmptyContent(Element root) {
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

void linkifyBodyText(Element root) {
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

String rewriteCidToFileUris(String html, List<AssociatedFile> files, EmailRecord r, boolean debug, Path assetDir) throws Exception {
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

boolean looksEmpty(String html) {
  Document document = Jsoup.parse(Common.nz(html));
  return document.text().isBlank() && document.select("img,svg,object,video,audio,canvas,[background],[style*=background]").isEmpty();
}

boolean shouldBoldHeaderLabel(String label) {
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

void appendHeaderRow(StringBuilder s, String label, String value) {
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
}
