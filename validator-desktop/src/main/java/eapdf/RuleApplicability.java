package eapdf;

import static eapdf.EaPdfChecks.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.w3c.dom.Element;

/** Explicit applicability decisions, translated from the web validator's final rule matrix. */
final class RuleApplicability {
  private final PDDocument document;
  private final JavaValidation.Report report;
  private final Element xml;
  private final List<Embedded> files;
  private final List<COSDictionary> sources;
  private final List<Element> emails, assets, attachments;
  private final MetadataChecks metadata;
  private final String profile;
  private final boolean single, multi, container, mail, isolated, structure, unknownSource, inspectable;
  private final Set<String> tags = new HashSet<>();
  private final List<COSDictionary> nodes = new ArrayList<>(), leaves;
  private boolean userProperties;

  RuleApplicability(PDDocument document, JavaValidation.Report report, Element xml, List<Embedded> files,
                    List<COSDictionary> sources, List<Element> emails, List<Element> assets, List<Element> attachments,
                    MetadataChecks metadata) throws IOException {
    this.document = document; this.report = report; this.xml = xml; this.files = files; this.sources = sources;
    this.emails = emails; this.assets = assets; this.attachments = attachments; this.metadata = metadata;
    profile = report.profile; single = Set.of("s", "si").contains(profile); multi = Set.of("m", "mi").contains(profile);
    container = Set.of("c", "ci").contains(profile); mail = single || multi; isolated = profile.endsWith("i");
    COSDictionary root = dict(document.getDocumentCatalog().getCOSObject(), "StructTreeRoot"); structure = root != null;
    unknownSource = metadata != null && metadata.sourceOwnershipUnknown();
    inspectable = metadata != null && (metadata.hasBoundSources() || !unknownSource && metadata.hasInspectableSources());
    if (root != null) {
      COSBase child = get(root, "K");
      if (child instanceof COSArray array) child = array.size() == 1 ? array.getObject(0) : null;
      collectStructure(child, root, identitySet());
    }
    collectParts(dict(dict(document.getDocumentCatalog().getCOSObject(), "DPartRoot"), "DPartRootNode"), identitySet());
    leaves = nodes.stream().filter(node -> get(node, "Start") != null || get(node, "End") != null).toList();
  }

  private void collectParts(COSDictionary node, Set<COSDictionary> seen) {
    if (node == null || !seen.add(node)) return;
    nodes.add(node);
    for (COSDictionary child : PageContentChecks.dictionaries(get(node, "DParts"))) collectParts(child, seen);
  }
  private void collectStructure(COSBase value, COSDictionary root, Set<COSDictionary> seen) {
    for (COSDictionary node : PageContentChecks.dictionaries(value)) {
      if (!seen.add(node)) continue;
      String tag = name(get(node, "S"));
      if (tag.startsWith("Mail_")) {
        tags.add(tag); userProperties |= !LogicalStructureChecks.properties(root, node).isEmpty();
      }
      collectStructure(get(node, "K"), root, seen);
    }
  }
  private boolean property(String name) { return emails.stream().anyMatch(email -> fieldElement(email, name) != null && !field(email, name).isEmpty()); }
  private boolean rawProperty(String name) { return emails.stream().anyMatch(email -> fieldElement(email, name) != null); }
  private boolean hasDpm(String key) { return nodes.stream().anyMatch(node -> get(dict(node, "DPM"), key) != null); }
  private boolean identityNodes() { return nodes.stream().anyMatch(node -> get(node, "Start") == null && get(node, "End") == null
      && (get(dict(node, "DPM"), "Mail_GUID") != null || get(dict(node, "DPM"), "Mail_MessageID") != null)); }

  void finish() throws IOException {
    List<String> missing = new ArrayList<>();
    for (String code : JavaValidation.RULES.keySet()) {
      if (report.seen.contains(code) || "VALIDATION_INCOMPLETE".equals(code)) continue;
      String reason = reason(code);
      if (reason != null) report.na(code, reason, null);
      else if (report.implemented.contains(code) && !unknownCondition(code))
        report.na(code, inactiveReason(code), null);
      else {
        if (!JavaValidation.recommendation(code)) missing.add(code);
        report.add("WARN", code, "Rule applicability or execution has not been established.",
            Map.of("state", "unverified", "reason", "No result or explicit non-applicability decision was recorded."));
      }
    }
    if (!missing.isEmpty()) report.add("WARN", "VALIDATION_INCOMPLETE", "Some rules have no verified result or applicability decision.", Map.of("rules", missing));
  }

  private boolean unknownCondition(String code) {
    return profile.isEmpty() && "COLLECTION_CHILD_VALIDATION".equals(code)
        || unknownSource && code.startsWith("MESSAGE_")
        || Set.of("ASSET_SIZE_TYPE", "ASSET_CONTENTTYPE", "ASSET_CHECKSUM_FORM").contains(code) && !assets.isEmpty()
        || "SOURCE_MIME_TYPE".equals(code) && !sources.isEmpty()
        || Set.of("ASSET_SIZE_MATCH", "ASSET_CONTENTTYPE_MATCH", "ASSET_MD5_MATCH").contains(code) && !assets.isEmpty()
        || code.startsWith("ATTACHMENT_XMP_") && !PROFILES.contains(profile) && !attachments.isEmpty() || "DPM_MAIL_GUID_MATCH".equals(code) && identityNodes();
  }

  private String reason(String code) throws IOException {
    Map<String, String> suppliedFields = Map.ofEntries(
        Map.entry("MESSAGE_TO_XMP", "To"), Map.entry("MESSAGE_TO_FOAF", "To"),
        Map.entry("MESSAGE_FROM_XMP", "From"), Map.entry("MESSAGE_FROM_FOAF", "From"),
        Map.entry("MESSAGE_CC_XMP", "Cc"), Map.entry("MESSAGE_CC_FOAF", "Cc"),
        Map.entry("MESSAGE_BCC_XMP", "Bcc"), Map.entry("MESSAGE_BCC_FOAF", "Bcc"),
        Map.entry("MESSAGE_SUBJECT_XMP", "Subject"), Map.entry("MESSAGE_ID_XMP", "messageid"),
        Map.entry("MESSAGE_SIZE_XMP", "SizeInBytes"), Map.entry("MESSAGE_ATTACHMENT_COUNT_XMP", "NumberAttachments"),
        Map.entry("MESSAGE_INREPLYTO_XMP", "In-Reply-To"));
    if (suppliedFields.containsKey(code) && emails.stream().noneMatch(email -> fieldElement(email, suppliedFields.get(code)) != null))
      return "This internal metadata check applies only when " + suppliedFields.get(code) + " is supplied; original-dependent presence is reported separately.";
    boolean structureRule = code.startsWith("MAIL_MESSAGE_") || code.startsWith("MAIL_CONTENT") || code.startsWith("MAIL_FIELD")
        || code.startsWith("USERPROPS_") || Set.of("STRUCTTREE_ROOT_DOCUMENT", "ROLEMAPNS_PRESENT", "ROLEMAP_MAIL_TAGS", "STRUCTURE_CORRESPONDENCE",
        "LOGICAL_TS32005_DECLARATION", "CUSTOM_TAG_MARKED_SEQUENCE_ONLY", "CUSTOM_TAG_OPERAND_MATCH", "HEADER_FIELDS_TAGGED_RECOMMENDED",
        "CONTENT_SET_MAIL_CONTENTSET_TAGGED", "MULTI_EMAIL_DOCUMENT_STRUCTURE").contains(code);
    if ("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE".equals(code) && AttachmentChecks.knownNonbody(document)) return "All content sets have known non-body types and valid page ranges.";
    if ("HEADER_FIELDS_TAGGED_RECOMMENDED".equals(code) && tags.isEmpty()) return "No custom EA-PDF tags activate this recommendation.";
    if (structureRule && !structure) return "No optional logical structure is present.";
    if (!profile.isEmpty()) {
      if (!single && Set.of("PROFILE_EMAIL_COUNT_SINGLE", "DPM_INTERMEDIATE_COUNT_SINGLE", "MAIL_MESSAGE_COUNT_SINGLE").contains(code)) return profileReason("single-message profiles 1s and 1si");
      if (!multi && Set.of("PROFILE_EMAIL_COUNT_MULTI", "DPM_INTERMEDIATE_COUNT_MULTI", "MAIL_MESSAGE_COUNT_MULTI", "PROFILE_PAGEMODE_MULTI", "PROFILE_NFSM_MULTI").contains(code)) return profileReason("multi-message profiles 1m and 1mi");
      if (!container && Set.of("DPM_INTERMEDIATE_COUNT_CONTAINER", "DPM_CONTAINER_CONTENTSET_TYPES", "MAIL_MESSAGE_COUNT_CONTAINER", "PROFILE_PAGEMODE_CONTAINER", "PROFILE_NFSM_CONTAINER", "COLLECTION_CHILD_VALIDATION").contains(code)) return profileReason("collection profiles 1c and 1ci");
      if (!mail && Set.of("MESSAGE_SENT_XMP", "MESSAGE_SENT_FORMAT", "MESSAGE_ID_XMP", "MAIL_GUID_XMP", "EMAIL_XMP_CONTAINER", "EMAIL_XMP_CARDINALITY", "MAIL_GUID_UNIQUE", "MESSAGE_SOURCE_COVERAGE", "DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", "DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", "DPM_EMAIL_CONTENT_SET_OWNERSHIP", "ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED", "ATTACHMENT_XMP_INVENTORY_RECOMMENDED", "ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", "ATTACHMENT_XMP_MESSAGE_ID_MATCH", "ATTACHMENT_XMP_CHECKSUM_MATCH").contains(code)) return profileReason("email profiles 1s, 1si, 1m and 1mi");
    }
    if (code.startsWith("PROFILE_PAGEMODE_SINGLE_") || code.startsWith("PROFILE_NFSM_SINGLE_")) {
      Boolean attached = metadata == null ? Boolean.FALSE : metadata.attachmentPresence();
      if (!profile.isEmpty() && (!single || attached != null && code.endsWith("_WITH_ATTACHMENTS") != attached))
        return !single ? profileReason("single-message profiles 1s and 1si")
            : attached ? "This rule applies only to emails without attachments; this email has attachments."
            : "This rule applies only to emails with attachments; this email has none.";
    }
    if ("COLLECTION_EXTENSIONS".equals(code) && document.getVersion() == 2.0f) return "Collection extensions are required only in PDF 1.7.";
    if ("AF_IN_NAME_TREE".equals(code) && files.isEmpty()) return "No associated file requires name-tree registration.";
    if (Set.of("EMBEDDED_STREAM", "EMBEDDED_SIZE_MATCH", "EMBEDDED_MD5_MATCH", "EMBEDDED_MD5_BINARY_FORMAT").contains(code) && files.isEmpty()) return "No embedded file requires payload inspection.";
    if ("EMBEDDED_MD5_BINARY_FORMAT".equals(code) && files.stream().noneMatch(file -> get(dict(file.stream(), "Params"), "CheckSum") != null)) return "No embedded stream declares an optional CheckSum.";
    if (Set.of("DPM_MAIL_GUID_PRESENT", "DPM_MAIL_GUID_MATCH").contains(code) && !identityNodes()) return "There are no intermediate email-identity DParts.";
    if ("DPM_MAIL_MESSAGEID_MATCH".equals(code) && !hasDpm("Mail_MessageID")) return "No DPart declares Mail_MessageID.";
    if ("DPM_SUBTYPE_REQUIRED".equals(code) && leaves.stream().noneMatch(node -> Set.of("BodyRendering", "AttachmentRendering").contains(name(get(dict(node, "DPM"), "Mail_ContentSetType"))))) return "No DPart leaf declares BodyRendering or AttachmentRendering, the types that require Mail_Subtype.";
    if ("DPM_FRONTMATTER_REQUIRED_CONTAINER".equals(code) && !container) return "FrontMatter is required only for collection profiles.";
    if ("DPM_SUBTYPE_MEDIA_TYPE_NAME".equals(code) && leaves.stream().noneMatch(node -> get(dict(node, "DPM"), "Mail_Subtype") != null)) return "No DPart leaf declares Mail_Subtype.";
    Map<String, String> additionalTags = Map.ofEntries(
        Map.entry("MAIL_MESSAGE_GUID_MATCH_DPM_XMP", "Mail_Message"), Map.entry("MAIL_MESSAGE_GUID_ATTRIBUTE", "Mail_Message"),
        Map.entry("MAIL_MESSAGE_ROLEMAP_DOCUMENT", "Mail_Message"), Map.entry("MAIL_CONTENTGROUP_ROLEMAP_PART", "Mail_ContentGroup"),
        Map.entry("MAIL_CONTENTSET_ROLEMAP_ART", "Mail_ContentSet"), Map.entry("MAIL_FIELD_ROLEMAP_APPROPRIATE", "Mail_Field"),
        Map.entry("MAIL_FIELDNAME_ROLEMAP_APPROPRIATE", "Mail_FieldName"), Map.entry("MAIL_FIELDVALUE_ROLEMAP_APPROPRIATE", "Mail_FieldValue"),
        Map.entry("MAIL_FIELD_NESTING_RECOMMENDED", "Mail_Field"), Map.entry("MAIL_FIELDNAME_NESTING_RECOMMENDED", "Mail_FieldName"),
        Map.entry("MAIL_FIELDVALUE_NESTING_RECOMMENDED", "Mail_FieldValue"),
        Map.entry("MAIL_CONTENTGROUP_CONTENTSETTYPE_ATTRIBUTE_RECOMMENDED", "Mail_ContentGroup"), Map.entry("MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT", "Mail_ContentGroup"),
        Map.entry("MAIL_CONTENTSET_SUBTYPE_ATTRIBUTE_RECOMMENDED", "Mail_ContentSet"), Map.entry("MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT", "Mail_ContentSet"));
    if (additionalTags.containsKey(code) && !tags.contains(additionalTags.get(code))) return "No /" + additionalTags.get(code) + " structure element activates this check.";
    if ("MULTI_EMAIL_DOCUMENT_STRUCTURE".equals(code) && !multi) return "Only multi-message profiles require multiple email Document elements.";
    Map<String, String> tagRules = Map.of("MAIL_MESSAGE_CHILDREN", "Mail_Message", "MAIL_CONTENTSET_CHILDREN", "Mail_ContentSet", "MAIL_CONTENTGROUP_CHILDREN", "Mail_ContentGroup",
        "MAIL_FIELD_CHILDREN", "Mail_Field", "MAIL_FIELDVALUE_WITH_NAME", "Mail_FieldValue");
    if (tagRules.containsKey(code) && !tags.contains(tagRules.get(code))) return "No /" + tagRules.get(code) + " structure element requires inspection.";
    if (Set.of("MESSAGE_SOURCE_COVERAGE", "XMP_CREATEDATE_SEMANTICS").contains(code) && !inspectable && !unknownSource) return "No inspectable retained source is available.";
    if ("XMP_CREATEDATE_SEMANTICS".equals(code) && !unknownSource && (metadata == null || !metadata.sourceHasSentTime())) return "No retained source has a normalized sent date.";
    if ("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID".equals(code) && !unknownSource && (metadata == null || !metadata.sourceHasDate(false))) return "No inspected source has an invalid Date header requiring Raw-Sent.";
    if ("MESSAGE_RAW_SENT_OMIT_IF_VALID".equals(code) && !unknownSource && (metadata == null || !metadata.sourceHasDate(true))) return "No inspected source has a valid Date header.";
    Map<String, String> raw = Map.of("MESSAGE_RAW_TO_XMP", "Raw-To", "MESSAGE_RAW_FROM_XMP", "Raw-From", "MESSAGE_RAW_CC_XMP", "Raw-Cc", "MESSAGE_RAW_BCC_XMP", "Raw-Bcc");
    if (raw.containsKey(code) && !rawProperty(raw.get(code))) return "Email XMP does not supply the optional " + raw.get(code) + " property.";
    String optional = code.startsWith("MESSAGE_CC_") ? "Cc" : code.startsWith("MESSAGE_BCC_") ? "Bcc" : "MESSAGE_INREPLYTO_XMP".equals(code) ? "In-Reply-To" : null;
    if (optional != null && !property(optional) && !unknownSource && (metadata == null || !metadata.sourceHasHeader(optional))) return "Neither the inspected source nor email XMP supplies the optional " + optional + " header.";
    if ("DOCINFO_KEYWORDS".equals(code) && !part3()) return "The Info Keywords requirement applies only to PDF/A-3.";
    if (code.startsWith("DOCINFO_") && code.endsWith("_MATCH")) return "The optional Info crosswalk has no supplied value or does not apply to this PDF/A part.";
    if (Set.of("PROFILE_COLLECTION", "COLLECTION_REQUIRED", "COLLECTION_EXTENSIONS", "COLLECTION_D_FORBIDDEN", "COLLECTION_VIEW_FORBIDDEN", "COLLECTION_NAVIGATOR_FORBIDDEN", "COLLECTION_SCHEMA_PRESENT", "COLLECTION_SCHEMA_FIELD", "COLLECTION_SCHEMA_E_FALSE").contains(code) && !container) return profileReason("collection profiles 1c and 1ci");
    if ("COLLECTION_FORBIDDEN".equals(code) && container) return "Profile 1" + profile + " permits a Collection entry; this prohibition applies only to email profiles.";
    if (Set.of("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID", "MESSAGE_RAW_SENT_OMIT_IF_VALID", "MESSAGE_TO_XMP", "MESSAGE_FROM_XMP", "MESSAGE_CC_XMP", "MESSAGE_BCC_XMP", "MESSAGE_TO_FOAF", "MESSAGE_FROM_FOAF", "MESSAGE_CC_FOAF", "MESSAGE_BCC_FOAF", "MESSAGE_RAW_TO_XMP", "MESSAGE_RAW_FROM_XMP", "MESSAGE_RAW_CC_XMP", "MESSAGE_RAW_BCC_XMP", "MESSAGE_SUBJECT_XMP", "MESSAGE_INREPLYTO_XMP", "MESSAGE_SIZE_XMP", "MESSAGE_ATTACHMENT_COUNT_XMP", "ATTACHMENT_XMP_CONTENTTYPE_MATCH").contains(code)
        && (!mail || !inspectable && !unknownSource)) return "This comparison requires a mail profile and an inspectable original source.";
    if (code.startsWith("ATTACHMENT_XMP_") && attachments.isEmpty()) return "No XMP attachment record requires inspection.";
    if ("ATTACHMENT_XMP_SIZE_MATCH".equals(code) && (container || !inspectable)) return "No corresponding native payload or inspectable original establishes this comparison.";
    if (code.startsWith("ASSET_") && !Set.of("ASSET_XMP", "ASSET_XMP_SKIPPED").contains(code) && assets.isEmpty()) return "No XMP source asset record requires inspection.";
    if (Set.of("SOURCE_WITHOUT_XMP", "SOURCE_MIME_TYPE", "SOURCE_MIME_GENERIC").contains(code) && sources.isEmpty()) return "No embedded Source associated file requires inspection.";
    if ("SOURCE_AF".equals(code) && isolated && sources.isEmpty()) return "Profile 1" + profile + " may omit the original source email, and no Source associated file is embedded.";
    if ("SOURCE_MIME_NONE".equals(code) && !sources.isEmpty()) return "Source associated files are present; the no-source diagnostic does not apply.";
    if (Set.of("SOURCE_AF", "SOURCE_WITHOUT_XMP", "ASSET_XMP").contains(code) && isolated) return profileReason("non-isolated profiles 1s, 1m and 1c");
    if ("SOURCE_IN_ISOLATED".equals(code) && !isolated) return profileReason("isolated profiles 1si, 1mi and 1ci");
    if (Set.of("MAIL_MESSAGE_COUNT_SINGLE", "MAIL_MESSAGE_COUNT_MULTI", "MAIL_MESSAGE_COUNT_CONTAINER", "PROFILE_EMAIL_COUNT_SINGLE", "PROFILE_EMAIL_COUNT_MULTI", "DPM_INTERMEDIATE_COUNT_SINGLE", "DPM_INTERMEDIATE_COUNT_MULTI", "DPM_INTERMEDIATE_COUNT_CONTAINER").contains(code) && profile.isEmpty()) return "The EA-PDF conformance declaration is unavailable, so no profile-specific count can be selected.";
    if (Set.of("CONTENT_SET_COUNT", "CONTENT_SET_NONBLANK", "CONTENT_SET_OUTLINE_REF").contains(code) && leaves.isEmpty()) return "No DPart leaf content set requires inspection.";
    if ("CONTENT_SET_OUTLINE_REF".equals(code) && get(document.getDocumentCatalog().getCOSObject(), "Outlines") == null) return "No Outlines entry is present.";
    if ("DPM_ATTACHMENTLIST_FORBIDDEN".equals(code) && !container) return profileReason("collection profiles 1c and 1ci");
    if (Set.of("USERPROPS_NAME_ASCII", "USERPROPS_TYPE", "USERPROPS_TAG_SCOPE").contains(code) && !userProperties) return "No custom UserProperties entries require inspection.";
    if (Set.of("ASSET_NUMBERMESSAGES_TYPE", "ASSET_NUMBERMESSAGES_MATCH_RENDERED").contains(code) && assets.stream().noneMatch(asset -> fieldElement(asset, "NumberMessages") != null)) return "No XMP source asset declares the optional NumberMessages property.";
    if ("EMBEDDED_MEDIATYPE_PARAMETERS_TYPE".equals(code) && files.stream().noneMatch(file -> get(file.stream(), "Mail_MediaTypeParameters") != null)) return "No embedded stream supplies Mail_MediaTypeParameters.";
    if (Set.of("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", "SOURCE_MEDIATYPE_PARAMETERS_MATCH").contains(code) && attachments.isEmpty()) return "No native attachment has an original-parameter comparison to perform.";
    if (Set.of("EMBEDDED_SIZE_RECOMMENDED", "EMBEDDED_MD5_RECOMMENDED").contains(code) && files.isEmpty()) return "No embedded file stream activates this recommendation.";
    if ("ATTACHMENT_XMP_CHECKSUM_MATCH".equals(code) && attachments.stream().noneMatch(attachment -> fieldElement(attachment, "CheckSum") != null)) return "No attachment XMP supplies the optional CheckSum.";
    if ("ATTACHMENT_XMP_MESSAGE_ID_MATCH".equals(code) && attachments.stream().noneMatch(attachment -> fieldElement(attachment, "Message-ID") != null)) return "No attachment XMP supplies the optional Message-ID.";
    if ("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED".equals(code) && attachments.isEmpty()) return "No declared native attachment activates original-byte comparison.";
    if ("ATTACHMENT_XMP_INVENTORY_RECOMMENDED".equals(code) && sources.isEmpty()) return "No original inventory is retained, so this recommendation was not compared.";
    if ("ATTACHMENT_XMP_CONTAINER".equals(code) && attachments.isEmpty() && !inspectable) return "No attachment record or inspectable original establishes an attachment requirement.";
    return null;
  }
  private String profileReason(String applicable) {
    return "This rule applies only to " + applicable + "; "
        + (profile.isEmpty() ? "this file has no usable EA-PDF conformance declaration." : "this file declares profile 1" + profile + ".");
  }

  /** Explains the existing implemented-rule fallback without changing its applicability decision. */
  private String inactiveReason(String code) {
    return switch (code) {
      case "ACTIONABLE_URLS_LINK_ANNOTATIONS" -> "No URL-like page text was found for comparison with Link annotations.";
      case "AF_IN_NAME_TREE" -> "No associated file with a readable embedded stream requires name-tree registration.";
      case "ASSOCIATED_FILE_MODDATE" -> "No associated file with a readable embedded stream requires a ModDate check.";
      case "ANNOTATION_CONTENTS_REQUIRED" -> "No file attachment annotation requires a Contents text check.";
      case "ASSET_CONTENTTYPE_GENERIC" -> "No XMP source asset declares a discouraged generic media type.";
      case "SOURCE_MIME_GENERIC" -> "No inspected Source file declares a discouraged generic media type.";
      case "COLLECTION_CONTENT_TYPES", "COLLECTION_FILENAMES_VALID", "COLLECTION_FILENAMES_UNIQUE_CASEFOLD" ->
          profileReason("collection profiles 1c and 1ci");
      case "CUSTOM_TAG_OPERAND_MATCH" -> "No marked-content sequence with a custom EA-PDF structure owner requires an operand comparison.";
      case "EMBEDDED_MEDIATYPE_RECONSTRUCTION" -> "No readable embedded stream is available for media-type reconstruction.";
      case "EMBEDDED_MEDIA_TYPE_MATCH_XMP" -> "No embedded payload and corresponding XMP media-type value are available for comparison.";
      case "SOURCE_MEDIATYPE_PARAMETERS_MATCH" -> "No independently associated original parameter value and stored PDF string activate a value comparison.";
      case "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED" -> "No independently associated original declares parameters that require preservation.";
      case "EMBEDDED_SIZE_MATCH" -> "No embedded stream supplies a size for comparison.";
      case "EMBEDDED_MD5_MATCH" -> "No embedded stream supplies a checksum for comparison.";
      case "MAIL_CONTENTGROUP_CONTENTSETTYPE_MATCH_IF_PRESENT" -> "No connected /Mail_ContentGroup supplies a Mail_ContentSetType attribute for comparison.";
      case "MAIL_CONTENTSET_SUBTYPE_MATCH_IF_PRESENT" -> "No connected /Mail_ContentSet supplies a Mail_Subtype attribute for comparison.";
      case "MAIL_CONTENTSET_ART_ONLY" -> "No /Art or /Mail_ContentSet structure element requires content-set ownership inspection.";
      case "MAIL_CONTENTSET_COUNT", "MAIL_MESSAGE_COUNT_SINGLE", "MAIL_MESSAGE_COUNT_MULTI" -> "No custom EA-PDF structure tags activate this count check.";
      case "MAIL_CONTENTSET_NESTED_UNDER_MESSAGE" -> "The outer collection does not require its content sets to be nested under /Mail_Message elements.";
      default -> "No applicable instance of this conditional rule was found during inspection.";
    };
  }
  private boolean part3() {
    return xml != null && descendants(xml).stream().anyMatch(element -> is(element, PDFA, "part") && "3".equals(textValue(element)));
  }
}
