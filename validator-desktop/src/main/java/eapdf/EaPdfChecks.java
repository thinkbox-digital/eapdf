package eapdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** EA-PDF file assertions; unavailable source evidence remains explicit. */
final class EaPdfChecks {
  static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  static final String DC = "http://purl.org/dc/elements/1.1/";
  static final String PDF = "http://ns.adobe.com/pdf/1.3/";
  static final String XMP = "http://ns.adobe.com/xap/1.0/";
  static final String PDFA = "http://www.aiim.org/pdfa/ns/id/";
  static final String ID = "http://www.pdfa.org/eapdf/ns/id/";
  static final String MAIL = "http://www.pdfa.org/eapdf/ns/meta/";
  static final String DECLARATIONS = "http://pdfa.org/declarations/";
  static final String FOAF = "http://xmlns.com/foaf/0.1/";
  static final Set<String> PROFILES = Set.of("s", "si", "m", "mi", "c", "ci");
  private final PDDocument document;
  private final JavaValidation.Report report;
  private final JavaValidation.Children children;
  private final int depth;
  private final COSDictionary catalog;
  private Element xml;
  private List<Element> documentDescriptions;
  private String profile = "";
  private String part = "";
  private final List<Element> emails = new ArrayList<>();
  private final List<Element> assets = new ArrayList<>();
  private final List<Element> attachments = new ArrayList<>();
  private final List<Embedded> files = new ArrayList<>();
  private final Set<COSDictionary> nameTreeSpecs = identitySet();
  private final Set<COSStream> nameTreeStreams = identitySet();
  private final Map<COSDictionary, List<String>> treeKeys = new IdentityHashMap<>();
  private final List<COSDictionary> sourceSpecs = new ArrayList<>();
  private final Set<COSDictionary> associatedSpecs = identitySet();
  private final Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
  private final Set<Integer> outlinePages = new HashSet<>();
  private int visits;

  record Embedded(String treeName, COSDictionary spec, COSStream stream, byte[] payload, String name,
                  String mediaType, String md5, String relationship, long size) {
    Embedded(String treeName, COSDictionary spec, COSStream stream, byte[] bytes, String name,
        String mediaType, String md5, String relationship) {
      this(treeName, spec, stream, bytes, name, mediaType, md5, relationship, bytes.length);
    }

    java.io.InputStream open() throws IOException {
      return payload != null ? new ByteArrayInputStream(payload) : BoundedPdfStreams.open(stream, BoundedPdfStreams.streamLimit());
    }

    byte[] bytes() {
      if (payload != null) return payload;
      try (var input = open()) { return input.readAllBytes(); }
      catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    boolean sameBytes(Embedded other) {
      if (size != other.size) return false;
      try (var first = open(); var second = other.open()) {
        while (true) {
          byte[] chunk = first.readNBytes(8192);
          if (!java.util.Arrays.equals(chunk, second.readNBytes(8192))) return false;
          if (chunk.length == 0) return true;
        }
      } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
    }
  }

  private static Embedded inspectEmbedded(COSDictionary spec, COSStream stream, String name,
      String media, String relationship) throws Exception {
    var digest = MessageDigest.getInstance("MD5");
    long size;
    BoundedPdfStreams.file();
    try (var input = BoundedPdfStreams.open(stream, BoundedPdfStreams.streamLimit())) {
      size = input.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
    }
    // Keep only measured size and checksum; decode payloads on demand while the PDF remains open.
    return new Embedded("", spec, stream, null, name, media, HexFormat.of().formatHex(digest.digest()), relationship, size);
  }

  EaPdfChecks(PDDocument document, JavaValidation.Report report, JavaValidation.Children children, int depth) {
    this.document = document; this.report = report; catalog = document.getDocumentCatalog().getCOSObject();
    this.children = children; this.depth = depth;
    int index = 0;
    for (PDPage page : document.getPages()) pageNumbers.put(page.getCOSObject(), index++);
  }

  void run(Path path) throws Exception {
    try (var budget = BoundedPdfStreams.scope(depth == 0)) {
      try { runChecks(path); }
      finally {
        if (BoundedPdfStreams.limitedReason() != null)
          report.unavailable("VALIDATION_INCOMPLETE", "A security limit prevented complete stream inspection: " + BoundedPdfStreams.limitedReason());
      }
    }
  }

  private void runChecks(Path path) throws Exception {
    versions(path);
    report.check("PAGE_COUNT", document.getNumberOfPages() > 0, "The document must have pages.", Map.of("pages", document.getNumberOfPages()));
    ValidationSession.stage("Reading metadata", path);
    metadata();
    if (xml == null) {
      report.add("WARN", "ASSET_XMP_SKIPPED", "Asset checks need readable document XMP.", null);
      if (Set.of("s", "si", "m", "mi").contains(profile)) report.check("MESSAGE_METADATA_SKIPPED", false, "Email metadata is unavailable.", null);
      else report.na("MESSAGE_METADATA_SKIPPED", "No mail profile activates email metadata checks.", null);
    }
    catalog();
    outlines();
    ValidationSession.stage("Checking embedded files", path);
    embedded(path);
    sourceFileSets();
    collection();
    ValidationSession.stage("Checking document parts", path);
    documentParts();
    ValidationSession.stage("Checking logical structure", path);
    LogicalStructureChecks logical = logicalStructure();
    ValidationSession.stage("Checking page content", path);
    ContentOwnership.Evidence ownership = new PageContentChecks(document, report, emails, xml).run();
    ContentOwnership.Result rendered = ContentOwnership.assess(document, emails, profile, report, ownership);
    MetadataChecks metadata = null;
    if (xml != null) {
      metadata = new MetadataChecks(report, files, sourceSpecs, emails, assets, attachments, profile, legacyEmail());
    ValidationSession.stage("Comparing original email metadata", path);
      metadata.assets(); metadata.emails(); metadata.nativeAttachments(document);
      if (Set.of("s", "si", "m", "mi").contains(profile)) {
        metadata.bodies(document, ownership);
        metadata.attachmentRenderings(document, ownership);
      }
      viewerModes(metadata.attachmentPresence());
      logical.sourceValues(metadata);
    ValidationSession.stage("Checking original attachments", path);
      new AttachmentChecks(document, report, attachments, files, emails, metadata, profile).run();
    }
    if (xml == null) { logical.sourceValues(null); viewerModes(false); }
    ValidationSession.stage("Checking embedded email PDFs", path);
    children(metadata);
    if (metadata != null) metadata.renderedCounts(rendered);
    new RuleApplicability(document, report, xml, files, sourceSpecs, emails, assets, attachments, metadata).finish();
  }

  private void versions(Path path) throws IOException {
    byte[] header;
    try (var stream = Files.newInputStream(path)) { header = stream.readNBytes(8); }
    String value = new String(header, java.nio.charset.StandardCharsets.US_ASCII);
    String headerVersion = value.startsWith("%PDF-") ? value.substring(5) : "";
    COSBase version = get(catalog, "Version");
    String named = name(version);
    float effective = document.getVersion();
    report.check("PDF_HEADER_VERSION_READ", Set.of("1.7", "2.0").contains(headerVersion), "The PDF header must declare 1.7 or 2.0.", Map.of("header", headerVersion));
    report.check("PDF_CATALOG_VERSION", version == null || version instanceof COSName && named.matches("[0-9]\\.[0-9]")
        && (effective == 1.7f || effective == 2.0f), "A supplied catalog Version must be a PDF version name.", Map.of("value", observed(version)));
    report.check("PDF_EFFECTIVE_VERSION", effective == 1.7f || effective == 2.0f, "The effective PDF version must be 1.7 or 2.0.", Map.of("version", effective));
  }

  private void metadata() throws Exception {
    try {
      if (document.getDocumentCatalog().getMetadata() == null) throw new IOException("Document XMP is missing");
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "0");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(BoundedPdfStreams.bytes(document.getDocumentCatalog().getMetadata().getCOSObject(), BoundedPdfStreams.metadataLimit()))).getDocumentElement();
    } catch (BoundedPdfStreams.Limit limit) {
      report.unavailable("EAPDF_XMP_UNCHECKED", "Metadata inspection stopped at a security limit: " + limit.getMessage());
      return;
    } catch (Exception error) {
      if (error.toString().contains("JAXP00010006") || error.toString().contains("maxElementDepth")) {
        report.unavailable("EAPDF_XMP_UNCHECKED", "Metadata inspection stopped at the XML depth limit.");
        return;
      }
      report.check("EAPDF_XMP_UNCHECKED", false, "EA-PDF metadata cannot be inspected.", Map.of("error", error.toString()));
      report.unavailable("PDFA_XMP_UNCHECKED", "PDF/A identification could not be read from document XMP.");
      report.unavailable("XMP_KEYWORDS_UNCHECKED", "XMP keywords could not be inspected.");
      return;
    }
    var conversionStates = xml.getElementsByTagNameNS("https://thinkbox.digital/ns/eapdf/conversion/1.0/", "status");
    for (int i = 0; i < conversionStates.getLength(); i++) {
      if ("INCOMPLETE".equals(conversionStates.item(i).getTextContent().trim())) {
        report.unavailable("VALIDATION_INCOMPLETE", "The creator recorded missing source information. See the conversion warnings in the PDF.");
        break;
      }
    }
    report.check("EAPDF_XMP_UNCHECKED", true, "EA-PDF metadata is readable.", null);
    report.check("PDFA_XMP_UNCHECKED", true, "PDF/A identification is readable.", null);
    report.check("XMP_KEYWORDS_UNCHECKED", true, "XMP keyword checks were applied.", null);
    Map<String, String> namespaces = Map.of("pdfmail", "http://www.pdfa.org/eapdf/", "pdfmailid", ID, "pdfmailmeta", MAIL);
    for (var entry : namespaces.entrySet()) {
      Set<String> observed = new LinkedHashSet<>();
      for (Element element : descendants(xml)) {
        String namespace = element.lookupNamespaceURI(entry.getKey());
        if (namespace != null) observed.add(namespace);
      }
      boolean bound = observed.contains(entry.getValue());
      String message = bound ? "Required namespace binding is present: " + entry.getKey() + " → " + entry.getValue()
          : observed.isEmpty() ? "Required namespace declaration is missing: xmlns:" + entry.getKey() + "=\"" + entry.getValue() + "\"."
          : "Prefix " + entry.getKey() + " is bound to " + observed + "; expected " + entry.getValue() + ".";
      report.check("XMP_NS_PREFIX", bound, message, Map.of("expected_prefix", entry.getKey(),
          "expected_namespace", entry.getValue(), "prefix_present", !observed.isEmpty(),
          "observed_namespaces_for_prefix", List.copyOf(observed)));
    }
    checkScalar("EAPDF_VERSION", ID, "version", "pdfmailid:version must be the XMP integer 1.", text -> nonnegative(text) && new BigInteger(text.strip()).equals(BigInteger.ONE));
    checkScalar("EAPDF_REV", ID, "rev", "pdfmailid:rev must be the XMP integer 2024.", text -> nonnegative(text) && new BigInteger(text.strip()).equals(BigInteger.valueOf(2024)));
    checkScalar("EAPDF_CONFORMANCE_VALUE", ID, "conformance", "pdfmailid:conformance must be XMP text: s, si, m, mi, c or ci.", PROFILES::contains);
    profile = value(ID, "conformance");
    if (profile.isEmpty() && !properties(ID, "conformance").isEmpty()) {
      Element supplied = valueElement(properties(ID, "conformance").getFirst());
      if (supplied != null && !children(supplied).isEmpty() && List.of("Bag", "Seq", "Alt").contains(children(supplied).getFirst().getLocalName()))
        profile = children(children(supplied).getFirst()).stream().map(item -> "'" + textValue(item) + "'").collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }
    report.profile = profile;
    report.check("EAPDF_CONFORMANCE_I_LAST", !profile.contains("i") || profile.endsWith("i") && profile.indexOf('i') == profile.lastIndexOf('i'),
        "The isolation letter i may occur only once, as the final letter: si, mi or ci.", Map.of("value", profile));
    part = value(PDFA, "part");
    String conformance = value(PDFA, "conformance").toUpperCase(Locale.ROOT);
    report.check("PDFA_PART", Set.of("3", "4").contains(part), "PDF/A part must be 3 or 4.", Map.of("value", part));
    report.check("PDFA_CONFORMANCE", "3".equals(part) && Set.of("A", "U").contains(conformance)
        || "4".equals(part) && Set.of("", "F", "E").contains(conformance), "The PDF/A flavour must be PDF/A-3a, PDF/A-3u, PDF/A-4, PDF/A-4f or PDF/A-4e.", Map.of("part", part, "conformance", conformance));
    checkScalar("XMP_PRODUCER", PDF, "Producer", "pdf:Producer must be a non-empty XMP text string.", text -> !text.isBlank());
    checkScalar("XMP_CREATEDATE", XMP, "CreateDate", "xmp:CreateDate must be an XMP date, e.g. 2000-01-01T12:00:00Z; reduced precision is allowed.", EaPdfChecks::date);
    List<Element> creators = properties(DC, "creator");
    boolean creatorValid = !creators.isEmpty() && creators.stream().allMatch(property -> {
      List<Element> children = children(property);
      return children.size() == 1 && is(children.getFirst(), RDF, "Seq") && !children(children.getFirst()).isEmpty()
          && children(children.getFirst()).stream().allMatch(item -> is(item, RDF, "li") && simple(item))
          && !children(children.getFirst()).getFirst().getTextContent().isBlank();
    });
    report.check("XMP_CREATOR", creatorValid, "dc:creator must be an ordered sequence naming the responsible person, organization or service, with a populated first item. Software belongs in xmp:CreatorTool and pdf:Producer; this check establishes structure, not attribution.", null);
    report.recommend("XMP_TITLE", defaultLanguagePresent(DC, "title"), "A unique x-default title is recommended.", null);
    boolean creator = false;
    if (!creators.isEmpty()) {
      var containers = children(valueElement(creators.getFirst()));
      if (containers.size() == 1 && List.of("Seq", "Bag", "Alt").contains(containers.getFirst().getLocalName())) {
        var members = children(containers.getFirst());
        if (!members.isEmpty()) creator = semanticTruthy(members.getFirst());
      }
    }
    Map<String, Boolean> provenance = Map.of("XMP_CREATOR_SEMANTICS", creator,
        "XMP_PRODUCER_SEMANTICS", !properties(PDF, "Producer").isEmpty() && semanticTruthy(properties(PDF, "Producer").getFirst()),
        "XMP_CREATEDATE_SEMANTICS", !properties(XMP, "CreateDate").isEmpty() && semanticTruthy(properties(XMP, "CreateDate").getFirst()));
    for (var entry : provenance.entrySet()) if (entry.getValue())
      report.add("WARN", entry.getKey(), entry.getKey().equals("XMP_CREATOR_SEMANTICS")
          ? "dc:creator identifies the entity responsible for creating the EA-PDF, not merely the generating software. Software identity belongs in xmp:CreatorTool and pdf:Producer. Attribution requires independent provenance."
          : "Creation attribution requires independent provenance; XMP alone cannot establish it.", Map.of("assessment", "unavailable"));
    report.check("XMP_KEYWORDS", value(PDF, "Keywords").contains("EA-PDF"), "XMP keywords must include EA-PDF.", Map.of("value", value(PDF, "Keywords")));
    readRecords("email", emails, "EMAIL_XMP_CONTAINER");
    readRecords("assets", assets, "ASSET_XMP");
    readRecords("attachments", attachments, "ATTACHMENT_XMP_CONTAINER");
    infoCrosswalk();
  }

  private void checkScalar(String code, String ns, String property, String requirement, java.util.function.Predicate<String> predicate) {
    List<Element> values = properties(ns, property);
    report.check(code, !values.isEmpty() && values.stream().allMatch(element -> simple(element) && predicate.test(textValue(element))),
        requirement, Map.of("value", value(ns, property), "expected", requirement));
  }

  private void readRecords(String property, List<Element> destination, String code) {
    List<Element> containers = properties(MAIL, property);
    if ("attachments".equals(property) && containers.isEmpty())
      report.check(code, false, "Document XMP must include attachments as an unordered rdf:Bag, even when empty.", null);
    if ("email".equals(property) && containers.isEmpty() && Set.of("s", "si", "m", "mi").contains(profile))
      report.check(code, false, "Email profiles require the document-level email array of structured records.", null);
    for (Element container : containers) {
      List<Element> kids = children(valueElement(container));
      boolean array = kids.size() == 1 && List.of("Bag", "Seq", "Alt").stream().anyMatch(kind -> is(kids.getFirst(), RDF, kind));
      boolean valid = array && is(kids.getFirst(), RDF, "Bag");
      if (array) for (Element item : children(kids.getFirst())) {
        if (!is(item, RDF, "li")) { valid = false; continue; }
        Element record = valueElement(item);
        boolean structured = record != null && !record.hasAttributeNS(RDF, "resource")
            && ("Resource".equals(record.getAttributeNS(RDF, "parseType")) || !children(record).isEmpty()
                || java.util.stream.IntStream.range(0, record.getAttributes().getLength())
                   .anyMatch(index -> MAIL.equals(record.getAttributes().item(index).getNamespaceURI())));
        if (structured) destination.add(record); else valid = false;
      }
      if (!"email".equals(property) || Set.of("s", "si", "m", "mi").contains(profile))
        report.check(code, valid, property + " must be an unordered XMP array of structured records."
            + ("attachments".equals(property) ? " An empty array is permitted." : ""), null);
    }
  }

  private void infoCrosswalk() {
    COSDictionary info = dict(document.getDocument().getTrailer(), "Info");
    report.check("DOCINFO_PRESENT", !"3".equals(part) || info != null, "PDF/A-3 requires a document information dictionary.", null);
    if (!"3".equals(part)) {
      report.supported("DOCINFO_TITLE_MATCH", "DOCINFO_SUBJECT_MATCH", "DOCINFO_PRODUCER_MATCH", "DOCINFO_AUTHOR_MATCH", "DOCINFO_CREATOR_MATCH",
          "DOCINFO_CREATIONDATE_MATCH", "DOCINFO_MODDATE_MATCH", "DOCINFO_KEYWORDS");
      return;
    }
    report.check("DOCINFO_KEYWORDS", string(get(info, "Keywords")).contains("EA-PDF"), "PDF/A-3 Info Keywords must include EA-PDF.", null);
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("Title", defaultLanguage(DC, "title")); expected.put("Subject", defaultLanguage(DC, "description"));
    expected.put("Producer", value(PDF, "Producer")); expected.put("Creator", value(XMP, "CreatorTool"));
    List<Element> creators = properties(DC, "creator");
    String author = "";
    if (!creators.isEmpty() && !children(creators.getFirst()).isEmpty()) {
      var names = children(children(creators.getFirst()).getFirst());
      if (!names.isEmpty()) author = names.getFirst().getTextContent();
    }
    expected.put("Author", author); expected.put("Keywords", value(PDF, "Keywords"));
    expected.forEach((key, value) -> {
      String code = "Keywords".equals(key) ? "DOCINFO_KEYWORDS" : "DOCINFO_" + key.toUpperCase(Locale.ROOT) + "_MATCH";
      COSBase supplied = get(info, key);
      report.supported(code);
      if (supplied != null) report.check(code, supplied instanceof COSString && normal(string(supplied)).equals(normal(value)),
          "Info " + key + " must match document XMP.", Map.of("info", observed(supplied), "xmp", value));
    });
    for (String field : List.of("CreationDate", "ModDate")) {
      String code = "DOCINFO_" + field.toUpperCase(Locale.ROOT) + "_MATCH";
      report.supported(code);
      if (get(info, field) == null) continue;
      try {
        var actual = org.apache.pdfbox.util.DateConverter.toCalendar(string(get(info, field)));
        var expectedDate = OffsetDateTime.parse(value(XMP, "CreationDate".equals(field) ? "CreateDate" : "ModifyDate"));
        report.check(code, actual != null && actual.toInstant().equals(expectedDate.toInstant()), "Info date must match XMP.", Map.of("field", field));
      } catch (Exception error) { report.check(code, false, "Info date must match a valid XMP date.", Map.of("field", field)); }
    }
  }

  private void catalog() {
    report.check("CATALOG_PIECEINFO", get(catalog, "PieceInfo") == null, "Catalog PieceInfo is prohibited.", null);
    COSDictionary preferences = dict(catalog, "ViewerPreferences");
    report.check("VIEWER_PREFS", preferences != null, "ViewerPreferences is required.", null);
    if (preferences != null) report.check("DISPLAY_DOCTITLE", get(preferences, "DisplayDocTitle") == COSBoolean.TRUE, "DisplayDocTitle must be Boolean true.", null);
    report.check("PAGEMODE_PRESENT", get(catalog, "PageMode") != null, "Catalog PageMode is required.", null);
    if (preferences != null) report.check("NONFULLSCREENPAGEMODE_PRESENT", get(preferences, "NonFullScreenPageMode") != null, "NonFullScreenPageMode is required.", null);
    if (profile.isEmpty()) { report.unavailable("PROFILE_RULES_SKIPPED", "The EA-PDF profile is unavailable."); return; }
    report.check("PROFILE_RULES_SKIPPED", true, "Profile checks were applied.", Map.of("profile", profile));
  }

  private void viewerModes(Boolean hasAttachments) {
    if (!PROFILES.contains(profile)) return;
    COSDictionary preferences = dict(catalog, "ViewerPreferences");
    report.supported("PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS", "PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS", "PROFILE_PAGEMODE_MULTI", "PROFILE_PAGEMODE_CONTAINER",
        "PROFILE_NFSM_SINGLE_NO_ATTACHMENTS", "PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS", "PROFILE_NFSM_MULTI", "PROFILE_NFSM_CONTAINER");
    if (profile.startsWith("s") && hasAttachments == null) {
      for (String field : List.of("PageMode", "NonFullScreenPageMode")) {
        COSBase value = get("PageMode".equals(field) ? catalog : preferences, field);
        Set<String> allowed = "NonFullScreenPageMode".equals(field) && "3".equals(part)
            ? Set.of("UseOutlines") : Set.of("UseOutlines", "UseAttachments");
        boolean valid = value instanceof COSName && allowed.contains(name(value));
        for (String branch : List.of("SINGLE_NO_ATTACHMENTS", "SINGLE_WITH_ATTACHMENTS"))
          report.add(valid ? "WARN" : "FAIL", "PROFILE_" + ("PageMode".equals(field) ? "PAGEMODE_" : "NFSM_") + branch,
              valid ? "The attachment condition needs a uniquely matched original email."
                  : "/" + field + " must be one of " + allowed + "; every possible attachment branch is contradicted.",
              Map.of("actual", observed(value), "possible_values", allowed, "assessment", valid ? "unavailable" : "all_branches_contradicted"));
      }
      report.unavailable("VALIDATION_INCOMPLETE", "Single-message viewer attachment conditions could not be established.");
      return;
    }
    hasAttachments = Boolean.TRUE.equals(hasAttachments);
    String suffix = container() ? "CONTAINER" : profile.startsWith("m") ? "MULTI" : hasAttachments ? "SINGLE_WITH_ATTACHMENTS" : "SINGLE_NO_ATTACHMENTS";
    String expected = container() || hasAttachments && profile.startsWith("s") ? "UseAttachments" : "UseOutlines";
    // Local interpretation REEXAM-5: UseAttachments is a PDF 2.0-only exit mode.
    String nfsm = "3".equals(part) ? "UseOutlines" : expected;
    report.check("PROFILE_PAGEMODE_" + suffix, expected.equals(name(get(catalog, "PageMode"))), "Catalog /PageMode must be /" + expected + " for profile 1" + profile + ".", Map.of("expected", expected));
    report.check("PROFILE_NFSM_" + suffix, nfsm.equals(name(get(preferences, "NonFullScreenPageMode"))), "/NonFullScreenPageMode must be /" + nfsm + " for profile 1" + profile + " with PDF/A-" + part + ".", Map.of("expected", nfsm));
    report.supported("PROFILE_PAGEMODE_SINGLE_NO_ATTACHMENTS", "PROFILE_PAGEMODE_SINGLE_WITH_ATTACHMENTS", "PROFILE_PAGEMODE_MULTI", "PROFILE_PAGEMODE_CONTAINER",
        "PROFILE_NFSM_SINGLE_NO_ATTACHMENTS", "PROFILE_NFSM_SINGLE_WITH_ATTACHMENTS", "PROFILE_NFSM_MULTI", "PROFILE_NFSM_CONTAINER");
  }

  private void outlines() throws IOException {
    var outline = document.getDocumentCatalog().getDocumentOutline();
    report.check("OUTLINES_PRESENT", outline != null, "The catalog must contain outlines.", null);
    if (outline == null) return;
    Set<COSDictionary> visited = identitySet();
    List<PDOutlineItem> pending = new ArrayList<>();
    if (outline.getFirstChild() != null) pending.add(outline.getFirstChild());
    while (!pending.isEmpty()) {
      PDOutlineItem item = pending.removeLast();
      if (!visited.add(item.getCOSObject())) throw new IOException("Outline tree contains a cycle or repeated node");
      PDPage page = item.findDestinationPage(document);
      if (page != null && pageNumbers.containsKey(page.getCOSObject())) outlinePages.add(pageNumbers.get(page.getCOSObject()));
      if (item.getNextSibling() != null) pending.add(item.getNextSibling());
      if (item.getFirstChild() != null) pending.add(item.getFirstChild());
    }
    report.check("OUTLINE_ITEMS_PRESENT", !visited.isEmpty(), "Outlines must contain items.", Map.of("items", visited.size()));
  }

  private void embedded(Path path) throws Exception {
    COSDictionary names = dict(catalog, "Names");
    readNameTree(dict(names, "EmbeddedFiles"), identitySet(), 0);
    Set<COSDictionary> allSpecs = identitySet();
    allSpecs.addAll(nameTreeSpecs);
    if (java.nio.file.Files.size(path) >= 128L * 1024 * 1024 && !document.isEncrypted())
      associatedSpecs.addAll(PdfAssociationScanner.collect(path, document, InspectionBudget.nodes(document)));
    else scanAssociations(catalog, associatedSpecs, identitySet(), InspectionBudget.nodes(document));
    associatedSpecs.addAll(MarkedFileAssociations.collect(document));
    allSpecs.addAll(associatedSpecs);
    for (PDPage page : document.getPages()) {
      for (var annotation : page.getAnnotations()) {
        if (annotation instanceof PDAnnotationFileAttachment attachment) {
          COSDictionary spec = dict(attachment.getCOSObject(), "FS");
          if (spec != null) allSpecs.add(spec);
          if (get(attachment.getCOSObject(), "Contents") instanceof COSString && decodedString(get(attachment.getCOSObject(), "Contents")) == null)
            report.unavailable("ANNOTATION_CONTENTS_REQUIRED", "The annotation Contents text cannot be decoded.");
          else report.check("ANNOTATION_CONTENTS_REQUIRED", get(attachment.getCOSObject(), "Contents") instanceof COSString, "A file attachment annotation requires Contents as a PDF text string; empty text is allowed.", null);
        }
      }
    }
    COSBase associated = get(catalog, "AF");
    if (associated instanceof COSArray array) for (int index = 0; index < array.size(); index++) {
      COSBase value = array.getObject(index);
      if (value instanceof COSDictionary spec && "Source".equals(name(get(spec, "AFRelationship")))) sourceSpecs.add(spec);
    }
    if (!profile.endsWith("i")) {
      report.check("AF_RULE", associated instanceof COSArray array && array.size() > 0, "Retained-source profiles require a catalog AF array.", null);
      report.check("SOURCE_AF", !sourceSpecs.isEmpty(), "Retained-source profiles require a primary Source associated file.", null);
    } else {
      report.check("AF_RULE", true, "An isolated profile permits an omitted or supplied catalog AF array.", null);
      report.recommend("SOURCE_IN_ISOLATED", sourceSpecs.isEmpty(), "Source presence alone cannot establish faithful preservation in an isolated profile.", null);
    }
    if (sourceSpecs.isEmpty()) report.add(profile.endsWith("i") ? "PASS" : "WARN", "SOURCE_MIME_NONE", "No primary Source media type is available to check.", null);
    report.supported("AF_RULE", "SOURCE_AF", "SOURCE_IN_ISOLATED");
    for (COSDictionary spec : allSpecs) {
      COSDictionary ef = dict(spec, "EF");
      Set<COSStream> streams = identitySet();
      if (ef != null) for (COSName key : ef.keySet()) if (get(ef, key.getName()) instanceof COSStream stream) streams.add(stream);
      String filename = string(get(spec, "UF"));
      if (filename.isEmpty()) filename = string(get(spec, "F"));
      report.check("EMBEDDED_STREAM", !streams.isEmpty(), "A file specification must reference an embedded stream.", Map.of("file", filename));
      for (String key : List.of("F", "UF", "DOS", "Mac", "Unix")) {
        COSBase value = get(spec, key);
        if (value != null) report.check("EMBEDDED_FILESPEC_NO_PATH_COMPONENTS", value instanceof COSString && !string(value).matches("(?s).*(?:[/\\\\]|\\.\\.).*"),
            "File specifications must not contain path components.", Map.of("file", observed(value)));
      }
      for (COSStream stream : streams) {
        String media = name(get(stream, "Subtype"));
        Embedded embedded = inspectEmbedded(spec, stream, filename, media, name(get(spec, "AFRelationship")));
        String md5 = embedded.md5();
        COSDictionary parameters = dict(stream, "Params");
        COSBase size = get(stream, "Size") == null ? get(parameters, "Size") : get(stream, "Size");
        report.recommend("EMBEDDED_SIZE_RECOMMENDED", get(parameters, "Size") != null, "Embedded-file Params should record the uncompressed size.", Map.of("file", filename));
        if (size != null) report.check("EMBEDDED_SIZE_MATCH", size instanceof COSInteger integer && integer.longValue() == embedded.size(), "Supplied embedded size must match the decoded bytes.", Map.of("file", filename, "actual", embedded.size()));
        COSBase canonicalChecksum = get(parameters, "CheckSum");
        COSBase checksum = canonicalChecksum;
        if (checksum == null) checksum = get(stream, "CheckSum");
        if (checksum == null) checksum = get(stream, "Checksum");
        if (checksum == null) checksum = get(parameters, "Checksum");
        report.recommend("EMBEDDED_MD5_RECOMMENDED", canonicalChecksum != null, "Embedded-file Params should record CheckSum.", Map.of("file", filename));
        if (checksum != null) {
          boolean binary = checksum instanceof COSString text && text.getBytes().length == 16;
          if (canonicalChecksum != null) report.check("EMBEDDED_MD5_BINARY_FORMAT", binary, "Embedded CheckSum must be a 16-byte PDF string.", Map.of("file", filename));
          report.check("EMBEDDED_MD5_MATCH", checksum instanceof COSString text && md5.equals(binary ? HexFormat.of().formatHex(text.getBytes()) : normal(text.getString()).toLowerCase(Locale.ROOT)), "Supplied embedded MD5 must match the decoded bytes.", Map.of("file", filename, "actual", md5));
        }
        files.add(embedded);
        if (isAssociated(spec)) {
          report.check("AF_IN_NAME_TREE", nameTreeSpecs.contains(spec), "Associated file specifications must be listed in EmbeddedFiles.", Map.of("file", filename));
          COSBase modified = get(parameters, "ModDate");
          report.check("ASSOCIATED_FILE_MODDATE", modified instanceof COSString && SourceHeaders.pdfDate(string(modified)),
              "Associated embedded streams require Params ModDate as a PDF date string.", Map.of("file", filename));
        }
        COSBase mediaParameters = get(stream, "Mail_MediaTypeParameters");
        if (mediaParameters != null) report.check("EMBEDDED_MEDIATYPE_PARAMETERS_TYPE", mediaParameters instanceof COSString,
            "The supplied Mail_MediaTypeParameters entry was checked for PDF-string type.",
            Map.of("assertion", "EMBEDDED-PARAMS", "file", filename, "supplied", observed(mediaParameters)));
        report.check("EMBEDDED_MEDIATYPE_RECONSTRUCTION", get(stream, "Subtype") instanceof COSName
            && (mediaParameters == null || mediaParameters instanceof COSString), "An embedded media type must reconstruct from its name and optional parameter text.", Map.of("file", filename));
      }
    }
    if (!report.seen.contains("EMBEDDED_FILESPEC_NO_PATH_COMPONENTS")) report.check("EMBEDDED_FILESPEC_NO_PATH_COMPONENTS", true, "No embedded file specification contains a prohibited path component.", null);
    report.supported("ANNOTATION_CONTENTS_REQUIRED", "EMBEDDED_STREAM", "EMBEDDED_FILESPEC_NO_PATH_COMPONENTS", "EMBEDDED_SIZE_MATCH", "EMBEDDED_MD5_MATCH", "EMBEDDED_SIZE_RECOMMENDED", "EMBEDDED_MD5_RECOMMENDED", "EMBEDDED_MD5_BINARY_FORMAT", "EMBEDDED_MEDIATYPE_PARAMETERS_TYPE", "AF_IN_NAME_TREE", "ASSOCIATED_FILE_MODDATE", "EMBEDDED_MEDIATYPE_RECONSTRUCTION");
    report.check("EMBEDDEDFILES_PRESENT", !nameTreeSpecs.isEmpty() || profile.endsWith("i") && associatedSpecs.isEmpty(), "Associated embedded files require the EmbeddedFiles name tree.", Map.of("files", nameTreeSpecs.size()));
  }

  private boolean isAssociated(COSDictionary spec) { return associatedSpecs.contains(spec); }

  private void sourceFileSets() throws Exception {
    int declarations = 0;
    Set<COSStream> related = identitySet();
    for (COSDictionary source : sourceSpecs) {
      COSBase value = get(source, "RF");
      if (value == null) continue;
      declarations++;
      COSDictionary ef = dict(source, "EF");
      if (!(value instanceof COSDictionary rf) || value instanceof COSStream || ef == null || rf.keySet().isEmpty()) {
        report.check("SOURCE_FILE_SET", false, "Related files require RF and EF dictionaries.", null); continue;
      }
      for (COSName key : rf.keySet()) {
        COSBase raw = get(rf, key.getName());
        if (!(get(ef, key.getName()) instanceof COSStream) || !(raw instanceof COSArray array) || array.size() == 0 || array.size() % 2 != 0 || array.size() > 200000) {
          report.check("SOURCE_FILE_SET", false, "RF entries require an existing EF key and nonempty filename/stream pairs.", Map.of("key", key.getName())); continue;
        }
        for (int index = 0; index < array.size(); index += 2) {
          COSBase filename = array.getObject(index), payload = array.getObject(index + 1);
          boolean unknownName = filename instanceof COSString && decodedString(filename) == null;
          boolean valid = filename instanceof COSString && (unknownName || !string(filename).isBlank()) && payload instanceof COSStream stream && "EmbeddedFile".equals(name(get(stream, "Type")));
          if (valid && unknownName) report.unavailable("SOURCE_FILE_SET", "A related filename cannot be decoded; its bytes remain inspectable.");
          else report.check("SOURCE_FILE_SET", valid, "Each related file requires its text filename and EmbeddedFile stream.", Map.of("key", key.getName()));
          if (!valid) continue;
          COSStream stream = (COSStream) payload;
          if (!related.add(stream)) continue;
          files.add(inspectEmbedded(new COSDictionary(), stream, string(filename), name(get(stream, "Subtype")), "RF"));
        }
      }
    }
    boolean supplements = !sourceSpecs.isEmpty() && files.stream().anyMatch(file -> "Supplement".equals(file.relationship()) && assets.stream().anyMatch(asset ->
        !field(asset, "Filename").isEmpty() && MetadataChecks.integerEquals(field(asset, "SizeInBytes"), file.size())
            && file.md5().equalsIgnoreCase(field(asset, "Checksum")) && file.mediaType().equals(field(asset, "ContentType"))));
    if (declarations == 0 && !supplements) report.na("SOURCE_FILE_SET", "No related source file set is declared; omitted undeclared companions cannot be inferred.", null);
    else if (!report.seen.contains("SOURCE_FILE_SET")) report.check("SOURCE_FILE_SET", true, "Declared source supplement members are available.", null);
  }

  private void readNameTree(COSDictionary tree, Set<COSDictionary> seen, int depth) throws IOException {
    if (tree == null) return;
    if (!seen.add(tree)) throw new IOException("EmbeddedFiles tree contains a cycle or repeated node");
    COSBase names = get(tree, "Names");
    if (names instanceof COSArray array) {
      if (array.size() % 2 != 0) throw new IOException("EmbeddedFiles name tree has an unmatched entry");
      for (int index = 1; index < array.size(); index += 2) if (array.getObject(index) instanceof COSDictionary spec) {
        nameTreeSpecs.add(spec);
        treeKeys.computeIfAbsent(spec, ignored -> new ArrayList<>()).add(string(array.getObject(index - 1)));
        COSDictionary ef = dict(spec, "EF");
        if (ef != null) for (COSName key : ef.keySet()) if (get(ef, key.getName()) instanceof COSStream stream) nameTreeStreams.add(stream);
      }
    }
    if (get(tree, "Kids") instanceof COSArray kids) for (COSBase child : kids) {
      if (resolve(child) instanceof COSDictionary dictionary) readNameTree(dictionary, seen, depth + 1);
    }
  }

  static void scanAssociations(COSBase object, Set<COSDictionary> specs, Set<COSBase> seen) throws IOException {
    scanAssociations(object, specs, seen, 1000000);
  }

  static void scanAssociations(COSBase object, Set<COSDictionary> specs, Set<COSBase> seen, int limit) throws IOException {
    // PDF references form a graph, including long sibling chains and back links.
    // Their traversal depth is not the nesting depth of a PDF tree.
    var pending = new java.util.ArrayDeque<COSBase>();
    object = resolve(object);
    if (object != null) { seen.add(object); pending.add(object); }
    while (!pending.isEmpty()) {
      COSBase current = pending.removeLast();
      if (current instanceof COSDictionary dictionary) {
        if (get(dictionary, "AF") instanceof COSArray associated) for (COSBase item : associated)
          if (resolve(item) instanceof COSDictionary spec) specs.add(spec);
        for (COSName key : dictionary.keySet()) queueAssociation(dictionary.getItem(key), pending, seen, limit);
      } else if (current instanceof COSArray array) for (COSBase item : array) queueAssociation(item, pending, seen, limit);
    }
  }

  private static void queueAssociation(COSBase value, java.util.ArrayDeque<COSBase> pending, Set<COSBase> seen, int limit) throws IOException {
    value = resolve(value);
    // Primitive values cannot contain an associated-file dictionary.
    if (!(value instanceof COSDictionary || value instanceof COSArray) || !seen.add(value)) return;
    pending.add(value);
  }

  private void collection() throws IOException {
    COSDictionary collection = dict(catalog, "Collection");
    if (!container()) {
      report.check("COLLECTION_FORBIDDEN", get(catalog, "Collection") == null, "Non-collection profiles must not have a Collection entry.", null);
      report.supported("PROFILE_COLLECTION", "COLLECTION_REQUIRED", "COLLECTION_EXTENSIONS", "COLLECTION_D_FORBIDDEN", "COLLECTION_VIEW_FORBIDDEN",
          "COLLECTION_NAVIGATOR_FORBIDDEN", "COLLECTION_SCHEMA_PRESENT", "COLLECTION_SCHEMA_FIELD", "COLLECTION_SCHEMA_E_FALSE", "COLLECTION_FILENAMES_VALID", "COLLECTION_FILENAMES_UNIQUE_CASEFOLD");
      return;
    }
    report.supported("COLLECTION_FORBIDDEN");
    report.check("PROFILE_COLLECTION", collection != null, "Collection profiles require a Collection dictionary.", null);
    report.check("COLLECTION_REQUIRED", collection != null, "Collection profiles require a Collection dictionary.", null);
    if (collection == null) return;
    report.check("COLLECTION_D_FORBIDDEN", get(collection, "D") == null, "Collection D is prohibited.", null);
    report.check("COLLECTION_VIEW_FORBIDDEN", !"H".equals(name(get(collection, "View"))), "Collection View H is prohibited.", null);
    COSDictionary navigator = dict(collection, "Navigator");
    report.check("COLLECTION_NAVIGATOR_FORBIDDEN", get(navigator, "SWF") == null && get(navigator, "APIVersion") == null, "A navigator must not contain SWF or APIVersion.", null);
    if (document.getVersion() == 1.7f) {
      COSDictionary extension = dict(dict(catalog, "Extensions"), "ADBE");
      report.check("COLLECTION_EXTENSIONS", "1.7".equals(name(get(extension, "BaseVersion")))
          && get(extension, "ExtensionLevel") instanceof COSInteger integer && integer.longValue() == 5,
          "PDF 1.7 collections require ADBE extension level 5.", null);
    } else report.supported("COLLECTION_EXTENSIONS");
    COSDictionary schema = dict(collection, "Schema");
    report.check("COLLECTION_SCHEMA_PRESENT", schema != null, "Collection schema is required.", null);
    if (schema != null) {
      Map<String, String> required = Map.of("To", "S", "From", "S", "Sent", "D", "Subject", "S", "Message-ID", "S", "Cc", "S", "Bcc", "S", "Size", "Size", "Attachments", "N");
      for (var entry : required.entrySet()) report.check("COLLECTION_SCHEMA_FIELD", entry.getValue().equals(name(get(dict(schema, entry.getKey()), "Subtype"))),
          "Collection schema field " + entry.getKey() + " must have /Subtype /" + entry.getValue() + ".", Map.of("field", entry.getKey(), "expected", entry.getValue()));
      for (COSName key : schema.keySet()) {
        COSDictionary field = dict(schema, key.getName());
        COSBase editable = get(field, "E");
        if (editable != null) report.check("COLLECTION_SCHEMA_E_FALSE", editable == COSBoolean.FALSE, "Collection custom fields must not be editable.", Map.of("field", key.getName()));
      }
    }
    report.supported("COLLECTION_SCHEMA_E_FALSE");
    record Folder(COSDictionary node, String parent, int depth) {}
    Set<COSDictionary> seenFolders = identitySet();
    Set<String> names = new HashSet<>();
    var pending = new java.util.ArrayDeque<Folder>();
    COSDictionary first = dict(collection, "Folders");
    if (first != null) pending.add(new Folder(first, "root", 0));
    String rootId = first == null ? "0" : folderId(first);
    while (!pending.isEmpty()) {
      Folder entry = pending.removeFirst(); COSDictionary folder = entry.node();
      if (!seenFolders.add(folder))
        throw new java.io.IOException("Cyclic or excessive collection folder tree");
      COSBase value = get(folder, "Name");
      String filename = string(value);
      report.check("COLLECTION_FILENAMES_VALID", value instanceof COSString && validCollectionFilename(filename),
          "Folder names must be valid filename text.", Map.of("name", filename));
      report.check("COLLECTION_FILENAMES_UNIQUE_CASEFOLD", names.add(entry.parent() + ":" + UnicodeCasefold.fold(filename)),
          "Sibling folder names must be unique after Unicode case folding.", Map.of("name", filename));
      COSDictionary child = dict(folder, "Child"), next = dict(folder, "Next");
      if (child != null) pending.add(new Folder(child, folderId(folder), entry.depth() + 1));
      if (next != null) pending.add(new Folder(next, entry.parent(), entry.depth()));
    }
    names.clear();
    for (var entry : treeKeys.entrySet()) for (String key : entry.getValue()) {
      var parsed = java.util.regex.Pattern.compile("^<([0-9]+)>(.*)$", java.util.regex.Pattern.DOTALL).matcher(key);
      boolean explicit = parsed.matches();
      String folder = explicit ? new BigInteger(parsed.group(1)).toString() : rootId;
      String filename = explicit ? parsed.group(2) : key;
      report.check("COLLECTION_FILENAMES_VALID", validCollectionFilename(filename),
          "Collection filenames must obey the PDF collection naming restrictions.", Map.of("name", key));
      report.check("COLLECTION_FILENAMES_UNIQUE_CASEFOLD", names.add(folder + ":" + UnicodeCasefold.fold(filename)),
          "Filenames must be unique after Unicode case folding within each folder.", Map.of("name", key));
    }
  }

  private static String folderId(COSDictionary folder) {
    COSBase value = get(folder, "ID");
    if (value instanceof COSInteger integer) return Long.toString(integer.longValue());
    if (value instanceof COSString text && text.getString().matches("[+-]?[0-9]+")) return new BigInteger(text.getString()).toString();
    return "unknown";
  }

  private static boolean validCollectionFilename(String filename) {
    return !filename.isEmpty() && filename.codePointCount(0, filename.length()) <= 255
        && filename.chars().noneMatch(point -> "/\\:*\"<>|".indexOf(point) >= 0 || point == 0) && !filename.endsWith(".");
  }

  private void children(MetadataChecks metadataChecks) throws Exception {
    if (!container()) { report.supported("COLLECTION_CHILD_VALIDATION", "COLLECTION_CONTENT_TYPES"); return; }
    int inspected = 0;
    boolean limited = false, countEvidenceComplete = true;
    Set<COSStream> seen = identitySet(); Set<COSDictionary> sourceIdentities = identitySet(); sourceIdentities.addAll(sourceSpecs);
    for (Embedded file : files) {
      if (!nameTreeSpecs.contains(file.spec()) || sourceIdentities.contains(file.spec()) || "RF".equals(file.relationship()) || "Supplement".equals(file.relationship()) || !seen.add(file.stream())) continue;
      // Check measured decoded size before allocating or opening an embedded PDF.
      if (!children.accepts(file.size(), depth)) {
        report.unavailable("COLLECTION_CHILD_VALIDATION", "Collection child size is invalid or its byte accounting exceeds numeric capacity.");
        report.unavailable("COLLECTION_CONTENT_TYPES", "Some collection members could not be inspected within the child budget.");
        limited = true; break;
      }
      Path temporary = org.apache.pdfbox.io.IOUtils.createProtectedTempFile(Path.of(System.getProperty("java.io.tmpdir")), "eapdf-validator-child-", ".pdf");
      String hash = null;
      boolean activeAdded = false, typeChecked = false;
      try {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = file.open(); var output = new java.security.DigestOutputStream(Files.newOutputStream(temporary), digest)) {
          input.transferTo(output);
        }
        hash = HexFormat.of().formatHex(digest.digest());
        boolean identified = false;
        try (PDDocument child = IndexedPdfLoader.load(temporary, org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
          if (child.getDocumentCatalog().getMetadata() != null) {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", "0");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Element metadata = factory.newDocumentBuilder().parse(new ByteArrayInputStream(BoundedPdfStreams.bytes(child.getDocumentCatalog().getMetadata().getCOSObject(), BoundedPdfStreams.metadataLimit()))).getDocumentElement();
            Map<String, String> identification = new LinkedHashMap<>();
            for (Element element : descendants(metadata)) if (is(element, RDF, "Description")) {
              for (Element property : children(element)) if (ID.equals(property.getNamespaceURI()) && simple(property)) identification.put(property.getLocalName(), property.getTextContent());
              for (String property : List.of("version", "rev", "conformance")) if (element.hasAttributeNS(ID, property)) identification.put(property, element.getAttributeNS(ID, property));
            }
            identified = "1".equals(identification.get("version")) && "2024".equals(identification.get("rev")) && PROFILES.contains(identification.getOrDefault("conformance", ""));
          }
        } catch (IOException error) { /* An unreadable PDF cannot establish the required member type. */ }
        report.check("COLLECTION_CONTENT_TYPES", identified, "Every non-source collection member must be an EA-PDF.", Map.of("file", file.name()));
        typeChecked = true;
        boolean pdf = "application/pdf".equalsIgnoreCase(file.mediaType());
        if (!pdf) try (var input = Files.newInputStream(temporary)) {
          pdf = new String(input.readNBytes(5), java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-");
        }
        if (get(file.spec(), "CI") == null && !pdf) { countEvidenceComplete = false; continue; }
        inspected++;
        if (!children.active.add(hash)) {
          report.check("COLLECTION_CHILD_VALIDATION", false, "Recursive collection child detected.", Map.of("file", file.name()));
          countEvidenceComplete = false; hash = null; continue;
        }
        activeAdded = true;
        com.google.gson.JsonObject result = children.cache.get(hash);
        if (result == null) {
          result = JavaValidation.validate(temporary, children, depth + 1);
          children.cache.put(hash, result);
        }
        String status = JavaValidation.text(result.getAsJsonObject("summary"), "status");
        if (metadataChecks != null) metadataChecks.childCounts(result);
        if ("INCOMPLETE".equals(status)) report.unavailable("COLLECTION_CHILD_VALIDATION", "Contained email validation is incomplete: " + file.name());
        report.add("FAIL".equals(status) ? "FAIL" : "PASS".equals(status) ? "PASS" : "WARN", "COLLECTION_CHILD_VALIDATION",
            "Contained email result: " + status, Map.of("file", file.name(), "sha256", hash, "result", result));
      } catch (Exception error) {
        countEvidenceComplete = false;
        report.unavailable("COLLECTION_CHILD_VALIDATION", "Contained email validation could not finish: " + error);
        if (!typeChecked) report.unavailable("COLLECTION_CONTENT_TYPES", "The member type could not be checked: " + file.name());
      }
      finally { if (activeAdded) children.active.remove(hash); Files.deleteIfExists(temporary); }
    }
    if (!report.seen.contains("COLLECTION_CONTENT_TYPES")) report.check("COLLECTION_CONTENT_TYPES", true, "No contained non-source file contradicts EA-PDF identification.", null);
    if (!limited || inspected > 0) report.check("COLLECTION_CHILD_VALIDATION", inspected > 0, "A collection requires identifiable email PDF members.", Map.of("count", inspected));
    if (metadataChecks != null) metadataChecks.finishChildren(!limited && countEvidenceComplete && inspected > 0);
  }

  private void documentParts() throws Exception {
    Map<String, Set<String>> identities = new LinkedHashMap<>();
    for (String key : List.of("Mail_GUID", "messageid")) {
      Set<String> values = new HashSet<>();
      for (String alias : "Mail_GUID".equals(key) ? List.of(key) : List.of("messageid", "Message-ID")) {
        for (Element property : properties(MAIL, alias)) identityValues(property, values, "Mail_GUID".equals(key));
        for (Element email : emails) identityValues(fieldElement(email, alias), values, "Mail_GUID".equals(key));
      }
      identities.put(key, values);
    }
    new DocumentPartChecks(document, report, emails, profile, pageNumbers, outlinePages, identities).run();
    if (container() && !report.seen.contains("DPM_FRONTMATTER_REQUIRED_CONTAINER"))
      report.check("DPM_FRONTMATTER_REQUIRED_CONTAINER", false, "A collection requires a declared FrontMatter content set.", null);
  }

  private static void identityValues(Element property, Set<String> values, boolean guid) {
    Element value = valueElement(property);
    if (value == null) return;
    List<Element> members = children(value);
    if (members.size() == 1 && List.of("Bag", "Seq", "Alt").stream().anyMatch(kind -> is(members.getFirst(), RDF, kind))) {
      for (Element member : children(members.getFirst())) identityValues(member, values, guid);
    } else if (simple(value) || !guid && value.hasAttributeNS(RDF, "resource")) {
      String text = value.hasAttributeNS(RDF, "resource") ? value.getAttributeNS(RDF, "resource") : value.getTextContent();
      if (!guid) text = normal(text);
      if (!text.isEmpty()) values.add(text);
    }
  }
  private Element legacyEmail() {
    if (!emails.isEmpty() || xml == null || !Set.of("s", "si", "m", "mi").contains(profile)) return null;
    Element record = xml.getOwnerDocument().createElementNS(RDF, "Description");
    for (String field : List.of("Mail_GUID", "Sent", "To", "From", "Cc", "Bcc", "Raw-To", "Raw-From", "Raw-Cc", "Raw-Bcc", "Raw-Sent", "Subject", "messageid", "Message-ID", "In-Reply-To", "SizeInBytes", "NumberAttachments")) {
      var supplied = properties(MAIL, field);
      if (!supplied.isEmpty()) record.appendChild(supplied.getFirst().cloneNode(true));
    }
    return record;
  }

  private LogicalStructureChecks logicalStructure() throws Exception {
    LogicalStructureChecks logical = new LogicalStructureChecks(document, report, emails, profile, xml);
    logical.run(); return logical;
  }

  boolean container() { return "c".equals(profile) || "ci".equals(profile); }
  List<Element> properties(String ns, String property) {
    List<Element> out = new ArrayList<>();
    if (xml == null) return out;
    if (documentDescriptions == null) {
      documentDescriptions = new ArrayList<>();
      for (Element rdf : descendants(xml)) if (is(rdf, RDF, "RDF"))
        for (Element description : children(rdf)) if (is(description, RDF, "Description")) documentDescriptions.add(description);
    }
    for (Element description : documentDescriptions) {
      for (Element element : children(description)) if (is(element, ns, property)) out.add(element);
      if (description.hasAttributeNS(ns, property)) {
        Element synthetic = xml.getOwnerDocument().createElementNS(ns, property);
        synthetic.setTextContent(description.getAttributeNS(ns, property)); out.add(synthetic);
      }
    }
    return out;
  }
  String value(String ns, String property) {
    var list = properties(ns, property);
    return list.isEmpty() ? "" : textValue(list.getFirst());
  }
  private static boolean semanticTruthy(Element property) {
    Element value = valueElement(property);
    if (value == null) return false;
    if (value.hasAttributeNS(RDF, "resource")) return !value.getAttributeNS(RDF, "resource").isEmpty();
    var members = children(value);
    if (members.size() == 1 && RDF.equals(members.getFirst().getNamespaceURI())
        && List.of("Seq", "Bag", "Alt").contains(members.getFirst().getLocalName())) return !children(members.getFirst()).isEmpty();
    return !members.isEmpty() || !value.getTextContent().isEmpty();
  }
  static String textValue(Element element) {
    Element value = valueElement(element);
    if (value == null) return "";
    if (value.hasAttributeNS(RDF, "resource")) return value.getAttributeNS(RDF, "resource");
    return simple(value) ? value.getTextContent() : "";
  }
  private List<Element> defaultLanguageItems(String ns, String property) {
    List<Element> values = new ArrayList<>();
    for (Element container : properties(ns, property)) for (Element alt : children(valueElement(container))) if (is(alt, RDF, "Alt"))
      for (Element item : children(alt)) if (is(item, RDF, "li") && "x-default".equals(item.getAttributeNS(XMLConstants.XML_NS_URI, "lang")) && simple(item)) values.add(item);
    return values;
  }
  private boolean defaultLanguagePresent(String ns, String property) { return defaultLanguageItems(ns, property).size() == 1; }
  String defaultLanguage(String ns, String property) {
    var values = defaultLanguageItems(ns, property);
    return values.size() == 1 ? textValue(values.getFirst()) : "";
  }
  static Element fieldElement(Element record, String name) {
    for (Element child : children(record)) if (is(child, MAIL, name)) return child;
    if (record.hasAttributeNS(MAIL, name)) {
      Element synthetic = record.getOwnerDocument().createElementNS(MAIL, name);
      synthetic.setTextContent(record.getAttributeNS(MAIL, name)); return synthetic;
    }
    return null;
  }
  static String field(Element record, String name) {
    Element element = fieldElement(record, name);
    if (element == null && "messageid".equals(name)) element = fieldElement(record, "Message-ID");
    element = valueElement(element);
    return element == null ? "" : element.getTextContent();
  }
  static Element valueElement(Element element) {
    for (int depth = 0; element != null && depth < 256; depth++) {
      if (element.hasAttributeNS(RDF, "resource")) return element;
      List<Element> members = children(element);
      Element value = members.stream().filter(member -> is(member, RDF, "value")).findFirst().orElse(null);
      if (value != null) { element = value; continue; }
      if (members.size() == 1 && is(members.getFirst(), RDF, "Description")) { element = members.getFirst(); continue; }
      if (element.hasAttributeNS(RDF, "value")) {
        Element scalar = element.getOwnerDocument().createElementNS(RDF, "value");
        scalar.setTextContent(element.getAttributeNS(RDF, "value")); return scalar;
      }
      return element;
    }
    return null;
  }
  static boolean simple(Element element) {
    element = valueElement(element);
    return element != null && children(element).isEmpty() && !element.hasAttributeNS(RDF, "resource") && !element.hasAttributeNS(RDF, "parseType");
  }
  static boolean is(Element element, String ns, String name) { return name.equals(element.getLocalName()) && ns.equals(element.getNamespaceURI()); }
  static List<Element> children(Element element) {
    List<Element> out = new ArrayList<>();
    if (element != null) for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element e) out.add(e);
    return out;
  }
  static List<Element> descendants(Element element) {
    List<Element> out = new ArrayList<>(); if (element == null) return out;
    out.add(element);
    var all = element.getElementsByTagNameNS("*", "*");
    for (int index = 0; index < all.getLength(); index++) out.add((Element) all.item(index));
    return out;
  }
  static String normal(String text) { return text.strip().replaceAll("\\s+", " "); }
  static boolean nonnegative(String text) {
    if (text == null || !text.strip().matches("[+-]?[0-9]+")) return false;
    return !text.strip().startsWith("-") || text.strip().substring(1).matches("0+");
  }
  static boolean date(String text) {
    var date = java.util.regex.Pattern.compile("([0-9]{4})(?:-([0-9]{2})(?:-([0-9]{2})(?:T([0-9]{2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]+))?)?(Z|[+-][0-9]{2}:[0-9]{2})?)?)?)?").matcher(text);
    if (!date.matches()) return false;
    try {
      java.time.LocalDate.of(Integer.parseInt(date.group(1)), date.group(2) == null ? 1 : Integer.parseInt(date.group(2)), date.group(3) == null ? 1 : Integer.parseInt(date.group(3)));
      if (date.group(4) != null && Integer.parseInt(date.group(4)) > 23 || date.group(5) != null && Integer.parseInt(date.group(5)) > 59
          || date.group(6) != null && Integer.parseInt(date.group(6)) > 59) return false;
      String zone = date.group(8);
      return zone == null || "Z".equals(zone) || Integer.parseInt(zone.substring(1, 3)) <= 23 && Integer.parseInt(zone.substring(4)) <= 59;
    } catch (java.time.DateTimeException invalid) { return false; }
  }
  static COSBase resolve(COSBase value) { return value instanceof COSObject object ? object.getObject() : value; }
  static COSBase get(COSDictionary object, String name) { if (object == null) return null; COSBase value = object.getDictionaryObject(COSName.getPDFName(name)); return value instanceof COSNull ? null : value; }
  static COSDictionary dict(COSDictionary object, String name) { COSBase value = get(object, name); return value instanceof COSDictionary dictionary && !(value instanceof COSStream) ? dictionary : null; }
  static String name(COSBase value) { return value instanceof COSName name ? name.getName() : ""; }
  /** Decode PDF text, matching the reference PDF decoder for UTF-16 and strict UTF-8. */
  static String decodedString(COSBase value) {
    if (!(value instanceof COSString text)) return null;
    byte[] bytes = text.getBytes();
    java.nio.charset.Charset charset = null;
    int offset = 0;
    if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
      charset = java.nio.charset.StandardCharsets.UTF_8; offset = 3;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
      charset = java.nio.charset.StandardCharsets.UTF_16BE; offset = 2;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
      charset = java.nio.charset.StandardCharsets.UTF_16LE; offset = 2;
    }
    if (charset == null) return text.getString();
    try {
      // The reference decoder drops incomplete UTF-16 code units (which can yield empty text).
      var action = charset.equals(java.nio.charset.StandardCharsets.UTF_8)
          ? java.nio.charset.CodingErrorAction.REPORT : java.nio.charset.CodingErrorAction.IGNORE;
      return charset.newDecoder().onMalformedInput(action)
          .onUnmappableCharacter(action)
          .decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
    } catch (java.nio.charset.CharacterCodingException error) { return null; }
  }
  static String string(COSBase value) { String text = decodedString(value); return text == null ? "" : text; }
  static String observed(COSBase value) { return value == null ? "absent" : value instanceof COSString text ? text.getString() : value instanceof COSName name ? "/" + name.getName() : value.getClass().getSimpleName(); }
  static <T> Set<T> identitySet() { return Collections.newSetFromMap(new IdentityHashMap<>()); }
}
