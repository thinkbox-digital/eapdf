package eapdf;

import static eapdf.EaPdfChecks.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.w3c.dom.Element;

/** Native attachment evidence inside each applicable DPart body range. */
final class AttachmentChecks {
  private static final String CODE = "ATTACHMENT_ANNOTATIONS_PRESENT";
  private static final String VISIBLE = "ATTACHMENT_ANNOTATIONS_VISIBLE_WITHIN_CROPBOX";
  private final PDDocument document;
  private final JavaValidation.Report report;
  private final List<Element> attachments;
  private final List<EaPdfChecks.Embedded> files;
  private final List<Element> emails;
  private final OwnerIndex ownerIndex;
  private final MetadataChecks metadata;
  private final String profile;

  AttachmentChecks(PDDocument document, JavaValidation.Report report, List<Element> attachments,
                   List<EaPdfChecks.Embedded> files, List<Element> emails, MetadataChecks metadata, String profile) {
    this.document = document; this.report = report; this.attachments = attachments; this.files = files;
    this.emails = emails; this.ownerIndex = new OwnerIndex(emails); this.metadata = metadata; this.profile = profile;
  }

  record Identity(String guid, String messageId) {
    boolean known() { return !guid.isEmpty() || !messageId.isEmpty(); }
    boolean matches(Identity other) {
      if (!guid.isEmpty() && !other.guid.isEmpty()) return guid.equals(other.guid);
      return !messageId.isEmpty() && !other.messageId.isEmpty() && comparableId(messageId).equals(comparableId(other.messageId));
    }
  }
  record Body(COSDictionary part, List<PDPage> pages, Identity identity) {}
  static final class BodyIndex {
    private final Map<String, List<Body>> byGuid = new java.util.HashMap<>(), byId = new java.util.HashMap<>();
    private final Map<Body, Integer> order = new java.util.IdentityHashMap<>();
    BodyIndex(List<Body> bodies) {
      for (Body body : bodies) {
        order.put(body, order.size());
        byGuid.computeIfAbsent(body.identity().guid(), ignored -> new ArrayList<>()).add(body);
        byId.computeIfAbsent(comparableId(body.identity().messageId()), ignored -> new ArrayList<>()).add(body);
      }
    }
    List<Body> matching(Identity identity) {
      Set<Body> possible = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      if (!identity.guid().isEmpty()) possible.addAll(byGuid.getOrDefault(identity.guid(), List.of()));
      if (!identity.messageId().isEmpty()) possible.addAll(byId.getOrDefault(comparableId(identity.messageId()), List.of()));
      return possible.stream().filter(body -> identity.matches(body.identity()))
          .sorted(java.util.Comparator.comparingInt(order::get)).toList();
    }
  }
  record Annotation(PDAnnotationFileAttachment annotation, PDPage page, EaPdfChecks.Embedded file) {}

  static Identity identity(Element record) {
    return new Identity(simple(fieldElement(record, "Mail_GUID")) ? field(record, "Mail_GUID") : "",
        simple(fieldElement(record, "messageid")) || simple(fieldElement(record, "Message-ID")) ? normal(field(record, "messageid")) : "");
  }

  static Identity identity(COSDictionary part) {
    Set<COSDictionary> seen = identitySet();
    for (COSDictionary node = part; node != null && seen.add(node); node = dict(node, "Parent")) {
      COSDictionary dpm = dict(node, "DPM");
      COSBase guidValue = get(dpm, "Mail_GUID"), idValue = get(dpm, "Mail_MessageID");
      if (guidValue instanceof COSString && decodedString(guidValue) == null) return new Identity("", "");
      String guid = guidValue instanceof COSString ? string(guidValue) : "";
      if (idValue instanceof COSString && decodedString(idValue) == null) return new Identity(guid, "");
      String id = idValue instanceof COSString ? normal(string(idValue)) : "";
      if (!guid.isEmpty() || !id.isEmpty()) return new Identity(guid, id);
    }
    return new Identity("", "");
  }

  private List<Element> owners(Identity identity) { return ownerIndex.owners(identity); }

  private static final class OwnerIndex {
    private final List<Element> emails;
    private final Map<String, List<Element>> guids = new java.util.HashMap<>(), ids = new java.util.HashMap<>();
    OwnerIndex(List<Element> emails) {
      this.emails = emails;
      // Keep every occurrence: duplicate identities must remain ambiguous.
      for (Element email : emails) {
        Identity id = identity(email);
        guids.computeIfAbsent(id.guid(), ignored -> new ArrayList<>()).add(email);
        ids.computeIfAbsent(id.messageId(), ignored -> new ArrayList<>()).add(email);
      }
    }
    List<Element> owners(Identity identity) {
      if (!identity.guid().isEmpty()) return guids.getOrDefault(identity.guid(), List.of());
      if (!identity.messageId().isEmpty()) return ids.getOrDefault(identity.messageId(), List.of());
      return emails.size() == 1 ? emails : List.of();
    }
  }

  static List<COSDictionary> leaves(PDDocument document) throws java.io.IOException {
    List<COSDictionary> leaves = new ArrayList<>();
    COSDictionary root = get(document.getDocumentCatalog().getCOSObject(), "DPartRoot") instanceof COSDictionary dictionary ? dictionary : null;
    COSDictionary node = get(root, "DPartRootNode") instanceof COSDictionary dictionary ? dictionary : null;
    var pending = new ArrayDeque<COSDictionary>(); if (node != null) pending.add(node);
    Set<COSDictionary> seen = identitySet();
    while (!pending.isEmpty()) {
      COSDictionary current = pending.removeFirst();
      if (!seen.add(current)) continue;
      if (get(current, "Start") != null || get(current, "End") != null) leaves.add(current);
      COSBase groups = get(current, "DParts");
      if (groups instanceof COSArray arrays) for (COSBase group : arrays)
        if (resolve(group) instanceof COSArray children) for (COSBase child : children)
          if (resolve(child) instanceof COSDictionary dictionary) pending.add(dictionary);
    }
    return leaves;
  }

  static List<Body> bodySets(PDDocument document) throws java.io.IOException {
    List<Body> bodies = new ArrayList<>();
    var pageIndex = new PageIndex(document);
    for (COSDictionary part : leaves(document)) {
      if (!"BodyRendering".equals(name(get(dict(part, "DPM"), "Mail_ContentSetType")))) continue;
      List<PDPage> span = pageIndex.range(part);
      if (!span.isEmpty()) bodies.add(new Body(part, span, identity(part)));
    }
    return bodies;
  }

  static Map<COSDictionary, Set<Element>> nativeOwnerScopes(PDDocument document, List<Element> emails) {
    try {
      Map<COSDictionary, Set<Element>> pages = new java.util.IdentityHashMap<>();
      Set<COSDictionary> unknownPages = identitySet();
      OwnerIndex ownerIndex = new OwnerIndex(emails);
      int work = 0;
      for (Body body : bodySets(document)) {
        List<Element> owners = body.identity().known() ? ownerIndex.owners(body.identity()) : List.of();
        for (PDPage page : body.pages()) {
          if (owners.size() != 1 || body.part().getKey() == null || body.part().getKey().getNumber() <= 0
              || get(page.getCOSObject(), "DPart") != body.part()) unknownPages.add(page.getCOSObject());
          else pages.computeIfAbsent(page.getCOSObject(), ignored -> identitySet()).add(owners.getFirst());
        }
      }
      Map<COSDictionary, Set<Element>> scopes = new java.util.IdentityHashMap<>();
      Set<COSDictionary> unknownFiles = identitySet();
      for (PDPage page : document.getPages()) for (var annotation : page.getAnnotations()) {
        if (!"FileAttachment".equals(name(get(annotation.getCOSObject(), "Subtype")))) continue;
        COSDictionary spec = dict(annotation.getCOSObject(), "FS");
        if (spec == null || spec.getKey() == null || spec.getKey().getNumber() <= 0) continue;
        Set<Element> owners = pages.getOrDefault(page.getCOSObject(), Set.of());
        if (unknownPages.contains(page.getCOSObject()) || owners.size() != 1) unknownFiles.add(spec);
        else scopes.computeIfAbsent(spec, ignored -> identitySet()).addAll(owners);
      }
      unknownFiles.forEach(scopes::remove);
      return scopes;
    } catch (java.io.IOException | IllegalArgumentException unavailable) { return Map.of(); }
  }

  static final class PageIndex {
    final List<PDPage> pages = new ArrayList<>();
    private final Map<COSDictionary, Integer> indices = new java.util.IdentityHashMap<>();
    PageIndex(PDDocument document) {
      for (PDPage page : document.getPages()) { indices.put(page.getCOSObject(), pages.size()); pages.add(page); }
    }
    List<PDPage> range(COSDictionary part) {
      int start = indices.getOrDefault(get(part, "Start"), -1);
      int end = get(part, "End") == null ? start : indices.getOrDefault(get(part, "End"), -1);
      return start >= 0 && end >= start ? pages.subList(start, end + 1) : List.of();
    }
  }
  static List<PDPage> pageRange(PDDocument document, COSDictionary part) { return new PageIndex(document).range(part); }

  static boolean knownNonbody(PDDocument document) throws java.io.IOException {
    var leaves = leaves(document);
    var pageIndex = new PageIndex(document);
    return !leaves.isEmpty() && leaves.stream().allMatch(part -> {
      COSBase type = get(dict(part, "DPM"), "Mail_ContentSetType");
      return type instanceof COSName named && DocumentPartChecks.utf8Name(named) != null
          && !"BodyRendering".equals(name(type)) && !pageIndex.range(part).isEmpty();
    });
  }

  void run() throws Exception {
    if (attachments.isEmpty() || !Set.of("s", "si", "m", "mi").contains(profile)) {
      for (String code : List.of(CODE, VISIBLE)) report.na(code, "No declared attachments in an applicable mail profile.", null); return;
    }
    List<COSDictionary> leaves = leaves(document);
    List<Body> bodies = new ArrayList<>();
    var pageIndex = new PageIndex(document);
    List<PDPage> pages = pageIndex.pages;
    boolean knownNonbody = !leaves.isEmpty();
    for (COSDictionary part : leaves) {
      COSBase type = get(dict(part, "DPM"), "Mail_ContentSetType");
      List<PDPage> span = pageIndex.range(part);
      boolean validRange = !span.isEmpty();
      knownNonbody &= type instanceof COSName named && DocumentPartChecks.utf8Name(named) != null && !"BodyRendering".equals(name(type)) && validRange;
      if (validRange && "BodyRendering".equals(name(type))) bodies.add(new Body(part, span, identity(part)));
    }
    Map<COSDictionary, EaPdfChecks.Embedded> filesBySpec = new java.util.IdentityHashMap<>();
    for (var file : files) filesBySpec.putIfAbsent(file.spec(), file);
    Map<COSDictionary, List<Annotation>> annotationsByPage = new java.util.IdentityHashMap<>();
    for (PDPage page : pages) for (var annotation : page.getAnnotations())
      if (annotation instanceof PDAnnotationFileAttachment attached) {
        COSDictionary spec = dict(attached.getCOSObject(), "FS");
        EaPdfChecks.Embedded file = filesBySpec.get(spec);
        annotationsByPage.computeIfAbsent(page.getCOSObject(), ignored -> new ArrayList<>()).add(new Annotation(attached, page, file));
      }
    BodyIndex bodyIndex = new BodyIndex(bodies);
    boolean unknownBodies = bodies.stream().anyMatch(body -> !body.identity().known());
    boolean oneBodyIdentity = bodies.stream().map(Body::identity).distinct().limit(2).count() <= 1;
    boolean unavailable = false;
    for (Element attachment : attachments) {
      Identity id = identity(attachment);
      List<Element> owners = owners(id);
      if (id.guid().isEmpty() && !id.messageId().isEmpty() && !emails.isEmpty() && owners.size() != 1 && !bodies.isEmpty()) {
        unavailable = true; continue;
      }
      Element owner = owners.size() == 1 ? owners.getFirst() : null;
      boolean uniqueMid = owner != null && !identity(owner).messageId().isEmpty()
          && ownerIndex.ids.getOrDefault(identity(owner).messageId(), List.of()).size() == 1;
      Identity bodyId = owner != null && (id.guid().isEmpty() || uniqueMid) ? identity(owner) : id;
      unavailable |= unknownBodies;
      List<Body> applicable = bodyIndex.matching(bodyId);
      if (applicable.isEmpty() && !unknownBodies && !id.known() && oneBodyIdentity) applicable = bodies;
      if (applicable.isEmpty()) {
        if (knownNonbody) continue;
        if (bodies.isEmpty() || unknownBodies || !id.guid().isEmpty() && owners.isEmpty()) unavailable = true;
        else { report.check(CODE, false, "No BodyRendering content set matches the established attachment owner.", Map.of("file", field(attachment, "Filename")));
          report.na(VISIBLE, "No qualifying attachment/body-set association is available for the visibility check.", Map.of("file", field(attachment, "Filename"))); }
        continue;
      }
      for (Body body : applicable) {
        String sourceChecksum = owner != null && (!id.known() || id.matches(identity(owner))) ? metadata.sourceChecksum(attachment, owner) : null;
        List<Annotation> candidates = body.pages().stream().flatMap(page -> annotationsByPage.getOrDefault(page.getCOSObject(), List.of()).stream()).toList();
        List<Annotation> matching = candidates.stream().filter(annotation -> matches(attachment, annotation.file(), sourceChecksum, false)).toList();
        var details = Map.of("file", field(attachment, "Filename"), "body_pages", body.pages().size());
        if (!matching.isEmpty()) {
          report.check(CODE, true, "A qualifying native annotation exists in this owning body content set.", details);
          report.check(VISIBLE, matching.stream().anyMatch(AttachmentChecks::visible),
              "At least one qualifying native annotation must be visible and wholly inside its page CropBox.", details);
          continue;
        }
        if (fieldElement(attachment, "CheckSum") == null && sourceChecksum == null
            && candidates.stream().anyMatch(annotation -> matches(attachment, annotation.file(), null, true))) {
          unavailable = true; continue;
        }
        report.check(CODE, false, "Each declared attachment requires a qualifying native annotation within each owning body content set.", details);
        report.na(VISIBLE, "No qualifying native annotation is available for the visibility check.", details);
      }
    }
    if (unavailable) for (String code : List.of(CODE, VISIBLE)) report.unavailable(code, "Attachment body ownership or independent native-payload association is unavailable.");
    else if (knownNonbody) for (String code : List.of(CODE, VISIBLE)) report.na(code, "All declared content sets have known non-body types and valid ranges.", null);
  }

  private static boolean matches(Element attachment, EaPdfChecks.Embedded file, String sourceChecksum, boolean ignoreName) {
    if (file == null || Set.of("Source", "Alternative").contains(file.relationship())) return false;
    COSDictionary params = dict(file.stream(), "Params");
    COSBase declaredSize = get(params, "Size");
    if (declaredSize != null && (!(declaredSize instanceof COSInteger size) || size.longValue() != file.size())) return false;
    COSBase declaredChecksum = get(params, "CheckSum");
    if (declaredChecksum instanceof COSString checksum && !java.util.HexFormat.of().formatHex(checksum.getBytes()).equalsIgnoreCase(file.md5())) return false;
    String filename = ignoreName || !simple(fieldElement(attachment, "Filename")) ? "" : field(attachment, "Filename");
    String checksum = simple(fieldElement(attachment, "CheckSum")) ? field(attachment, "CheckSum") : "";
    boolean named = !filename.isEmpty() && filename.equals(nativeName(file));
    boolean digested = !checksum.isEmpty() && checksum.equalsIgnoreCase(file.md5());
    boolean source = sourceChecksum != null && sourceChecksum.equalsIgnoreCase(file.md5());
    boolean size = MetadataChecks.integerEquals(field(attachment, "SizeInBytes"), file.size());
    String type = field(attachment, "Content-Type");
    boolean media = !type.isEmpty() && !file.mediaType().isEmpty() && MetadataChecks.sameMedia(type,
        file.mediaType() + ";" + string(get(file.stream(), "Mail_MediaTypeParameters")));
    boolean empty = MetadataChecks.integerEquals(field(attachment, "SizeInBytes"), 0) && file.size() == 0;
    if (!filename.isEmpty() && !(named || digested || source || empty)) return false;
    if (!checksum.isEmpty() && !digested || fieldElement(attachment, "SizeInBytes") != null && !size || !type.isEmpty() && !media) return false;
    return named || digested || source || empty || size && (media || type.isEmpty());
  }

  static String nativeName(EaPdfChecks.Embedded file) {
    for (String key : List.of("UF", "F")) {
      String value = decodedString(get(file.spec(), key));
      if (value != null && !value.isEmpty()) return value;
    }
    return file.treeName();
  }

  private static boolean visible(Annotation annotation) {
    var rect = annotation.annotation().getRectangle(); var box = annotation.page().getCropBox();
    int flags = annotation.annotation().getAnnotationFlags();
    return (flags & (1 | 2 | 32)) == 0 && rect != null && rect.getWidth() > 0 && rect.getHeight() > 0
        && rect.getLowerLeftX() >= box.getLowerLeftX() - 0.01 && rect.getLowerLeftY() >= box.getLowerLeftY() - 0.01
        && rect.getUpperRightX() <= box.getUpperRightX() + 0.01 && rect.getUpperRightY() <= box.getUpperRightY() + 0.01;
  }

  static String guid(COSDictionary part) { return identity(part).guid(); }
  private static String comparableId(String value) {
    return normal(value.toLowerCase(java.util.Locale.ROOT).replace("\"", "").replace("'", "").replace('<', ' ').replace('>', ' '));
  }
}
