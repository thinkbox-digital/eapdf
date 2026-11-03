package eapdf;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.w3c.dom.Element;

/** Immutable expectations from normalized input streams, independent of generated PDF metadata. */
final class PreservationInventory {
  private static final String MAIL = "http://www.pdfa.org/eapdf/ns/meta/";
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  record Fingerprint(long size, String sha256) {}
  record Attachment(String filename, String contentType, Fingerprint original) {}
  record Asset(String relationship, Fingerprint original) {}
  record Agent(String name, String mailbox) {}
  record Headers(Map<String, String> fields, Map<String, List<Agent>> addresses, List<String> replies) {}
  record Message(String guid, List<Attachment> attachments, List<Asset> assets, Headers headers,
                 String bodyContentType, boolean sentUnavailable, List<String> problems) {}

  final String profile;
  final boolean isolated;
  final String sourceDisposition;
  final String attachmentDisposition;
  final Map<String, Message> messages;

  private PreservationInventory(String profile, Map<String, Message> messages) {
    this.profile = profile;
    this.isolated = profile.endsWith("i");
    this.sourceDisposition = isolated ? "omitted by isolation policy; not fingerprinted" : "retained";
    this.attachmentDisposition = isolated ? "zero-byte placeholders; original hashes when available" : "native bytes retained";
    this.messages = Map.copyOf(messages);
  }

  static PreservationInventory capture(List<EmailRecord> records, String profile) throws Exception {
    ProcessingLimits.checkCount(records.size());
    Map<String, Message> messages = new LinkedHashMap<>();
    boolean isolated = profile.endsWith("i");
    long metadataSize = 0;
    for (EmailRecord record : records) {
      Common.finalizeRecordMetadata(record);
      Common.requireCreationDate(record);
      List<Attachment> attachments = new ArrayList<>();
      List<Asset> assets = new ArrayList<>();
      for (AssociatedFile file : record.associatedFiles) {
        if (file == null) continue;
        if (file.emailAttachment || !isolated && ("Source".equals(file.afRelationship) || file.sourceSetMember)) {
          metadataSize = Math.addExact(metadataSize, 1024L + 2L * (Common.nz(file.filename).length()
              + Common.sourceContentType(file).length()));
          require(metadataSize <= ProcessingLimits.metadataBytes(), "Input inventory exceeds the metadata budget");
          require(file.hasData() || isolated, "Input payload is unavailable: " + file.filename);
          Fingerprint original = null;
          if (file.hasData()) try (InputStream input = file.openData()) { original = fingerprint(input); }
          if (file.emailAttachment) attachments.add(new Attachment(Common.portablePdfFilename(file.filename),
              Common.sourceContentType(file), original));
          if (!isolated && ("Source".equals(file.afRelationship) || file.sourceSetMember))
            assets.add(new Asset(file.sourceSetMember ? "Supplement" : "Source", original));
        }
      }
      if (!isolated && assets.stream().noneMatch(asset -> asset.relationship().equals("Source"))) {
        byte[] source = record.sourceBytes == null ? Common.synthesizeEml(record) : record.sourceBytes;
        assets.add(new Asset("Source", fingerprint(new ByteArrayInputStream(source))));
      }
      Headers headers = captureHeaders(record);
      long headerSize = headers.fields().values().stream().mapToLong(String::length).sum()
          + headers.replies().stream().mapToLong(String::length).sum()
          + headers.addresses().values().stream().flatMap(List::stream)
              .mapToLong(agent -> (long) agent.name().length() + agent.mailbox().length()).sum();
      String bodyType = Common.contentSetSubtype(record);
      metadataSize = Math.addExact(metadataSize, 1024L + 2L * (record.mailGuid.length() + headerSize + bodyType.length()));
      require(metadataSize <= ProcessingLimits.metadataBytes(), "Input inventory exceeds the metadata budget");
      Message previous = messages.put(record.mailGuid, new Message(record.mailGuid, List.copyOf(attachments), List.copyOf(assets),
          headers, bodyType, !Common.validSentDate(record.sentXmp), record.sourceProblems.stream()
          // Diagnostic text follows XML's character and line-ending rules.
          // Source and attachment fingerprints above still compare original bytes.
          .map(problem -> Common.normalizeBody(record.mailGuid + " | " + record.subject + " | " + problem)).toList()));
      require(previous == null, "Duplicate input Mail_GUID: " + record.mailGuid);
    }
    require(!messages.isEmpty(), "No expected input messages");
    return new PreservationInventory(profile, messages);
  }

  private static Headers captureHeaders(EmailRecord record) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("Subject", record.subject);
    fields.put("messageid", record.messageId);
    fields.put("Raw-From", record.rawFrom);
    fields.put("Raw-To", record.rawTo);
    fields.put("Raw-Cc", record.rawCc);
    fields.put("Raw-Bcc", record.rawBcc);
    fields.put("Sent", record.sentXmp);
    if (Common.sourceDateNeedsRaw(record.rawDateHeader)) fields.put("Raw-Sent", record.rawDateHeader);
    fields.replaceAll((name, value) -> Common.normalizeBody(value));
    fields.values().removeIf(String::isBlank);
    Map<String, List<Agent>> addresses = new LinkedHashMap<>();
    addresses.put("From", captureAgents(record.fromAgents));
    addresses.put("To", captureAgents(record.toAgents));
    addresses.put("Cc", captureAgents(record.ccAgents));
    addresses.put("Bcc", captureAgents(record.bccAgents));
    return new Headers(Map.copyOf(fields), Map.copyOf(addresses),
        record.inReplyTo.stream().map(Common::normalizeBody).toList());
  }

  private static List<Agent> captureAgents(List<MailAgent> agents) {
    if (agents == null) return List.of();
    // Non-SMTP originals retain their existing Raw-* representation. This
    // snapshot verifies emitted FOAF values without inventing SMTP addresses.
    return agents.stream().filter(agent -> Common.smtpMailbox(agent.email))
        .map(agent -> new Agent(Common.normalizeBody(agent.name).replace('\n', ' ').replace('\t', ' '),
            Common.mailto(agent.email))).toList();
  }

  private static void reconcileHeaders(Element row, Message expected) throws IOException {
    for (var field : expected.headers().fields().entrySet())
      require(field.getValue().equals(value(row, field.getKey())),
          "Email header " + field.getKey() + " differs from input for " + expected.guid());
    String foaf = "http://xmlns.com/foaf/0.1/";
    for (var address : expected.headers().addresses().entrySet()) {
      List<Agent> actual = new ArrayList<>();
      Element field = property(row, address.getKey());
      if (field != null) {
        var agents = field.getElementsByTagNameNS(RDF, "Description");
        for (int index = 0; index < agents.getLength(); index++) {
          Element agent = (Element) agents.item(index);
          actual.add(new Agent(agent.getAttributeNS(foaf, "name"), agent.getAttributeNS(foaf, "mbox")));
        }
      }
      require(address.getValue().equals(actual), "Email header " + address.getKey() + " differs from input for " + expected.guid());
    }
    Map<String, Integer> replies = new HashMap<>();
    Element field = property(row, "In-Reply-To");
    if (field != null) {
      var values = field.getElementsByTagNameNS(RDF, "li");
      for (int index = 0; index < values.getLength(); index++) replies.merge(values.item(index).getTextContent(), 1, Integer::sum);
    }
    Map<String, Integer> originalReplies = new HashMap<>();
    expected.headers().replies().forEach(reply -> originalReplies.merge(reply, 1, Integer::sum));
    require(originalReplies.equals(replies), "Email header In-Reply-To differs from input for " + expected.guid());
  }

  private static Fingerprint fingerprint(InputStream input) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[8192];
    long size = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      size = Math.addExact(size, count);
      ProcessingLimits.checkOutput(size);
      digest.update(buffer, 0, count);
    }
    return new Fingerprint(size, HexFormat.of().formatHex(digest.digest()));
  }

  final class Reconciliation {
    boolean permitsMissingSent(String guid) {
      Message expected = messages.get(guid);
      return expected != null && expected.sentUnavailable();
    }

    private final Map<String, Integer> renderedMessages = new HashMap<>();

    void inspect(PDDocument document, org.w3c.dom.Document metadata, String actualProfile, boolean root) throws Exception {
      if (root) require(profile.equals(actualProfile), "Output profile differs from the captured policy");
      var inspected = root ? messages.values().stream()
          : rows(metadata, "email").stream().map(row -> messages.get(value(row, "Mail_GUID"))).filter(java.util.Objects::nonNull);
      List<String> problems = inspected.flatMap(message -> message.problems().stream()).toList();
      if (!problems.isEmpty()) {
        String namespace = "https://thinkbox.digital/ns/eapdf/conversion/1.0/";
        var states = metadata.getElementsByTagNameNS(namespace, "status");
        require(states.getLength() == 1 && "INCOMPLETE".equals(states.item(0).getTextContent().trim()),
            "Missing incomplete-source status");
        var notes = metadata.getElementsByTagNameNS(namespace, "problems");
        Set<String> retained = new HashSet<>();
        if (notes.getLength() == 1) {
          var entries = ((Element)notes.item(0)).getElementsByTagNameNS(RDF, "li");
          for (int i = 0; i < entries.getLength(); i++) retained.add(entries.item(i).getTextContent());
        }
        require(retained.containsAll(problems), "Missing source-problem details");
      }
      require(isolated == actualProfile.endsWith("i"), "Child isolation policy differs from the expected input policy");
      Map<String, Message> present = new LinkedHashMap<>();
      for (Element row : rows(metadata, "email")) {
        String guid = value(row, "Mail_GUID");
        Message expected = messages.get(guid);
        require(expected != null && present.put(guid, expected) == null, "Unexpected or repeated output Mail_GUID: " + guid);
        reconcileHeaders(row, expected);
        require(Integer.toString(expected.attachments().size()).equals(value(row, "NumberAttachments")),
            "Attachment count differs from input for " + guid);
      }
      require(!present.isEmpty(), "Output contains no expected messages");
      if (root) require(present.keySet().equals(messages.keySet()), "Output message inventory differs from input");
      Map<String, Integer> attachmentRows = new HashMap<>();
      for (Element row : rows(metadata, "attachments")) {
        String guid = value(row, "Mail_GUID");
        require(present.containsKey(guid), "Attachment metadata has an unexpected owner: " + guid);
        attachmentRows.merge(guid, 1, Integer::sum);
      }
      for (Message message : present.values()) require(attachmentRows.getOrDefault(message.guid(), 0) == message.attachments().size(),
          "Attachment metadata inventory differs from input for " + message.guid());

      Map<Asset, Integer> expectedAssets = new HashMap<>();
      for (Message message : present.values()) for (Asset asset : message.assets()) addAsset(expectedAssets, asset);
      Map<Asset, Integer> actualAssets = new HashMap<>();
      IdentityHashMap<COSStream, Fingerprint> streams = new IdentityHashMap<>();
      var associated = document.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
      if (associated instanceof COSArray array) for (int index = 0; index < array.size(); index++) {
        if (!(array.getObject(index) instanceof COSDictionary file)) continue;
        String relationship = file.getNameAsString(COSName.getPDFName("AFRelationship"));
        if ("Source".equals(relationship) || "Supplement".equals(relationship))
          addAsset(actualAssets, new Asset(relationship, payload(new PDComplexFileSpecification(file), streams)));
      }
      require(expectedAssets.equals(actualAssets), "Retained source/file-set bytes differ from input");
      if (actualProfile.equals("c") || actualProfile.equals("ci")) return;

      Map<String, List<Attachment>> remaining = new HashMap<>();
      for (Message message : present.values()) {
        renderedMessages.merge(message.guid(), 1, Integer::sum);
        remaining.put(message.guid(), new ArrayList<>(message.attachments()));
      }
      Set<String> pageOwners = new HashSet<>();
      Set<COSDictionary> annotations = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      Map<String, Set<COSDictionary>> referencedFiles = new HashMap<>();
      Fingerprint empty = fingerprint(new ByteArrayInputStream(new byte[0]));
      for (var page : document.getPages()) {
        var part = page.getCOSObject().getDictionaryObject(COSName.getPDFName("DPart"));
        String guid = part instanceof COSDictionary dictionary ? PdfCreationValidator.messageIdentifier(dictionary) : "";
        if (!guid.isBlank()) {
          require(present.containsKey(guid), "Rendered page has an unexpected message owner: " + guid);
          pageOwners.add(guid);
        }
        COSDictionary content = part instanceof COSDictionary dictionary
            ? dictionary.getCOSDictionary(COSName.getPDFName("DPM")) : null;
        if (content != null && "BodyRendering".equals(content.getNameAsString(COSName.getPDFName("Mail_ContentSetType")))) {
          Message expected = present.get(guid);
          require(expected != null, "Body rendering has no expected message owner");
          String actualType = Common.nz(content.getNameAsString(COSName.getPDFName("Mail_Subtype")));
          require(expected.bodyContentType().equals(actualType) || Common.sameContentType(expected.bodyContentType(), actualType),
              "Body rendering media type differs from input for " + guid);
        }
        for (var annotation : page.getAnnotations()) {
          if (!(annotation instanceof PDAnnotationFileAttachment attachment)
              || !(attachment.getFile() instanceof PDComplexFileSpecification file)) continue;
          if (!"Data".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))) continue;
          require(annotations.add(annotation.getCOSObject()), "One attachment annotation is reused for multiple occurrences");
          List<Attachment> candidates = remaining.get(guid);
          require(candidates != null, "Native attachment has no expected message owner");
          // Body and attachment-list icons may reference the same retained occurrence.
          if (!referencedFiles.computeIfAbsent(guid,
              ignored -> java.util.Collections.newSetFromMap(new IdentityHashMap<>())).add(file.getCOSObject())) continue;
          Fingerprint actual = payload(file, streams);
          String filename = file.getFileUnicode() == null ? file.getFile() : file.getFileUnicode();
          require(file.getFile() == null || file.getFile().equals(filename), "Ambiguous attachment filenames");
          var embedded = file.getEmbeddedFileUnicode() == null ? file.getEmbeddedFile() : file.getEmbeddedFileUnicode();
          String contentType = embedded.getSubtype();
          String parameters = embedded.getCOSObject().getString(COSName.getPDFName("Mail_MediaTypeParameters"));
          if (parameters != null) contentType += ";" + parameters;
          int match = -1;
          for (int index = 0; index < candidates.size(); index++) {
            Attachment expected = candidates.get(index);
            if (expected.filename().equals(filename) && Common.sameContentType(expected.contentType(), contentType)
                && actual.equals(isolated ? empty : expected.original())) { match = index; break; }
          }
          require(match >= 0, "Native attachment bytes/occurrence differ from input for " + guid + ": " + filename);
          candidates.remove(match);
        }
      }
      require(pageOwners.containsAll(present.keySet()), "An expected message has no rendered page owner");
      for (var entry : remaining.entrySet()) require(entry.getValue().isEmpty(), "Missing native attachment occurrences for " + entry.getKey());
    }

    void finish() throws IOException {
      require(renderedMessages.keySet().equals(messages.keySet()) && renderedMessages.values().stream().allMatch(count -> count == 1),
          "Expected messages must occur exactly once in rendered documents/collection children");
    }
  }

  private static void addAsset(Map<Asset, Integer> assets, Asset asset) {
    // Source occurrences remain distinct; shared source-set Supplements may be deduplicated.
    if (asset.relationship().equals("Supplement")) assets.put(asset, 1);
    else assets.merge(asset, 1, Integer::sum);
  }

  private static Fingerprint payload(PDComplexFileSpecification file, IdentityHashMap<COSStream, Fingerprint> cache) throws Exception {
    Fingerprint result = null;
    var ef = file.getCOSObject().getDictionaryObject(COSName.EF);
    require(ef instanceof COSDictionary, "Expected embedded payload is unavailable");
    for (COSName key : List.of(COSName.F, COSName.UF)) {
      var value = ((COSDictionary) ef).getDictionaryObject(key);
      if (value == null) continue;
      require(value instanceof COSStream, "Expected embedded payload is not a stream");
      COSStream stream = (COSStream) value;
      Fingerprint actual = cache.get(stream);
      if (actual == null) {
        try (InputStream input = stream.createInputStream()) { actual = fingerprint(input); }
        cache.put(stream, actual);
      }
      require(result == null || result.equals(actual), "Alternative embedded streams contain different bytes");
      result = actual;
    }
    require(result != null, "Expected embedded payload is unavailable");
    return result;
  }

  static Set<COSDictionary> listedFiles(PDDocument document) throws Exception {
    Set<COSDictionary> files = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    var names = document.getDocumentCatalog().getNames();
    if (names != null && names.getEmbeddedFiles() != null)
      listedFiles(names.getEmbeddedFiles(), files, java.util.Collections.newSetFromMap(new IdentityHashMap<>()),
          new IdentityHashMap<>(), 0);
    return files;
  }

  private static void listedFiles(PDNameTreeNode<PDComplexFileSpecification> node, Set<COSDictionary> files,
                                  Set<COSDictionary> seen, Map<COSDictionary, Set<Long>> placements, int depth) throws Exception {
    require(seen.add(node.getCOSObject()), "Embedded-file name tree is cyclic or too deep");
    ProcessingLimits.checkCancelled();
    if (node.getNames() != null) for (var entry : node.getNames().entrySet()) {
      PDComplexFileSpecification file = entry.getValue();
      files.add(file.getCOSObject());
      if (!"Data".equals(file.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")))) continue;
      var prefix = java.util.regex.Pattern.compile("^<(\\d+)>(.*)$", java.util.regex.Pattern.DOTALL).matcher(entry.getKey());
      boolean grouped = prefix.matches();
      long folder = grouped ? Long.parseLong(prefix.group(1)) : 0;
      String filename = grouped ? prefix.group(2) : entry.getKey();
      String stored = file.getFileUnicode() == null ? file.getFile() : file.getFileUnicode();
      require(filename.equals(stored), "Collection member listing has a different filename");
      require(placements.computeIfAbsent(file.getCOSObject(), ignored -> new java.util.HashSet<>()).add(folder),
          "Collection member is listed more than once in the same folder");
    }
    if (node.getKids() != null) for (PDNameTreeNode<PDComplexFileSpecification> child : node.getKids())
      listedFiles(child, files, seen, placements, depth + 1);
  }

  private static List<Element> rows(org.w3c.dom.Document metadata, String name) {
    List<Element> rows = new ArrayList<>();
    var containers = metadata.getElementsByTagNameNS(MAIL, name);
    for (int index = 0; index < containers.getLength(); index++) {
      for (var child = containers.item(index).getFirstChild(); child != null; child = child.getNextSibling()) {
        if (!(child instanceof Element bag) || !RDF.equals(bag.getNamespaceURI()) || !"Bag".equals(bag.getLocalName())) continue;
        for (var item = bag.getFirstChild(); item != null; item = item.getNextSibling())
          if (item instanceof Element row && RDF.equals(row.getNamespaceURI()) && "li".equals(row.getLocalName())) rows.add(row);
      }
    }
    return rows;
  }

  private static Element property(Element row, String name) {
    for (var child = row.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element element && MAIL.equals(element.getNamespaceURI()) && name.equals(element.getLocalName())) return element;
    return null;
  }

  private static String value(Element row, String name) {
    Element property = property(row, name);
    return property == null ? "" : property.getTextContent();
  }

  private static void require(boolean condition, String message) throws IOException {
    if (!condition) throw new IOException("Preservation inventory mismatch: " + message);
  }
}
