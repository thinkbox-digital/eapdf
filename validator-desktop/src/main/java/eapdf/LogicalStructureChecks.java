package eapdf;

import static eapdf.EaPdfChecks.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.w3c.dom.Element;

final class LogicalStructureChecks {
  private static final Set<String> CUSTOM = Set.of("Mail_Message", "Mail_ContentGroup", "Mail_ContentSet", "Mail_Field", "Mail_FieldName", "Mail_FieldValue");
  private final PDDocument document;
  private final JavaValidation.Report report;
  private final List<Element> emails;
  private final Set<String> simpleGuids = new java.util.HashSet<>();
  private final Map<String, List<Element>> emailsByGuid = new HashMap<>(), emailsById = new HashMap<>();
  private final String profile;
  private final Element xml;
  private final ArrayList<Entry> entries = new ArrayList<>();
  private Map<Entry, List<Entry>> childrenByParent = new java.util.IdentityHashMap<>();
  private Set<COSDictionary> seen = identitySet();
  private COSDictionary root;
  private record Entry(COSDictionary element, Entry parent, String tag, List<COSDictionary> properties) {}

  LogicalStructureChecks(PDDocument document, JavaValidation.Report report, List<Element> emails, String profile, Element xml) {
    this.document = document; this.report = report; this.emails = emails; this.profile = profile; this.xml = xml;
    for (Element email : emails) {
      if (simple(fieldElement(email, "Mail_GUID")) && !field(email, "Mail_GUID").isEmpty()) simpleGuids.add(field(email, "Mail_GUID"));
      var identity = AttachmentChecks.identity(email);
      emailsByGuid.computeIfAbsent(identity.guid(), ignored -> new ArrayList<>()).add(email);
      emailsById.computeIfAbsent(identity.messageId(), ignored -> new ArrayList<>()).add(email);
    }
  }

  void run() throws Exception {
    try { inspectStructure(); }
    finally {
      // Only mail properties and their ancestor chains are used by sourceValues.
      // Replace the maps so their large backing arrays can be collected too.
      childrenByParent = new java.util.IdentityHashMap<>();
      seen = identitySet();
      entries.removeIf(entry -> !entry.tag().startsWith("Mail_") || entry.properties().isEmpty());
      entries.trimToSize();
      simpleGuids.clear();
    }
  }

  private void inspectStructure() throws Exception {
    root = dict(document.getDocumentCatalog().getCOSObject(), "StructTreeRoot");
    if (root == null) {
      report.add("WARN", "STRUCTTREE_PRESENT", "Optional logical structure is absent.", null);
      // Pipeline applicability must not depend on membership in the EA-PDF registry.
      report.na("ISO32005_UPSTREAM", "No logical structure is present.", null);
      for (String code : JavaValidation.RULES.keySet()) {
        if (code.startsWith("MAIL_") && !Set.of("MAIL_GUID_XMP", "MAIL_GUID_UNIQUE").contains(code)
            || code.startsWith("USERPROPS_") || code.startsWith("ROLEMAP") || code.startsWith("CUSTOM_TAG_")
            || Set.of("STRUCTTREE_ROOT_DOCUMENT", "LOGICAL_TS32005_DECLARATION", "STRUCTURE_CORRESPONDENCE", "CONTENT_SET_MAIL_CONTENTSET_TAGGED").contains(code)) {
          report.na(code, "No logical structure is present.", null);
        }
      }
      return;
    }
    report.check("STRUCTTREE_PRESENT", true, "Optional logical structure is present.", null);
    COSBase child = get(root, "K");
    if (child instanceof COSArray array && array.size() == 1) child = array.getObject(0);
    var roles = new StructureRoles(root, document.getVersion() >= 2.0f);
    var rootRole = child instanceof COSDictionary dictionary && !(child instanceof COSStream)
        ? roles.element(dictionary) : new StructureRoles.Result("", "Root must be exactly one structure dictionary");
    boolean valid = "Document".equals(rootRole.role());
    if (rootRole.limited()) report.unavailable("STRUCTTREE_ROOT_DOCUMENT", rootRole.error());
    else report.check("STRUCTTREE_ROOT_DOCUMENT", valid, "StructTreeRoot K must resolve to exactly one Document element through the applicable role map.", Map.of("resolved_role", rootRole.role(), "reason", rootRole.error()));
    iso();
    walk(get(root, "K"), null, 0);
    if (!valid) return;
    // Match the existing parent-identity rule once, rather than scanning the
    // entire structure tree for every custom element and field-value sibling.
    for (Entry entry : entries)
      childrenByParent.computeIfAbsent(entry.parent(), ignored -> new ArrayList<>()).add(entry);
    if (entries.stream().anyMatch(entry -> attributeErrors(root, entry.element())))
      report.unavailable("VALIDATION_INCOMPLETE", "Structure attributes could not be resolved completely.");
    COSDictionary rolemap = dict(root, "RoleMap");
    for (Entry entry : entries) {
      if (!CUSTOM.contains(entry.tag())) continue;
      String expected = Map.of("Mail_Message", "Document", "Mail_ContentGroup", "Part", "Mail_ContentSet", "Art").get(entry.tag());
      if (expected != null) {
        boolean correct = expected.equals(name(get(rolemap, entry.tag())));
        report.check("ROLEMAP_MAIL_TAGS", correct, "/" + entry.tag() + " must map to /" + expected + ".", Map.of("tag", entry.tag(), "expected", expected));
        report.check(Map.of("Mail_Message", "MAIL_MESSAGE_ROLEMAP_DOCUMENT", "Mail_ContentGroup", "MAIL_CONTENTGROUP_ROLEMAP_PART", "Mail_ContentSet", "MAIL_CONTENTSET_ROLEMAP_ART").get(entry.tag()),
            correct, "/" + entry.tag() + " must map to /" + expected + ".", Map.of("tag", entry.tag()));
      } else {
        String code = Map.of("Mail_Field", "MAIL_FIELD_ROLEMAP_APPROPRIATE", "Mail_FieldName", "MAIL_FIELDNAME_ROLEMAP_APPROPRIATE", "Mail_FieldValue", "MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE").get(entry.tag());
        var compatibility = roles.role(entry.tag(), null);
        String mapped = compatibility.role();
        if (compatibility.limited()) report.unavailable(code, compatibility.error());
        else report.check(code, !mapped.isEmpty(), "Custom field tags require a PDF 1.7 standard role mapping.", Map.of("tag", entry.tag(), "role", mapped));
      }
      if (document.getVersion() >= 2.0f) {
        COSDictionary namespace = dict(entry.element(), "NS");
        var resolved = roles.element(entry.element());
        if (resolved.limited()) report.unavailable("ROLEMAPNS_PRESENT", resolved.error());
        else report.check("ROLEMAPNS_PRESENT", "https://pdfa.org/ns/ea-pdf/mail-1".equals(string(get(namespace, "NS")))
            && get(dict(namespace, "RoleMapNS"), entry.tag()) != null
            && !resolved.role().isEmpty() && (expected == null || expected.equals(resolved.role())),
            "PDF 2.0 custom mail tags require a registered EA-PDF namespace and a valid RoleMapNS chain to the required standard role.", Map.of("tag", entry.tag(), "resolved_role", resolved.role(), "reason", resolved.error()));
      }
      childrenRules(entry);
      userProperties(entry);
      if ("Mail_Message".equals(entry.tag())) {
        List<COSDictionary> identifiers = entry.properties().stream().filter(p -> "Mail_GUID".equals(string(get(p, "N")))).toList();
        boolean supplied = identifiers.stream().anyMatch(p -> get(p, "V") != null
            && (!(get(p, "V") instanceof COSString) || !string(get(p, "V")).isEmpty()));
        boolean unreadable = identifiers.stream().anyMatch(p -> get(p, "V") instanceof COSString && decodedString(get(p, "V")) == null);
        if (!supplied && unreadable) report.unavailable("MAIL_MESSAGE_GUID_ATTRIBUTE", "The required logical GUID text cannot be decoded.");
        else report.check("MAIL_MESSAGE_GUID_ATTRIBUTE", supplied, "Mail_Message requires its Mail_GUID attribute; USERPROPS_TYPE checks the value's object class.", null);
        for (COSDictionary property : identifiers) {
          if (get(property, "V") instanceof COSString && decodedString(get(property, "V")) == null)
            report.unavailable("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "The logical GUID text could not be decoded.");
          else if (get(property, "V") instanceof COSString && !string(get(property, "V")).isEmpty()
              && !simpleGuids.isEmpty()) report.check("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", get(property, "V") instanceof COSString
              && simpleGuids.contains(string(get(property, "V"))),
              "The logical Mail_GUID must match its XMP email identity.", null);
        }
      }
      if ("Mail_ContentGroup".equals(entry.tag())) report.check("MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE", ancestor(entry, "Mail_Message"), "Mail_ContentGroup requires a Mail_Message ancestor.", null);
      // Content-set ancestry needs actual DPart/content evidence for document-wide
      // material. PageContentChecks performs this after resolving page connections.
      if (entry.tag().startsWith("Mail_Field")) {
        String code = Map.of("Mail_Field", "MAIL_FIELD_PROFILE_USE", "Mail_FieldName", "MAIL_FIELDNAME_PROFILE_USE", "Mail_FieldValue", "MAIL_FIELDVALUE_PROFILE_USE").get(entry.tag());
        report.check(code, !Set.of("c", "ci").contains(profile), "Custom email fields are restricted to email profiles.", null);
        String parentTag = "Mail_Field".equals(entry.tag()) ? "Mail_ContentSet" : "Mail_Field";
        report.recommend(Map.of("Mail_Field", "MAIL_FIELD_NESTING_RECOMMENDED", "Mail_FieldName", "MAIL_FIELDNAME_NESTING_RECOMMENDED", "Mail_FieldValue", "MAIL_FIELDVALUE_NESTING_RECOMMENDED").get(entry.tag()),
            ancestor(entry, parentTag), "/" + entry.tag() + " should have an ancestor /" + parentTag + ".", Map.of("ancestor", parentTag));
      }
    }
    for (String code : List.of("MAIL_FIELD_PROFILE_USE", "MAIL_FIELDNAME_PROFILE_USE", "MAIL_FIELDVALUE_PROFILE_USE"))
      if (!report.seen.contains(code)) report.check(code, true, "No custom field tag violates the profile restriction.", null);
    if (!report.seen.contains("ROLEMAPNS_PRESENT") && entries.stream().anyMatch(entry -> entry.tag().startsWith("Mail_")))
      report.check("ROLEMAPNS_PRESENT", true, "PDF 1.7 custom mail tags do not require PDF 2.0 namespace bindings.", null);
    long messages = entries.stream().filter(e -> "Mail_Message".equals(e.tag())).count();
    boolean custom = entries.stream().anyMatch(e -> CUSTOM.contains(e.tag()));
    if (custom) {
      String code = Set.of("s", "si").contains(profile) ? "MAIL_MESSAGE_COUNT_SINGLE" : Set.of("m", "mi").contains(profile) ? "MAIL_MESSAGE_COUNT_MULTI" : "MAIL_MESSAGE_COUNT_CONTAINER";
      if (PROFILES.contains(profile)) report.check(code, Set.of("s", "si").contains(profile) ? messages == 1 : Set.of("m", "mi").contains(profile) ? messages >= 2 : messages == 0,
          "Profile 1" + profile + " requires " + (Set.of("s", "si").contains(profile) ? "exactly one" : Set.of("m", "mi").contains(profile) ? "at least two" : "zero") + " /Mail_Message structure elements.", Map.of("count", messages));
      report.check("MAIL_CONTENTSET_COUNT", entries.stream().anyMatch(e -> "Mail_ContentSet".equals(e.tag())), "Custom mail tagging requires Mail_ContentSet elements.", null);
    }
    if (!custom && Set.of("c", "ci").contains(profile)) report.check("MAIL_MESSAGE_COUNT_CONTAINER", true, "No Mail_Message element is present in the collection.", null);
    report.supported("MAIL_MESSAGE_COUNT_SINGLE", "MAIL_MESSAGE_COUNT_MULTI", "MAIL_MESSAGE_COUNT_CONTAINER", "MAIL_CONTENTSET_COUNT",
        "MAIL_MESSAGE_CHILDREN", "MAIL_CONTENTGROUP_CHILDREN", "MAIL_CONTENTSET_CHILDREN", "MAIL_FIELD_CHILDREN", "MAIL_FIELDVALUE_WITH_NAME",
        "MAIL_FIELD_PROFILE_USE", "MAIL_FIELDNAME_PROFILE_USE", "MAIL_FIELDVALUE_PROFILE_USE", "MAIL_MESSAGE_GUID_ATTRIBUTE", "MAIL_MESSAGE_ROLEMAP_DOCUMENT",
        "MAIL_CONTENTGROUP_ROLEMAP_PART", "MAIL_CONTENTSET_ROLEMAP_ART", "MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE",
        "MAIL_CONTENTSET_NESTED_UNDER_MESSAGE", "MAIL_FIELD_NESTING_RECOMMENDED", "MAIL_FIELDNAME_NESTING_RECOMMENDED", "MAIL_FIELDVALUE_NESTING_RECOMMENDED",
        "USERPROPS_NAME_ASCII", "USERPROPS_TYPE", "USERPROPS_TAG_SCOPE", "MAIL_FIELD_ROLEMAP_APPROPRIATE", "MAIL_FIELDNAME_ROLEMAP_APPROPRIATE", "MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE");
    if (entries.stream().noneMatch(entry -> "Mail_ContentGroup".equals(entry.tag())))
      report.check("MAIL_CONTENTGROUP_NESTED_UNDER_MESSAGE", true, "No content group has an invalid message ancestor.", null);
    if (Set.of("m", "mi").contains(profile)) report.check("MULTI_EMAIL_DOCUMENT_STRUCTURE", entries.stream().filter(entry -> entry.parent() != null
        && Set.of("Document", "Mail_Message").contains(entry.tag())).count() >= 2, "A multi-message structure requires an explicit or implicit Document for each email.", null);
    else report.na("MULTI_EMAIL_DOCUMENT_STRUCTURE", "The multi-message structure condition does not apply.", null);
  }

  private void iso() throws Exception {
    if (document.getVersion() != 2.0f) {
      report.na("LOGICAL_TS32005_DECLARATION", "The PDF 2.0 logical-structure condition does not apply.", null);
      report.na("ISO32005_UPSTREAM", "The PDF 2.0 logical-structure condition does not apply.", null);
      return;
    }
    boolean declared = validDeclaration();
    report.check("LOGICAL_TS32005_DECLARATION", declared, "PDF 2.0 logical structure requires the ISO 32005 declaration.", null);
    if (!"PASS".equals(JavaValidation.text(report.pdfa, "status"))) {
      report.na("ISO32005_UPSTREAM", "The ISO pass awaits a successful PDF/A run.", null);
      return;
    }
    var result = report.iso32005 == null ? JavaValidation.iso(Path.of(report.path)) : report.iso32005;
    String status = JavaValidation.text(result, "status");
    if (!Set.of("PASS", "FAIL").contains(status) || result.get("rule_total").getAsInt() != 1636) report.unavailable("ISO32005_UPSTREAM", "The selected ISO 32005 execution could not be verified.");
    else report.check("ISO32005_UPSTREAM", "PASS".equals(status), "Selected upstream ISO 32005 structure checks.", result);
  }

  private void walk(COSBase value, Entry parent, int depth) throws IOException {
    value = resolve(value);
    if (depth > 256 || entries.size() >= InspectionBudget.nodes(document)) throw new IOException("Structure tree exceeds the inspection limit");
    if (value instanceof COSArray array) { for (COSBase child : array) walk(child, parent, depth + 1); return; }
    if (!(value instanceof COSDictionary dictionary) || get(dictionary, "S") == null) return;
    if (!seen.add(dictionary)) throw new IOException("Structure tree contains a repeated or cyclic element");
    Entry entry = new Entry(dictionary, parent, name(get(dictionary, "S")), properties(root, dictionary));
    entries.add(entry); walk(get(dictionary, "K"), entry, depth + 1);
  }

  private boolean validDeclaration() {
    boolean matched = false, valid = true, found = false;
    for (Element rdf : descendants(xml)) if (is(rdf, RDF, "RDF")) {
      for (Element description : children(rdf)) if (is(description, RDF, "Description")) {
        if (description.hasAttributeNS(DECLARATIONS, "declarations")) { found = true; valid = false; }
        for (Element property : children(description)) if (is(property, DECLARATIONS, "declarations")) {
          found = true;
          Element value = valueElement(property); List<Element> members = children(value);
          if (members.size() != 1 || !is(members.getFirst(), RDF, "Bag")) { valid = false; continue; }
          for (Element item : children(members.getFirst())) {
            Element record = valueElement(item);
            if (!is(item, RDF, "li") || record == null || record.hasAttributeNS(RDF, "resource") || children(record).isEmpty()) { valid = false; continue; }
            Element target = declarationField(record, "conformsTo");
            if (!uriValue(target)) valid = false;
            else matched |= "https://pdfa.org/declarations#iso32005".equals(textValue(target));
            Element claims = declarationField(record, "claimData");
            if (claims == null) continue;
            List<Element> bags = children(valueElement(claims));
            if (bags.size() != 1 || !is(bags.getFirst(), RDF, "Bag")) { valid = false; continue; }
            for (Element entry : children(bags.getFirst())) {
              Element claim = valueElement(entry);
              if (!is(entry, RDF, "li") || claim == null || claim.hasAttributeNS(RDF, "resource") || children(claim).isEmpty()) { valid = false; continue; }
              for (String field : List.of("claimBy", "claimCredentials", "claimDate", "claimReport")) {
                Element supplied = declarationField(claim, field);
                if (supplied == null) continue;
                valid &= "claimReport".equals(field) ? uriValue(supplied) : simple(supplied) && (!"claimDate".equals(field) || date(textValue(supplied)));
              }
            }
          }
        }
      }
      break;
    }
    return found && matched && valid;
  }

  private static Element declarationField(Element record, String name) {
    Element result = null;
    if (record.hasAttributeNS(DECLARATIONS, name)) {
      result = record.getOwnerDocument().createElementNS(DECLARATIONS, name);
      result.setTextContent(record.getAttributeNS(DECLARATIONS, name));
    }
    for (Element child : children(record)) if (is(child, DECLARATIONS, name)) result = child;
    return result;
  }

  private static boolean uriValue(Element property) {
    Element value = valueElement(property);
    return value != null && (simple(value) || value.hasAttributeNS(RDF, "resource"))
        && textValue(value).matches("[A-Za-z][A-Za-z0-9+.-]*:[^\\s]*");
  }

  static List<COSDictionary> properties(COSDictionary root, COSDictionary element) {
    List<COSDictionary> attributes = new ArrayList<>();
    COSBase classes = get(element, "C");
    if (classes instanceof COSName name) collectAttributes(get(dict(root, "ClassMap"), name.getName()), attributes);
    else if (classes instanceof COSArray array) for (COSBase child : array)
      if (resolve(child) instanceof COSName name) collectAttributes(get(dict(root, "ClassMap"), name.getName()), attributes);
    collectAttributes(get(element, "A"), attributes);
    COSBase active = null;
    for (COSDictionary attribute : attributes) if ("UserProperties".equals(name(get(attribute, "O"))) && get(attribute, "P") != null)
      active = get(attribute, "P"); // A later P replaces the whole previous array, including an empty array.
    List<COSDictionary> properties = new ArrayList<>();
    if (active instanceof COSArray array) for (COSBase item : array)
      if (resolve(item) instanceof COSDictionary property) properties.add(property);
    return properties;
  }

  static boolean attributeErrors(COSDictionary root, COSDictionary element) {
    List<COSDictionary> attributes = new ArrayList<>();
    COSBase classes = get(element, "C");
    if (classes instanceof COSName name) collectAttributes(get(dict(root, "ClassMap"), name.getName()), attributes);
    else if (classes instanceof COSArray array) for (COSBase child : array)
      if (resolve(child) instanceof COSName name) collectAttributes(get(dict(root, "ClassMap"), name.getName()), attributes);
    collectAttributes(get(element, "A"), attributes);
    return attributes.stream().anyMatch(attribute -> !(get(attribute, "O") instanceof COSName));
  }

  private static void collectAttributes(COSBase value, List<COSDictionary> attributes) {
    value = resolve(value);
    if (value instanceof COSArray array) for (COSBase item : array) collectAttributes(item, attributes);
    else if (value instanceof COSDictionary dictionary) attributes.add(dictionary);
  }

  private void childrenRules(Entry entry) {
    Map<String, String> codes = Map.of("Mail_Message", "MAIL_MESSAGE_CHILDREN", "Mail_ContentGroup", "MAIL_CONTENTGROUP_CHILDREN", "Mail_ContentSet", "MAIL_CONTENTSET_CHILDREN", "Mail_Field", "MAIL_FIELD_CHILDREN");
    if (codes.containsKey(entry.tag())) {
      List<String> children = childrenByParent.getOrDefault(entry, List.of()).stream().map(Entry::tag).toList();
      Set<String> allowed = switch (entry.tag()) {
        case "Mail_Message" -> Set.of("Mail_ContentGroup", "Mail_ContentSet");
        case "Mail_ContentGroup" -> Set.of("Mail_ContentSet");
        case "Mail_ContentSet" -> Set.of("Mail_Field");
        default -> Set.of("Mail_FieldName", "Mail_FieldValue");
      };
      boolean minimum = "Mail_ContentSet".equals(entry.tag()) || "Mail_Field".equals(entry.tag()) && children.contains("Mail_FieldName")
          || !"Mail_Field".equals(entry.tag()) && children.stream().anyMatch(allowed::contains);
      report.check(codes.get(entry.tag()), minimum && children.stream().filter(CUSTOM::contains).allMatch(allowed::contains),
          "Immediate custom children must satisfy EA-PDF Table 11.", Map.of("tag", entry.tag(), "children", children));
    }
    if ("Mail_FieldValue".equals(entry.tag())) report.check("MAIL_FIELDVALUE_WITH_NAME", entry.parent() != null && "Mail_Field".equals(entry.parent().tag())
        && childrenByParent.getOrDefault(entry.parent(), List.of()).stream().anyMatch(e -> "Mail_FieldName".equals(e.tag())), "Mail_FieldValue requires a sibling Mail_FieldName in the same Mail_Field.", null);
  }

  private void userProperties(Entry entry) {
    Set<String> messageOnly = Set.of("Mail_MessageID", "Mail_GUID");
    Set<String> headers = Set.of("Mail_To", "Mail_Raw_To", "Mail_From", "Mail_Raw_From", "Mail_Sent", "Mail_Raw_Sent", "Mail_Subject", "Mail_SizeInBytes", "Mail_NumberAttachments", "Mail_Cc", "Mail_Raw_Cc", "Mail_Bcc", "Mail_Raw_Bcc", "Mail_InReplyTo");
    for (COSDictionary property : entry.properties()) {
      COSBase rawName = get(property, "N"); COSBase value = get(property, "V");
      String name = string(rawName);
      boolean ascii = rawName instanceof COSString text;
      if (rawName instanceof COSString text) for (byte b : text.getBytes()) ascii &= (b & 0x80) == 0;
      report.check("USERPROPS_NAME_ASCII", ascii, "UserProperties N must contain ASCII PDF string bytes.", Map.of("name", name));
      boolean valid = value != null;
      String expectedType = "a /V value";
      if (messageOnly.contains(name) || headers.contains(name) || Set.of("Mail_ContentSetType", "Mail_Subtype").contains(name)) {
        valid = value instanceof COSString;
        expectedType = "a PDF text string in /V";
        if (Set.of("Mail_SizeInBytes", "Mail_NumberAttachments").contains(name)) valid = value instanceof COSInteger integer && integer.longValue() >= 0;
        if (Set.of("Mail_SizeInBytes", "Mail_NumberAttachments").contains(name)) expectedType = "a nonnegative PDF integer in /V";
        if ("Mail_Sent".equals(name)) valid = value instanceof COSString && string(value).matches("D:[0-9]{4}(?:[0-9]{2}){0,5}(?:Z|[+-][0-9]{2}'?(?:[0-9]{2}'?)?)?");
        if ("Mail_Sent".equals(name)) expectedType = "a PDF date string in /V, starting D:YYYY with optional month, day, time and zone";
        Set<String> scope = messageOnly.contains(name) ? Set.of("Mail_Message") : "Mail_ContentSetType".equals(name) ? Set.of("Mail_ContentSet", "Mail_ContentGroup")
            : "Mail_Subtype".equals(name) ? Set.of("Mail_ContentSet") : Set.of("Mail_Message", "Mail_FieldValue");
        report.check("USERPROPS_TAG_SCOPE", scope.contains(entry.tag()), name + " may occur on " + String.join(" or ", scope.stream().sorted().map(tag -> "/" + tag).toList()) + ".", Map.of("name", name, "tag", entry.tag()));
      } else if (name.startsWith("Mail_")) {
        valid = value instanceof COSString || value instanceof COSNumber || value instanceof COSBoolean;
        expectedType = "a PDF string, number or boolean in /V";
        report.check("USERPROPS_TAG_SCOPE", Set.of("Mail_Message", "Mail_FieldName", "Mail_FieldValue").contains(entry.tag()), name + " may occur on /Mail_Message, /Mail_FieldName or /Mail_FieldValue.", Map.of("name", name, "tag", entry.tag()));
      }
      report.check("USERPROPS_TYPE", valid, (name.isEmpty() ? "The user property" : name) + " requires " + expectedType + ".", Map.of("name", name, "value", observed(value)));
    }
  }

  private boolean ancestor(Entry entry, String tag) {
    for (Entry parent = entry.parent(); parent != null; parent = parent.parent()) if (tag.equals(parent.tag())) return true;
    return false;
  }

  void sourceValues(MetadataChecks metadata) {
    String code = "USERPROPS_VALUES_UNMODIFIED";
    boolean compared = false;
    for (Entry entry : entries) {
      if (!entry.tag().startsWith("Mail_")) continue;
      for (COSDictionary property : entry.properties()) {
        String name = string(get(property, "N"));
        if (!name.startsWith("Mail_") || Set.of("Mail_GUID", "Mail_ContentSetType", "Mail_Subtype").contains(name)) continue;
        compared = true;
        if (metadata == null || !metadata.hasInspectableSources()) continue;
        COSBase value = get(property, "V");
        Map<String, Object> detail = Map.of("name", name, "tag", entry.tag(), "value", observed(value));
        Entry message = entry;
        while (message != null && !"Mail_Message".equals(message.tag())) message = message.parent();
        String guid = "", mid = "";
        boolean known = message != null;
        if (message != null) for (COSDictionary identity : message.properties()) {
          String key = string(get(identity, "N"));
          if (!Set.of("Mail_GUID", "Mail_MessageID").contains(key)) continue;
          COSBase raw = get(identity, "V");
          if (!(raw instanceof COSString) || decodedString(raw) == null) { known = false; break; }
          if ("Mail_GUID".equals(key)) guid = string(raw); else mid = string(raw);
        }
        List<Element> owners = !known ? List.of() : !guid.isEmpty() ? emailsByGuid.getOrDefault(guid, List.of())
            : !mid.isEmpty() ? emailsById.getOrDefault(mid, List.of()) : List.of();
        if (owners.size() != 1 || !metadata.hasSource(owners.getFirst())) {
          report.add("WARN", code, "The enclosing message and its original source cannot be uniquely identified.", detail); continue;
        }
        Element owner = owners.getFirst();
        try {
          boolean equal;
          if (Set.of("Mail_SizeInBytes", "Mail_NumberAttachments").contains(name)) {
            if (!(value instanceof COSInteger number) || number.longValue() < 0) {
              report.na(code, "USERPROPS_TYPE checks the invalid count type.", detail); continue;
            }
            equal = number.longValue() == metadata.sourceCount(owner, name);
          } else if ("Mail_Sent".equals(name)) {
            if (!(value instanceof COSString) || decodedString(value) == null) {
              report.na(code, "USERPROPS_TYPE checks the invalid PDF date type.", detail); continue;
            }
            java.time.Instant actual;
            try { actual = SourceHeaders.pdfInstant(string(value)); }
            catch (IllegalArgumentException | java.time.DateTimeException invalid) {
              report.na(code, "USERPROPS_TYPE checks the invalid PDF date.", detail); continue;
            }
            List<String> dates = metadata.sourceHeader(owner, "Date");
            if (dates.size() != 1) throw new IllegalArgumentException("No unique original date");
            equal = actual.equals(SourceHeaders.instant(dates.getFirst()));
          } else {
            String header = switch (name) {
              case "Mail_MessageID" -> "Message-ID"; case "Mail_InReplyTo" -> "In-Reply-To";
              case "Mail_Raw_Sent" -> "Date";
              default -> name.substring(name.startsWith("Mail_Raw_") ? 9 : 5);
            };
            List<String> values = metadata.sourceHeader(owner, header);
            if (values.size() > 1) throw new IllegalArgumentException("Repeated original header");
            String expected = values.isEmpty() ? "" : unfolded(values.getFirst());
            if (!name.startsWith("Mail_Raw_")) expected = SourceHeaders.decoded(expected);
            if (value instanceof COSString) {
              if (decodedString(value) == null) throw new IllegalArgumentException("Unreadable property text");
              equal = (name.startsWith("Mail_Raw_") ? unfolded(string(value)) : string(value)).equals(expected);
            } else if (!Set.of("Mail_To", "Mail_From", "Mail_Cc", "Mail_Bcc", "Mail_Subject", "Mail_MessageID", "Mail_InReplyTo", "Mail_Raw_Sent",
                "Mail_Raw_To", "Mail_Raw_From", "Mail_Raw_Cc", "Mail_Raw_Bcc").contains(name) && value instanceof COSBoolean booleanValue) {
              equal = Boolean.toString(booleanValue.getValue()).equalsIgnoreCase(expected);
            } else if (!name.startsWith("Mail_Raw_") && value instanceof COSNumber number) {
              try { equal = new java.math.BigDecimal(number.toString().replaceFirst("^COS(?:Int|Float)\\{", "").replace("}", "")).compareTo(new java.math.BigDecimal(expected)) == 0; }
              catch (NumberFormatException invalid) { equal = false; }
            } else { report.na(code, "USERPROPS_TYPE checks this invalid header value type.", detail); continue; }
          }
          report.add(equal ? "PASS" : "WARN", code, equal ? "Attribute value agrees with its original email." : "Attribute value should retain the original email value without modification.", detail);
        } catch (Exception unavailable) {
          report.add("WARN", code, "Original attribute value comparison is unavailable: " + unavailable.getMessage(), detail);
        }
      }
    }
    if (!compared || metadata == null || !metadata.hasInspectableSources())
      report.na(code, "No source-derived mail properties or inspectable original sources require comparison.", null);
  }

  private static String unfolded(String text) {
    return text.replaceAll("\\r?\\n(?=[ \\t])", "").replaceAll("^[ \\t]+|[ \\t]+$", "");
  }
}
