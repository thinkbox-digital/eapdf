package eapdf;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import java.awt.Font;
import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.jsoup.Jsoup;

final class FontSupport {
  static final ThreadLocal<Boolean> ARCHIVE_FONTS = ThreadLocal.withInitial(() -> false);
  static void register(PdfRendererBuilder builder, File file, String family) {
    if (!ARCHIVE_FONTS.get() || family.startsWith("ArchiveFallback")) {
      builder.useFont(file, family);
      return;
    }
    // The renderer's File supplier always subsets; its stream supplier honors this flag.
    builder.useFont((com.openhtmltopdf.extend.FSSupplier<java.io.InputStream>) () -> {
      try { return java.nio.file.Files.newInputStream(file.toPath()); }
      catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }, family, 400,
        com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle.NORMAL,
        false);
  }
  // Cache metrics only; PDFont instances belong to their individual documents.
  private static final com.openhtmltopdf.extend.FSCacheEx<String, com.openhtmltopdf.extend.FSCacheValue> METRICS =
      new com.openhtmltopdf.extend.FSCacheEx<>() {
        private final Map<String, com.openhtmltopdf.extend.FSCacheValue> values = new LinkedHashMap<>(64, .75f, true);
        public synchronized void put(String key, com.openhtmltopdf.extend.FSCacheValue value) {
          if (values.size() >= 128) values.remove(values.keySet().iterator().next());
          values.put(key, value);
        }
        public synchronized com.openhtmltopdf.extend.FSCacheValue get(String key) { return values.get(key); }
        public synchronized com.openhtmltopdf.extend.FSCacheValue get(String key, java.util.concurrent.Callable<? extends com.openhtmltopdf.extend.FSCacheValue> loader) {
          var value = values.get(key);
          if (value == null) try { value = loader.call(); if (value != null) put(key, value); }
          catch (Exception error) { throw new IllegalStateException("Cannot load font metrics", error); }
          return value;
        }
      };
  private static final Map<String, Font> COVERAGE = new LinkedHashMap<>();
  private static final Map<PDType0Font, UiFonts> UI = new WeakHashMap<>();

  private static final class UiFonts {
    final WeakReference<PDDocument> document;
    final List<Path> paths;
    final Map<Path, WeakReference<PDType0Font>> loaded = new LinkedHashMap<>();

    UiFonts(PDDocument document, List<Path> paths) {
      this.document = new WeakReference<>(document);
      this.paths = paths;
    }
  }

  static List<Path> fallbacks(String primary) throws Exception {
    LinkedHashSet<Path> paths = new LinkedHashSet<>();
    Path bundled = Common.bundledAssetPath("assets/DejaVuSans.ttf", ".ttf");
    if (bundled != null) paths.add(bundled.toAbsolutePath().normalize());
    String configured = System.getProperty("eapdf.fallbackFonts", System.getenv("EAPDF_FALLBACK_FONTS"));
    if (configured != null && !configured.isBlank()) {
      for (String item : configured.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
        if (!item.isBlank()) paths.add(Path.of(item).toAbsolutePath().normalize());
      }
    }
    Path cjk = Common.bundledAssetPath("assets/DroidSansFallbackFull.ttf", ".ttf");
    if (cjk != null) paths.add(cjk.toAbsolutePath().normalize());
    Path korean = Common.bundledAssetPath("assets/NanumGothic-Regular.ttf", ".ttf");
    if (korean != null) paths.add(korean.toAbsolutePath().normalize());
    paths.remove(Path.of(primary).toAbsolutePath().normalize());
    if (paths.size() > 16) throw Common.fail("At most 16 fallback fonts may be configured");
    for (Path path : paths) if (!java.nio.file.Files.isRegularFile(path)) throw Common.fail("Fallback font not found: " + path);
    return List.copyOf(paths);
  }

  private static synchronized Font coverage(Path path) throws Exception {
    ProcessingLimits.checkFont(path);
    String key = path.toString() + ":" + java.nio.file.Files.getLastModifiedTime(path).toMillis();
    Font font = COVERAGE.get(key);
    if (font == null) {
      font = Font.createFont(Font.TRUETYPE_FONT, path.toFile());
      if (COVERAGE.size() >= 20) COVERAGE.remove(COVERAGE.keySet().iterator().next());
      COVERAGE.put(key, font);
    }
    return font;
  }

  static String configure(PdfRendererBuilder builder, String primary, String xhtml, EmailRecord record) throws Exception {
    List<Path> fonts = new ArrayList<>(List.of(Path.of(primary).toAbsolutePath().normalize()));
    fonts.addAll(fallbacks(primary));
    StringBuilder signature = new StringBuilder();
    for (Path path : fonts) {
      ProcessingLimits.checkFont(path);
      signature.append(path.toRealPath()).append(':').append(java.nio.file.Files.size(path)).append(':')
          .append(java.nio.file.Files.getLastModifiedTime(path)).append('|');
    }
    final String namespace = signature.toString();
    builder.useCacheStore(PdfRendererBuilder.CacheStore.PDF_FONT_METRICS,
        new com.openhtmltopdf.extend.FSCacheEx<String, com.openhtmltopdf.extend.FSCacheValue>() {
          public void put(String key, com.openhtmltopdf.extend.FSCacheValue value) { METRICS.put(namespace + key, value); }
          public com.openhtmltopdf.extend.FSCacheValue get(String key) { return METRICS.get(namespace + key); }
          public com.openhtmltopdf.extend.FSCacheValue get(String key, java.util.concurrent.Callable<? extends com.openhtmltopdf.extend.FSCacheValue> loader) { return METRICS.get(namespace + key, loader); }
        });
    StringBuilder families = new StringBuilder("ArchiveFont");
    for (int index = 1; index < fonts.size(); index++) {
      String family = "ArchiveFallback" + index;
      register(builder, fonts.get(index).toFile(), family);
      families.append(", ").append(family);
    }
    var document = Jsoup.parse(xhtml, "", org.jsoup.parser.Parser.xmlParser());
    document.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml);
    document.outputSettings().prettyPrint(false);
    for (var body : document.select("body")) {
      body.traverse((node, depth) -> {
        if (!(node instanceof org.jsoup.nodes.TextNode text)) return;
        var parent = text.parent();
        if (parent != null
            && !java.util.Set.of("style", "script").contains(parent.nodeName())
            && text.getWholeText().indexOf('\uFEFF') >= 0) {
          text.text(pdfText(text.getWholeText()));
        }
      });
    }
    var neededFonts = applyEmbeddedFontFamilies(document, families.toString());
    var head = document.selectFirst("head");
    if (head != null) {
      head.appendElement("style").text("html, body, body * { font-family: " + families + " !important; }");
      if (neededFonts.contains(BodyFont.MONO)) {
        Path mono = Common.bundledAssetPath("assets/DejaVuSansMono.ttf", ".ttf");
        if (mono == null) throw Common.fail("Bundled fixed-width font is missing");
        ProcessingLimits.checkFont(mono);
        register(builder, mono.toFile(), "ArchiveMono");
        head.appendElement("style").text("body .ea-plain-text, body .ea-plain-text *, body pre, body pre *, body code, body code * { font-family: ArchiveMono, "
            + families + " !important; }");
      }
      if (neededFonts.contains(BodyFont.SERIF)) {
        Path serif = Common.bundledAssetPath("assets/LiberationSerif-Regular.ttf", ".ttf");
        if (serif == null) throw Common.fail("Bundled serif font is missing");
        ProcessingLimits.checkFont(serif);
        register(builder, serif.toFile(), "ArchiveSerif");
      }
    }
    reportMissing(record, "rendered message text", document.text(), fonts);
    if (record != null) {
      reportMissing(record, "subject", Common.nz(record.subject), fonts);
      reportMissing(record, "sender", Common.nz(record.from), fonts);
      reportMissing(record, "recipients", Common.nz(record.to) + Common.nz(record.cc) + Common.nz(record.bcc), fonts);
    }
    return document.outerHtml();
  }

  static String pdfText(String text) {
    // FEFF's word-joining meaning is preserved by WORD JOINER, whose Unicode
    // value is permitted in PDF/A ToUnicode maps. Keep original email data intact.
    return Common.nz(text).replace('\uFEFF', '\u2060');
  }

  private enum BodyFont { DEFAULT, SERIF, MONO }

  private static java.util.EnumSet<BodyFont> applyEmbeddedFontFamilies(org.jsoup.nodes.Document document, String families) {
    var selected = new java.util.IdentityHashMap<org.jsoup.nodes.Element, BodyFont>();
    var requestedFamilies = sourceFontFamilies(document);
    var neededFonts = java.util.EnumSet.noneOf(BodyFont.class);
    for (var element : document.select("body, body *")) {
      BodyFont inherited = selected.getOrDefault(element.parent(), BodyFont.DEFAULT);
      BodyFont font = element.hasClass("ea-plain-text")
          || java.util.Set.of("pre", "code", "kbd", "samp", "tt").contains(element.normalName()) ? BodyFont.MONO : inherited;
      String style = element.attr("style");
      String requested = requestedFamilies.get(element);
      if (requested == null) requested = element.attr("face");
      BodyFont authored = fontFamily(requested);
      if ("inherit".equalsIgnoreCase(requested.trim())) font = inherited;
      else if (authored != null) font = authored;
      selected.put(element, font);
      neededFonts.add(font);
      String prefix = switch (font) {
        case DEFAULT -> "";
        case SERIF -> "ArchiveSerif, ";
        case MONO -> "ArchiveMono, ";
      };
      // Inline priority also defeats source !important rules that name unavailable
      // fonts. Such a rule otherwise leaves the renderer with no font and no text.
      element.attr("style", PdfMailWriter.appendStyle(style, "font-family: "
          + prefix + families + " !important"));
    }
    return neededFonts;
  }

  private static Map<org.jsoup.nodes.Element, String> sourceFontFamilies(org.jsoup.nodes.Document document) {
    com.openhtmltopdf.layout.SharedContext previous = null;
    try {
      previous = com.openhtmltopdf.util.ThreadCtx.get().sharedContext();
    } catch (NullPointerException ignored) {}
    var context = new com.openhtmltopdf.layout.SharedContext();
    context.setMedia("print");
    context.setPrint(true);
    context.setUserAgentCallback(new com.openhtmltopdf.swing.NaiveUserAgent() {
      @Override public com.openhtmltopdf.resource.ImageResource getImageResource(String uri,
          com.openhtmltopdf.outputdevice.helper.ExternalResourceType type) { return null; }
      @Override public com.openhtmltopdf.resource.CSSResource getCSSResource(String uri) {
        return new com.openhtmltopdf.resource.CSSResource(new java.io.StringReader(""));
      }
      @Override public String resolveUri(String baseUri, String uri) { return uri; }
    });
    context.registerWithThread();
    try {
      var namespace = new com.openhtmltopdf.simple.extend.XhtmlNamespaceHandler();
      context.setNamespaceHandler(namespace);
      var styles = new com.openhtmltopdf.context.StyleReference(context.getUserAgentCallback());
      context.setCss(styles);
      var dom = new org.jsoup.helper.W3CDom().fromJsoup(document);
      styles.setDocumentContext(context, namespace, dom, new com.openhtmltopdf.extend.UserInterface() {
        public boolean isHover(org.w3c.dom.Element element) { return false; }
        public boolean isActive(org.w3c.dom.Element element) { return false; }
        public boolean isFocus(org.w3c.dom.Element element) { return false; }
        public boolean isMarker(org.w3c.dom.Element element) { return false; }
      });
      Map<org.jsoup.nodes.Element, String> families = new java.util.IdentityHashMap<>();
      var elements = dom.getElementsByTagName("*");
      for (int index = 0; index < elements.getLength(); index++) {
        var element = (org.w3c.dom.Element) elements.item(index);
        var family = styles.getCascadedStyle(element, false)
            .propertyByName(com.openhtmltopdf.css.constants.CSSName.FONT_FAMILY);
        Object source = element.getUserData(org.jsoup.helper.W3CDom.SourceProperty);
        if (family != null && source instanceof org.jsoup.nodes.Element original) {
          families.put(original, family.getValue().getCssText());
        }
      }
      return families;
    } finally {
      com.openhtmltopdf.util.ThreadCtx.get().setSharedContext(previous);
    }
  }

  private static BodyFont fontFamily(String requested) {
    if (requested.isBlank()) return null;
    for (String entry : requested.split(",")) {
      String family = entry.trim().replaceAll("^[\"']|[\"']$", "").toLowerCase(java.util.Locale.ROOT);
      if (java.util.Set.of("monospace", "ui-monospace", "courier", "courier new", "consolas", "menlo", "monaco", "lucida console",
          "dejavu sans mono", "liberation mono", "archivemono").contains(family)) return BodyFont.MONO;
      if (java.util.Set.of("serif", "ui-serif", "times", "times new roman", "georgia", "cambria", "garamond", "palatino",
          "palatino linotype", "book antiqua", "liberation serif", "dejavu serif", "archiveserif").contains(family)) return BodyFont.SERIF;
      if (java.util.Set.of("sans-serif", "ui-sans-serif", "system-ui", "arial", "helvetica", "calibri", "aptos", "aptos display",
          "verdana", "tahoma", "segoe ui", "archivefont").contains(family)) return BodyFont.DEFAULT;
    }
    return null;
  }

  private static void reportMissing(EmailRecord record, String field, String text, List<Path> fonts) throws Exception {
    LinkedHashSet<String> missing = new LinkedHashSet<>();
    // Resolve each needed font once for this scan, retaining the existing size
    // and modification-time checks without repeating filesystem calls per glyph.
    Font[] resolved = new Font[fonts.size()];
    for (int codePoint : text.codePoints().distinct().toArray()) {
      if (Character.isISOControl(codePoint) || Character.getType(codePoint) == Character.FORMAT) continue;
      boolean supported = false;
      for (int index = 0; index < fonts.size(); index++) {
        if (resolved[index] == null) resolved[index] = coverage(fonts.get(index));
        if (resolved[index].canDisplay(codePoint)) {
          supported = true;
          break;
        }
      }
      if (!supported) missing.add(String.format("U+%04X", codePoint));
    }
    if (!missing.isEmpty()) Common.warn(record, "Missing font glyphs in " + field + ": "
        + String.join(", ", missing.stream().limit(24).toList()) + "; visible text may contain replacements. Configure EAPDF_FALLBACK_FONTS for additional coverage.");
  }

  static PDType0Font loadUiFont(PDDocument document, String primary) throws Exception {
    ProcessingLimits.checkFont(Path.of(primary));
    PDType0Font font = PDType0Font.load(document, new File(primary));
    synchronized (UI) {
      UI.put(font, new UiFonts(document, fallbacks(primary)));
    }
    return font;
  }

  static PDType0Font select(PDType0Font primary, int codePoint) throws Exception {
    if (PdfMailWriter.fontCanEncode(primary, codePoint)) return primary;
    UiFonts context;
    synchronized (UI) {
      context = UI.get(primary);
    }
    if (context == null) return null;
    PDDocument document = context.document.get();
    if (document == null) return null;
    for (Path path : context.paths) {
      if (!coverage(path).canDisplay(codePoint)) continue;
      WeakReference<PDType0Font> reference = context.loaded.get(path);
      PDType0Font candidate = reference == null ? null : reference.get();
      if (candidate == null) {
        candidate = PDType0Font.load(document, path.toFile());
        context.loaded.put(path, new WeakReference<>(candidate));
      }
      if (PdfMailWriter.fontCanEncode(candidate, codePoint)) return candidate;
    }
    return null;
  }

  static void draw(PDPageContentStream content, PDType0Font primary, float size, float x, float y, String text) throws Exception {
    String safe = PdfMailWriter.sanitizePdfText(primary, text);
    content.beginText();
    content.newLineAtOffset(x, y);
    PDType0Font current = null;
    StringBuilder run = new StringBuilder();
    for (int codePoint : safe.codePoints().toArray()) {
      PDType0Font selected = select(primary, codePoint);
      if (selected == null) selected = primary;
      if (selected != current) {
        if (run.length() > 0) content.showText(run.toString());
        run.setLength(0);
        content.setFont(selected, size);
        current = selected;
      }
      run.appendCodePoint(codePoint);
    }
    if (run.length() > 0) content.showText(run.toString());
    content.endText();
  }

  static float width(PDType0Font primary, float size, String text) throws Exception {
    float width = 0;
    String safe = PdfMailWriter.sanitizePdfText(primary, text);
    for (int codePoint : safe.codePoints().toArray()) {
      PDType0Font selected = select(primary, codePoint);
      if (selected == null) selected = primary;
      width += selected.getStringWidth(new String(Character.toChars(codePoint))) / 1000f * size;
    }
    return width;
  }
}
