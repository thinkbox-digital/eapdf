package eapdf;

import static eapdf.EaPdfChecks.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.contentstream.PDContentStream;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.w3c.dom.Element;

/** Parses actual PDF text, paint and marked content, including nested Form XObjects. */
final class PageContentChecks {
  record Key(COSDictionary scope, int mcid) {}
  record Location(COSDictionary scope, int instruction) {}
  static final class Sequence {
    final String tag;
    final Key key;
    final int instruction;
    boolean text;
    Sequence(String tag, Key key, int instruction) { this.tag = tag; this.key = key; this.instruction = instruction; }
  }
  static final class Page {
    boolean paint;
    boolean text;
    boolean pointCustom;
    int unmarked;
    int structuredPaint;
    final List<Sequence> sequences = new ArrayList<>();
  }
  private final PDDocument document;
  private final JavaValidation.Report report;
  private final Map<COSDictionary, Page> pages = new IdentityHashMap<>();
  private final Map<Key, List<COSDictionary>> owners = new HashMap<>();
  private final Map<Integer, COSBase> parentTree = new HashMap<>();
  private final Map<COSDictionary, Set<COSDictionary>> scopePages = new IdentityHashMap<>();
  private final Map<COSDictionary, COSDictionary> objectOwners = new IdentityHashMap<>();
  private final Map<COSDictionary, COSDictionary> objectPages = new IdentityHashMap<>();
  private final Map<COSDictionary, Set<COSDictionary>> objectLocations = new IdentityHashMap<>();
  private final Map<COSStream, COSDictionary> pageStreams = new IdentityHashMap<>();
  private final Set<COSDictionary> structureSeen = identitySet();
  private final Set<COSDictionary> uncertainStructure = identitySet();
  private final Set<COSDictionary> formActive = identitySet();
  private final List<String> errors = new ArrayList<>();
  private long count;
  private final long instructionLimit;
  private final Map<COSDictionary, Integer> pagePositions = new IdentityHashMap<>();
  private int[] paintedThrough;
  private COSDictionary currentPage;
  private final List<Element> emails;
  private final Element xml;
  private PageTextStore pageTexts;
  private final Map<COSDictionary, Set<String>> independentEmailOwners = new IdentityHashMap<>();
  private final Set<COSDictionary> documentWideParts = identitySet();
  private final Map<String, Integer> emailGuidCounts = new HashMap<>();

  PageContentChecks(PDDocument document, JavaValidation.Report report) { this(document, report, List.of()); }
  PageContentChecks(PDDocument document, JavaValidation.Report report, List<Element> emails) {
    this(document, report, emails, null);
  }
  PageContentChecks(PDDocument document, JavaValidation.Report report, List<Element> emails, Element xml) {
    this.document = document; this.report = report; this.emails = emails; this.xml = xml;
    instructionLimit = InspectionBudget.instructions(document);
    for (Element email : emails) if (simple(fieldElement(email, "Mail_GUID")))
      emailGuidCounts.merge(field(email, "Mail_GUID"), 1, Integer::sum);
  }

  static String normalizedUrl(String value) {
    String text = value.strip().replaceAll("\\s+", "");
    if (text.toLowerCase(java.util.Locale.ROOT).startsWith("www.")) text = "http://" + text;
    try {
      var uri = java.net.URI.create(text);
      if (uri.getScheme() == null) return text.toLowerCase(java.util.Locale.ROOT);
      String path = uri.getRawPath() == null ? "" : uri.getRawPath().replaceFirst("/+$", "");
      if (uri.isOpaque()) return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + ":" + uri.getRawSchemeSpecificPart();
      return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + ":"
          + (uri.getRawAuthority() == null ? "" : "//" + uri.getRawAuthority().toLowerCase(java.util.Locale.ROOT))
          + path + (uri.getRawQuery() == null || uri.getRawQuery().isEmpty() ? "" : "?" + uri.getRawQuery());
    } catch (IllegalArgumentException invalid) { return text.toLowerCase(java.util.Locale.ROOT); }
  }

  static Set<String> annotationUriCandidates(String value) {
    var candidates = new HashSet<String>();
    String normalized = normalizedUrl(value);
    if (!normalized.isEmpty()) candidates.add(normalized);
    try {
      String query = java.net.URI.create(value.strip()).getRawQuery();
      if (query != null) for (String pair : query.split("&")) {
        String[] parts = pair.split("=", 2);
        String key = java.net.URLDecoder.decode(parts[0], java.nio.charset.StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT);
        if (parts.length != 2 || !Set.of("url", "u", "target", "redirect", "redirect_uri").contains(key)) continue;
        String destination = java.net.URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8);
        destination = java.net.URLDecoder.decode(destination.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        String extra = normalizedUrl(destination);
        if (!extra.isEmpty()) candidates.add(extra);
      }
    } catch (IllegalArgumentException invalid) { /* The literal URI remains available. */ }
    return candidates;
  }

  static boolean urlHasMatchingAnnotation(String value, Set<String> candidates, String text) {
    String normalized = normalizedUrl(value);
    if (candidates.contains(normalized)) return true;
    var breaks = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(value) + "[ \t]*\\r?\\n").matcher(text);
    while (breaks.find()) {
      String continuation = text.substring(breaks.end()).lines().limit(2).collect(java.util.stream.Collectors.joining()).replaceAll("\\s+", "");
      for (String candidate : candidates) if (candidate.startsWith(normalized) && !candidate.equals(normalized)
          && continuation.contains(candidate.substring(normalized.length()))) return true;
    }
    return false;
  }

  ContentOwnership.Evidence run() throws IOException {
    try (PageTextStore texts = new PageTextStore()) {
      pageTexts = texts;
      inspectPages();
    } finally { pageTexts = null; }
    return new ContentOwnership.Evidence(independentEmailOwners, documentWideParts);
  }

  private void inspectPages() throws IOException {
    boolean anyFont = false;
    int number = 0;
    // One document walk retains the same per-page text. Calling getText for
    // every page repeatedly walks the entire page tree on large archives.
    java.io.StringWriter pageText = new java.io.StringWriter();
    PDFTextStripper stripper = new PDFTextStripper() {
      @Override protected void startPage(PDPage page) throws IOException {
        pageText.getBuffer().setLength(0);
        super.startPage(page);
      }
      @Override protected void endPage(PDPage page) throws IOException {
        super.endPage(page);
        pageTexts.put(page.getCOSObject(), pageText.toString());
      }
    };
    stripper.writeText(document, pageText);
    paintedThrough = new int[document.getNumberOfPages() + 1];
    for (PDPage page : document.getPages()) {
      pagePositions.put(page.getCOSObject(), number);
      number++;
      currentPage = page.getCOSObject();
      Page analysis = new Page(); pages.put(page.getCOSObject(), analysis);
      COSBase content = get(page.getCOSObject(), "Contents");
      if (content instanceof COSStream stream) pageStreams.put(stream, page.getCOSObject());
      else if (content instanceof COSArray array) for (COSBase value : array) if (resolve(value) instanceof COSStream stream) pageStreams.put(stream, page.getCOSObject());
      try { parse(page, page.getResources(), page.getCOSObject(), analysis, new ArrayList<>(), 0); }
      catch (IOException invalid) { errors.add("Page " + number + ": " + invalid.getMessage()); }
      if (!page.getAnnotations().isEmpty()) analysis.paint = true;
      paintedThrough[number] = paintedThrough[number - 1] + (analysis.paint ? 1 : 0);
      for (var annotation : page.getAnnotations()) {
        objectPages.put(annotation.getCOSObject(), page.getCOSObject());
        objectLocations.computeIfAbsent(annotation.getCOSObject(), ignored -> identitySet()).add(page.getCOSObject());
      }
      anyFont |= page.getResources() != null && page.getResources().getFontNames().iterator().hasNext();
      String text = pageTexts.get(page.getCOSObject());
      var uris = new HashSet<String>();
      for (var annotation : page.getAnnotations()) {
        COSDictionary action = dict(annotation.getCOSObject(), "A");
        if ("Link".equals(name(get(annotation.getCOSObject(), "Subtype"))) && "URI".equals(name(get(action, "S")))) uris.addAll(annotationUriCandidates(string(get(action, "URI"))));
      }
      var urls = java.util.regex.Pattern.compile("(?i)\\b(?:https?://|mailto:|www\\.)[^\\s<>]+").matcher(text);
      List<String> unmatched = new ArrayList<>(); boolean anyUrl = false;
      while (urls.find()) {
        anyUrl = true;
        String url = urls.group().replaceFirst("[.,;:!?)]*$", "");
        if (!urlHasMatchingAnnotation(url, uris, text)) unmatched.add(url);
      }
      if (anyUrl && unmatched.isEmpty()) report.check("ACTIONABLE_URLS_LINK_ANNOTATIONS", true, "URL-like text has matching Link annotations.", Map.of("page", number));
      if (!unmatched.isEmpty()) report.recommend("ACTIONABLE_URLS_LINK_ANNOTATIONS", false, "URL-like text may need a matching Link annotation under the chosen action policy.", Map.of("page", number, "urls", unmatched));

    }
    pageContext();
    report.supported("PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED");
    report.supported("ACTIONABLE_URLS_LINK_ANNOTATIONS");
    List<Page> painted = pages.values().stream().filter(page -> page.paint).toList();
    if (painted.isEmpty()) report.na("TEXT_CONTENT_PDF_TEXT_OBJECTS", "There is no visible page content to assess.", null);
    else report.recommend("TEXT_CONTENT_PDF_TEXT_OBJECTS", painted.stream().allMatch(page -> page.text), "Nonblank pages should use PDF text objects for textual content.", null);
    if (!anyFont) report.recommend("FONTS_NONE", false, "No fonts were found in page resources.", null);
    else report.na("FONTS_NONE", "Page resources contain fonts; used-font conformance is checked by veraPDF.", null);
    Set<COSDictionary> visited = identitySet();
    COSDictionary root = dict(document.getDocumentCatalog().getCOSObject(), "DPartRoot");
    for (COSDictionary leaf : AttachmentChecks.leaves(document)) contentSets(leaf, visited, 0);
    COSDictionary structure = dict(document.getDocumentCatalog().getCOSObject(), "StructTreeRoot");
    if (structure != null) {
      try {
        structure(structure.getItem(COSName.K), structure, null, 0);
        numbers(dict(structure, "ParentTree"), identitySet(), 0);
      } catch (IOException invalid) { errors.add(invalid.getMessage()); }
      for (var entry : objectOwners.entrySet()) {
        COSBase key = get(entry.getKey(), "StructParent");
        if (!(key instanceof COSInteger integer) || parentTree.get(integer.intValue()) != entry.getValue()) errors.add("OBJR ParentTree entry does not identify its owner");
      }
      for (var entry : pages.entrySet()) for (Sequence sequence : entry.getValue().sequences) {
        if (sequence.key == null) continue;
        List<COSDictionary> matches = owners.getOrDefault(sequence.key, List.of());
        if (matches.size() != 1) { errors.add("Marked content has no unique structure owner: MCID " + sequence.key.mcid()); continue; }
        COSDictionary owner = matches.getFirst();
        COSBase key = get(sequence.key.scope(), "StructParents");
        COSBase parent = key instanceof COSInteger integer ? parentTree.get(integer.intValue()) : null;
        if (!(parent instanceof COSArray array) || sequence.key.mcid() >= array.size() || array.getObject(sequence.key.mcid()) != owner) errors.add("ParentTree does not identify the marked-content owner");
      }
      Set<Key> actual = new HashSet<>(); Set<Location> locations = new HashSet<>();
      for (Page page : pages.values()) for (Sequence sequence : page.sequences) if (sequence.key != null
          && locations.add(new Location(sequence.key.scope(), sequence.instruction)) && !actual.add(sequence.key))
        errors.add("Duplicate MCID in the content scope");
      parentTreeEntries(structure, actual);
      for (Key key : owners.keySet()) if (!actual.contains(key)) {
        errors.add("Structure references missing marked content: MCID " + key.mcid());
        for (COSDictionary owner : owners.get(key)) markUncertain(owner);
      }
      Set<COSDictionary> connected = identitySet();
      for (var entry : owners.entrySet()) if (actual.contains(entry.getKey()))
        for (COSDictionary owner : entry.getValue()) connectAncestors(connected, owner, structure);
      for (var entry : objectOwners.entrySet()) if (objectLocations.containsKey(entry.getKey())) connectAncestors(connected, entry.getValue(), structure);
      for (COSDictionary element : structureSeen) if (Set.of("Mail_Message", "Mail_ContentGroup", "Mail_ContentSet").contains(name(get(element, "S"))) && !connected.contains(element))
        errors.add("EA-PDF structure element has no verified content connection: " + name(get(element, "S")));
      report.check("STRUCTURE_CORRESPONDENCE", errors.isEmpty(), "Marked content, structure references and ParentTree must agree in both directions.", Map.of("errors", errors));
      relations(structure);
    } else if (pages.values().stream().flatMap(page -> page.sequences.stream()).anyMatch(sequence -> sequence.key != null) || !errors.isEmpty()) {
      errors.add("Marked content has MCIDs but no StructTreeRoot");
      report.check("STRUCTURE_CORRESPONDENCE", false, "Content markings require valid logical structure.", Map.of("errors", errors));
    }
    if (structureSeen.stream().anyMatch(element -> name(get(element, "S")).startsWith("Mail_")))
      report.check("CUSTOM_TAG_MARKED_SEQUENCE_ONLY", pages.values().stream().noneMatch(page -> page.pointCustom), "Custom Mail_ tags must mark sequences, not points.", null);
    else if (!report.seen.contains("CUSTOM_TAG_MARKED_SEQUENCE_ONLY")) report.na("CUSTOM_TAG_MARKED_SEQUENCE_ONLY", "No custom EA-PDF structure tags activate this check.", null);
    coreFields();
    customOperands();
    COSDictionary markInfo = dict(document.getDocumentCatalog().getCOSObject(), "MarkInfo");
    boolean taggedCoverage = !AttachmentChecks.leaves(document).isEmpty() && structure != null && get(markInfo, "Marked") == COSBoolean.TRUE && errors.isEmpty()
        && pages.values().stream().allMatch(page -> page.unmarked == 0 && page.structuredPaint > 0)
        && objectPages.keySet().stream().allMatch(objectOwners::containsKey);
    report.recommend("CUSTOM_CONTENTSETS_TAGGEDPDF_RECOMMENDED", taggedCoverage, "Declared content-set pages should have connected structure, tagged paint and annotation references.", null);
    if (structureSeen.stream().anyMatch(element -> name(get(element, "S")).startsWith("Mail_")))
      report.add("WARN", "HEADER_FIELDS_TAGGED_RECOMMENDED", "Complete rendered-header tagging needs a source-to-rendered-field inventory.", Map.of("assessment", "unavailable", "strength", "SHOULD"));
    else report.na("HEADER_FIELDS_TAGGED_RECOMMENDED", "No custom EA-PDF tags activate this conditional recommendation.", null);
    for (String code : List.of("UNSPECIFIED_COLOR_SRGB_ASSUMED", "REFERENCED_ASSETS_AS_ASSOCIATED_FILES", "REFERENCED_ASSETS_ASSOCIATED_TO_APPROPRIATE_OBJECT", "LOGICAL_STRUCTURE_EQUIVALENCE_RECOMMENDED")) {
      report.add("WARN", code, "This recommendation needs original-source and conversion-policy evidence.", Map.of("assessment", "unavailable", "strength", "SHOULD"));
    }
    report.supported("CUSTOM_TAG_OPERAND_MATCH");
    if (Set.of("c", "ci").contains(report.profile) && !report.seen.contains("CORE_FIELDS_TEXT_OBJECTS")) {
      report.na("CORE_FIELDS_TEXT_OBJECTS", "The outer collection has no rendered email Core Fields; those are checked in its children.", null);
    }
  }

  private void pageContext() {
    Map<COSDictionary, List<String>> candidates = new IdentityHashMap<>();
    Map<String, Integer> frequency = new HashMap<>();
    for (var page : pageTexts.pages()) {
      List<String> lines = pageTexts.get(page).lines().map(EaPdfChecks::normal).filter(line -> !line.isEmpty()).toList();
      var chosen = new java.util.LinkedHashSet<String>();
      chosen.addAll(lines.subList(0, Math.min(3, lines.size())));
      chosen.addAll(lines.subList(Math.max(0, lines.size() - 3), lines.size()));
      candidates.put(page, new ArrayList<>(chosen));
      chosen.stream().map(UnicodeCasefold::fold).distinct().forEach(line -> frequency.merge(line, 1, Integer::sum));
    }
    int number = 0; List<Integer> missing = new ArrayList<>();
    for (PDPage page : document.getPages()) {
      number++; int n = number;
      String pattern = "(?i)\\b(?:page\\s*[:#]?\\s*" + n + "|p\\.?\\s*" + n + "|" + n + "\\s*/\\s*" + document.getNumberOfPages()
          + "|" + n + "\\s+of\\s+" + document.getNumberOfPages() + ")\\b";
      boolean identified = candidates.get(page.getCOSObject()).stream().anyMatch(line -> java.util.regex.Pattern.compile(pattern).matcher(line).find()
          || line.length() >= 4 && frequency.getOrDefault(UnicodeCasefold.fold(line), 0) == 1);
      if (!identified) missing.add(n);
    }
    for (String code : List.of("PAGE_CONTEXT_PRESENT_RECOMMENDED", "PAGE_CONTEXT_TEXT_OBJECTS_RECOMMENDED"))
      if (document.getNumberOfPages() == 0) report.na(code, "No pages require page-identifying context.", null);
      else report.recommend(code, missing.isEmpty(), "Every page should have page-identifying context in extracted PDF text.", Map.of("pages_unassessed", missing));
  }

  private void coreFields() throws IOException {
    List<Sequence> fields = new ArrayList<>();
    for (Page page : pages.values()) for (Sequence sequence : page.sequences)
      if (Set.of("Mail_FieldName", "Mail_FieldValue").contains(sequence.tag)
          || sequence.key != null && owners.getOrDefault(sequence.key, List.of()).stream()
              .anyMatch(owner -> Set.of("Mail_FieldName", "Mail_FieldValue").contains(name(get(owner, "S"))))) fields.add(sequence);
    if (!fields.isEmpty()) {
      report.check("CORE_FIELDS_TEXT_OBJECTS", fields.stream().allMatch(sequence -> sequence.text),
          "Rendered Core Field names and values must use PDF text objects.", Map.of("sequences", fields.size())); return;
    }
    if (!Set.of("s", "si", "m", "mi").contains(report.profile)) {
      report.na("CORE_FIELDS_TEXT_OBJECTS", "No rendered Core Field sequences in a mail profile require assessment.", null); return;
    }
    var bodies = new AttachmentChecks.BodyIndex(AttachmentChecks.bodySets(document));
    List<String> unresolved = new ArrayList<>(); int verified = 0;
    for (Element email : emails) {
      var relevant = bodies.matching(AttachmentChecks.identity(email));
      if (relevant.isEmpty()) unresolved.add("No owning body content set for " + field(email, "Mail_GUID"));
      for (var body : relevant) {
        String text = body.pages().stream().limit(3).map(page -> pageTexts.get(page.getCOSObject())).collect(java.util.stream.Collectors.joining("\n"));
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        for (String label : List.of("From", "To", "Cc", "Bcc")) expected.put(label, field(email, "Raw-" + label));
        expected.put("Subject", field(email, "Subject")); expected.put("Message-ID", field(email, "messageid"));
        Map<String, String> extracted = new HashMap<>(); String active = null;
        var pattern = java.util.regex.Pattern.compile("^(From|To|Cc|Bcc|Date|Sent|Subject|Message-ID|In-Reply-To|Folder|Archive Global ID|Archive Email\\(s\\)):\\s*(.*)$");
        for (String line : text.lines().limit(160).toList()) {
          var match = pattern.matcher(line);
          if (match.find()) { active = match.group(1); extracted.put(active, match.group(2)); }
          else if (active != null) {
            String wanted = expected.getOrDefault(active, ""), value = extracted.get(active);
            if (!wanted.isEmpty() && comparable(value).startsWith(comparable(wanted))
                || Set.of("Date", "Sent").contains(active) && dateText(email, value)) break;
            extracted.put(active, value + " " + line);
            if (!wanted.isEmpty() && !comparable(wanted).startsWith(comparable(extracted.get(active)))
                && !comparable(extracted.get(active)).startsWith(comparable(wanted))) break;
          }
          if (dateText(email, extracted.getOrDefault("Date", extracted.getOrDefault("Sent", "")))
              && expected.entrySet().stream().allMatch(entry -> entry.getValue().isEmpty()
                  || comparable(extracted.getOrDefault(entry.getKey(), "")).startsWith(comparable(entry.getValue())))) break;
        }
        List<String> missing = new ArrayList<>();
        for (String label : List.of("From", "To", "Cc", "Bcc"))
          if (fieldElement(email, label) != null && field(email, "Raw-" + label).isEmpty()) missing.add(label + " (structured rendering unavailable)");
        expected.forEach((label, wanted) -> { if (!wanted.isEmpty() && !comparable(extracted.getOrDefault(label, "")).startsWith(comparable(wanted))) missing.add(label); });
        if (!dateText(email, extracted.getOrDefault("Date", extracted.getOrDefault("Sent", "")))) missing.add("Date/Sent");
        if (missing.isEmpty()) verified++; else unresolved.addAll(missing);
      }
    }
    if (verified > 0 && unresolved.isEmpty()) report.check("CORE_FIELDS_TEXT_OBJECTS", true, "Recognized rendered Core Fields match XMP values in extracted PDF text.", Map.of("content_sets", verified));
    else report.unavailable("CORE_FIELDS_TEXT_OBJECTS", "Core Field text encoding needs review: " + unresolved);
  }

  private static String comparable(String text) {
    try { text = SourceHeaders.decoded(text); } catch (IllegalArgumentException unavailable) { /* Keep undecoded evidence. */ }
    return text.replaceAll("[\\s<>\"“”]+", "");
  }
  private static boolean dateText(Element email, String value) {
    if (!field(email, "Raw-Sent").isEmpty()) return comparable(value).startsWith(comparable(field(email, "Raw-Sent")));
    try { return SourceHeaders.instant(value).equals(java.time.OffsetDateTime.parse(field(email, "Sent")).toInstant()); }
    catch (IllegalArgumentException | java.time.DateTimeException invalid) { return false; }
  }

  private void customOperands() {
    List<Map<String, Object>> failures = new ArrayList<>();
    List<String> unavailable = new ArrayList<>();
    Set<Key> observed = new HashSet<>();
    int compared = 0;
    for (Page page : pages.values()) for (Sequence sequence : page.sequences) {
      if (sequence.key != null) observed.add(sequence.key);
      List<COSDictionary> candidates = sequence.key == null ? List.of() : owners.getOrDefault(sequence.key, List.of());
      boolean custom = sequence.tag.startsWith("Mail_") || candidates.stream().anyMatch(owner -> name(get(owner, "S")).startsWith("Mail_"));
      if (!custom) continue;
      if (candidates.size() != 1) {
        unavailable.add("No unique corresponding structure owner for " + sequence.tag);
      } else {
        String expected = name(get(candidates.getFirst(), "S"));
        if (!sequence.tag.equals(expected)) failures.add(Map.of("actual", sequence.tag, "expected", expected, "mcid", sequence.key.mcid()));
        else compared++;
      }
    }
    for (var entry : owners.entrySet()) if (!observed.contains(entry.getKey())
        && entry.getValue().stream().anyMatch(owner -> name(get(owner, "S")).startsWith("Mail_"))) {
      unavailable.add("A custom structure content reference has no observed sequence");
    }
    if (!failures.isEmpty()) report.check("CUSTOM_TAG_OPERAND_MATCH", false,
        "Marked-content operands differ from their corresponding custom structure element names.", Map.of("actual", failures, "unavailable", unavailable));
    else if (!unavailable.isEmpty()) report.unavailable("CUSTOM_TAG_OPERAND_MATCH", String.join("; ", unavailable));
    else if (compared > 0) report.check("CUSTOM_TAG_OPERAND_MATCH", true,
        "Marked-content operands match their corresponding custom structure element names.", Map.of("comparisons", compared));
  }

  private void contentSets(COSDictionary node, Set<COSDictionary> visited, int depth) throws IOException {
    if (node == null) return;
    if (depth > 256 || !visited.add(node)) throw new IOException("DPart tree is cyclic or too deep");
    COSBase children = get(node, "DParts");
    if (children != null) { for (COSDictionary child : dictionaries(children)) contentSets(child, visited, depth + 1); return; }
    COSDictionary start = dict(node, "Start"), end = dict(node, "End");
    if (start == null) return;
    if (end == null) end = start;
    Integer first = pagePositions.get(start), last = pagePositions.get(end);
    // Preserve the existing assessment even for malformed end references;
    // DPart range checks separately report missing/reversed endpoints.
    boolean paint = first != null && paintedThrough[last != null && last >= first ? last + 1 : paintedThrough.length - 1] > paintedThrough[first];
    report.check("CONTENT_SET_NONBLANK", paint, "A content set must contain visible paint or annotations.", null);
  }

  private void parse(PDContentStream stream, PDResources resources, COSDictionary scope, Page result, List<Sequence> stack, int depth) throws IOException {
    if (depth > 256) throw new IOException("Form XObjects are too deeply nested");
    scopePages.computeIfAbsent(scope, ignored -> identitySet()).add(currentPage);
    if (scope instanceof COSStream) objectLocations.computeIfAbsent(scope, ignored -> identitySet()).add(currentPage);
    PDFStreamParser parser = new PDFStreamParser(stream);
    int minimum = stack.size(), instruction = 0; boolean textOpen = false;
    List<COSBase> operands = new ArrayList<>();
    try {
      Object token;
      while ((token = parser.parseNextToken()) != null) {
        if (++count > instructionLimit) throw new IOException("Content inspection exceeds its instruction limit");
        if (token instanceof COSBase operand) { operands.add(operand); continue; }
        if (!(token instanceof Operator operator)) continue;
        String op = operator.getName(); instruction++;
        if (Set.of("BDC", "BMC", "DP", "MP").contains(op)) {
          if (operands.isEmpty() || !(operands.getFirst() instanceof COSName)) throw new IOException("Marked-content tag is not a PDF name");
          String tag = name(operands.getFirst());
          COSDictionary properties = null;
          if (operands.size() > 1) {
            COSBase raw = operands.get(1);
            if (raw instanceof COSName named && resources != null && resources.getProperties(named) != null) properties = resources.getProperties(named).getCOSObject();
            else if (raw instanceof COSDictionary dictionary) properties = dictionary;
          }
          if (Set.of("BDC", "DP").contains(op) && properties == null) throw new IOException("Marked-content properties cannot be resolved");
          COSBase mcid = get(properties, "MCID");
          if (mcid != null && (!(mcid instanceof COSInteger integer) || integer.longValue() < 0 || integer.longValue() > Integer.MAX_VALUE))
            errors.add("Marked-content MCID must be a nonnegative integer");
          if (Set.of("BMC", "BDC").contains(op)) {
            Key key = mcid instanceof COSInteger integer && integer.longValue() >= 0 && integer.longValue() <= Integer.MAX_VALUE ? new Key(scope, integer.intValue()) : null;
            Sequence sequence = new Sequence(tag, key, instruction); result.sequences.add(sequence); stack.add(sequence);
          } else result.pointCustom |= tag.startsWith("Mail_");
        } else if ("EMC".equals(op)) {
          if (stack.size() <= minimum) throw new IOException("Marked-content end has no matching start");
          stack.removeLast();
        } else if ("BT".equals(op)) {
          if (textOpen) throw new IOException("Nested PDF text object"); textOpen = true;
        } else if ("ET".equals(op)) {
          if (!textOpen) throw new IOException("PDF text end has no matching start"); textOpen = false;
        } else if (Set.of("Tj", "TJ", "'", "\"").contains(op) && textOpen && hasText(operands)) {
          result.paint = true; result.text = true; stack.forEach(s -> s.text = true);
          paint(result, stack);
        } else if (Set.of("Do", "BI", "sh", "S", "s", "f", "F", "f*", "B", "B*", "b", "b*").contains(op)) {
          result.paint = true;
          if ("Do".equals(op) && !operands.isEmpty() && operands.getFirst() instanceof COSName named && resources != null && resources.getXObject(named) instanceof PDFormXObject form) {
            if (!formActive.add(form.getCOSObject())) throw new IOException("Recursive Form XObject");
            try { parse(form, form.getResources() == null ? resources : form.getResources(), form.getCOSObject(), result, stack, depth + 1); }
            finally { formActive.remove(form.getCOSObject()); }
          } else paint(result, stack);
        }
        operands.clear();
      }
      if (stack.size() != minimum || textOpen) throw new IOException("Unclosed marked-content sequence or text object");
    } finally { parser.close(); }
  }

  private static void paint(Page result, List<Sequence> stack) {
    if (stack.stream().anyMatch(s -> "Artifact".equals(s.tag))) return;
    if (stack.stream().anyMatch(s -> s.key != null)) result.structuredPaint++;
    else result.unmarked++;
  }

  private void relations(COSDictionary root) throws IOException {
    Map<COSDictionary, Set<COSDictionary>> parts = new IdentityHashMap<>();
    Map<COSDictionary, Set<COSDictionary>> ranges = new IdentityHashMap<>();
    var pageIndex = new AttachmentChecks.PageIndex(document);
    for (COSDictionary leaf : AttachmentChecks.leaves(document)) for (PDPage page : pageIndex.range(leaf))
      ranges.computeIfAbsent(page.getCOSObject(), ignored -> identitySet()).add(leaf);
    Map<COSDictionary, List<Set<COSDictionary>>> references = new IdentityHashMap<>();
    Set<Key> observed = new HashSet<>();
    for (Page page : pages.values()) for (Sequence sequence : page.sequences) if (sequence.key != null) observed.add(sequence.key);
    for (var entry : owners.entrySet()) if (observed.contains(entry.getKey())) for (COSDictionary owner : entry.getValue()) {
      Set<COSDictionary> choices = identitySet();
      for (COSDictionary page : scopePages.getOrDefault(entry.getKey().scope(), Set.of())) choices.addAll(ranges.getOrDefault(page, Set.of()));
      reference(parts, references, owner, choices, root);
    }
    for (var entry : objectOwners.entrySet()) {
      var locations = objectLocations.getOrDefault(entry.getKey(), Set.of());
      Set<COSDictionary> choices = identitySet();
      for (COSDictionary page : locations) choices.addAll(ranges.getOrDefault(page, Set.of()));
      if (!locations.isEmpty()) reference(parts, references, entry.getValue(), choices, root);
      else markUncertain(entry.getValue());
    }
    boolean uncertainRanges = ranges.values().stream().anyMatch(choices -> choices.size() != 1 || choices.stream().anyMatch(part -> part instanceof COSStream));
    boolean ownershipUnavailable = new StructureOwnershipBudget(document, pages, scopePages).unavailable(
        structureSeen.stream().filter(element -> Set.of("Mail_Message", "Mail_ContentGroup", "Mail_ContentSet").contains(name(get(element, "S")))).toList());
    Map<COSDictionary, Integer> tagged = new IdentityHashMap<>();
    boolean custom = structureSeen.stream().anyMatch(element -> name(get(element, "S")).startsWith("Mail_"));
    boolean ambiguousContentSetType = xml == null || descendants(xml).stream()
        .anyMatch(node -> is(node, MAIL, "Mail_ContentSetType") || is(node, MAIL, "ContentSetType"));
    for (COSDictionary element : structureSeen) {
      String tag = name(get(element, "S"));
      if (Set.of("Mail_ContentGroup", "Mail_ContentSet", "Mail_Message").contains(tag)) {
        String expected = "Mail_ContentGroup".equals(tag) ? "Part" : "Mail_ContentSet".equals(tag) ? "Art" : "Document";
        String code = "Mail_ContentGroup".equals(tag) ? "MAIL_CONTENTGROUP_ROLEMAP_PART" : "Mail_ContentSet".equals(tag) ? "MAIL_CONTENTSET_ROLEMAP_ART" : "MAIL_MESSAGE_ROLEMAP_DOCUMENT";
        if (!report.seen.contains(code)) report.check(code, expected.equals(name(get(dict(root, "RoleMap"), tag))), tag + " must role map to /" + expected + ".", null);
      }
      if (Set.of("Mail_Field", "Mail_FieldName", "Mail_FieldValue").contains(tag)) {
        String prefix = "Mail_Field".equals(tag) ? "MAIL_FIELD" : "Mail_FieldName".equals(tag) ? "MAIL_FIELDNAME" : "MAIL_FIELDVALUE";
        String role = name(get(dict(root, "RoleMap"), tag));
        if (!report.seen.contains(prefix + "_ROLEMAP_APPROPRIATE")) report.check(prefix + "_ROLEMAP_APPROPRIATE", !role.isEmpty() && !role.startsWith("Mail_"), tag + " requires a standard role mapping.", null);
        String expected = "Mail_Field".equals(tag) ? "Mail_ContentSet" : "Mail_Field";
        boolean nested = false; Set<COSDictionary> seen = identitySet();
        for (COSDictionary parent = dict(element, "P"); parent != null && parent != root && seen.add(parent); parent = dict(parent, "P")) nested |= expected.equals(name(get(parent, "S")));
        if (!report.seen.contains(prefix + "_NESTING_RECOMMENDED")) report.recommend(prefix + "_NESTING_RECOMMENDED", nested, tag + " should occur below /" + expected + ".", null);
      }
      Set<COSDictionary> connected = parts.getOrDefault(element, Set.of());
      if ("Mail_ContentSet".equals(tag)) for (COSDictionary part : connected) tagged.merge(part, 1, Integer::sum);
      if (Set.of("Art", "Mail_ContentSet").contains(tag)) {
        COSDictionary namespace = dict(element, "NS");
        boolean knownRole = namespace == null || ("Art".equals(tag) ? "http://iso.org/pdf/ssn" : "https://pdfa.org/ns/ea-pdf/mail-1").equals(string(get(namespace, "NS")));
        if (ownershipUnavailable && tag.startsWith("Mail_") || !errors.isEmpty() || connected.isEmpty() || !knownRole || references.getOrDefault(element, List.of()).stream()
            .anyMatch(choices -> choices.size() != 1 || choices.stream().anyMatch(part -> part instanceof COSStream)))
          report.unavailable("MAIL_CONTENTSET_ART_ONLY", "Art content-set ownership is unavailable without valid content connections and a resolved role.");
        else report.check("MAIL_CONTENTSET_ART_ONLY", connected.size() == 1,
            "An explicit or implicit Art element must represent one DPart content set.", Map.of("connected_content_sets", connected.size()));
      }
      if (Set.of("Mail_ContentGroup", "Mail_ContentSet").contains(tag) && !Set.of("c", "ci").contains(report.profile)) {
        boolean enclosingMessage = false; Set<COSDictionary> ancestors = identitySet();
        for (COSDictionary parent = dict(element, "P"); parent != null && parent != root && ancestors.add(parent); parent = dict(parent, "P"))
          enclosingMessage |= "Mail_Message".equals(name(get(parent, "S")));
        String code = "Mail_ContentGroup".equals(tag) ? "MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE" : "MAIL_CONTENTSET_NESTED_UNDER_MESSAGE";
        boolean global = "Mail_ContentSet".equals(tag) && !ownershipUnavailable && !uncertainRanges
            && errors.isEmpty() && !uncertainStructure.contains(element) && !connected.isEmpty()
            && references.getOrDefault(element, List.of()).stream().allMatch(choices -> choices.size() == 1)
            && connected.stream().allMatch(this::documentWidePart);
        if (global && !enclosingMessage) documentWideParts.addAll(connected);
        if (!enclosingMessage && "Mail_ContentSet".equals(tag)
            && (connected.isEmpty() || ownershipUnavailable || !errors.isEmpty() || uncertainStructure.contains(element)))
          report.unavailable(code, "Document-wide content-set ownership could not be established.");
        else report.check(code, enclosingMessage || global,
            tag + " requires a Mail_Message ancestor unless its content has proven document-wide ownership.", null);
      }
      List<COSDictionary> properties = LogicalStructureChecks.properties(root, element);
      if ("Mail_Message".equals(tag)) {
        List<COSDictionary> identities = properties.stream().filter(property -> "Mail_GUID".equals(string(get(property, "N")))).toList();
        if (identities.isEmpty()) report.unavailable("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "No logical Mail_GUID property is available to compare.");
        for (COSDictionary identity : identities) {
          if (!(get(identity, "V") instanceof COSString) || decodedString(get(identity, "V")) == null) {
            report.unavailable("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "The logical GUID value is not readable PDF text."); continue;
          }
          String guid = string(get(identity, "V"));
          if (!ownershipUnavailable && !uncertainRanges && errors.isEmpty() && !uncertainStructure.contains(element)
              && references.getOrDefault(element, List.of()).stream().allMatch(choices -> choices.size() == 1)
              && !guid.isEmpty() && emailGuidCounts.getOrDefault(guid, 0) == 1)
            for (COSDictionary part : connected) independentEmailOwners.computeIfAbsent(part, ignored -> new HashSet<>()).add(guid);
          boolean known = !ownershipUnavailable && !connected.isEmpty() && !uncertainStructure.contains(element), equal = true, mismatch = false;
          for (COSDictionary part : connected) {
            Set<String> guids = new HashSet<>(); boolean unknown = false;
            Set<COSDictionary> visited = identitySet();
            for (COSDictionary node = dict(part, "Parent"); node != null && visited.add(node); node = dict(node, "Parent")) {
              COSBase value = get(dict(node, "DPM"), "Mail_GUID");
              if (value == null) continue;
              if (value instanceof COSString && decodedString(value) != null && !string(value).isEmpty()) guids.add(string(value));
              else unknown = true;
            }
            boolean identified = !unknown && guids.size() == 1;
            known &= identified;
            if (identified && !guids.contains(guid)) { mismatch = true; equal = false; }
          }
          if (known || mismatch) report.check("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", equal, "Logical message GUID must match the DPart owners of its actual content.", null);
          else report.unavailable("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "The logical message's DPart content identity could not be established.");
        }
      }
      for (String field : List.of("Mail_ContentSetType", "Mail_Subtype")) {
        boolean applies = "Mail_ContentSetType".equals(field) && "Mail_ContentGroup".equals(tag) || "Mail_Subtype".equals(field) && "Mail_ContentSet".equals(tag);
        if (!applies) continue;
        List<COSDictionary> values = properties.stream().filter(property -> field.equals(string(get(property, "N")))).toList();
        String prefix = "Mail_Subtype".equals(field) ? "MAIL_CONTENTSET_SUBTYPE" : "MAIL_CONTENTGROUP_CONTENTSETTYPE";
        if (values.isEmpty() && LogicalStructureChecks.attributeErrors(root, element)) report.unavailable(prefix + "_MATCH_IF_PRESENT", "Optional attribute resolution is incomplete.");
        report.recommend(prefix + "_ATTRIBUTE_RECOMMENDED", !values.isEmpty(), "The optional structure attribute is recommended.", Map.of("attribute", field));
        for (COSDictionary value : values) {
          if (connected.isEmpty()) { report.unavailable(prefix + "_MATCH_IF_PRESENT", "The optional attribute has no verified DPart content owner."); continue; }
          COSBase supplied = get(value, "V");
          if (!(supplied instanceof COSString) || decodedString(supplied) == null) {
            report.unavailable(prefix + "_MATCH_IF_PRESENT", "The supplied attribute is not readable PDF text; USERPROPS_TYPE checks its type."); continue;
          }
          boolean mismatch = false, unknown = ownershipUnavailable || uncertainStructure.contains(element)
              || "Mail_ContentSetType".equals(field) && ambiguousContentSetType;
          List<String> candidates = null;
          for (Set<COSDictionary> reference : references.getOrDefault(element, List.of())) {
            List<String> possible = new ArrayList<>(); boolean incomplete = reference.isEmpty();
            for (COSDictionary part : reference) {
              COSBase raw = get(dict(part, "DPM"), field);
              String actual = raw instanceof COSName named ? DocumentPartChecks.utf8Name(named) : null;
              if (actual == null) { incomplete = true; continue; }
              if (possible.stream().noneMatch(observation -> equivalent(field, observation, actual))) possible.add(actual);
            }
            if (incomplete || possible.isEmpty()) { unknown = true; continue; }
            unknown |= possible.size() > 1;
            if (candidates == null) candidates = new ArrayList<>(possible);
            else candidates.removeIf(candidate -> possible.stream().noneMatch(observation -> equivalent(field, observation, candidate)));
          }
          mismatch = !ownershipUnavailable && candidates != null && candidates.stream().noneMatch(candidate -> equivalent(field, string(supplied), candidate));
          if (unknown && !mismatch) report.unavailable(prefix + "_MATCH_IF_PRESENT", "Some DPart attribute observations are unavailable.");
          else report.check(prefix + "_MATCH_IF_PRESENT", !mismatch, "Supplied structure attributes must match their connected DPart metadata.", Map.of("attribute", field));
        }
      }
    }
    if (!report.seen.contains("MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE"))
      report.check("MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE", true, "No content group has an invalid message ancestor.", null);
    if (!Set.of("c", "ci").contains(report.profile) && !report.seen.contains("MAIL_CONTENTSET_NESTED_UNDER_MESSAGE"))
      report.check("MAIL_CONTENTSET_NESTED_UNDER_MESSAGE", true, "No content set has an invalid message ancestor.", null);
    if (custom) {
      boolean coverage = true;
      for (COSDictionary page : pages.keySet()) if (dict(page, "DPart") != null) coverage &= tagged.containsKey(dict(page, "DPart"));
      if (ownershipUnavailable || !errors.isEmpty() || uncertainRanges) report.unavailable("CONTENT_SET_MAIL_CONTENTSET_TAGGED", "Content-set tag coverage is unavailable while structure/content correspondence is invalid.");
      else report.check("CONTENT_SET_MAIL_CONTENTSET_TAGGED", coverage, "Each DPart content set requires connected Mail_ContentSet tagging when custom tags are used.", null);
    } else report.na("CONTENT_SET_MAIL_CONTENTSET_TAGGED", "Optional custom mail tags are absent.", null);
    report.supported("MAIL_CONTENTSET_ART_ONLY", "MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED", "MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT", "MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED", "MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT");
  }

  private boolean documentWidePart(COSDictionary part) {
    if (!Set.of("FrontMatter", "ConversionReport", "PolicySettings").contains(name(get(dict(part, "DPM"), "Mail_ContentSetType")))) return false;
    COSDictionary root = dict(document.getDocumentCatalog().getCOSObject(), "DPartRoot");
    Set<COSDictionary> seen = identitySet();
    for (COSDictionary node = part; node != null && node != root; node = dict(node, "Parent")) {
      if (node instanceof COSStream || !seen.add(node) || seen.size() > 256) return false;
      COSDictionary metadata = dict(node, "DPM");
      if (get(node, "DPM") != null && (metadata == null || metadata instanceof COSStream)) return false;
      if (get(metadata, "Mail_GUID") != null || get(metadata, "Mail_MessageID") != null) return false;
      COSDictionary parent = dict(node, "Parent");
      if (parent == root && root != null) return get(root, "DPartRootNode") == node;
      try {
        if (parent == null || !dictionaries(get(parent, "DParts")).contains(node)) return false;
      } catch (IllegalArgumentException invalid) { return false; }
    }
    return false;
  }

  private static boolean equivalent(String field, String left, String right) {
    return "Mail_Subtype".equals(field) ? MetadataChecks.sameMedia(left, right) : left.equals(right);
  }
  private void reference(Map<COSDictionary, Set<COSDictionary>> parts, Map<COSDictionary, List<Set<COSDictionary>>> references,
                         COSDictionary owner, Set<COSDictionary> choices, COSDictionary root) {
    for (COSDictionary part : choices) connect(parts, owner, part, root);
    Set<COSDictionary> seen = identitySet();
    for (COSDictionary element = owner; element != null && element != root && seen.add(element); element = dict(element, "P"))
      references.computeIfAbsent(element, ignored -> new ArrayList<>()).add(choices);
  }

  private void markUncertain(COSDictionary owner) {
    connectAncestors(uncertainStructure, owner, dict(document.getDocumentCatalog().getCOSObject(), "StructTreeRoot"));
  }

  private static void connectAncestors(Set<COSDictionary> connected, COSDictionary owner, COSDictionary root) {
    Set<COSDictionary> seen = identitySet();
    for (COSDictionary node = owner; node != null && node != root && seen.add(node); node = dict(node, "P")) connected.add(node);
  }

  private void connect(Map<COSDictionary, Set<COSDictionary>> parts, COSDictionary owner, COSDictionary part, COSDictionary root) {
    if (part == null) return;
    Set<COSDictionary> visited = identitySet();
    for (COSDictionary element = owner; element != null && element != root && visited.add(element); element = dict(element, "P")) {
      parts.computeIfAbsent(element, ignored -> identitySet()).add(part);
    }
  }

  private static boolean hasText(List<? extends COSBase> values) {
    for (COSBase value : values) {
      if (value instanceof COSString text && !text.getString().isBlank()) return true;
      if (value instanceof COSArray array && hasText(array.toList())) return true;
    }
    return false;
  }

  private void structure(COSBase value, COSDictionary parent, COSDictionary page, int depth) throws IOException {
    boolean indirect = value instanceof COSObject;
    value = resolve(value);
    if (depth > 256 || structureSeen.size() > InspectionBudget.nodes(document)) throw new IOException("Structure exceeds the inspection limit");
    if (value instanceof COSArray array) { for (COSBase child : array) structure(child, parent, page, depth + 1); return; }
    if (value instanceof COSInteger integer && page != null && integer.longValue() >= 0 && integer.longValue() <= Integer.MAX_VALUE) { owner(page, integer.intValue(), parent); return; }
    if (!(value instanceof COSDictionary dictionary)) {
      if (value != null) { errors.add("Structure element has an invalid K entry"); markUncertain(parent); }
      return;
    }
    if (get(dictionary, "S") != null || "StructElem".equals(name(get(dictionary, "Type")))) {
      if (!indirect) errors.add("Structure element is not an indirect object");
      if (!(get(dictionary, "S") instanceof COSName)) errors.add("Structure element has no structure type name");
      if (!structureSeen.add(dictionary)) throw new IOException("Structure contains a repeated element");
      if (get(dictionary, "P") != parent) errors.add("Structure element Parent reference is incorrect");
      // ISO 32000-2, Table 355: K is optional, including for an empty table cell.
      // Any actual content must still reconcile with the ParentTree below.
      structure(dictionary.getItem(COSName.K), dictionary, dict(dictionary, "Pg") == null ? page : dict(dictionary, "Pg"), depth + 1);
    } else if ("MCR".equals(name(get(dictionary, "Type")))) {
      COSDictionary scope = get(dictionary, "Stm") instanceof COSStream stream ? stream : dict(dictionary, "Pg") == null ? page : dict(dictionary, "Pg");
      if (scope != null && get(dictionary, "MCID") instanceof COSInteger integer && integer.longValue() >= 0 && integer.longValue() <= Integer.MAX_VALUE) {
        COSDictionary contentPage = dict(dictionary, "Pg") == null ? page : dict(dictionary, "Pg");
        if (scope instanceof COSStream stream && pageStreams.containsKey(stream)) scope = pageStreams.get(stream);
        if (contentPage == null || !scopePages.getOrDefault(scope, Set.of()).contains(contentPage)) errors.add("Structure reference does not identify a page using its content scope");
        owner(scope, integer.intValue(), parent);
      }
      else errors.add("Marked-content reference lacks a resolvable scope or MCID");
    } else if ("OBJR".equals(name(get(dictionary, "Type")))) {
      // Object references need their own ParentTree entry, separate from page MCID arrays.
      COSDictionary object = dict(dictionary, "Obj");
      if (object == null) errors.add("OBJR does not reference an object");
      else {
        if (objectOwners.put(object, parent) != null) errors.add("Object reference has more than one structure owner");
        COSDictionary declared = dict(dictionary, "Pg") == null ? page : dict(dictionary, "Pg");
        if (declared != null && !objectLocations.getOrDefault(object, scopePages.getOrDefault(object, Set.of())).contains(declared)) errors.add("OBJR object is not on its declared page");
      }
    } else { errors.add("Structure element has an invalid K dictionary"); markUncertain(parent); }
  }

  private void owner(COSDictionary scope, int mcid, COSDictionary owner) { owners.computeIfAbsent(new Key(scope, mcid), ignored -> new ArrayList<>()).add(owner); }
  private record Lookup(int key, Integer mcid) {}
  private void parentTreeEntries(COSDictionary structure, Set<Key> actual) {
    Map<Integer, COSDictionary> used = new HashMap<>();
    Map<Lookup, COSDictionary> expected = new HashMap<>();
    Set<COSDictionary> marked = identitySet();
    for (Key key : actual) {
      marked.add(key.scope()); COSBase value = get(key.scope(), "StructParents");
      if (!(value instanceof COSInteger integer) || integer.longValue() < 0 || integer.longValue() > Integer.MAX_VALUE) { errors.add("Content scope has no valid StructParents key"); continue; }
      int index = integer.intValue();
      COSDictionary previous = used.putIfAbsent(index, key.scope());
      if (previous != null && previous != key.scope()) errors.add("Different content scopes share a StructParents key");
      var matches = owners.getOrDefault(key, List.of());
      if (matches.size() == 1) expected.put(new Lookup(index, key.mcid()), matches.getFirst());
    }
    for (var entry : objectOwners.entrySet()) {
      COSBase value = get(entry.getKey(), "StructParent");
      if (!(value instanceof COSInteger integer) || integer.longValue() < 0 || integer.longValue() > Integer.MAX_VALUE) { errors.add("OBJR object has no valid StructParent key"); continue; }
      if (used.putIfAbsent(integer.intValue(), entry.getKey()) != null) errors.add("OBJR object reuses a parent-tree key");
      expected.put(new Lookup(integer.intValue(), null), entry.getValue());
    }
    Set<COSDictionary> scopes = identitySet(); scopes.addAll(scopePages.keySet()); scopes.addAll(objectLocations.keySet());
    for (COSDictionary scope : scopes) if (get(scope, "StructParent") != null && !objectOwners.containsKey(scope)) errors.add("Object with StructParent has no OBJR reference");
    for (var entry : parentTree.entrySet()) {
      if (entry.getValue() instanceof COSArray array) for (int index = 0; index < array.size(); index++) {
        COSBase owner = array.getObject(index);
        if (owner != null && !(owner instanceof COSNull) && !expected.containsKey(new Lookup(entry.getKey(), index))) errors.add("Orphan ParentTree array entry");
      } else if (entry.getValue() != null && !(entry.getValue() instanceof COSNull) && !expected.containsKey(new Lookup(entry.getKey(), null))) errors.add("Orphan ParentTree object entry");
    }
    COSBase next = get(structure, "ParentTreeNextKey");
    if (next != null && (!(next instanceof COSInteger integer) || integer.longValue() < 0 || !parentTree.isEmpty() && integer.longValue() <= parentTree.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1)))
      errors.add("ParentTreeNextKey does not exceed existing keys");
  }

  private List<Integer> numbers(COSDictionary node, Set<COSDictionary> seen, int depth) throws IOException {
    if (node == null) return List.of();
    if (depth > 256 || seen.size() > InspectionBudget.nodes(document) || !seen.add(node)) throw new IOException("ParentTree is cyclic or too deep");
    COSBase rawNumbers = get(node, "Nums"), rawKids = get(node, "Kids");
    if (rawNumbers != null && rawKids != null) errors.add("ParentTree node has both Nums and Kids");
    List<Integer> keys = new ArrayList<>();
    if (rawNumbers != null) {
      if (!(rawNumbers instanceof COSArray nums) || nums.size() % 2 != 0) throw new IOException("ParentTree Nums must contain key/value pairs");
      for (int index = 0; index < nums.size(); index += 2) {
        if (!(nums.getObject(index) instanceof COSInteger integer) || integer.longValue() < 0 || integer.longValue() > Integer.MAX_VALUE) throw new IOException("ParentTree key is not a nonnegative integer");
        if (parentTree.put(integer.intValue(), nums.getObject(index + 1)) != null || !keys.isEmpty() && integer.intValue() <= keys.getLast()) errors.add("ParentTree keys are duplicated or out of order");
        keys.add(integer.intValue());
      }
    }
    if (rawKids != null) {
      if (!(rawKids instanceof COSArray kids) || kids.size() == 0) throw new IOException("ParentTree Kids must be a nonempty array");
      for (COSBase raw : kids) {
        if (!(resolve(raw) instanceof COSDictionary child)) throw new IOException("ParentTree child is not a dictionary");
        List<Integer> childKeys = numbers(child, seen, depth + 1);
        if (!childKeys.isEmpty() && !keys.isEmpty() && childKeys.getFirst() <= keys.getLast()) errors.add("ParentTree child ranges overlap or are out of order");
        keys.addAll(childKeys);
      }
    }
    COSBase limits = get(node, "Limits");
    if (limits != null && (!(limits instanceof COSArray array) || array.size() != 2 || keys.isEmpty()
        || !(array.getObject(0) instanceof COSInteger first) || !(array.getObject(1) instanceof COSInteger last)
        || first.longValue() != keys.getFirst() || last.longValue() != keys.getLast())) errors.add("ParentTree Limits do not describe its actual keys");
    return keys;
  }
  static List<COSDictionary> dictionaries(COSBase value) {
    List<COSDictionary> out = new ArrayList<>();
    collect(value, out, 0); return out;
  }
  private static void collect(COSBase value, List<COSDictionary> out, int depth) {
    if (depth > 256) throw new IllegalArgumentException("Nested array exceeds the inspection limit");
    value = resolve(value);
    if (value instanceof COSDictionary dictionary) out.add(dictionary);
    else if (value instanceof COSArray array) for (COSBase child : array) collect(child, out, depth + 1);
  }
}
