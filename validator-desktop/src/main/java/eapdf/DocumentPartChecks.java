package eapdf;

import static eapdf.EaPdfChecks.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.w3c.dom.Element;

final class DocumentPartChecks {
  private final PDDocument document;
  private final JavaValidation.Report report;
  private final List<Element> emails;
  private final String profile;
  private final Map<COSDictionary, Integer> pages;
  private final Set<Integer> outlines;
  private final Map<String, Set<String>> xmpIdentities;
  private final List<COSDictionary> leaves = new ArrayList<>();
  private final Set<COSDictionary> visited = identitySet();
  private int messages;
  private int maximumDepth;
  private final Set<COSBase> metadataSeen = identitySet();
  private int metadataItems;
  private boolean metadataLimited;

  DocumentPartChecks(PDDocument document, JavaValidation.Report report, List<Element> emails, String profile,
                     Map<COSDictionary, Integer> pages, Set<Integer> outlines, Map<String, Set<String>> xmpIdentities) {
    this.document = document; this.report = report; this.emails = emails; this.profile = profile; this.pages = pages; this.outlines = outlines;
    this.xmpIdentities = xmpIdentities;
  }

  void run() throws IOException {
    COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
    COSDictionary root = get(catalog, "DPartRoot") instanceof COSDictionary dictionary ? dictionary : null;
    report.check("DPARTROOT_PRESENT", root != null, "The catalog requires DPartRoot.", null);
    if (root == null) return;
    COSDictionary node = get(root, "DPartRootNode") instanceof COSDictionary dictionary ? dictionary : null;
    report.check("DPM_ROOTNODE_PRESENT", node != null, "DPartRoot requires DPartRootNode.", null);
    if (node == null) return;
    report.check("DPART_OBJECT_STRUCTURE", !(root instanceof COSStream) && !(node instanceof COSStream)
        && catalog.getItem(COSName.getPDFName("DPartRoot")) instanceof COSObject
        && root.getItem(COSName.getPDFName("DPartRootNode")) instanceof COSObject,
        "DPartRoot and its root node must be indirect dictionaries.", null);
    checkType(root, "DPartRoot");
    walk(node, root, 0);
    COSBase level = get(root, "RecordLevel");
    if (level != null) report.check("DPART_OBJECT_STRUCTURE", level instanceof COSInteger value && value.longValue() >= 0 && value.longValue() <= maximumDepth,
        "RecordLevel must identify an actual hierarchy level as an integer.", Map.of("levels", maximumDepth + 1));
    COSBase labels = get(root, "NodeNameList");
    if (labels != null) {
      boolean correct = labels instanceof COSArray array && array.size() == maximumDepth + 1;
      report.check("DPART_OBJECT_STRUCTURE", correct, "NodeNameList must have one PDF name for each hierarchy level.", null);
      if (labels instanceof COSArray array) for (COSBase label : array) checkLabel(resolve(label));
    }
    report.check("DPM_LEAF_REQUIRED", !leaves.isEmpty(), "The document-part tree must contain content sets.", Map.of("count", leaves.size()));
    if (Set.of("s", "si").contains(profile)) {
      report.check("DPM_INTERMEDIATE_COUNT_SINGLE", messages == 1, "A single-message profile requires one email DPM node.", Map.of("count", messages));
      report.check("PROFILE_EMAIL_COUNT_SINGLE", !leaves.isEmpty(), "A single-message profile requires at least one content set.", Map.of("content_sets", leaves.size()));
    } else if (Set.of("m", "mi").contains(profile)) {
      report.check("DPM_INTERMEDIATE_COUNT_MULTI", messages >= 2, "A multi-message profile requires at least two email DPM nodes.", Map.of("count", messages));
      report.check("PROFILE_EMAIL_COUNT_MULTI", leaves.size() >= 2,
          "A multi-message profile requires at least two content sets.", Map.of("content_sets", leaves.size()));
    } else if (Set.of("c", "ci").contains(profile)) {
      report.check("DPM_INTERMEDIATE_COUNT_CONTAINER", messages == 0, "A collection must not contain email identity DPM nodes.", Map.of("count", messages));
      report.check("DPM_FRONTMATTER_REQUIRED_CONTAINER", leaves.stream().anyMatch(leaf -> "FrontMatter".equals(name(get(dict(leaf, "DPM"), "Mail_ContentSetType")))),
          "A collection requires a FrontMatter content set.", null);
    }
    report.supported("DPM_INTERMEDIATE_COUNT_SINGLE", "DPM_INTERMEDIATE_COUNT_MULTI", "DPM_INTERMEDIATE_COUNT_CONTAINER", "PROFILE_EMAIL_COUNT_SINGLE", "PROFILE_EMAIL_COUNT_MULTI", "DPM_FRONTMATTER_REQUIRED_CONTAINER");
    int[] ownership = new int[document.getNumberOfPages()];
    COSDictionary[] owners = new COSDictionary[ownership.length];
    int previousEnd = -1;
    boolean ordering = true;
    for (COSDictionary leaf : leaves) {
      Integer start = pages.get(get(leaf, "Start"));
      Integer end = get(leaf, "End") == null ? start : pages.get(get(leaf, "End"));
      boolean valid = start != null && end != null && start <= end;
      report.check("CONTENT_SET_COUNT", valid, "A content set must have a valid nonempty page range.", Map.of("start", start == null ? -1 : start + 1, "end", end == null ? -1 : end + 1));
      if (!valid) continue;
      ordering &= start > previousEnd; previousEnd = end;
      report.check("CONTENT_SET_OUTLINE_REF", outlines.contains(start), "An outline must reference the first page of each content set.", Map.of("page", start + 1));
      for (int index = start; index <= end; index++) { ownership[index]++; owners[index] = leaf; }
    }
    boolean covered = ordering;
    for (var page : document.getPages()) {
      int index = pages.get(page.getCOSObject());
      covered &= ownership[index] == 1 && !(owners[index] instanceof COSStream) && get(page.getCOSObject(), "DPart") == owners[index];
    }
    report.check("PAGE_DPART_PRESENT", covered, "Every page must reference its unique reachable leaf DPart in page order.", null);
    report.check("DPM_KEY_PREFIX", true, "Required EA-PDF fields use Mail_; unrelated extension keys remain permitted.", null);
  }

  private void checkType(COSDictionary value, String expected) {
    COSBase type = get(value, "Type");
    if (type != null) report.check("DPART_OBJECT_STRUCTURE", type instanceof COSName && expected.equals(name(type)),
        "If supplied, /Type must be /" + expected + ".", Map.of("expected", expected));
  }

  static String utf8Name(COSName name) {
    try { return java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(name.getBytes())).toString(); }
    catch (java.nio.charset.CharacterCodingException error) { return null; }
  }

  private void checkLabel(COSBase value) {
    if (!(value instanceof COSName name)) {
      report.check("DPART_OBJECT_STRUCTURE", false, "Hierarchy labels and DPM keys must be PDF names.", null); return;
    }
    String text = utf8Name(name);
    if (text == null) { report.unavailable("DPART_OBJECT_STRUCTURE", "An XML name label cannot be decoded through UTF-8."); return; }
    String start = "A-Z_a-z:\\x{C0}-\\x{D6}\\x{D8}-\\x{F6}\\x{F8}-\\x{2FF}\\x{370}-\\x{37D}\\x{37F}-\\x{1FFF}\\x{200C}-\\x{200D}\\x{2070}-\\x{218F}\\x{2C00}-\\x{2FEF}\\x{3001}-\\x{D7FF}\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFFD}\\x{10000}-\\x{EFFFF}";
    String extra = "\\-.0-9\\x{B7}\\x{300}-\\x{36F}\\x{203F}-\\x{2040}";
    if (text.matches("[" + start + "][" + start + extra + "]*")) return;
    if (!text.isEmpty() && text.matches("[" + start + extra + "]+"))
      report.unavailable("DPART_OBJECT_STRUCTURE", "An XML NMTOKEN label needs the unresolved Name-token interpretation.");
    else report.check("DPART_OBJECT_STRUCTURE", false, "A hierarchy label or DPM key contains invalid XML name characters.", Map.of("name", text));
  }

  private void inspectMetadata(COSBase value, boolean arrayItem, int depth) {
    if (metadataLimited) return;
    value = resolve(value);
    if (++metadataItems > InspectionBudget.nodes(document) || depth > 256) {
      metadataLimited = true;
      report.unavailable("DPART_OBJECT_STRUCTURE", "DPM value inspection exceeded its limit."); return;
    }
    if (value == null || value instanceof COSNull || value instanceof COSStream) {
      if (arrayItem || value instanceof COSStream) report.check("DPART_OBJECT_STRUCTURE", false, "DPM values cannot be streams or null array items.", null);
    } else if (value instanceof COSDictionary dictionary) {
      if (!metadataSeen.add(value)) return;
      for (COSName key : dictionary.keySet()) {
        COSBase item = get(dictionary, key.getName());
        if (item == null) continue;
        checkLabel(key); inspectMetadata(item, false, depth + 1);
      }
    } else if (value instanceof COSArray array) {
      if (!metadataSeen.add(value)) return;
      for (COSBase item : array) inspectMetadata(item, true, depth + 1);
    } else if (value instanceof COSName name) {
      report.check("DPM_NAME_TYPES", utf8Name(name) != null, "DPM name values must have valid UTF-8 bytes.", null);
    } else if (!(value instanceof COSString || value instanceof COSNumber || value instanceof COSBoolean))
      report.check("DPART_OBJECT_STRUCTURE", false, "Unsupported DPM value type.", null);
  }

  private void walk(COSDictionary node, COSDictionary parent, int depth) throws IOException {
    if (depth > 256 || visited.size() >= InspectionBudget.nodes(document) || !visited.add(node)) throw new IOException("DPart tree is cyclic or exceeds the inspection limit");
    maximumDepth = Math.max(maximumDepth, depth);
    report.check("DPART_OBJECT_STRUCTURE", !(node instanceof COSStream), "DPart nodes must be dictionaries, not streams.", null);
    checkType(node, "DPart");
    report.check("DPART_OBJECT_STRUCTURE", get(node, "Metadata") == null, "DPart dictionaries must not contain Metadata.", null);
    report.check("DPART_OBJECT_STRUCTURE", get(node, "Parent") == parent, "DPart Parent must identify the containing node.", null);
    COSDictionary dpm = dict(node, "DPM");
    COSBase kids = get(node, "DParts");
    if (get(node, "DPM") != null) {
      report.check("DPART_OBJECT_STRUCTURE", dpm != null, "DPM must be a dictionary.", null);
      if (dpm != null) inspectMetadata(dpm, false, 0);
    }
    boolean hasIdentity = get(dpm, "Mail_GUID") != null || get(dpm, "Mail_MessageID") != null;
    if (hasIdentity && kids != null) messages++;
    if (Set.of("c", "ci").contains(profile)) report.check("DPM_INTERMEDIATE_COUNT_CONTAINER", !hasIdentity, "Collections must not contain email identity DPM entries.", null);
    for (String field : List.of("Mail_GUID", "Mail_MessageID")) {
      COSBase value = get(dpm, field);
      if (value == null) continue;
      String text = decodedString(value);
      String code = "Mail_GUID".equals(field) ? "DPM_MAIL_GUID_MATCH" : "DPM_MAIL_MESSAGEID_MATCH";
      if (value instanceof COSString && text == null) {
        report.unavailable(code, "DPM email identity text could not be decoded.");
        if ("Mail_GUID".equals(field) && hasIdentity && kids != null)
          report.unavailable("DPM_MAIL_GUID_PRESENT", "DPM email identity text could not be decoded.");
        continue;
      }
      String xmpField = "Mail_GUID".equals(field) ? "Mail_GUID" : "messageid";
      String identity = "Mail_GUID".equals(field) ? text : text == null ? null : normal(text);
      boolean present = value instanceof COSString && text != null && !text.isEmpty();
      if ("Mail_GUID".equals(field) && hasIdentity && kids != null)
        report.check("DPM_MAIL_GUID_PRESENT", present, "An email intermediate node requires a nonempty text Mail_GUID.", null);
      if (present || !"Mail_GUID".equals(field) || kids == null)
        report.check(code, identity != null && !identity.isEmpty() && xmpIdentities.getOrDefault(xmpField, Set.of()).contains(identity),
            "DPM email identity must match its document XMP value.", Map.of("field", field, "value", observed(value)));
    }
    if (hasIdentity && kids != null && get(dpm, "Mail_GUID") == null)
      report.check("DPM_MAIL_GUID_PRESENT", false, "An email intermediate node requires Mail_GUID.", null);
    for (String field : List.of("Mail_ContentSetType", "Mail_Subtype")) {
      if (get(dpm, field) != null) report.check("DPM_NAME_TYPES", get(dpm, field) instanceof COSName,
          "DPM type and subtype entries must be PDF names.", Map.of("field", field, "value", observed(get(dpm, field))));
    }
    if (kids != null) {
      report.check("DPART_OBJECT_STRUCTURE", get(node, "Start") == null && get(node, "End") == null, "Intermediate DParts must not specify page ranges.", null);
      if (!(kids instanceof COSArray groups) || groups.size() == 0) { report.check("DPART_OBJECT_STRUCTURE", false, "DParts must be a nonempty array of arrays.", null); return; }
      for (COSBase value : groups) {
        if (!(resolve(value) instanceof COSArray group) || group.size() == 0) { report.check("DPART_OBJECT_STRUCTURE", false, "Each DParts group must be a nonempty array.", null); continue; }
        for (COSBase entry : group) {
          if (!(resolve(entry) instanceof COSDictionary child)) { report.check("DPART_OBJECT_STRUCTURE", false, "DParts entries must reference dictionaries.", null); continue; }
          report.check("DPART_OBJECT_STRUCTURE", entry instanceof COSObject, "DParts children must be indirect references.", null);
          walk(child, node, depth + 1);
        }
      }
      return;
    }
    report.check("DPART_OBJECT_STRUCTURE", get(node, "Start") != null && (get(node, "End") == null || get(node, "End") != get(node, "Start")),
        "A leaf requires Start and a single-page leaf must omit End.", null);
    leaves.add(node);
    report.check("DPM_LEAF_REQUIRED", dpm != null, "Each leaf content set requires a DPM dictionary.", null);
    report.check("DPM_CONTENTSETTYPE_PRESENT", get(dpm, "Mail_ContentSetType") != null, "A leaf requires Mail_ContentSetType; its PDF name type is checked separately.", null);
    if (get(dpm, "Mail_Desc") instanceof COSString && decodedString(get(dpm, "Mail_Desc")) == null)
      report.unavailable("DPM_DESC_REQUIRED", "The content-set description text cannot be decoded.");
    else report.check("DPM_DESC_REQUIRED", get(dpm, "Mail_Desc") instanceof COSString && !string(get(dpm, "Mail_Desc")).isEmpty(), "A leaf requires a human-readable text Mail_Desc.", null);
    String type = name(get(dpm, "Mail_ContentSetType"));
    if (Set.of("BodyRendering", "AttachmentRendering").contains(type)) report.check("DPM_SUBTYPE_REQUIRED", get(dpm, "Mail_Subtype") != null, "Rendering content sets require Mail_Subtype.", null);
    if (get(dpm, "Mail_Subtype") != null) report.check("DPM_SUBTYPE_MEDIA_TYPE_NAME", get(dpm, "Mail_Subtype") instanceof COSName
        && MetadataChecks.mediaType(name(get(dpm, "Mail_Subtype"))), "Mail_Subtype must be a media type PDF name.", null);
    if (Set.of("c", "ci").contains(profile)) {
      report.check("DPM_ATTACHMENTLIST_FORBIDDEN", !"AttachmentList".equals(type), "Collection content sets must not use AttachmentList.", null);
      report.check("DPM_CONTAINER_CONTENTSET_TYPES", !Set.of("BodyRendering", "AttachmentRendering", "EmailHeaderRendering", "RawEmailRendering").contains(type),
          "Collection content sets must not contain email rendering types.", null);
    }
    report.supported("DPM_ATTACHMENTLIST_FORBIDDEN", "DPM_CONTAINER_CONTENTSET_TYPES", "DPM_SUBTYPE_REQUIRED", "DPM_SUBTYPE_MEDIA_TYPE_NAME", "DPM_MAIL_GUID_PRESENT", "DPM_MAIL_GUID_MATCH", "DPM_MAIL_MESSAGEID_MATCH");
  }
}
