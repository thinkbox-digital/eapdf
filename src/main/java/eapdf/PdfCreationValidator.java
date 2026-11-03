package eapdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.w3c.dom.Element;

final class PdfCreationValidator {
  private static final String MAIL = "http://www.pdfa.org/eapdf/ns/meta/";
  private static final String ID = "http://www.pdfa.org/eapdf/ns/id/";
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";

  static void validate(Path path) throws Exception {
    validate(path, null);
  }

  static void validate(Path path, PreservationInventory expected) throws Exception {
    validate(path, expected, false);
  }

  static void validate(Path path, PreservationInventory expected, boolean reportProgress) throws Exception {
    MetadataScanner.validate(path, reportProgress);
    try (PDDocument document = IndexedPdfLoader.load(path)) {
      ChildValidationContext context = new ChildValidationContext(expected, reportProgress);
      context.outerMetadataScanned = true;
      validate(document, 0, context);
      if (context.inventory != null) context.inventory.finish();
    }
  }

  static void validate(PDDocument document) throws Exception {
    validate(document, null);
  }

  static void validate(PDDocument document, PreservationInventory expected) throws Exception {
    validate(document, expected, false);
  }

  private static void validate(PDDocument document, PreservationInventory expected, boolean reportProgress) throws Exception {
    ChildValidationContext context = new ChildValidationContext(expected, reportProgress);
    validate(document, 0, context);
    if (context.inventory != null) context.inventory.finish();
  }

  private static final class ChildValidationContext {
    boolean outerMetadataScanned;
    long count;
    final PreservationInventory.Reconciliation inventory;
    final boolean reportProgress;

    ChildValidationContext(PreservationInventory expected, boolean reportProgress) {
      this.reportProgress = reportProgress;
      inventory = expected == null ? null : expected.new Reconciliation();
    }
  }

  private static void validate(PDDocument document, int depth, ChildValidationContext context) throws Exception {
    ProcessingLimits.checkCancelled();
    ProcessingLimits.checkPages(document.getNumberOfPages());
    var catalog = document.getDocumentCatalog();
    require(catalog.getMetadata() != null, "Missing document XMP metadata");
    if (depth != 0 || !context.outerMetadataScanned)
      validateXmpEncoding(document, depth == 0 && context.reportProgress);
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    var metadata = factory.newDocumentBuilder().parse(new ByteArrayInputStream(catalog.getMetadata().toByteArray()));
    validateAttachmentContainer(metadata.getDocumentElement());
    var profiles = metadata.getElementsByTagNameNS(ID, "conformance");
    require(profiles.getLength() == 1, "Missing or ambiguous EA-PDF identification");
    String profile = profiles.item(0).getTextContent();
    require(List.of("s", "si", "m", "mi", "c", "ci").contains(profile), "Unknown EA-PDF profile");
    if (catalog.getCOSObject().containsKey(COSName.STRUCT_TREE_ROOT)) {
      validateStructureRoot((COSDictionary) catalog.getCOSObject().getDictionaryObject(COSName.STRUCT_TREE_ROOT));
      boolean declaration = false;
      String namespace = "http://pdfa.org/declarations/";
      var declarations = metadata.getElementsByTagNameNS(namespace, "declarations");
      for (int index = 0; index < declarations.getLength(); index++) {
        var claims = ((Element) declarations.item(index)).getElementsByTagNameNS(namespace, "conformsTo");
        for (int claim = 0; claim < claims.getLength(); claim++) {
          Element target = (Element) claims.item(claim);
          var item = target.getParentNode();
          var bag = item == null ? null : item.getParentNode();
          if (!(item instanceof Element entry) || !RDF.equals(entry.getNamespaceURI()) || !"li".equals(entry.getLocalName())
              || !(bag instanceof Element container) || !RDF.equals(container.getNamespaceURI()) || !"Bag".equals(container.getLocalName())
              || bag.getParentNode() != declarations.item(index)) continue;
          String uri = target.getTextContent().trim();
          if (uri.isBlank()) uri = target.getAttributeNS(RDF, "resource");
          declaration |= "https://pdfa.org/declarations#iso32005".equals(uri);
        }
      }
      require(declaration, "Tagged PDF 2.0 is missing its ISO TS 32005 declaration");
    }
    var records = metadata.getElementsByTagNameNS(MAIL, "email");
    List<Element> emails = new ArrayList<>();
    for (int index = 0; index < records.getLength(); index++) {
      Element container = (Element) records.item(index);
      for (var child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (child instanceof Element bag) {
          for (var item = bag.getFirstChild(); item != null; item = item.getNextSibling()) {
            if (item instanceof Element email) emails.add(email);
          }
        }
      }
    }
    if (profile.equals("s") || profile.equals("si")) require(emails.size() == 1, "Single-message profile requires one email");
    if (profile.equals("m") || profile.equals("mi")) require(emails.size() >= 2, "Multi-message profile requires two emails");
    java.util.Set<String> identifiers = new java.util.HashSet<>();
    for (Element email : emails) {
      String sent = value(email, "Sent");
      boolean knownMissing = sent.isBlank() && context.inventory != null
          && context.inventory.permitsMissingSent(value(email, "Mail_GUID"));
      require(Common.validSentDate(sent) || knownMissing, "Missing or invalid Sent");
      require(!value(email, "Mail_GUID").isBlank(), "Email is missing Mail_GUID");
      require(identifiers.add(value(email, "Mail_GUID")), "Duplicate email Mail_GUID");
    }
    require(document.getNumberOfPages() > 0 && catalog.getDocumentOutline() != null, "Missing pages or outlines");
    boolean hasEmailAttachments = emails.stream().anyMatch(email -> {
      String count = value(email, "NumberAttachments");
      return !count.isBlank() && Long.parseLong(count) > 0;
    });
    String expectedMode = profile.equals("c") || profile.equals("ci") ||
        ((profile.equals("s") || profile.equals("si")) && hasEmailAttachments) ? "UseAttachments" : "UseOutlines";
    require(expectedMode.equals(catalog.getCOSObject().getNameAsString(COSName.PAGE_MODE)), "Incorrect PageMode for profile and email attachments");
    var preferences = catalog.getViewerPreferences();
    String expectedNonFullScreen = expectedMode;
    var parts = metadata.getElementsByTagNameNS("http://www.aiim.org/pdfa/ns/id/", "part");
    if ((profile.equals("s") || profile.equals("si")) && parts.getLength() > 0
        && "3".equals(parts.item(0).getTextContent())) expectedNonFullScreen = "UseOutlines";
    require(preferences != null && expectedNonFullScreen.equals(preferences.getCOSObject().getNameAsString(COSName.getPDFName("NonFullScreenPageMode"))),
        "Incorrect NonFullScreenPageMode for profile and email attachments");
    require(catalog.getCOSObject().containsKey(COSName.getPDFName("DPartRoot")), "Missing document-part metadata");
    for (var page : document.getPages()) require(page.getCOSObject().containsKey(COSName.getPDFName("DPart")), "Page is missing DPart");
    var names = catalog.getNames();
    if (names != null && names.getEmbeddedFiles() != null && names.getEmbeddedFiles().getNames() != null) {
      if (depth == 0 && context.reportProgress) ConversionProgress.begin("checking_embedded_files", names.getEmbeddedFiles().getNames().size(), "files");
      java.util.Set<String> collectionNames = new java.util.HashSet<>();
      for (var entry : names.getEmbeddedFiles().getNames().entrySet()) {
        checkFileSpecification(entry.getValue());
        if (profile.equals("c") || profile.equals("ci")) {
          String key = entry.getKey();
          var prefix = java.util.regex.Pattern.compile("^<\\d+>").matcher(key);
          String folder = prefix.find() ? prefix.group() : "";
          String filename = key.substring(folder.length());
          require(validCollectionFilename(filename), "Invalid collection filename: " + key);
          require(collectionNames.add(Common.filenameCaseKey(folder + filename)), "Duplicate collection filename: " + key);
          for (String stored : List.of(Common.nz(entry.getValue().getFile()), Common.nz(entry.getValue().getFileUnicode()))) {
            require(validCollectionFilename(stored), "Invalid collection file specification filename: " + stored);
          }
        }
        if (depth == 0 && context.reportProgress) ConversionProgress.advance("checking_embedded_files");
      }
    }
    if (depth == 0 && context.reportProgress) ConversionProgress.stage("checking_preservation");
    if (profile.equals("c") || profile.equals("ci")) {
      validateCollection(catalog.getCOSObject());
      if (context.inventory != null) context.inventory.inspect(document, metadata, profile, depth == 0);
      validateContainedFiles(document, metadata, depth, context);
      return;
    }
    var attachments = metadata.getElementsByTagNameNS(MAIL, "attachments");
    AttachmentPages attachmentPages = null;
    if (depth == 0 && context.reportProgress) ConversionProgress.beginUnknown("checking_attachment_annotations", "attachments");
    for (int index = 0; index < attachments.getLength(); index++) {
      Element container = (Element) attachments.item(index);
      var items = container.getElementsByTagNameNS(RDF, "li");
      for (int item = 0; item < items.getLength(); item++) {
        if (attachmentPages == null) attachmentPages = new AttachmentPages(document);
        validateAttachment(attachmentPages, (Element) items.item(item));
        if (depth == 0 && context.reportProgress) ConversionProgress.advance("checking_attachment_annotations");
      }
    }
    if (depth == 0 && context.reportProgress) ConversionProgress.stage("checking_preservation");
    if (context.inventory != null) context.inventory.inspect(document, metadata, profile, depth == 0);
  }

  static void validateAttachmentContainer(Element metadata) throws Exception {
    var containers = metadata.getElementsByTagNameNS(MAIL, "attachments");
    require(containers.getLength() > 0, "Missing required XMP attachments container; an empty rdf:Bag is permitted");
    for (int index = 0; index < containers.getLength(); index++) {
      Element bag = null;
      var container = containers.item(index);
      for (var child = container.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element element)) continue;
        require(bag == null && RDF.equals(element.getNamespaceURI()) && "Bag".equals(element.getLocalName()),
            "XMP attachments must be an unordered rdf:Bag");
        bag = element;
      }
      require(bag != null, "XMP attachments must contain an rdf:Bag, even when empty");
      for (var child = bag.getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element element)) continue;
        require(RDF.equals(element.getNamespaceURI()) && "li".equals(element.getLocalName()),
            "XMP attachments Bag must contain rdf:li attachment records");
      }
    }
  }

  static void validateStructureRoot(COSDictionary root) throws Exception {
    COSBase child = root.getDictionaryObject(COSName.K);
    if (child instanceof COSArray array) {
      require(array.size() == 1, "StructTreeRoot K array must have exactly one Document element");
      child = array.getObject(0);
    }
    require(child instanceof COSDictionary dictionary && !(child instanceof COSStream)
        && "Document".equals(dictionary.getNameAsString(COSName.S)), "StructTreeRoot K must be one Document dictionary");
  }

  static void validateUtf8(byte[] bytes) throws Exception {
    validateUtf8(new ByteArrayInputStream(bytes));
  }

  static void validateUtf8(java.io.InputStream input) throws Exception {
    var decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
    var declaration = new Utf8Declaration();
    try {
      var reader = new java.io.InputStreamReader(input, decoder);
      char[] buffer = new char[8192];
      for (int count; (count = reader.read(buffer)) != -1;) {
        ProcessingLimits.checkCancelled();
        for (int index = 0; index < count; index++) {
          require(buffer[index] != 0, "XMP must be UTF-8, not UTF-16/32");
          declaration.accept(buffer[index]);
        }
      }
    } catch (java.nio.charset.CharacterCodingException error) {
      throw new IOException("EA-PDF creation check failed: XMP must be UTF-8", error);
    }
  }

  /** Recognizes the leading XML declaration without retaining its text. */
  private static final class Utf8Declaration {
    private final String prefix = "<?xml";
    private int position, attribute;
    private boolean first = true, disabled, question, previousWord, found, valid = true;
    private char quote;
    private final StringBuilder value = new StringBuilder(6);
    private static boolean space(char c) { return c == ' ' || c >= '\t' && c <= '\r'; }
    private static boolean word(char c) { return Character.isLetterOrDigit(c) || c == '_'; }
    void accept(char c) throws IOException {
      if (disabled) return;
      if (first) { first = false; if (c == '\uFEFF') return; }
      if (position == 0 && space(c)) return;
      if (position < prefix.length()) {
        if (c != prefix.charAt(position++)) disabled = true;
        return;
      }
      if (position == prefix.length()) {
        if (!space(c)) disabled = true;
        position++; return;
      }
      if (question) {
        if (c == '>') require(!found || valid, "XMP XML declaration must specify UTF-8");
        disabled = true; return;
      }
      if (c == '?') { question = true; return; }
      if (!found) {
        char lower = Character.toLowerCase(c);
        if (attribute > 0 && attribute < 8) {
          if (lower == "encoding".charAt(attribute)) attribute++;
          else attribute = 0;
        } else if (attribute == 8) {
          if (c == '=') attribute = 9;
          else if (!space(c)) attribute = 0;
        } else if (attribute == 9) {
          if (c == '\'' || c == '"') { quote = c; attribute = 10; }
          else if (!space(c)) attribute = 0;
        } else if (attribute == 10) {
          if (c == quote) {
            found = true; valid = value.toString().equals("utf-8") || value.toString().equals("utf8");
          } else if (value.length() < 6) value.append(lower);
        }
        if (attribute == 0 && !previousWord && lower == 'e') attribute = 1;
      }
      previousWord = word(c);
    }
  }

  static void validateXmpEncoding(PDDocument document) throws Exception {
    validateXmpEncoding(document, false);
  }

  private static void validateXmpEncoding(PDDocument document, boolean reportProgress) throws Exception {
    if (reportProgress) ConversionProgress.beginUnknown("checking_metadata_objects", "objects");
    java.util.Set<COSStream> streams = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    visitDocumentDictionaries(document, dictionary -> {
      var metadata = dictionary.getDictionaryObject(COSName.METADATA);
      if (metadata instanceof COSStream stream) streams.add(stream);
      if (dictionary instanceof COSStream stream && COSName.METADATA.equals(stream.getCOSName(COSName.TYPE))) streams.add(stream);
      if (reportProgress) ConversionProgress.advance("checking_metadata_objects");
    });
    if (reportProgress) ConversionProgress.begin("checking_metadata_encoding", streams.size(), "metadata streams");
    for (COSStream stream : streams) {
      try (var input = stream.createInputStream()) { validateUtf8(input); }
      if (reportProgress) ConversionProgress.advance("checking_metadata_encoding");
    }
  }

  @FunctionalInterface
  private interface DictionaryVisitor { void accept(COSDictionary dictionary) throws Exception; }

  private static void visitDocumentDictionaries(PDDocument document, DictionaryVisitor visitor) throws Exception {
    java.util.Set<COSBase> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    var pending = new java.util.ArrayDeque<COSBase>();
    pending.add(document.getDocument().getTrailer());
    visitDictionaryGraph(pending, seen, visitor);
    // Walk reachable objects in parent-first order before loading orphan objects.
    // Loading every object first can build long, unattached sibling chains which
    // overflow PDFBox's recursive document-state propagation when later attached.
    // Keep checking orphan metadata too; do not narrow the original check's scope.
    for (var key : new java.util.ArrayList<>(document.getDocument().getXrefTable().keySet())) {
      pending.add(document.getDocument().getObjectFromPool(key));
      visitDictionaryGraph(pending, seen, visitor);
    }
  }

  private static void visitDictionaryGraph(java.util.ArrayDeque<COSBase> pending,
      java.util.Set<COSBase> seen, DictionaryVisitor visitor) throws Exception {
    while (!pending.isEmpty()) {
      COSBase base = pending.removeFirst();
      if (base instanceof COSObject object) base = object.getObject();
      if (!(base instanceof COSDictionary) && !(base instanceof COSArray)) continue;
      if (!seen.add(base)) continue;
      ProcessingLimits.checkCancelled();
      if (base instanceof COSDictionary dictionary) {
        visitor.accept(dictionary);
        for (COSBase value : dictionary.getValues()) if (value != null) pending.add(value);
      } else if (base instanceof COSArray array) {
        for (COSBase value : array) if (value != null) pending.add(value);
      }
    }
  }

  static void validateRelatedFiles(COSDictionary file) throws Exception {
    if (!file.containsKey(COSName.getPDFName("RF"))) return;
    var related = file.getDictionaryObject(COSName.getPDFName("RF"));
    var embedded = file.getDictionaryObject(COSName.EF);
    require(related instanceof COSDictionary && embedded instanceof COSDictionary
        && COSName.FILESPEC.equals(file.getCOSName(COSName.TYPE)), "RF requires Filespec and RF/EF dictionaries");
    var rf = (COSDictionary) related;
    var ef = (COSDictionary) embedded;
    require(!rf.keySet().isEmpty(), "RF dictionary must not be empty");
    for (COSName key : rf.keySet()) {
      var value = rf.getDictionaryObject(key);
      require(ef.getDictionaryObject(key) instanceof COSStream && value instanceof COSArray,
          "Every RF key requires an EF stream and related-files array");
      COSArray pairs = (COSArray) value;
      require(pairs.size() > 0 && pairs.size() % 2 == 0, "RF must contain filename/stream pairs");
      for (int index = 0; index < pairs.size(); index += 2) {
        require(pairs.getObject(index) instanceof org.apache.pdfbox.cos.COSString name && !name.getString().isBlank()
            && pairs.getObject(index + 1) instanceof COSStream stream && COSName.EMBEDDED_FILE.equals(stream.getCOSName(COSName.TYPE)),
            "Invalid RF filename or embedded-file stream");
      }
    }
  }

  private static String embeddedMediaType(COSStream stream) {
    String type = Common.nz(stream.getNameAsString(COSName.SUBTYPE));
    String parameters = stream.getString(COSName.getPDFName("Mail_MediaTypeParameters"));
    return parameters == null ? type : type + ";" + parameters;
  }

  private static boolean sourceSupplement(COSDictionary file, org.w3c.dom.Document metadata) throws Exception {
    var embedded = file.getDictionaryObject(COSName.EF);
    if (!(embedded instanceof COSDictionary ef)) return false;
    COSBase payload = ef.getDictionaryObject(COSName.UF, COSName.F);
    if (!(payload instanceof COSStream stream)) return false;
    MessageDigest checksum = MessageDigest.getInstance("MD5");
    long size;
    try (var input = new DigestInputStream(stream.createInputStream(), checksum)) {
      size = input.transferTo(java.io.OutputStream.nullOutputStream());
    }
    String digest = HexFormat.of().formatHex(checksum.digest());
    var containers = metadata.getElementsByTagNameNS(MAIL, "assets");
    for (int index = 0; index < containers.getLength(); index++) {
      var assets = ((Element) containers.item(index)).getElementsByTagNameNS(RDF, "li");
      for (int entry = 0; entry < assets.getLength(); entry++) {
        Element asset = (Element) assets.item(entry);
        String name = value(asset, "Filename");
        // XMP source names may contain original absolute/relative paths. The
        // preserved bytes, size and media type establish asset identity.
        if (!name.isBlank()
            && Long.toString(size).equals(value(asset, "SizeInBytes")) && digest.equalsIgnoreCase(value(asset, "Checksum"))
            && Common.sameContentType(value(asset, "ContentType"), embeddedMediaType(stream))) return true;
      }
    }
    return false;
  }

  private static void validateContainedFiles(PDDocument document, org.w3c.dom.Document metadata,
                                            int depth, ChildValidationContext context) throws Exception {
    var listedFiles = context.inventory == null ? null : PreservationInventory.listedFiles(document);
    java.util.Set<COSDictionary> sources = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    java.util.Set<COSStream> related = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    var af = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
    if (af instanceof COSArray array) for (int index = 0; index < array.size(); index++) {
      if (array.getObject(index) instanceof COSDictionary file && "Source".equals(file.getNameAsString(COSName.getPDFName("AFRelationship")))) {
        sources.add(file);
        validateRelatedFiles(file);
        if (file.getDictionaryObject(COSName.getPDFName("RF")) instanceof COSDictionary rf) {
          for (COSBase value : rf.getValues()) {
            if (value instanceof COSObject object) value = object.getObject();
            if (value instanceof COSArray pairs) for (int pair = 1; pair < pairs.size(); pair += 2) related.add((COSStream) pairs.getObject(pair));
          }
        }
      }
    }
    java.util.Set<COSDictionary> supplements = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    if (!sources.isEmpty() && af instanceof COSArray array) for (int index = 0; index < array.size(); index++) {
      if (array.getObject(index) instanceof COSDictionary file && "Supplement".equals(file.getNameAsString(COSName.getPDFName("AFRelationship")))
          && sourceSupplement(file, metadata)) supplements.add(file);
    }
    visitDocumentDictionaries(document, file -> {
      if (!(file.getDictionaryObject(COSName.EF) instanceof COSDictionary ef) || sources.contains(file) || supplements.contains(file)) return;
      COSBase stream = ef.getDictionaryObject(COSName.UF, COSName.F);
      if (stream instanceof COSStream payload && related.contains(payload)) return;
      require(stream instanceof COSStream, "Contained file has no embedded stream");
      if (listedFiles != null && !listedFiles.contains(file))
        throw new IOException("Preservation inventory mismatch: Contained EA-PDF is absent from the collection member listing");
      ProcessingLimits.checkCancelled();
      ProcessingLimits.checkCount(++context.count);
      Path temporary = java.nio.file.Files.createTempFile("eapdf-contained-", ".pdf");
      try {
        try (var input = ((COSStream) stream).createInputStream();
             var output = java.nio.file.Files.newOutputStream(temporary)) {
          byte[] buffer = new byte[8192];
          int count; long temporaryBytes = 0;
          while ((count = input.read(buffer)) != -1) {
            temporaryBytes = Math.addExact(temporaryBytes, count);
            ProcessingLimits.checkOutput(temporaryBytes);
            output.write(buffer, 0, count);
          }
        }
        try (PDDocument child = IndexedPdfLoader.load(temporary, org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache())) {
          require(child.getDocumentCatalog().getMetadata() != null, "Contained file is not an EA-PDF");
          validateXmpEncoding(child);
          var factory = DocumentBuilderFactory.newInstance();
          factory.setNamespaceAware(true);
          factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
          var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(child.getDocumentCatalog().getMetadata().toByteArray()));
          var profiles = xml.getElementsByTagNameNS(ID, "conformance");
          require(profiles.getLength() == 1 && List.of("s", "si", "m", "mi", "c", "ci").contains(profiles.item(0).getTextContent()),
              "Contained file is not an EA-PDF; use an auxiliary sidecar");
          for (var expected : java.util.Map.of("version", "1", "rev", "2024").entrySet()) {
            var values = xml.getElementsByTagNameNS(ID, expected.getKey());
            require(values.getLength() == 1 && expected.getValue().equals(values.item(0).getTextContent()), "Contained file has invalid EA-PDF identification");
          }
          // Reopen and check the bytes actually embedded in the final parent.
          // Identification alone cannot establish child creation consistency.
          validate(child, depth + 1, context);
        }
      } finally { java.nio.file.Files.deleteIfExists(temporary); }
    });
  }

  private static final class AttachmentPages {
    private final List<org.apache.pdfbox.pdmodel.PDPage> all = new ArrayList<>();
    private final java.util.Map<String, List<org.apache.pdfbox.pdmodel.PDPage>> byMessage = new java.util.HashMap<>();
    AttachmentPages(PDDocument document) {
      for (var page : document.getPages()) {
        all.add(page);
        COSDictionary part = (COSDictionary) page.getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
        byMessage.computeIfAbsent(messageIdentifier(part), ignored -> new ArrayList<>()).add(page);
      }
    }
    List<org.apache.pdfbox.pdmodel.PDPage> forMessage(String identifier) {
      return identifier.isBlank() ? all : byMessage.getOrDefault(identifier, List.of());
    }
  }

  private static void validateAttachment(AttachmentPages pages, Element metadata) throws Exception {
    String filename = Common.portablePdfFilename(value(metadata, "Filename"));
    long expectedSize = Long.parseLong(value(metadata, "SizeInBytes"));
    String expectedType = value(metadata, "Content-Type");
    String expectedChecksum = value(metadata, "CheckSum");
    String identifier = value(metadata, "Mail_GUID");
    for (var page : pages.forMessage(identifier)) {
      for (var annotation : page.getAnnotations()) {
        if (!(annotation instanceof PDAnnotationFileAttachment attachment)
            || !(attachment.getFile() instanceof PDComplexFileSpecification file)) continue;
        checkFileSpecification(file);
        if (!filename.equals(file.getFilename())) continue;
        var embedded = file.getEmbeddedFileUnicode() == null ? file.getEmbeddedFile() : file.getEmbeddedFileUnicode();
        if (embedded == null) continue;
        String type = embedded.getSubtype();
        String parameters = embedded.getCOSObject().getString(COSName.getPDFName("Mail_MediaTypeParameters"));
        if (parameters != null) type += ";" + parameters;
        if (!Common.sameContentType(expectedType, type)) continue;
        MessageDigest checksum = MessageDigest.getInstance("MD5");
        long size;
        try (var input = new DigestInputStream(embedded.createInputStream(), checksum)) {
          size = input.transferTo(java.io.OutputStream.nullOutputStream());
        }
        if (size != expectedSize) continue;
        byte[] actualChecksum = checksum.digest();
        if (!expectedChecksum.isBlank() && !expectedChecksum.equalsIgnoreCase(HexFormat.of().formatHex(actualChecksum))) continue;
        COSDictionary parametersDictionary = (COSDictionary) embedded.getCOSObject().getDictionaryObject(COSName.PARAMS);
        var storedSize = parametersDictionary == null ? null : parametersDictionary.getDictionaryObject(COSName.SIZE);
        if (storedSize != null && (!(storedSize instanceof org.apache.pdfbox.cos.COSInteger integer)
            || integer.longValue() != size)) continue;
        var storedChecksum = parametersDictionary == null ? null : parametersDictionary.getDictionaryObject(COSName.getPDFName("CheckSum"));
        if (storedChecksum != null && (!(storedChecksum instanceof org.apache.pdfbox.cos.COSString binary)
            || !MessageDigest.isEqual(binary.getBytes(), actualChecksum))) continue;
        require(!annotation.isHidden() && !annotation.isInvisible() && !annotation.isNoView(), "Attachment annotation is not visible");
        var rectangle = annotation.getRectangle();
        require(rectangle != null && page.getCropBox().contains(rectangle.getLowerLeftX(), rectangle.getLowerLeftY())
            && page.getCropBox().contains(rectangle.getUpperRightX(), rectangle.getUpperRightY()), "Attachment annotation is outside CropBox");
        return;
      }
    }
    throw new IOException("EA-PDF creation check failed: no matching native attachment annotation for " + filename);
  }

  static String messageIdentifier(COSDictionary part) {
    java.util.Set<COSDictionary> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    while (part != null && seen.add(part) && seen.size() <= 256) {
      COSDictionary properties = (COSDictionary) part.getDictionaryObject(COSName.getPDFName("DPM"));
      if (properties != null && properties.containsKey(COSName.getPDFName("Mail_GUID"))) return properties.getString(COSName.getPDFName("Mail_GUID"));
      part = (COSDictionary) part.getDictionaryObject(COSName.getPDFName("Parent"));
    }
    return "";
  }

  private static void checkFileSpecification(PDComplexFileSpecification file) throws Exception {
    validateRelatedFiles(file.getCOSObject());
    for (COSName key : List.of(COSName.F, COSName.UF)) {
      String filename = file.getCOSObject().getString(key);
      if (filename != null) require(filename.equals(Common.portablePdfFilename(filename)), "Nonportable embedded filename: " + filename);
    }
    var embedded = file.getCOSObject().getDictionaryObject(COSName.EF);
    if (!(embedded instanceof COSDictionary streams)) return;
    java.util.Set<org.apache.pdfbox.cos.COSStream> checked = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (COSName key : streams.keySet()) {
      var entry = streams.getDictionaryObject(key);
      if (!(entry instanceof org.apache.pdfbox.cos.COSStream stream) || !checked.add(stream)) continue;
      var parameters = stream.getDictionaryObject(COSName.PARAMS);
      if (!(parameters instanceof COSDictionary dictionary)) continue;
      var stored = dictionary.getDictionaryObject(COSName.getPDFName("CheckSum"));
      if (stored == null) continue;
      require(stored instanceof org.apache.pdfbox.cos.COSString binary && binary.getBytes().length == 16,
          "Embedded-file checksum must be a 16-byte PDF string: " + file.getFilename());
      MessageDigest checksum = MessageDigest.getInstance("MD5");
      try (var input = new DigestInputStream(stream.createInputStream(), checksum)) {
        input.transferTo(java.io.OutputStream.nullOutputStream());
      }
      require(MessageDigest.isEqual(((org.apache.pdfbox.cos.COSString) stored).getBytes(), checksum.digest()),
          "Embedded-file checksum does not match its payload: " + file.getFilename());
    }
  }

  private static String value(Element element, String name) {
    var values = element.getElementsByTagNameNS(MAIL, name);
    return values.getLength() == 0 ? "" : values.item(0).getTextContent();
  }

  private static boolean validCollectionFilename(String name) {
    return !name.isEmpty() && name.codePointCount(0, name.length()) <= 255 && !name.endsWith(".")
        && !java.util.regex.Pattern.compile("[\\x00\\\\/:*\"<>|]").matcher(name).find();
  }

  private static void validateCollection(COSDictionary catalog) throws Exception {
    var collectionValue = catalog.getDictionaryObject(COSName.getPDFName("Collection"));
    require(collectionValue instanceof COSDictionary, "Missing Collection dictionary");
    var collection = (COSDictionary) collectionValue;
    require(!collection.containsKey(COSName.D), "Collection default document is forbidden");
    var schemaValue = collection.getDictionaryObject(COSName.getPDFName("Schema"));
    require(schemaValue instanceof COSDictionary, "Missing collection schema");
    var schema = (COSDictionary) schemaValue;
    for (COSName field : schema.keySet()) {
      var definition = schema.getDictionaryObject(field);
      if (definition instanceof COSDictionary dictionary && dictionary.containsKey(COSName.E)) {
        require(org.apache.pdfbox.cos.COSBoolean.FALSE.equals(dictionary.getDictionaryObject(COSName.E)),
            "Editable collection schema field: " + field.getName());
      }
    }
    var folders = collection.getDictionaryObject(COSName.getPDFName("Folders"));
    if (folders instanceof COSDictionary root) validateFolders(root, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
  }

  private static void validateFolders(COSDictionary folder, int depth, java.util.Set<COSDictionary> visited) throws Exception {
    ProcessingLimits.checkCancelled();
    java.util.Set<String> siblings = new java.util.HashSet<>();
    COSDictionary current = folder;
    while (current != null) {
      require(visited.size() < 10000, "Excessive collection folder tree");
      require(visited.add(current), "Cyclic collection folder tree");
      String name = Common.nz(current.getString(COSName.NAME));
      require(validCollectionFilename(name), "Invalid collection folder name: " + name);
      require(siblings.add(Common.filenameCaseKey(name)), "Duplicate collection folder name: " + name);
      var child = current.getDictionaryObject(COSName.getPDFName("Child"));
      if (child instanceof COSDictionary dictionary) validateFolders(dictionary, depth + 1, visited);
      var next = current.getDictionaryObject(COSName.NEXT);
      current = next instanceof COSDictionary dictionary ? dictionary : null;
    }
  }

  private static void require(boolean valid, String message) throws IOException {
    if (!valid) throw new IOException("EA-PDF creation check failed: " + message);
  }
}
