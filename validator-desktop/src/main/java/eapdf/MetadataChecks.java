package eapdf;

import static eapdf.EaPdfChecks.*;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import jakarta.mail.Address;
import jakarta.mail.Session;
import jakarta.mail.Part;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.w3c.dom.Element;

final class MetadataChecks {
  private JavaValidation.Report report;
  private MetadataChecks comparisonProbe;
  private boolean candidateProbe;
  private Element comparedEmail;
  private final List<EaPdfChecks.Embedded> files;
  private final List<COSDictionary> sourceSpecs;
  private final Set<COSDictionary> sourceSpecSet = identitySet();
  private final List<Element> emails;
  private final List<Element> assets;
  private final List<Element> attachments;
  private final String profile;
  private long inspectedMimeBytes;
  private final Map<String, List<String>> bodyTypes = new java.util.HashMap<>();
  private final Map<Element, NativeAttachment> originalAttachmentBindings = new java.util.IdentityHashMap<>();
  private final Map<Element, Source> sourceBindings = new java.util.IdentityHashMap<>();
  private final Map<Element, EaPdfChecks.Embedded> assetBindings = new java.util.IdentityHashMap<>();
  private final Map<String, Set<COSDictionary>> sourceOccurrences = new java.util.HashMap<>();
  private final Map<String, Set<String>> childRenderedSources = new java.util.HashMap<>();
  private boolean childCountsComplete = true, childrenFinished;
  private final Map<Source, List<NativeAttachment>> attachmentInventories = new java.util.HashMap<>();
  private final Map<Element, List<Element>> attachmentOwnerCache = new java.util.IdentityHashMap<>();
  private final Map<Element, List<Element>> attachmentsByEmail = new java.util.IdentityHashMap<>();
  private final Map<String, List<Element>> emailsByGuid = new java.util.HashMap<>(), emailsByMessageId = new java.util.HashMap<>();
  private int inspectableSourceCount;
  private final Map<Source, Boolean> incompleteSources = new java.util.HashMap<>();
  private int candidateComparisons;
  private final Element legacyEmail;
  private final boolean completeGuids;
  private Boolean universalHeadersReadable, universalSourcesIncomplete;

  MetadataChecks(JavaValidation.Report report, List<EaPdfChecks.Embedded> files, List<COSDictionary> sourceSpecs,
                 List<Element> emails, List<Element> assets, List<Element> attachments, String profile) {
    this(report, files, sourceSpecs, emails, assets, attachments, profile, null);
  }
  MetadataChecks(JavaValidation.Report report, List<EaPdfChecks.Embedded> files, List<COSDictionary> sourceSpecs,
                 List<Element> emails, List<Element> assets, List<Element> attachments, String profile, Element legacyEmail) {
    this.legacyEmail = legacyEmail;
    this.report = report; this.files = files; this.sourceSpecs = sourceSpecs; this.emails = emails;
    this.assets = assets; this.attachments = attachments; this.profile = profile;
    sourceSpecSet.addAll(sourceSpecs);
    for (var file : files) if (sourceSpecSet.contains(file.spec()))
      sourceOccurrences.computeIfAbsent(file.md5().toLowerCase(Locale.ROOT) + ":" + file.size(), ignored -> identitySet()).add(file.spec());
    completeGuids = !emails.isEmpty() && emails.stream().allMatch(e -> simple(fieldElement(e, "Mail_GUID")) && !field(e, "Mail_GUID").isEmpty());
    for (Element email : emails.isEmpty() && legacyEmail != null ? List.of(legacyEmail) : emails) {
      emailsByGuid.computeIfAbsent(field(email, "Mail_GUID"), ignored -> new ArrayList<>()).add(email);
      emailsByMessageId.computeIfAbsent(normal(field(email, "messageid")), ignored -> new ArrayList<>()).add(email);
    }
    for (Element attachment : attachments) {
      Set<Element> owners = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
      owners.addAll(attachmentOwners(attachment));
      for (Element owner : owners) attachmentsByEmail.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(attachment);
    }
  }

  void assets() {
    report.check("ASSET_XMP_SKIPPED", true, "Asset checks were applied to readable document metadata.", null);
    List<EaPdfChecks.Embedded> sources = files.stream().filter(f -> sourceSpecSet.contains(f.spec())).toList();
    Set<COSDictionary> ambiguousSources = identitySet(); int ambiguousAssets = 0;
    Set<EaPdfChecks.Embedded> remainingAssets = new java.util.LinkedHashSet<>(files.stream().filter(file -> sourceSpecSet.contains(file.spec()) || Set.of("RF", "Supplement").contains(file.relationship())).toList());
    var filesByName = index(new ArrayList<>(remainingAssets), file -> basename(file.name()));
    var filesByDigest = index(new ArrayList<>(remainingAssets), file -> file.md5().toLowerCase(Locale.ROOT));
    var assetsByName = index(assets, asset -> basename(field(asset, "Filename")));
    var assetsByDigest = index(assets.stream().filter(asset -> simple(fieldElement(asset, "Checksum"))).toList(),
        asset -> field(asset, "Checksum").toLowerCase(Locale.ROOT));
    report.supported("SOURCE_MIME_NONE", "SOURCE_MIME_TYPE", "SOURCE_MIME_GENERIC", "SOURCE_WITHOUT_XMP", "ASSET_XMP", "ASSET_XMP_SKIPPED",
        "ASSET_XMP_FIELDS", "ASSET_SIZE_TYPE", "ASSET_NUMBERMESSAGES_TYPE", "ASSET_NUMBERMESSAGES_MATCH_RENDERED", "ASSET_CHECKSUM_FORM", "ASSET_CONTENTTYPE", "ASSET_MATCH",
        "ASSET_MD5_MATCH", "ASSET_SIZE_MATCH", "ASSET_CONTENTTYPE_MATCH", "ASSET_CONTENTTYPE_GENERIC");
    if (assets.isEmpty()) {
      if (profile.endsWith("i")) report.recommend("ASSET_XMP", sources.isEmpty(),
          "An isolated profile may omit source metadata when no source is embedded.", null);
      else report.check("ASSET_XMP", false, "A non-isolated profile requires source asset metadata.", null);
      if (!sources.isEmpty() && !profile.endsWith("i")) for (String code : List.of("ASSET_XMP_FIELDS", "ASSET_SIZE_TYPE", "ASSET_NUMBERMESSAGES_TYPE",
          "ASSET_CHECKSUM_FORM", "ASSET_CONTENTTYPE", "ASSET_MATCH", "ASSET_MD5_MATCH", "ASSET_SIZE_MATCH", "ASSET_CONTENTTYPE_MATCH"))
        report.check(code, false, "Required source asset records are missing.", null);
    }
    for (EaPdfChecks.Embedded source : sources) {
      if (!Set.of("application/octet-stream", "application/zip", "text/plain", "application/octetstream").contains(source.mediaType().toLowerCase(Locale.ROOT)))
        report.check("SOURCE_MIME_TYPE", !source.mediaType().isEmpty(), "Source files must declare a media type.", Map.of("file", source.name()));
      if (Set.of("application/octet-stream", "application/zip", "text/plain", "application/octetstream").contains(source.mediaType().toLowerCase(Locale.ROOT)))
        report.add("WARN", "SOURCE_MIME_GENERIC", "A specific source-email media type is recommended.", Map.of("file", source.name(), "media_type", source.mediaType()));
    }
    for (Element asset : assets) {
      String filename = field(asset, "Filename");
      report.check("ASSET_XMP_FIELDS", !filename.isBlank() && fieldElement(asset, "SizeInBytes") != null && !field(asset, "ContentType").isBlank() && !field(asset, "Checksum").isBlank(),
          "Asset records require Filename, SizeInBytes, ContentType and Checksum.", Map.of("file", filename));
      boolean requiredStrings = List.of("Filename", "ContentType", "Checksum").stream().allMatch(property -> simple(fieldElement(asset, property)) && !field(asset, property).isBlank());
      if (requiredStrings || fieldElement(asset, "SizeInBytes") == null) checkInteger(asset, "SizeInBytes", "ASSET_SIZE_TYPE");
      if (requiredStrings && fieldElement(asset, "NumberMessages") != null) checkInteger(asset, "NumberMessages", "ASSET_NUMBERMESSAGES_TYPE");
      if (requiredStrings || !simple(fieldElement(asset, "Checksum")) || field(asset, "Checksum").isBlank()) report.check("ASSET_CHECKSUM_FORM", simple(fieldElement(asset, "Checksum")) && field(asset, "Checksum").matches("[0-9a-fA-F]{32}"), "Asset Checksum must be 32 hexadecimal MD5 characters.", Map.of("file", filename));
      if (requiredStrings || !simple(fieldElement(asset, "ContentType")) || field(asset, "ContentType").isBlank()) report.check("ASSET_CONTENTTYPE", simple(fieldElement(asset, "ContentType")) && mediaType(field(asset, "ContentType")), "Asset ContentType must contain a valid media type.", Map.of("file", filename));
      if (Set.of("application/octet-stream", "application/zip", "text/plain", "application/octetstream").contains(baseMedia(field(asset, "ContentType"))))
        report.add("WARN", "ASSET_CONTENTTYPE_GENERIC", "A specific source-email media type is recommended.", Map.of("file", filename));
      List<EaPdfChecks.Embedded> named = filename.isEmpty() ? List.of()
          : filesByName.getOrDefault(basename(filename), List.of()).stream().filter(remainingAssets::contains).toList();
      List<EaPdfChecks.Embedded> digested = simple(fieldElement(asset, "Checksum"))
          ? filesByDigest.getOrDefault(field(asset, "Checksum").toLowerCase(Locale.ROOT), List.of()).stream().filter(remainingAssets::contains).toList() : List.of();
      List<EaPdfChecks.Embedded> candidates = digested.isEmpty() ? named : digested;
      if (candidates.size() > 1) {
        var sameName = candidates.stream().filter(named::contains).toList();
        if (sameName.size() == 1) candidates = sameName;
        else {
          var first = candidates.getFirst();
          if (candidates.stream().allMatch(file -> file.sameBytes(first)
              && sameMedia(reconstructedMedia(file), reconstructedMedia(first)))) candidates = List.of(first);
        }
      }
      if (candidates.size() == 1) {
        var source = candidates.getFirst();
        if (field(asset, "Checksum").equalsIgnoreCase(source.md5())) assetBindings.put(asset, source);
        remainingAssets.remove(source);
        report.check("ASSET_MATCH", true, "The asset description identifies a preserved source.", Map.of("file", filename));
        report.check("ASSET_MD5_MATCH", field(asset, "Checksum").equalsIgnoreCase(source.md5()), "Asset MD5 must match preserved source bytes.", Map.of("file", filename, "actual", source.md5()));
        if (!simple(fieldElement(asset, "SizeInBytes")))
          report.add("WARN", "ASSET_SIZE_MATCH", "Source size comparison requires SizeInBytes as a valid simple integer; its type violation is reported separately.", Map.of("assessment", "unavailable"));
        else report.check("ASSET_SIZE_MATCH", integerEquals(field(asset, "SizeInBytes"), source.size()), "Asset size must match preserved source bytes.", Map.of("file", filename, "actual", source.size()));
        report.check("ASSET_CONTENTTYPE_MATCH", (get(source.stream(), "Mail_MediaTypeParameters") == null || get(source.stream(), "Mail_MediaTypeParameters") instanceof org.apache.pdfbox.cos.COSString)
            && sameMedia(field(asset, "ContentType"), reconstructedMedia(source)), "Asset media type and parameters must match its embedded stream.", Map.of("file", filename));
      } else if (candidates.isEmpty()) report.check("ASSET_MATCH", false, "The asset description must identify a preserved source.", Map.of("file", filename));
      else {
        ambiguousAssets++; candidates.forEach(candidate -> ambiguousSources.add(candidate.spec()));
        report.unavailable("ASSET_MATCH", "Several preserved sources could match this asset: " + filename);
        report.unavailable("ASSET_MD5_MATCH", "Source occurrence identity is ambiguous.");
        if (simple(fieldElement(asset, "Checksum")) && field(asset, "Checksum").matches("[0-9a-fA-F]{32}")
            && files.stream().filter(file -> sourceSpecSet.contains(file.spec()) || Set.of("RF", "Supplement").contains(file.relationship()))
               .noneMatch(file -> field(asset, "Checksum").equalsIgnoreCase(file.md5())))
          report.check("ASSET_MD5_MATCH", false, "The asset checksum matches none of the possible original sources.", null);
        if (simple(fieldElement(asset, "SizeInBytes")) && nonnegative(field(asset, "SizeInBytes"))
            && candidates.stream().allMatch(f -> !integerEquals(field(asset, "SizeInBytes"), f.size())))
          report.check("ASSET_SIZE_MATCH", false, "Every possible source contradicts the declared asset size.", Map.of("file", filename));
        else report.unavailable("ASSET_SIZE_MATCH", "Ambiguous source ownership prevents an exact size comparison.");
        if (simple(fieldElement(asset, "ContentType")) && !field(asset, "ContentType").isBlank()
            && candidates.stream().allMatch(f -> !f.mediaType().isEmpty() && !sameMedia(field(asset, "ContentType"),
                f.mediaType() + ";" + string(get(f.stream(), "Mail_MediaTypeParameters")))))
          report.check("ASSET_CONTENTTYPE_MATCH", false, "Every possible source contradicts the declared asset media type.", Map.of("file", filename));
        else report.unavailable("ASSET_CONTENTTYPE_MATCH", "Ambiguous source ownership prevents an exact media comparison.");
      }
    }
    long ambiguousRemaining = remainingAssets.stream().filter(file -> sourceSpecSet.contains(file.spec()) && ambiguousSources.contains(file.spec())).count();
    if (ambiguousRemaining > ambiguousAssets) report.check("SOURCE_WITHOUT_XMP", false, "There are more unmatched original sources than ambiguous asset records.", null);
    for (var file : remainingAssets) if (sourceSpecSet.contains(file.spec())) {
      if (ambiguousSources.contains(file.spec())) report.unavailable("SOURCE_WITHOUT_XMP", "Source metadata coverage needs an unambiguous asset association: " + file.name());
      else report.check("SOURCE_WITHOUT_XMP", false, "A retained original source has no matching asset metadata.", Map.of("file", file.name()));
    }
    if (!sources.isEmpty() && !report.seen.contains("SOURCE_WITHOUT_XMP")) report.check("SOURCE_WITHOUT_XMP", true, "Every retained original source has matching asset metadata.", null);
    if (PROFILES.contains(profile)) for (Element attachment : attachments) {
      String filename = field(attachment, "Filename");
      report.check("ATTACHMENT_XMP_FILENAME", simple(fieldElement(attachment, "Filename")) && !filename.isEmpty(), "Attachment Filename must be populated normal text.", null);
      report.check("ATTACHMENT_XMP_SIZE_PRESENT", fieldElement(attachment, "SizeInBytes") != null, "Attachment SizeInBytes is required.", Map.of("file", filename));
      checkInteger(attachment, "SizeInBytes", "ATTACHMENT_XMP_SIZE_TYPE");
      report.check("ATTACHMENT_XMP_CONTENTTYPE", simple(fieldElement(attachment, "Content-Type")) && !field(attachment, "Content-Type").isBlank(),
          "Attachment Content-Type must be nonempty normal text preserving the original header, including any errors.", Map.of("file", filename, "value", field(attachment, "Content-Type")));
      if (fieldElement(attachment, "CheckSum") != null) {
        boolean valid = simple(fieldElement(attachment, "CheckSum")) && field(attachment, "CheckSum").matches("[0-9a-fA-F]{32}");
        report.check("ATTACHMENT_XMP_CHECKSUM_FORM", valid,
            valid ? "Attachment XMP CheckSum has the required 32 hexadecimal characters (format check only)."
                : "Attachment XMP CheckSum must be normal text of exactly 32 ASCII hexadecimal characters (0-9, A-F or a-f).",
            Map.of("file", filename, "CheckSum", textValue(fieldElement(attachment, "CheckSum")),
                "normal_text", simple(fieldElement(attachment, "CheckSum")),
                "scope", "XMP format; attachment-byte comparisons are reported separately."));
      } else report.check("ATTACHMENT_XMP_CHECKSUM_FORM", true, "Optional attachment XMP CheckSum is omitted, as Table 19 permits.", Map.of("file", filename));
      String guid = field(attachment, "Mail_GUID");
      for (String identity : List.of("Mail_GUID", "Message-ID")) if (fieldElement(attachment, identity) != null)
        report.check("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID", simple(fieldElement(attachment, identity)),
            "Supplied attachment identities must be normal text.", Map.of("field", identity));
      if (simple(fieldElement(attachment, "Mail_GUID")) && (emails.isEmpty() || !emailsByGuid.containsKey(guid))) {
        if (completeGuids)
          report.check("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID", false, "Supplied attachment GUID does not identify any represented email.", null);
        else report.unavailable("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID", "The email GUID inventory is incomplete, so attachment ownership is unavailable.");
      }
      if (field(attachment, "Message-ID").isEmpty() && field(attachment, "messageid").isEmpty()) report.check("ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID",
          simple(fieldElement(attachment, "Mail_GUID")) && !guid.isEmpty(),
          "An attachment without Message-ID must identify its owning email by Mail_GUID.", Map.of("file", filename));
    }
    report.supported("ATTACHMENT_XMP_FILENAME", "ATTACHMENT_XMP_SIZE_PRESENT", "ATTACHMENT_XMP_SIZE_TYPE", "ATTACHMENT_XMP_CONTENTTYPE", "ATTACHMENT_XMP_CHECKSUM_FORM", "ATTACHMENT_XMP_MAIL_GUID_IF_NO_MESSAGE_ID");
    if (attachments.isEmpty() || Set.of("c", "ci").contains(profile)) {
      report.na("ATTACHMENT_XMP_SIZE_MATCH", "Native attachment-size comparisons require attachment records in an email profile.", null);
      report.na("ATTACHMENT_XMP_CONTENTTYPE_MATCH", "Original attachment comparisons run only for supplied attachment records in their owning email PDF.", null);
    }
    for (EaPdfChecks.Embedded file : files) {
      if (!"Source".equals(file.relationship())) continue;
      List<Element> named = assetsByName.getOrDefault(basename(file.name()), List.of());
      List<Element> digested = assetsByDigest.getOrDefault(file.md5().toLowerCase(Locale.ROOT), List.of());
      List<Element> candidates = digested.isEmpty() ? named : digested;
      if (candidates.size() > 1) {
        var sameName = candidates.stream().filter(named::contains).toList();
        if (sameName.size() == 1) candidates = sameName;
      }
      if (candidates.size() != 1) {
        boolean parameters = (candidates.isEmpty() ? assets : candidates).stream().anyMatch(asset -> simple(fieldElement(asset, "ContentType")) && field(asset, "ContentType").contains(";"));
        if (candidates.size() > 1 || parameters) report.unavailable("EMBEDDED_MEDIA_TYPE_MATCH_XMP", "The equivalent source asset cannot be selected unambiguously: " + file.name());
        if (parameters) report.unavailable("EMBEDDED_MEDIA_TYPE_MATCH_XMP", "Source asset parameters require an unambiguous equivalent XMP association.");
        continue;
      }
      String type = field(candidates.getFirst(), "ContentType");
      if (type.isEmpty()) continue;
      String reconstructed = reconstructedMedia(file);
      report.check("EMBEDDED_MEDIA_TYPE_MATCH_XMP", reconstructed != null && sameMedia(reconstructed, type), "Reconstructed embedded media type must match its equivalent XMP record.",
          Map.of("file", file.name(), "actual", reconstructed == null ? "unavailable" : reconstructed, "xmp", type));
    }
    report.supported("EMBEDDED_MEDIA_TYPE_MATCH_XMP", "EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", "SOURCE_MEDIATYPE_PARAMETERS_MATCH");
  }

  private List<Element> attachmentOwners(Element attachment) {
    // The metadata DOM is immutable during a validation. In archives, the same
    // attachment is queried for each email and again for each native file.
    return attachmentOwnerCache.computeIfAbsent(attachment, this::findAttachmentOwners);
  }

  private List<Element> findAttachmentOwners(Element attachment) {
    var records = emails.isEmpty() && legacyEmail != null ? List.of(legacyEmail) : emails;
    String guid = simple(fieldElement(attachment, "Mail_GUID")) ? field(attachment, "Mail_GUID") : "";
    if (!guid.isEmpty()) return emailsByGuid.getOrDefault(guid, List.of());
    String mid = simple(fieldElement(attachment, "Message-ID")) ? normal(field(attachment, "Message-ID")) : "";
    if (!mid.isEmpty()) return emailsByMessageId.getOrDefault(mid, List.of());
    return records.size() == 1 ? records : List.of();
  }

  void nativeAttachments(org.apache.pdfbox.pdmodel.PDDocument document) {
    if (!Set.of("s", "si", "m", "mi").contains(profile)) return;
    List<EaPdfChecks.Embedded> natives = files.stream().filter(file -> !Set.of("Source", "Alternative").contains(file.relationship())).toList();
    Map<Element, List<EaPdfChecks.Embedded>> possible = new java.util.IdentityHashMap<>(), comparable = new java.util.IdentityHashMap<>();
    var scopes = AttachmentChecks.nativeOwnerScopes(document, emails);
    Map<Element, List<Element>> attachmentsByOwner = new java.util.IdentityHashMap<>();
    List<Element> unowned = new ArrayList<>();
    Map<Element, Integer> attachmentOrder = new java.util.IdentityHashMap<>();
    Map<Element, String> declaredChecksums = new java.util.IdentityHashMap<>();
    Map<Element, String> simpleFilenames = new java.util.IdentityHashMap<>();
    for (Element attachment : attachments) {
      attachmentOrder.put(attachment, attachmentOrder.size());
      declaredChecksums.put(attachment, attachmentChecksum(attachment));
      if (simple(fieldElement(attachment, "Filename"))) simpleFilenames.put(attachment, field(attachment, "Filename"));
      var owners = attachmentOwners(attachment);
      if (owners.size() != 1) unowned.add(attachment);
      else attachmentsByOwner.computeIfAbsent(owners.getFirst(), ignored -> new ArrayList<>()).add(attachment);
    }
    for (EaPdfChecks.Embedded file : natives) {
      Set<Element> scope = scopes.get(file.spec());
      List<Element> scopedAttachments = attachments;
      if (scope != null) {
        Set<Element> scoped = identitySet(); scoped.addAll(unowned);
        for (Element owner : scope) scoped.addAll(attachmentsByOwner.getOrDefault(owner, List.of()));
        scopedAttachments = scoped.stream().sorted(java.util.Comparator.comparingInt(attachmentOrder::get)).toList();
      }
      String filename = AttachmentChecks.nativeName(file);
      List<Element> candidates = scopedAttachments.stream().filter(attachment -> {
        String checksum = declaredChecksums.get(attachment);
        if (checksum.isEmpty() && fieldElement(attachment, "CheckSum") == null) {
          var owners = attachmentOwners(attachment);
          if (owners.size() == 1) checksum = sourceChecksum(attachment, owners.getFirst());
        }
        return file.md5().equalsIgnoreCase(checksum);
      }).toList();
      if (candidates.size() > 1) {
        var named = candidates.stream().filter(attachment -> filename.equals(simpleFilenames.get(attachment))).toList();
        if (named.size() == 1) candidates = named;
      }
      if (candidates.isEmpty()) candidates = scopedAttachments.stream().filter(attachment -> filename.equals(simpleFilenames.get(attachment))).toList();
      if (candidates.isEmpty() && file.size() == 0) candidates = scopedAttachments.stream().filter(attachment -> fieldElement(attachment, "CheckSum") == null && integerEquals(field(attachment, "SizeInBytes"), 0)).toList();
      for (Element attachment : candidates) possible.computeIfAbsent(attachment, ignored -> new ArrayList<>()).add(file);
      if (candidates.size() > 1) {
        Element first = candidates.getFirst();
        if (!validComparison(first) || candidates.stream().anyMatch(attachment -> !validComparison(attachment)
            || !new BigInteger(field(attachment, "SizeInBytes").strip()).equals(new BigInteger(field(first, "SizeInBytes").strip()))
            || !attachmentChecksum(attachment).equalsIgnoreCase(attachmentChecksum(first))
            || !sameMedia(field(attachment, "Content-Type"), field(first, "Content-Type")))) continue;
      }
      for (Element attachment : candidates) comparable.computeIfAbsent(attachment, ignored -> new ArrayList<>()).add(file);
    }
    for (Element attachment : attachments) {
      List<EaPdfChecks.Embedded> candidates = comparable.getOrDefault(attachment, List.of());
      if (candidates.size() > 1) {
        var first = candidates.getFirst();
        if (candidates.stream().anyMatch(file -> file.size() != first.size() || !file.md5().equals(first.md5())
            || !sameMedia(reconstructedMedia(file), reconstructedMedia(first)))) candidates = List.of();
      }
      boolean associated = !candidates.isEmpty();
      if (!associated) candidates = possible.getOrDefault(attachment, List.of());
      String size = field(attachment, "SizeInBytes"), checksum = attachmentChecksum(attachment), type = field(attachment, "Content-Type");
      if (!candidates.isEmpty()) {
        boolean wrongSize = simple(fieldElement(attachment, "SizeInBytes")) && nonnegative(size) && candidates.stream().allMatch(file -> !integerEquals(size, file.size()));
        boolean wrongChecksum = checksum.matches("[0-9a-fA-F]{32}") && candidates.stream().allMatch(file -> !checksum.equalsIgnoreCase(file.md5()));
        if (wrongSize || associated) report.check("ATTACHMENT_XMP_SIZE_MATCH", !wrongSize && simple(fieldElement(attachment, "SizeInBytes")) && nonnegative(size),
            "Attachment XMP SizeInBytes was compared with independently associated stored native bytes.", Map.of("file", field(attachment, "Filename")));
        if (fieldElement(attachment, "CheckSum") != null && (wrongChecksum || associated))
          report.check("ATTACHMENT_XMP_CHECKSUM_MATCH", !wrongChecksum && checksum.matches("[0-9a-fA-F]{32}"),
              "Supplied attachment XMP CheckSum was compared with stored native bytes.", Map.of("file", field(attachment, "Filename")));
        if (simple(fieldElement(attachment, "Content-Type")) && !type.isBlank()) {
          boolean known = candidates.stream().allMatch(file -> reconstructedMedia(file) != null && !file.mediaType().isEmpty());
          if (associated) {
            report.check("EMBEDDED_MEDIA_TYPE_MATCH_XMP", sameMedia(type, reconstructedMedia(candidates.getFirst())), "Associated native media type must match equivalent XMP Content-Type.", null);
          } else {
            if (known && candidates.stream().allMatch(file -> !sameMedia(type, reconstructedMedia(file)))) report.check("EMBEDDED_MEDIA_TYPE_MATCH_XMP", false, "Attachment media type contradicts every possible stored native payload.", null);
            if (type.contains(";") && known && candidates.stream().allMatch(file -> !sameMedia(mediaParameters(type), mediaParameters(reconstructedMedia(file)))))
              report.check("EMBEDDED_MEDIA_TYPE_MATCH_XMP", false, "Attachment media parameters contradict every possible stored native payload.", null);
          }
        }
        if (associated) {
          List<Element> owners = attachmentOwners(attachment);
          for (var file : candidates) originalParameters(file, owners.size() == 1 ? owners.getFirst() : null);
        }
      }
      if (!associated && !natives.isEmpty()) {
        report.unavailable("ATTACHMENT_XMP_SIZE_MATCH", "No unique native payload association establishes the attachment-size comparison.");
        if (fieldElement(attachment, "CheckSum") != null) report.unavailable("ATTACHMENT_XMP_CHECKSUM_MATCH", "No unique native payload association establishes the attachment-checksum comparison.");
        report.unavailable("EMBEDDED_MEDIA_TYPE_MATCH_XMP", "Native media-type comparison needs an unambiguous equivalent XMP association.");
        if (simple(fieldElement(attachment, "Content-Type")) && type.contains(";")) report.unavailable("EMBEDDED_MEDIA_TYPE_MATCH_XMP", "Declared native media parameters require an unambiguous payload association.");
      }
      if (!associated && !natives.isEmpty()) {
        report.unavailable("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED", "Native attachment association is unavailable for the original-byte recommendation.", Map.of("file", field(attachment, "Filename")));
        for (String code : List.of("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", "SOURCE_MEDIATYPE_PARAMETERS_MATCH")) {
          if (sourceSpecs.isEmpty() && profile.endsWith("i")) report.na(code, "The isolated original is deliberately omitted.", null);
          else report.unavailable(code, "Native attachment association is unavailable for comparing original parameters.");
        }
      } else originalNativeFidelity(attachment, candidates);
    }
  }

  private static boolean validComparison(Element attachment) {
    String checksum = attachmentChecksum(attachment);
    return simple(fieldElement(attachment, "SizeInBytes")) && nonnegative(field(attachment, "SizeInBytes"))
        && simple(fieldElement(attachment, "Content-Type")) && !field(attachment, "Content-Type").isBlank()
        && (fieldElement(attachment, "CheckSum") == null || checksum.matches("[0-9a-fA-F]{32}"));
  }

  private static String reconstructedMedia(EaPdfChecks.Embedded file) {
    COSBase parameters = get(file.stream(), "Mail_MediaTypeParameters");
    return parameters != null && !(parameters instanceof org.apache.pdfbox.cos.COSString) ? null : file.mediaType() + ";" + string(parameters);
  }

  private void originalParameters(EaPdfChecks.Embedded file, Element owner) {
    var details = Map.of("file", file.name(), "check", "original_parameter_fidelity");
    String reason = null;
    if (sourceSpecs.isEmpty() && profile.endsWith("i")) reason = "This isolated file omits the original email; original parameter preservation was not compared.";
    List<String> types = reason == null ? originalMediaTypes(file, owner) : List.of();
    if (reason != null) {
      for (String code : List.of("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", "SOURCE_MEDIATYPE_PARAMETERS_MATCH")) report.na(code, reason, details);
    } else if (types.size() != 1) {
      for (String code : List.of("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", "SOURCE_MEDIATYPE_PARAMETERS_MATCH"))
        report.unavailable(code, "Original attachment parameters cannot be independently associated with these native bytes.", details);
    } else {
      String expected = types.getFirst(); int separator = expected.indexOf(';');
      if (separator < 0 || expected.substring(separator + 1).isBlank()) {
        for (String code : List.of("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", "SOURCE_MEDIATYPE_PARAMETERS_MATCH"))
          report.na(code, expected.isEmpty() ? "The independently matched original attachment has no stored media-type declaration."
              : "The independently matched original supplies no media-type parameters.", details);
        return;
      }
      COSBase supplied = get(file.stream(), "Mail_MediaTypeParameters");
      var evidence = new java.util.LinkedHashMap<String, Object>(details);
      evidence.put("expected_parameters", expected.substring(separator + 1)); evidence.put("supplied", observed(supplied));
      report.check("EMBEDDED_MEDIATYPE_PARAMETERS_REQUIRED", supplied != null,
          "The independently matched original supplies parameters, so Mail_MediaTypeParameters must be present.", evidence);
      if (!(supplied instanceof org.apache.pdfbox.cos.COSString)) {
        report.na("SOURCE_MEDIATYPE_PARAMETERS_MATCH", "Original-value comparison requires the stored PDF string; its absence or type is reported separately.", evidence); return;
      }
      String value = decodedString(supplied);
      if (value == null) report.unavailable("SOURCE_MEDIATYPE_PARAMETERS_MATCH", "The stored media-parameter string cannot be decoded.", evidence);
      else report.check("SOURCE_MEDIATYPE_PARAMETERS_MATCH", sameMedia(mediaParameters(expected), mediaParameters("application/octet-stream;" + value)),
          "Stored parameters were compared with the independently matched original attachment.", evidence);
    }
  }

  private List<String> originalMediaTypes(EaPdfChecks.Embedded file, Element owner) {
    Source source = sourceBindings.get(owner);
    if (source == null || file == null) return List.of();
    try {
      return originalAttachments(source).stream().filter(attachment -> file.size() == attachment.size()
          && file.md5().equalsIgnoreCase(attachment.md5()))
          .map(NativeAttachment::contentType).distinct().limit(2).toList();
    } catch (Exception unavailable) { return List.of(); }
  }

  void renderedCounts(ContentOwnership.Result content) {
    for (Element asset : assets) {
      if (fieldElement(asset, "NumberMessages") == null) continue;
      if (!simple(fieldElement(asset, "NumberMessages")) || !nonnegative(field(asset, "NumberMessages"))) {
        report.na("ASSET_NUMBERMESSAGES_MATCH_RENDERED", "The supplied count has no valid nonnegative integer value to compare; its type is reported separately.", Map.of("file", field(asset, "Filename")));
        continue;
      }
      var file = assetBindings.get(asset);
      var evidence = new java.util.LinkedHashMap<String, Object>();
      evidence.put("assertion", "T16-NumberMessages"); evidence.put("check", "rendered_message_count");
      evidence.put("file", field(asset, "Filename")); evidence.put("declared", field(asset, "NumberMessages"));
      if (file != null && sourceOccurrences.getOrDefault(file.md5().toLowerCase(Locale.ROOT) + ":" + file.size(), Set.of()).size() > 1) {
        report.unavailable("ASSET_NUMBERMESSAGES_MATCH_RENDERED", "Identical source bytes occur in more than one source file; their individual rendered-message counts cannot be assigned by checksum alone.", evidence);
        continue;
      }
      if (Set.of("c", "ci").contains(profile)) {
        boolean known = childrenFinished && childCountsComplete && file != null && sourceSpecSet.contains(file.spec());
        if (!known) report.unavailable("ASSET_NUMBERMESSAGES_MATCH_RENDERED", "Validated child evidence does not establish this source asset's rendered-message count.", evidence);
        else {
          long count = childRenderedSources.getOrDefault(file.md5().toLowerCase(Locale.ROOT) + ":" + file.size(), Set.of()).size();
          evidence.put("rendered_messages", count); evidence.put("evidence", "validated child source bytes and independently owned content");
          report.check("ASSET_NUMBERMESSAGES_MATCH_RENDERED", integerEquals(field(asset, "NumberMessages"), count),
              "NumberMessages was compared with rendered emails in the validated collection children, using source byte identity.", evidence);
        }
        continue;
      }
      boolean known = file != null && sourceSpecSet.contains(file.spec()) && content.complete()
          && !emails.isEmpty() && sourceBindings.size() == emails.size()
          && emails.stream().allMatch(email -> content.renderedGuids().contains(field(email, "Mail_GUID")));
      if (!known) {
        report.unavailable("ASSET_NUMBERMESSAGES_MATCH_RENDERED", "The supplied NumberMessages is an integer, but this source's successfully rendered message count cannot be independently established.", evidence);
        continue;
      }
      long count = sourceBindings.entrySet().stream().filter(entry -> entry.getValue().file().spec() == file.spec()
          && content.renderedGuids().contains(field(entry.getKey(), "Mail_GUID"))).count();
      evidence.put("rendered_messages", count);
      report.check("ASSET_NUMBERMESSAGES_MATCH_RENDERED", integerEquals(field(asset, "NumberMessages"), count),
          "NumberMessages was compared with this source's independently bound, rendered email records.", evidence);
    }
  }

  void childCounts(com.google.gson.JsonObject child) {
    if (!"PASS".equals(JavaValidation.text(child.getAsJsonObject("summary"), "status"))) { childCountsComplete = false; return; }
    int declared = -1;
    Map<String, String> sources = new java.util.LinkedHashMap<>();
    for (var item : child.getAsJsonArray("items")) {
      var row = item.getAsJsonObject();
      if (!"PASS".equals(JavaValidation.text(row, "level")) || !row.get("details").isJsonObject()) continue;
      var details = row.getAsJsonObject("details");
      if ("EMAIL_XMP_CARDINALITY".equals(JavaValidation.text(row, "code")) && details.has("count")) declared = details.get("count").getAsInt();
      if ("MESSAGE_SOURCE_COVERAGE".equals(JavaValidation.text(row, "code")) && details.has("source_md5") && details.has("source_size")) {
        String guid = JavaValidation.text(details, "Mail_GUID");
        String key = JavaValidation.text(details, "source_md5").toLowerCase(Locale.ROOT) + ":" + details.get("source_size").getAsLong();
        if (guid.isEmpty() || sources.put(guid, key) != null) childCountsComplete = false;
      }
    }
    if (declared < 1 || sources.size() != declared) { childCountsComplete = false; return; }
    for (var source : sources.entrySet())
      if (!childRenderedSources.computeIfAbsent(source.getValue(), ignored -> new HashSet<>()).add(source.getKey())) childCountsComplete = false;
  }

  void finishChildren(boolean complete) { childrenFinished = complete; }

  private static String mediaParameters(String type) {
    if (type == null) return "";
    int separator = type.indexOf(';');
    return "application/octet-stream;" + (separator < 0 ? "" : type.substring(separator + 1));
  }

  private static String collectionEmailReason(String code) {
    String subject = switch (code) {
      case "MESSAGE_SOURCE_COVERAGE" -> "Matching an email to its original source";
      case "EMAIL_XMP_CONTAINER" -> "The RDF structure of per-email XMP records";
      case "EMAIL_XMP_CARDINALITY" -> "The required count of per-email XMP records";
      case "MESSAGE_TO_XMP" -> "The To recipient metadata requirement";
      case "MESSAGE_TO_FOAF" -> "The FOAF Agent encoding requirement for To recipients";
      case "MESSAGE_RAW_TO_XMP" -> "The optional Raw-To text format check";
      case "MESSAGE_FROM_XMP" -> "The From sender metadata requirement";
      case "MESSAGE_FROM_FOAF" -> "The FOAF Agent encoding requirement for From senders";
      case "MESSAGE_RAW_FROM_XMP" -> "The optional Raw-From text format check";
      case "MESSAGE_SENT_XMP" -> "The Sent date metadata requirement";
      case "MESSAGE_SENT_FORMAT" -> "The Sent date format check";
      case "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID" -> "Preserving an invalid original Date header as Raw-Sent";
      case "MESSAGE_RAW_SENT_OMIT_IF_VALID" -> "The recommendation to omit Raw-Sent when the Date header is valid";
      case "MESSAGE_SUBJECT_XMP" -> "The Subject metadata requirement";
      case "MESSAGE_ID_XMP" -> "The Message-ID metadata requirement";
      case "MAIL_GUID_XMP" -> "The per-email Mail_GUID metadata requirement";
      case "MAIL_GUID_UNIQUE" -> "Mail_GUID uniqueness among the represented email records";
      case "MESSAGE_SIZE_XMP" -> "The original email SizeInBytes comparison";
      case "MESSAGE_ATTACHMENT_COUNT_XMP" -> "The per-email NumberAttachments comparison";
      case "MESSAGE_CC_XMP" -> "The optional Cc recipient metadata check";
      case "MESSAGE_CC_FOAF" -> "The FOAF Agent encoding requirement for Cc recipients";
      case "MESSAGE_RAW_CC_XMP" -> "The optional Raw-Cc text format check";
      case "MESSAGE_BCC_XMP" -> "The optional Bcc recipient metadata check";
      case "MESSAGE_BCC_FOAF" -> "The FOAF Agent encoding requirement for Bcc recipients";
      case "MESSAGE_RAW_BCC_XMP" -> "The optional Raw-Bcc text format check";
      case "MESSAGE_INREPLYTO_XMP" -> "The In-Reply-To identifier metadata check";
      case "MESSAGE_METADATA_SKIPPED" -> "Per-email metadata inspection";
      default -> "The " + code + " email check";
    };
    return subject + " applies to embedded email PDFs; this file is the outer collection.";
  }

  void emails() throws Exception {
    List<Source> sources = new ArrayList<>();
    Set<COSDictionary> parsedSpecs = identitySet();
    List<EaPdfChecks.Embedded> mimeFiles = files.stream().filter(file -> sourceSpecSet.contains(file.spec())
        && !"application/vnd.ms-outlook".equalsIgnoreCase(file.mediaType()) && !file.name().toLowerCase(Locale.ROOT).endsWith(".msg")
        && (Set.of("message/rfc822", "message/global").contains(file.mediaType().toLowerCase(Locale.ROOT)) || file.name().toLowerCase(Locale.ROOT).endsWith(".eml"))).toList();
    MimeSourceStore store = null;
    if (mimeFiles.stream().mapToLong(EaPdfChecks.Embedded::size).sum() > 8L * 1024 * 1024) {
      store = new MimeSourceStore(mimeFiles); report.resources.add(store);
    }
    for (EaPdfChecks.Embedded file : files) {
      if (!sourceSpecSet.contains(file.spec()) || !parsedSpecs.add(file.spec())) continue;
      boolean outlookFile = "application/vnd.ms-outlook".equalsIgnoreCase(file.mediaType()) || file.name().toLowerCase(Locale.ROOT).endsWith(".msg");
      OutlookSource.Evidence outlook = outlookFile ? OutlookSource.inspect(file.bytes()) : null;
      OutlookSource.Core nativeCore = outlookFile && outlook == null ? OutlookSource.nativeCore(file.bytes()) : null;
      if (outlookFile && outlook == null && nativeCore == null) continue;
      if (!outlookFile && !Set.of("message/rfc822", "message/global").contains(file.mediaType().toLowerCase(Locale.ROOT)) && !file.name().toLowerCase(Locale.ROOT).endsWith(".eml")) continue;
      try (var input = store != null && !outlookFile ? store.open(file)
          : new ByteArrayInputStream(nativeCore != null ? new byte[0] : outlook == null ? file.bytes() : outlook.headers())) {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), input);
        sources.add(new Source(file, message, outlook, nativeCore));
      }
    }
    inspectableSourceCount = sources.size();
    if (attachments.isEmpty() && !sources.isEmpty() && Set.of("s", "si", "m", "mi").contains(profile)) {
      boolean found = false, unreadable = false;
      for (Source source : sources) try { found |= !originalAttachments(source).isEmpty(); }
      catch (Exception unavailable) { unreadable = true; }
      if (found) report.recommend("ATTACHMENT_XMP_INVENTORY_RECOMMENDED", false, "The original email has attachments but document XMP declares none.", null);
      else if (unreadable) report.unavailable("ATTACHMENT_XMP_INVENTORY_RECOMMENDED", "The original attachment inventory could not be inspected.");
      else report.na("ATTACHMENT_XMP_INVENTORY_RECOMMENDED", "The inspectable original emails contain no MIME attachments.", null);
    }
    if (Set.of("c", "ci").contains(profile)) {
      for (String code : JavaValidation.RULES.keySet()) if (code.startsWith("MESSAGE_") || Set.of("EMAIL_XMP_CONTAINER", "EMAIL_XMP_CARDINALITY", "MAIL_GUID_XMP", "MAIL_GUID_UNIQUE").contains(code)) {
        report.na(code, collectionEmailReason(code), null);
      }
      return;
    }
    if (!PROFILES.contains(profile)) { report.na("MESSAGE_METADATA_SKIPPED", "No mail profile activates these checks.", null); return; }
    report.check("MESSAGE_METADATA_SKIPPED", true, "Email metadata checks were applied.", null);
    report.check("EMAIL_XMP_CARDINALITY", Set.of("s", "si").contains(profile) ? emails.size() == 1 : emails.size() >= 2,
        "Profile 1" + profile + " requires " + (Set.of("s", "si").contains(profile) ? "exactly one" : "at least two") + " XMP email records.", Map.of("count", emails.size()));
    Set<String> guids = new HashSet<>();
    SourceIndex remaining = new SourceIndex(sources);
    NativeSource nativeSource = nativeSource();
    boolean nativeInventory = nativeSource != null && nativeSource.core().attachments() != null && attachments.stream().allMatch(attachment -> {
      var owners = attachmentOwners(attachment);
      return owners.size() == 1 && owners.getFirst() == emails.getFirst();
    });
    if (sources.isEmpty() && !attachments.isEmpty() && !nativeInventory) {
      for (String code : List.of("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED", "ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", "ATTACHMENT_XMP_MESSAGE_ID_MATCH", "ATTACHMENT_XMP_CONTENTTYPE_MATCH")) {
        if (sourceSpecs.isEmpty()) report.na(code, "No inspectable original MIME attachment inventory is preserved; a separate native copy is not universally required.", null);
        else report.unavailable(code, "The original attachment inventory cannot be read from this source format.");
      }
    }
    for (Element email : emails.isEmpty() && legacyEmail != null ? List.of(legacyEmail) : emails) {
      String guid = field(email, "Mail_GUID");
      report.check("MESSAGE_ID_XMP", true, "Message-ID is optional; supplied values have separate type and original-source checks.", null);
      report.check("MAIL_GUID_XMP", simple(fieldElement(email, "Mail_GUID")) && !guid.isEmpty(), "Each email requires a normal text Mail_GUID.", null);
      if (!guid.isEmpty()) report.check("MAIL_GUID_UNIQUE", guids.add(guid), "Each email Mail_GUID must be unique.", Map.of("value", guid));
      else report.unavailable("MAIL_GUID_UNIQUE", "GUID uniqueness cannot be assessed until a normal text GUID is available.");
      if (fieldElement(email, "Sent") != null && !simple(fieldElement(email, "Sent")))
        report.unavailable("MESSAGE_SENT_XMP", "The supplied Sent value is not normal text; MESSAGE_SENT_FORMAT reports its type violation.");
      else if (sourceSpecs.isEmpty() && !field(email, "Sent").isEmpty() && !dateTime(field(email, "Sent")))
        report.unavailable("MESSAGE_SENT_XMP", "Sent presence cannot establish a valid dateTime; MESSAGE_SENT_FORMAT reports its invalid value.");
      else report.check("MESSAGE_SENT_XMP", fieldElement(email, "Sent") != null && !field(email, "Sent").isEmpty(), "Each email requires Sent metadata.", Map.of("Mail_GUID", guid));
      if (!field(email, "Sent").isEmpty() || fieldElement(email, "Sent") != null)
        report.check("MESSAGE_SENT_FORMAT", simple(fieldElement(email, "Sent")) && dateTime(field(email, "Sent")), "Sent must be an XMP dateTime.", Map.of("Mail_GUID", guid));
      for (String field : List.of("Subject", "Raw-To", "Raw-From", "Raw-Cc", "Raw-Bcc", "Raw-Sent", "messageid", "Message-ID")) {
        if (fieldElement(email, field) == null) continue;
        String code = switch (field) {
          case "Subject" -> "MESSAGE_SUBJECT_XMP"; case "messageid", "Message-ID" -> "MESSAGE_ID_XMP";
          case "Raw-Sent" -> "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID"; default -> "MESSAGE_" + field.toUpperCase(Locale.ROOT).replace('-', '_') + "_XMP";
        };
        report.check(code, simple(fieldElement(email, field)), "Supplied email text must retain its normal XMP string type.", Map.of("field", field, "Mail_GUID", guid));
      }
      Element reply = fieldElement(email, "In-Reply-To");
      if (reply != null) {
        var containers = children(reply);
        boolean valid = containers.size() == 1 && is(containers.getFirst(), RDF, "Bag")
            && children(containers.getFirst()).stream().allMatch(item -> is(item, RDF, "li") && simple(item));
        report.check("MESSAGE_INREPLYTO_XMP", valid, "In-Reply-To must be an unordered Bag of normal text identifiers.", null);
      }
      if (fieldElement(email, "messageid") != null && fieldElement(email, "Message-ID") != null)
        report.check("MESSAGE_ID_XMP", field(email, "messageid").equals(field(email, "Message-ID")),
            "Supplied Message-ID aliases must agree.", null);
      for (String field : List.of("SizeInBytes", "NumberAttachments")) if (fieldElement(email, field) != null) checkInteger(email, field,
          "SizeInBytes".equals(field) ? "MESSAGE_SIZE_XMP" : "MESSAGE_ATTACHMENT_COUNT_XMP");
      for (String field : List.of("To", "From", "Cc", "Bcc")) foaf(email, field);
      List<Source> candidates = remaining.candidates(email, emails.isEmpty());
      if (candidates.size() > 1 && candidates.stream().map(source -> source.file().md5()).distinct().count() == 1)
        candidates = List.of(candidates.getFirst());
      if (candidates.size() == 1 && (sourceSpecs.size() > 1 || incomplete(candidates.getFirst()))
          && !candidateFailures(email, candidates.getFirst()).isEmpty()) candidates = List.of();
      if (candidates.size() == 1) {
        Source source = candidates.getFirst();
        remaining.remove(source);
        sourceBindings.put(email, source);
        if (!emails.isEmpty()) report.check("MESSAGE_SOURCE_COVERAGE", true, "The email has one uniquely matched preserved source.",
            Map.of("Mail_GUID", guid, "source", source.file().name(), "source_md5", source.file().md5(), "source_size", source.file().size()));
        try { compare(email, source); }
        catch (jakarta.mail.internet.ParseException error) {
          report.unavailable("ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED", "Malformed original MIME parameters prevent a complete attachment inventory: " + error.getMessage());
          report.unavailable("ATTACHMENT_XMP_CONTENTTYPE_MATCH", "Original MIME parameter interpretation is unavailable.");
        }
        try {
          List<String> types = new ArrayList<>();
          if (source.nativeCore() != null) types.addAll(source.nativeCore().bodyTypes());
          else if (source.outlook() == null) collectBodies(source.message(), types, 0);
          else types.addAll(source.outlook().bodyTypes());
          bodyTypes.put(guid, types);
        } catch (jakarta.mail.internet.ParseException error) {
          report.unavailable("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", "Original MIME body parameters could not be interpreted.");
        }
      } else if (!sourceSpecs.isEmpty()) {
        report.unavailable("MESSAGE_SOURCE_COVERAGE", "The represented email cannot be uniquely bound to a supported source: " + guid);
        for (String code : List.of("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", "MESSAGE_METADATA_MATCH_SOURCE"))
          report.unavailable(code, "Source-dependent email metadata cannot be completely assessed without a unique original association.", Map.of("Mail_GUID", guid));
        universalComparisons(email, sources);
        compareNativeCore(email, nativeSource);
      }
      else {
        report.na("MESSAGE_SOURCE_COVERAGE", "No original email is embedded in this isolated profile.", Map.of("Mail_GUID", guid));
        for (String code : List.of("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", "MESSAGE_METADATA_MATCH_SOURCE", "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID", "MESSAGE_RAW_SENT_OMIT_IF_VALID")) {
          report.na(code, "The source-dependent comparison has no embedded original.", null);
        }
      }
    }
    report.supported("MESSAGE_TO_XMP", "MESSAGE_FROM_XMP", "MESSAGE_CC_XMP", "MESSAGE_BCC_XMP", "MESSAGE_SUBJECT_XMP", "MESSAGE_SIZE_XMP", "MESSAGE_ATTACHMENT_COUNT_XMP", "MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", "MESSAGE_METADATA_MATCH_SOURCE", "MESSAGE_ID_XMP", "MESSAGE_METADATA_SKIPPED", "MESSAGE_RAW_TO_XMP", "MESSAGE_RAW_FROM_XMP", "MESSAGE_RAW_CC_XMP", "MESSAGE_RAW_BCC_XMP", "MESSAGE_INREPLYTO_XMP",
        "MESSAGE_TO_FOAF", "MESSAGE_FROM_FOAF", "MESSAGE_CC_FOAF", "MESSAGE_BCC_FOAF");
  }

  boolean sourceOwnershipUnknown() { return Set.of("s", "si", "m", "mi").contains(profile) && !sourceSpecs.isEmpty() && !emails.isEmpty() && sourceBindings.size() < emails.size(); }
  boolean hasBoundSources() { return !sourceBindings.isEmpty(); }
  boolean sourceHasHeader(String header) {
    return sourceBindings.values().stream().anyMatch(source -> source.nativeCore() == null ? !header(source.message(), header).isEmpty()
        : "In-Reply-To".equals(header) ? !source.nativeCore().reply().isEmpty()
        : !source.nativeCore().addresses().getOrDefault(header, List.of()).isEmpty()
          || source.nativeCore().rawAddresses().containsKey(header) && !source.nativeCore().rawAddresses().get(header).display().isEmpty());
  }
  boolean sourceHasSentTime() {
    return sourceHasDate(true) || sourceBindings.values().stream().anyMatch(source -> source.nativeCore() != null && source.nativeCore().sent() != null);
  }
  boolean sourceHasDate(boolean valid) {
    for (Source source : sourceBindings.values()) {
      String raw = header(source.message(), "Date");
      if (raw.isEmpty()) continue;
      try { SourceHeaders.instant(raw); if (valid) return true; }
      catch (IllegalArgumentException | java.time.DateTimeException invalid) {
        if (!valid && !SourceHeaders.dateBeyondRange(raw)) return true;
      }
    }
    return false;
  }

  Boolean attachmentPresence() {
    if (!attachments.isEmpty() || emails.stream().anyMatch(email -> nonnegative(field(email, "NumberAttachments"))
        && new BigInteger(field(email, "NumberAttachments").strip()).signum() > 0)) return true;
    for (Source source : sourceBindings.values()) try {
      if (!originalAttachments(source).isEmpty()) return true;
    } catch (Exception unavailable) { return null; }
    if (!emails.isEmpty() && sourceBindings.size() < emails.size() && !sourceSpecs.isEmpty()) return null;
    return false;
  }

  void bodies(org.apache.pdfbox.pdmodel.PDDocument document) throws Exception {
    bodies(document, ContentOwnership.Evidence.empty());
  }

  void bodies(org.apache.pdfbox.pdmodel.PDDocument document, ContentOwnership.Evidence ownership) throws Exception {
    if (AttachmentChecks.knownNonbody(document)) {
      report.na("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", "Every declared content set has a known non-body type and valid page range.", null); return;
    }
    if (inspectableSourceCount == 0) {
      if (sourceSpecs.isEmpty() && profile.endsWith("i")) {
        report.na("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", "This isolated file omits the original email; the body-source comparison was not performed.",
            Map.of("assertion", "T8-SUBTYPE", "check", "body_source_media_type")); return;
      }
      report.unavailable("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", "No inspectable original MIME body is available for the body-source comparison.",
          Map.of("assertion", "T8-SUBTYPE", "check", "body_source_media_type")); return;
    }
    var bodies = new AttachmentChecks.BodyIndex(AttachmentChecks.bodySets(document));
    boolean unresolved = false, failed = false; int checked = 0;
    for (Element email : emails) {
      List<String> types = bodyTypes.get(field(email, "Mail_GUID"));
      var relevant = bodies.matching(AttachmentChecks.identity(email));
      if (types == null || types.isEmpty() || relevant.isEmpty()) { unresolved = true; continue; }
      for (var body : relevant) {
        Set<String> independent = ownership.owners().getOrDefault(body.part(), Set.of());
        if (independent.size() != 1 || !independent.contains(field(email, "Mail_GUID"))) { unresolved = true; continue; }
        COSBase raw = get(dict(body.part(), "DPM"), "Mail_Subtype");
        String subtype = raw instanceof org.apache.pdfbox.cos.COSName named ? DocumentPartChecks.utf8Name(named) : null;
        if (subtype == null || subtype.isEmpty()) { unresolved = true; continue; }
        checked++;
        if (types.stream().noneMatch(type -> sameMedia(subtype, type))) {
          failed = true;
          report.check("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", false, "BodyRendering Mail_Subtype must match a MIME body type from its uniquely owned source.",
              Map.of("declared", subtype, "source_types", types));
        }
      }
    }
    if (failed) return;
    if (unresolved) report.unavailable("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", "Some body content sets lack an unambiguous original body media comparison.");
    else report.check("DPM_BODY_MEDIA_TYPE_MATCH_SOURCE", true, "BodyRendering media types agree with the original MIME body representations.", Map.of("content_sets", checked));
  }

  void attachmentRenderings(org.apache.pdfbox.pdmodel.PDDocument document, ContentOwnership.Evidence ownership) throws Exception {
    var bySpec = new java.util.IdentityHashMap<COSDictionary, List<EaPdfChecks.Embedded>>();
    for (var file : files) bySpec.computeIfAbsent(file.spec(), ignored -> new ArrayList<>()).add(file);
    var pageIndex = new AttachmentChecks.PageIndex(document);
    var contentSets = AttachmentChecks.leaves(document);
    int inspected = 0, work = 0; boolean unknownContentSets = contentSets.isEmpty();
    for (COSDictionary part : contentSets) {
      COSBase contentType = get(dict(part, "DPM"), "Mail_ContentSetType");
      if (!(contentType instanceof org.apache.pdfbox.cos.COSName named) || DocumentPartChecks.utf8Name(named) == null || name(contentType).isEmpty()
          || pageIndex.range(part).isEmpty()) unknownContentSets = true;
      if (!"AttachmentRendering".equals(name(contentType))) continue;
      inspected++;
      COSBase raw = get(dict(part, "DPM"), "Mail_Subtype");
      String subtype = raw instanceof org.apache.pdfbox.cos.COSName named ? DocumentPartChecks.utf8Name(named) : null;
      var details = new java.util.LinkedHashMap<String, Object>();
      details.put("assertion", "T8-SUBTYPE"); details.put("check", "attachment_source_media_type"); details.put("declared", observed(raw));
      if (sourceSpecs.isEmpty() && profile.endsWith("i")) {
        report.na("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", "This isolated file omits the original email; the attachment-source comparison was not performed.", details); continue;
      }
      Set<String> ownerIds = ownership.owners().getOrDefault(part, Set.of());
      List<Element> owners = ownerIds.size() == 1 ? emailsByGuid.getOrDefault(ownerIds.iterator().next(), List.of()) : List.of();
      Element owner = owners.size() == 1 ? owners.getFirst() : null;
      if (owner == null || !sourceBindings.containsKey(owner) || subtype == null || subtype.isEmpty()) {
        report.unavailable("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", "Attachment-rendering media comparison requires an independently owned source and readable subtype.", details); continue;
      }
      Set<COSDictionary> associated = identitySet();
      List<COSDictionary> contexts = new ArrayList<>(); contexts.add(part); contexts.add(dict(part, "DPM"));
      for (var page : pageIndex.range(part)) {
        contexts.add(page.getCOSObject());
      }
      for (COSDictionary context : contexts) if (get(context, "AF") instanceof org.apache.pdfbox.cos.COSArray array)
        for (COSBase value : array) {
          if (resolve(value) instanceof COSDictionary spec && "Source".equals(name(get(spec, "AFRelationship")))) associated.add(spec);
        }
      Set<String> types = new java.util.LinkedHashSet<>();
      for (COSDictionary spec : associated) for (var file : bySpec.getOrDefault(spec, List.of())) types.addAll(originalMediaTypes(file, owner));
      if (types.size() == 1 && !types.iterator().next().isEmpty()) {
        details.put("source_type", types.iterator().next());
        report.check("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", sameMedia(subtype, types.iterator().next()),
            "AttachmentRendering subtype was compared with source bytes explicitly associated with its pages or DPart.", details);
      } else {
        boolean contradiction = false;
        try {
          var originals = originalAttachments(sourceBindings.get(owner));
          contradiction = !originals.isEmpty() && originals.stream().allMatch(attachment -> !attachment.contentType().isEmpty()
              && !sameMedia(subtype, attachment.contentType()));
        } catch (Exception unavailable) { /* A partial source inventory cannot prove a universal contradiction. */ }
        if (contradiction) report.check("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", false,
            "AttachmentRendering subtype contradicts every attachment in its independently established original email.", details);
        else report.unavailable("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", "AttachmentRendering has no unique original-payload association; a plausible media type is not proof of identity.", details);
      }
    }
    if (unknownContentSets && !(sourceSpecs.isEmpty() && profile.endsWith("i")))
      report.unavailable("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", "Unreadable content-set type or page-range evidence prevents a complete AttachmentRendering applicability check.",
          Map.of("assertion", "T8-SUBTYPE", "check", "attachment_source_media_type"));
    else if (inspected == 0) report.na("DPM_ATTACHMENT_MEDIA_TYPE_MATCH_SOURCE", sourceSpecs.isEmpty() && profile.endsWith("i")
          ? "This isolated file omits the original email; the attachment-source comparison was not performed."
          : "No AttachmentRendering content set activates the attachment-source comparison.",
          Map.of("assertion", "T8-SUBTYPE", "check", "attachment_source_media_type"));
  }

  private void collectBodies(Part part, List<String> types, int depth) throws Exception {
    String base = baseMedia(part.getContentType());
    if ("attachment".equalsIgnoreCase(part.getDisposition()) || Set.of("message/rfc822", "message/global").contains(base)
        || part.getFileName() != null && !Set.of("text/plain", "text/html").contains(base)) return;
    if ("multipart/encrypted".equals(base)) { types.add(part.getContentType()); return; }
    if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
      String start = "multipart/related".equals(base) ? new jakarta.mail.internet.ContentType(part.getContentType()).getParameter("start") : null;
      for (int index = 0; index < multipart.getCount(); index++) {
        Part child = multipart.getBodyPart(index);
        if ("multipart/related".equals(base)) {
          String[] ids = child.getHeader("Content-ID");
          if (start == null || start.isBlank()) { if (index != 0) continue; }
          else if (ids == null || ids.length == 0 || !stripId(start).equals(stripId(normal(ids[0])))) continue;
        }
        collectBodies(child, types, depth + 1);
      }
    } else types.add(part.getContentType());
  }

  private record Source(EaPdfChecks.Embedded file, MimeMessage message, OutlookSource.Evidence outlook, OutlookSource.Core nativeCore) {}
  private static String stripId(String value) { return value.strip().replaceAll("^<|>$", ""); }
  private static String header(MimeMessage message, String field) { try { String value = message.getHeader(field, " "); return value == null ? "" : value; } catch (Exception error) { return ""; } }

  private boolean incomplete(Source source) {
    return incompleteSources.computeIfAbsent(source, selected -> {
      byte[] bytes = selected.file().bytes();
      if (bytes.length >= 5 && new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("From ")) return true;
      try {
        for (NativeAttachment attachment : originalAttachments(selected)) {
          if (Set.of("message/rfc822", "message/global", "application/mbox", "application/vnd.ms-outlook").contains(baseMedia(attachment.contentType()))
              || attachment.names().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).matches(".*\\.(?:eml|msg|mbox|mbx|pst|ost|nsf)$"))) return true;
        }
        return false;
      } catch (Exception unavailable) { return true; }
    });
  }

  private static boolean recoveredHeaders(Source source) {
    String raw = new String(source.outlook() == null ? source.file().bytes() : source.outlook().headers(), java.nio.charset.StandardCharsets.ISO_8859_1);
    boolean first = true, haveHeader = false;
    for (String line : raw.split("\\r\\n|\\n|\\r", -1)) {
      if (line.isEmpty()) break;
      if (first && line.startsWith("From ")) { first = false; continue; }
      first = false;
      if (line.startsWith(" ") || line.startsWith("\t")) { if (!haveHeader) return true; continue; }
      if (!line.matches("[!-9;-~]+:.*")) return true;
      haveHeader = true;
    }
    return false;
  }

  private static String sourceField(String code) {
    return switch (code) {
      case "MESSAGE_ID_XMP" -> "messageid";
      case "MESSAGE_SIZE_XMP" -> "SizeInBytes";
      case "MESSAGE_ATTACHMENT_COUNT_XMP" -> "NumberAttachments";
      case "MESSAGE_SENT_XMP" -> "Sent";
      case "MESSAGE_SUBJECT_XMP" -> "Subject";
      case "MESSAGE_INREPLYTO_XMP" -> "In-Reply-To";
      case "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID" -> "Raw-Sent";
      default -> {
        String middle = code.replaceFirst("^MESSAGE_", "").replaceFirst("_(XMP|FOAF)$", "");
        yield switch (middle) {
          case "TO" -> "To"; case "FROM" -> "From"; case "CC" -> "Cc"; case "BCC" -> "Bcc";
          case "RAW_TO" -> "Raw-To"; case "RAW_FROM" -> "Raw-From"; case "RAW_CC" -> "Raw-Cc"; case "RAW_BCC" -> "Raw-Bcc";
          default -> middle;
        };
      }
    };
  }

  private Map<String, Object> sourceDetails(String code, Object supplied) {
    Map<String, Object> details = new java.util.LinkedHashMap<>();
    details.put("field", sourceField(code));
    if (comparedEmail != null) details.put("Mail_GUID", field(comparedEmail, "Mail_GUID"));
    if (supplied instanceof Map<?, ?> map) for (var entry : map.entrySet()) details.put(entry.getKey().toString(), entry.getValue());
    return details;
  }

  private static boolean addressPresence(String code) {
    return Set.of("MESSAGE_TO_XMP", "MESSAGE_FROM_XMP", "MESSAGE_CC_XMP", "MESSAGE_BCC_XMP").contains(code);
  }

  private void sourceCheck(String code, boolean condition, String message, Object details) {
    if (candidateProbe || !code.startsWith("MESSAGE_")) { report.check(code, condition, message, details); return; }
    // Presence is assessed independently against the original; retain legacy per-field probe outcomes only for binding.
    if (addressPresence(code)) return;
    var evidence = sourceDetails(code, details);
    if (comparedEmail != null && fieldElement(comparedEmail, sourceField(code)) == null)
      report.na("MESSAGE_METADATA_MATCH_SOURCE", "This source-derived field is omitted; its required presence is assessed separately.", evidence);
    else report.check("MESSAGE_METADATA_MATCH_SOURCE", condition, message, evidence);
  }

  private void sourceUnavailable(String code, String message) {
    if (candidateProbe || !code.startsWith("MESSAGE_")) { report.unavailable(code, message); return; }
    if (addressPresence(code)) return;
    report.unavailable("MESSAGE_METADATA_MATCH_SOURCE", message, sourceDetails(code, null));
  }

  private void requiredCoreField(Element email, String property, boolean required, boolean rawAlternative) {
    if (candidateProbe) return;
    var details = Map.of("Mail_GUID", field(email, "Mail_GUID"), "field", property);
    if (!required) report.na("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", "The original supplies no applicable nonempty value for this Core Field.", details);
    else report.check("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", (Set.of("To", "From", "Cc", "Bcc").contains(property)
        ? MailboxChecks.read(email, property).declaredCount() > 0 : fieldElement(email, property) != null)
        || rawAlternative && fieldElement(email, "Raw-" + property) != null && !field(email, "Raw-" + property).isBlank(),
        "A nonempty applicable original Core Field requires its email XMP property.", details);
  }

  private void corePresence(Element email, Source source) {
    if (candidateProbe) return;
    MimeMessage message = source.message();
    for (String property : List.of("To", "From", "Cc", "Bcc", "Subject", "Message-ID", "In-Reply-To", "Date")) {
      String target = "Message-ID".equals(property) ? "messageid" : "Date".equals(property) ? "Sent" : property;
      boolean rawOnly = false;
      if (Set.of("To", "From", "Cc", "Bcc").contains(property)) try {
        var addresses = sourceAddresses(SourceHeaders.originalText(header(message, property)));
        // Comment-only and empty-group headers contain no structured mailbox.
        rawOnly = true;
        for (var address : addresses) {
          var members = address.isGroup() ? address.getGroup(true) : new InternetAddress[]{address};
          for (var member : members) if (MailboxChecks.valid(member.getAddress())) rawOnly = false;
        }
      } catch (Exception unavailable) { /* Unreadable source tokens cannot justify replacing known mailboxes. */ }
      requiredCoreField(email, target, !header(message, property).isBlank(), rawOnly);
    }
    try { requiredCoreField(email, "NumberAttachments", !originalAttachments(source).isEmpty(), false); }
    catch (Exception unavailable) { report.unavailable("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", "The original attachment inventory cannot establish the required count field.", Map.of("field", "NumberAttachments")); }
  }

  private void nativeCorePresence(Element email, OutlookSource.Core core) {
    for (var entry : core.text().entrySet()) requiredCoreField(email, entry.getKey(), !entry.getValue().isBlank(), false);
    for (String property : List.of("To", "From", "Cc", "Bcc")) {
      var raw = core.rawAddresses().get(property);
      boolean known = core.addresses().containsKey(property) || raw != null;
      if (!known) report.unavailable("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", "The stored Outlook address field cannot establish conditional presence.", Map.of("field", property));
      else requiredCoreField(email, property, !core.addresses().getOrDefault(property, List.of()).isEmpty()
          || raw != null && !raw.display().isBlank(), core.addresses().getOrDefault(property, List.of()).isEmpty());
    }
    requiredCoreField(email, "In-Reply-To", !core.reply().isBlank(), false);
    requiredCoreField(email, "Sent", core.sent() != null, false);
    if (core.attachments() != null) requiredCoreField(email, "NumberAttachments", !core.attachments().isEmpty(), false);
  }

  private Set<String> candidateFailures(Element email, Source source) {
    JavaValidation.Report candidate = new JavaValidation.Report(report.path);
    // Reuse the archive indexes and MIME inventory in this dedicated comparison
    // probe. Rebuilding them once per email would restore quadratic work.
    if (comparisonProbe == null) comparisonProbe = new MetadataChecks(candidate, files, sourceSpecs, emails, assets, attachments, profile);
    comparisonProbe.report = candidate; comparisonProbe.candidateProbe = true;
    try { comparisonProbe.compare(email, source); }
    catch (Exception unavailable) { /* A MIME inventory failure does not erase established header findings. */ }
    finally { comparisonProbe.report = null; }
    if (comparisonProbe.attachmentInventories.containsKey(source))
      attachmentInventories.put(source, comparisonProbe.attachmentInventories.get(source));
    Set<String> failures = new HashSet<>(), unknown = new HashSet<>();
    for (var value : candidate.items) {
      var item = value.getAsJsonObject(); String code = item.get("code").getAsString(), level = item.get("level").getAsString();
      if ("WARN".equals(level)) unknown.add(code);
      if ("FAIL".equals(level) && code.startsWith("MESSAGE_") && !Set.of("MESSAGE_ATTACHMENT_COUNT_XMP", "MESSAGE_SOURCE_COVERAGE").contains(code)) failures.add(code);
    }
    failures.removeAll(unknown); return failures;
  }

  private void universalComparisons(Element email, List<Source> sources) {
    // Every email is assessed against the same immutable document source list.
    // Do not decode every original again for each ambiguous message.
    if (universalHeadersReadable == null)
      universalHeadersReadable = !sources.isEmpty() && sources.size() == sourceSpecs.size()
          && sources.stream().noneMatch(MetadataChecks::recoveredHeaders);
    if (!universalHeadersReadable) return;
    if (universalSourcesIncomplete == null) universalSourcesIncomplete = sources.stream().anyMatch(this::incomplete);
    if (universalSourcesIncomplete) {
      report.unavailable("MESSAGE_SOURCE_COVERAGE", "Nested email or mailbox candidates prevent a universal outer-header contradiction."); return;
    }
    Set<String> common = null;
    boolean allContradict = true;
    for (Source source : sources) {
      Set<String> failures = candidateFailures(email, source);
      allContradict &= !failures.isEmpty();
      if (common == null) common = new HashSet<>(failures); else common.retainAll(failures);
    }
    if (common == null) return;
    if (allContradict && common.isEmpty()) report.check("MESSAGE_SOURCE_COVERAGE", false, "No inspectable original satisfies the supplied email Core Fields.", null);
    comparedEmail = email;
    for (String code : common) {
      if ("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID".equals(code))
        report.check(code, false, "Every inspectable original requires missing Raw-Sent metadata.", sourceDetails(code, null));
      else if (fieldElement(email, sourceField(code)) == null)
        report.check("MESSAGE_CORE_FIELDS_SOURCE_REQUIRED", false, "Every inspectable original requires this omitted Core Field.", sourceDetails(code, null));
      else if (!addressPresence(code)) sourceCheck(code, false, "Every inspectable original contradicts this supplied email field.", null);
    }
  }

  private void compare(Element email, Source source) throws Exception {
    comparedEmail = email;
    if (source.nativeCore() != null) {
      compareNativeCore(email, new NativeSource(source.file(), source.nativeCore()));
      return;
    }
    corePresence(email, source);
    MimeMessage message = source.message();
    String guid = field(email, "Mail_GUID");
    for (String property : List.of("To", "From", "Cc", "Bcc", "Date")) {
      String metadata = "Date".equals(property) ? "Raw-Sent" : "Raw-" + property;
      if (!simple(fieldElement(email, metadata))) continue;
      String code = "Date".equals(property) ? "MESSAGE_RAW_SENT_REQUIRED_IF_INVALID" : "MESSAGE_RAW_" + property.toUpperCase(Locale.ROOT) + "_XMP";
      compareRaw(email, message, property, metadata, code);
    }
    compareSubject(email, message);
    try {
      String originalId = SourceHeaders.originalText(header(message, "Message-ID"));
      sourceCheck("MESSAGE_ID_XMP", normal(decodedOrLiteral(field(email, "messageid"))).equals(normal(decodedOrLiteral(originalId))),
          "Message-ID must preserve the source identifier, including case and punctuation.", Map.of("Mail_GUID", guid));
    } catch (IllegalArgumentException unavailable) { sourceUnavailable("MESSAGE_ID_XMP", "Original Message-ID octets cannot be decoded."); }
    List<String> replies = new ArrayList<>();
    for (Element container : children(valueElement(fieldElement(email, "In-Reply-To"))))
      if (is(container, RDF, "Bag")) for (Element item : children(container)) replies.add(decodedOrLiteral(item.getTextContent()));
    try {
      if (emails.size() <= 1 || !replies.isEmpty() || !header(message, "In-Reply-To").isEmpty()) sourceCheck("MESSAGE_INREPLYTO_XMP", replyIds(String.join(" ", replies)).equals(replyIds(decodedOrLiteral(SourceHeaders.originalText(header(message, "In-Reply-To"))))),
          "Reply identifiers must preserve the original header.", Map.of("Mail_GUID", guid));
    } catch (IllegalArgumentException unavailable) { sourceUnavailable("MESSAGE_INREPLYTO_XMP", "Original reply-header octets cannot be decoded."); }
    if (!field(email, "SizeInBytes").isEmpty()) sourceCheck("MESSAGE_SIZE_XMP", integerEquals(field(email, "SizeInBytes"), source.file().size()),
        "Supplied SizeInBytes must match preserved source bytes.", Map.of("Mail_GUID", guid, "actual", source.file().size()));
    if (fieldElement(email, "SizeInBytes") == null && emails.size() <= 1)
      sourceCheck("MESSAGE_SIZE_XMP", true, "An inspected source does not require an otherwise omitted SizeInBytes value.", null);
    for (String property : List.of("To", "From", "Cc", "Bcc")) compareAddresses(email, message, property);
    if (message.getHeader("Date") != null) {
      String original = header(message, "Date");
      java.time.Instant instant = null;
      try { instant = SourceHeaders.instant(original); }
      catch (IllegalArgumentException | java.time.DateTimeException invalid) { /* Assess known invalid dates separately from parser limits. */ }
      if (instant != null) {
        report.recommend("MESSAGE_RAW_SENT_OMIT_IF_VALID", fieldElement(email, "Raw-Sent") == null,
            "Raw-Sent should be omitted when the original date is valid.", Map.of("Mail_GUID", guid));
        if (simple(fieldElement(email, "Sent")) && dateTime(field(email, "Sent"))) {
          try {
            sourceCheck("MESSAGE_SENT_XMP", java.time.OffsetDateTime.parse(field(email, "Sent")).toInstant().equals(instant),
                "Sent must preserve the original date as an instant.", Map.of("Mail_GUID", guid));
          } catch (java.time.DateTimeException unavailable) {
            sourceUnavailable("MESSAGE_SENT_XMP", "This valid Sent dateTime has no supported known instant for comparison.");
          }
        }
      } else if (SourceHeaders.dateBeyondRange(original)) {
        report.unavailable("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID", "Original date normalization exceeds the supported calendar range.");
        if (dateTime(field(email, "Sent"))) sourceUnavailable("MESSAGE_SENT_XMP", "The original date instant is outside the supported comparison range.");
      } else {
        report.check("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID", fieldElement(email, "Raw-Sent") != null,
            "An invalid or blank original Date header requires Raw-Sent metadata.", Map.of("Mail_GUID", guid));
      }
    }
    report.supported("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID", "MESSAGE_RAW_SENT_OMIT_IF_VALID");
    if (source.outlook() != null && source.outlook().attachments() == null) {
      unavailableInventory(source.outlook().attachmentError());
      return;
    }
    List<NativeAttachment> originalAttachments = originalAttachments(source);
    sourceCheck("MESSAGE_ATTACHMENT_COUNT_XMP", originalAttachments.isEmpty() && fieldElement(email, "NumberAttachments") == null
        || integerEquals(field(email, "NumberAttachments"), originalAttachments.size()), "NumberAttachments must match the original MIME attachment inventory.",
        Map.of("Mail_GUID", guid, "actual", originalAttachments.size()));
    compareAttachments(email, originalAttachments, header(message, "Message-ID"), source.outlook() != null);
  }

  private void compareAttachments(Element email, List<NativeAttachment> originalAttachments, String originalId, boolean outlook) {
    String guid = field(email, "Mail_GUID");
    List<Element> owned = attachmentsByEmail.getOrDefault(email, List.of());
    Map<Integer, Integer> assignments = attachmentAssignments(owned, originalAttachments);
    Set<Integer> reserved = new HashSet<>(assignments.values());
    for (int index = 0; index < owned.size(); index++) {
      Element attachment = owned.get(index);
      if (fieldElement(attachment, "Message-ID") != null) report.check("ATTACHMENT_XMP_MESSAGE_ID_MATCH",
          stripId(field(attachment, "Message-ID")).equals(stripId(originalId)),
          "Attachment Message-ID must agree with its containing original email.", Map.of("file", field(attachment, "Filename")));
      Integer chosen = assignments.get(index);
      if (chosen == null) {
        int best = -1;
        for (int candidate = 0; candidate < originalAttachments.size(); candidate++) {
          if (reserved.contains(candidate)) continue;
          NativeAttachment possible = originalAttachments.get(candidate);
          boolean named = possible.names().contains(field(attachment, "Filename"));
          boolean unnamed = possible.names().isEmpty() && (!possible.nestedContent().isEmpty() || integerEquals(field(attachment, "SizeInBytes"), possible.size())
              && normal(field(attachment, "Content-Type")).equals(normal(possible.contentType())));
          boolean exactBytes = !attachmentChecksum(attachment).isEmpty() && possible.md5().equalsIgnoreCase(attachmentChecksum(attachment))
              && integerEquals(field(attachment, "SizeInBytes"), possible.size());
          if (!named && !unnamed && !exactBytes) continue;
          int score = (nestedCandidateMatches(attachment, possible) ? 1000 : 0) + (possible.md5().equalsIgnoreCase(attachmentChecksum(attachment)) ? 100 : 0)
              + (integerEquals(field(attachment, "SizeInBytes"), possible.size()) ? 10 : 0)
              + (baseMedia(field(attachment, "Content-Type")).equals(baseMedia(possible.contentType())) ? 1 : 0);
          if (score > best) { best = score; chosen = candidate; }
        }
      }
      if (chosen == null) {
        if (!originalAttachments.isEmpty() && originalAttachments.stream().allMatch(original -> !original.names().isEmpty()
            && !original.names().contains(field(attachment, "Filename"))))
          report.check("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", false, "The supplied filename contradicts every original attachment filename.", Map.of("file", field(attachment, "Filename")));
        else report.unavailable("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", "The original attachment occurrence cannot be independently selected.");
        report.unavailable("ATTACHMENT_XMP_CONTENTTYPE_MATCH", "The original attachment occurrence cannot be independently selected.");
        continue;
      }
      reserved.add(chosen);
      NativeAttachment match = originalAttachments.get(chosen);
      originalAttachmentBindings.put(attachment, match);
      if (match.names().isEmpty()) report.na("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", "The original supplies no filename; a generated filename is permitted.", Map.of("file", field(attachment, "Filename")));
      else report.check("ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", match.names().contains(field(attachment, "Filename")),
          "Attachment Filename must preserve the original case-sensitive filename.", Map.of("file", field(attachment, "Filename"), "original_names", match.names()));
      if (outlook && match.contentType().isEmpty()) report.na("ATTACHMENT_XMP_CONTENTTYPE_MATCH",
          "The original MSG attachment has no stored MIME type to compare.", Map.of("file", field(attachment, "Filename")));
      else report.check("ATTACHMENT_XMP_CONTENTTYPE_MATCH", sameMedia(field(attachment, "Content-Type"), match.contentType()),
          "Attachment Content-Type must match its original MIME Content-Type.", Map.of("file", field(attachment, "Filename"), "expected", match.contentType()));
    }
    if (originalAttachments.isEmpty()) report.na("ATTACHMENT_XMP_INVENTORY_RECOMMENDED", "The original email has no attachments to describe.", Map.of("Mail_GUID", guid));
    else report.recommend("ATTACHMENT_XMP_INVENTORY_RECOMMENDED", reserved.size() == originalAttachments.size(),
        "Attachment metadata should describe every original attachment occurrence.", Map.of("Mail_GUID", guid, "original_attachments", originalAttachments.size(), "described_occurrences", reserved.size()));
  }

  private boolean nestedCandidateMatches(Element attachment, NativeAttachment original) {
    if (original.nestedContent().isEmpty()) return false;
    for (var file : files) if (!"Source".equals(file.relationship()) && AttachmentChecks.nativeName(file).equals(field(attachment, "Filename"))) try {
      if (original.nestedContent().equals(OutlookSource.standaloneContent(file.bytes()))) return true;
    } catch (Exception unreadable) { /* The selected comparison will report unavailable evidence. */ }
    return false;
  }

  private void originalNativeFidelity(Element attachment, List<EaPdfChecks.Embedded> nativeFiles) {
    String code = "ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED";
    var details = Map.of("file", field(attachment, "Filename"));
    if (sourceSpecs.isEmpty() && profile.endsWith("i")) { report.na(code, "The isolated original is omitted; original bytes were not compared.", details); return; }
    if (nativeFiles.isEmpty()) { report.na(code, "No independently associated nonzero retained native payload activates this recommendation.", details); return; }
    if (nativeFiles.stream().allMatch(file -> file.size() == 0)) { report.na(code, "Zero-byte placeholders do not activate nonzero native byte preservation.", details); return; }
    NativeAttachment original = originalAttachmentBindings.get(attachment);
    if (original == null) { report.unavailable(code, "No independently associated original attachment bytes are available.", details); return; }
    for (var file : nativeFiles) {
      if (file.size() == 0) continue;
      if (original.nestedContent().isEmpty()) report.recommend(code, file.size() == original.size() && file.md5().equalsIgnoreCase(original.md5()),
          "Retained native bytes should preserve the full decoded original attachment bytes.", Map.of("file", file.name(), "stored_size", file.size(), "original_size", original.size(), "stored_md5", file.md5(), "original_md5", original.md5()));
      else try {
        report.recommend(code, original.nestedContent().equals(OutlookSource.standaloneContent(file.bytes())),
            "Extracted Outlook message properties and nested attachment streams should preserve the original storage.", details);
      } catch (Exception unreadable) { report.unavailable(code, "The extracted Outlook attachment cannot be inspected.", details); }
    }
  }

  private void unavailableInventory(String reason) {
    for (String code : List.of("MESSAGE_METADATA_MATCH_SOURCE", "ATTACHMENT_NATIVE_BYTES_SOURCE_FIDELITY_RECOMMENDED", "ATTACHMENT_XMP_FILENAME_MATCH_SOURCE", "ATTACHMENT_XMP_CONTENTTYPE_MATCH", "ATTACHMENT_XMP_INVENTORY_RECOMMENDED"))
      report.unavailable(code, "Original Outlook attachment comparison is unavailable: " + reason);
  }

  private record NativeSource(EaPdfChecks.Embedded original, OutlookSource.Core core) {}

  private NativeSource nativeSource() {
    if (!Set.of("s", "si").contains(profile) || emails.size() != 1 || sourceSpecs.size() != 1) return null;
    EaPdfChecks.Embedded original = files.stream().filter(file -> file.spec() == sourceSpecs.getFirst()).findFirst().orElse(null);
    if (original == null || !("application/vnd.ms-outlook".equalsIgnoreCase(original.mediaType()) || original.name().toLowerCase(Locale.ROOT).endsWith(".msg"))) return null;
    var nativeCore = OutlookSource.nativeCore(original.bytes());
    return nativeCore == null ? null : new NativeSource(original, nativeCore);
  }

  private void compareNativeCore(Element email, NativeSource source) {
    comparedEmail = email;
    if (source == null) return;
    var nativeCore = source.core(); var original = source.original();
    if (!candidateProbe) nativeCorePresence(email, nativeCore);
    for (var entry : nativeCore.text().entrySet()) {
      String code = "Subject".equals(entry.getKey()) ? "MESSAGE_SUBJECT_XMP" : "MESSAGE_ID_XMP";
      sourceCheck(code, (entry.getValue().isEmpty() || simple(fieldElement(email, entry.getKey()))) && normal(field(email, entry.getKey())).equals(normal(entry.getValue())),
          "Email metadata must agree with the stored Outlook property.", Map.of("field", entry.getKey(), "source", original.name(), "expected", entry.getValue()));
    }
    if (fieldElement(email, "SizeInBytes") != null) sourceCheck("MESSAGE_SIZE_XMP", integerEquals(field(email, "SizeInBytes"), nativeCore.size()),
        "Email SizeInBytes must match the original MSG byte count.", Map.of("source", original.name(), "expected", nativeCore.size()));
    if (nativeCore.attachments() != null && (fieldElement(email, "NumberAttachments") != null || !nativeCore.attachments().isEmpty())) sourceCheck("MESSAGE_ATTACHMENT_COUNT_XMP", integerEquals(field(email, "NumberAttachments"), nativeCore.attachments().size()),
        "NumberAttachments must match the stored Outlook by-value inventory.", Map.of("source", original.name(), "expected", nativeCore.attachments().size()));
    for (var entry : nativeCore.addresses().entrySet()) {
      var actual = MailboxChecks.read(email, entry.getKey());
      String prefix = "MESSAGE_" + entry.getKey().toUpperCase(Locale.ROOT);
      var raw = nativeCore.rawAddresses().get(entry.getKey());
      boolean displayOnly = entry.getValue().isEmpty() && raw != null && !raw.display().isEmpty();
      sourceCheck(prefix + "_XMP", displayOnly ? simple(fieldElement(email, "Raw-" + entry.getKey()))
          && normal(field(email, "Raw-" + entry.getKey())).equals(normal(raw.display())) : entry.getValue().isEmpty() || actual.present() && actual.declaredCount() > 0,
          "Stored Outlook mailboxes require " + entry.getKey() + " metadata.", Map.of("source", original.name()));
      var expectedCounts = entry.getValue().stream().map(MailboxChecks::key).collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
      var actualCounts = actual.mailboxes().stream().map(MailboxChecks::key).collect(java.util.stream.Collectors.groupingBy(java.util.function.Function.identity(), java.util.stream.Collectors.counting()));
      sourceCheck(prefix + "_FOAF", actual.valid() && actualCounts.equals(expectedCounts),
          entry.getKey() + " mailbox occurrences must match the stored Outlook recipient table; original header order remains unavailable.",
          Map.of("source", original.name(), "expected", entry.getValue(), "xmp", actual.mailboxes()));
    }
    if (nativeCore.attachments() == null) unavailableInventory(nativeCore.attachmentError());
    else {
    List<NativeAttachment> inventory = nativeCore.attachments().stream().map(attachment -> new NativeAttachment(
        attachment.names(), attachment.contentType(), attachment.size(), attachment.md5(), attachment.nestedContent())).toList();
    compareAttachments(email, inventory, nativeCore.text().getOrDefault("messageid", ""), true);
    }
    for (String property : List.of("To", "From", "Cc", "Bcc")) {
      if (!nativeCore.addresses().containsKey(property)) {
        sourceUnavailable("MESSAGE_" + property.toUpperCase(Locale.ROOT) + "_XMP", "Stored Outlook " + property + " addresses cannot be resolved to SMTP mailboxes.");
        sourceUnavailable("MESSAGE_" + property.toUpperCase(Locale.ROOT) + "_FOAF", "Stored Outlook " + property + " mailbox comparison is unavailable.");
      }
      if (fieldElement(email, "Raw-" + property) != null) compareNativeRaw(email, nativeCore, property);
    }
    if (fieldElement(email, "Raw-Sent") != null) report.unavailable("MESSAGE_RAW_SENT_REQUIRED_IF_INVALID", "The MSG contains no original Date header for comparing Raw-Sent.");
    report.na("MESSAGE_RAW_SENT_OMIT_IF_VALID", "No transport Date header is stored in this MSG.", null);
    List<String> replies = new ArrayList<>();
    for (Element container : children(valueElement(fieldElement(email, "In-Reply-To"))))
      if (is(container, RDF, "Bag")) for (Element item : children(container)) replies.add(item.getTextContent());
    sourceCheck("MESSAGE_INREPLYTO_XMP", replyIds(String.join(" ", replies)).equals(replyIds(nativeCore.reply())),
        "Reply identifiers must match the stored Outlook In-Reply-To property.", null);
    if (nativeCore.sent() != null) {
      if (field(email, "Sent").isEmpty()) sourceCheck("MESSAGE_SENT_XMP", false, "Stored Outlook submission time requires Sent metadata.", null);
      else if (simple(fieldElement(email, "Sent")) && dateTime(field(email, "Sent"))) try {
        sourceCheck("MESSAGE_SENT_XMP", java.time.OffsetDateTime.parse(field(email, "Sent")).toInstant().equals(nativeCore.sent()),
            "Sent must preserve the stored Outlook submission time.", Map.of("source", original.name(), "expected", nativeCore.sent().toString()));
      } catch (java.time.DateTimeException unavailable) { sourceUnavailable("MESSAGE_SENT_XMP", "The valid Sent value cannot be compared as a known instant."); }
    }
  }

  private void compareNativeRaw(Element email, OutlookSource.Core core, String property) {
    String code = "MESSAGE_RAW_" + property.toUpperCase(Locale.ROOT) + "_XMP";
    String actual = field(email, "Raw-" + property);
    var raw = core.rawAddresses().get(property);
    if (raw != null && (!raw.display().isEmpty() || java.util.List.of().equals(core.addresses().get(property)))
        && simple(fieldElement(email, "Raw-" + property)) && normal(actual).equals(normal(raw.display()))) {
      sourceCheck(code, true, "Raw-" + property + " preserves the stored Outlook display field.", null); return;
    }
    List<String> addresses = core.addresses().get(property);
    if (raw == null || addresses == null || addresses.size() != raw.names().size()) {
      sourceUnavailable(code, "Stored Outlook " + property + " names and mailboxes are not fully readable."); return;
    }
    try {
      List<String> expected = new ArrayList<>(), supplied = new ArrayList<>();
      for (int i = 0; i < addresses.size(); i++) expected.add(MailboxChecks.key(addresses.get(i)) + "\n" + normal(raw.names().get(i)));
      for (InternetAddress address : InternetAddress.parseHeader(actual, true)) {
        if (address.isGroup() || !MailboxChecks.valid(address.getAddress())) throw new IllegalArgumentException("Unrecognized native address text");
        supplied.add(MailboxChecks.key(address.getAddress()) + "\n" + normal(address.getPersonal() == null ? "" : address.getPersonal()));
      }
      java.util.Collections.sort(expected); java.util.Collections.sort(supplied);
      sourceCheck(code, expected.equals(supplied), "Raw-" + property + " names and mailboxes must preserve the stored Outlook fields.", null);
    } catch (Exception error) { sourceUnavailable(code, "Raw-" + property + " cannot be compared with the stored Outlook address fields."); }
  }

  private void compareSubject(Element email, MimeMessage message) {
    String raw = header(message, "Subject");
    if (raw.isEmpty()) {
      sourceCheck("MESSAGE_SUBJECT_XMP", field(email, "Subject").isEmpty(), "Subject must also preserve an absent original Subject.", null); return;
    }
    try {
      String original = SourceHeaders.originalText(raw), actual = field(email, "Subject");
      if (normal(actual).equals(normal(original))) {
        sourceCheck("MESSAGE_SUBJECT_XMP", true, "Subject preserves the literal original header.", null); return;
      }
      if (actual.isEmpty()) {
        sourceCheck("MESSAGE_SUBJECT_XMP", false, "A nonempty original Subject requires its metadata field.", null); return;
      }
      original = SourceHeaders.decoded(original);
      try { actual = SourceHeaders.decoded(actual); }
      catch (IllegalArgumentException error) { /* Compare malformed supplied words literally with known source text. */ }
      sourceCheck("MESSAGE_SUBJECT_XMP", normal(actual).equals(normal(original)), "Subject must match the original header text.", null);
    } catch (IllegalArgumentException error) {
      sourceUnavailable("MESSAGE_SUBJECT_XMP", "Original Subject encoding cannot be decoded reliably.");
    }
  }

  private void compareRaw(Element email, MimeMessage message, String header, String property, String code) {
    try {
      String original = SourceHeaders.originalText(header(message, header)), actual = field(email, property);
      if (!"Date".equals(header)) {
        List<MailboxChecks.Key> left = rawMailboxKeys(actual), right = rawMailboxKeys(original);
        if (left != null && right != null && !left.equals(right)) {
          sourceCheck(code, false, "Raw address metadata changes mailbox data from the original header.", Map.of("field", property)); return;
        }
      }
      sourceCheck(code, normal(decodedOrLiteral(actual)).equals(normal(decodedOrLiteral(original))),
          "Supplied raw metadata must preserve its matched original header.", Map.of("field", property));
    } catch (IllegalArgumentException unavailable) { sourceUnavailable(code, "Original raw header octets cannot be decoded: " + header); }
  }

  private static List<MailboxChecks.Key> rawMailboxKeys(String value) {
    if (value.codePoints().anyMatch(point -> point > 127)) return null;
    try {
      List<MailboxChecks.Key> keys = new ArrayList<>();
      for (InternetAddress address : InternetAddress.parseHeader(value, true)) {
        InternetAddress[] members = address.isGroup() ? address.getGroup(true) : new InternetAddress[]{address};
        for (InternetAddress member : members) {
          String mailbox = member.getAddress().strip();
          if (!MailboxChecks.valid(mailbox)) return null;
          keys.add(MailboxChecks.key(mailbox));
        }
      }
      return keys;
    } catch (Exception unavailable) { return null; }
  }

  private static String decodedOrLiteral(String text) {
    try { return SourceHeaders.decoded(text); }
    catch (IllegalArgumentException unsupported) { return text; }
  }

  private static String attachmentChecksum(Element attachment) {
    return simple(fieldElement(attachment, "CheckSum")) ? field(attachment, "CheckSum") : "";
  }

  private static boolean attachmentPlaceholder(Element attachment) {
    String checksum = attachmentChecksum(attachment);
    return integerEquals(field(attachment, "SizeInBytes"), 0)
        && (checksum.isEmpty() || checksum.equalsIgnoreCase("d41d8cd98f00b204e9800998ecf8427e"));
  }

  /** Reserve occurrences once, moving less constrained records aside when necessary. */
  private static Map<Integer, Integer> attachmentAssignments(List<Element> records, List<NativeAttachment> originals) {
    Map<Integer, Integer> matches = new java.util.HashMap<>(), owners = new java.util.HashMap<>();
    for (int start = 0; start < records.size(); start++) {
      var pending = new java.util.ArrayDeque<Integer>(); pending.add(start);
      Set<Integer> visited = new HashSet<>(); visited.add(start);
      Map<Integer, Integer> predecessors = new java.util.HashMap<>();
      boolean assigned = false;
      while (!pending.isEmpty() && !assigned) {
        int record = pending.removeFirst(); Element metadata = records.get(record);
        for (int source = 0; source < originals.size(); source++) {
          NativeAttachment original = originals.get(source);
          String checksum = attachmentChecksum(metadata);
          if (predecessors.containsKey(source)
              || !original.names().isEmpty() && !original.names().contains(field(metadata, "Filename"))
              || !attachmentPlaceholder(metadata) && (!integerEquals(field(metadata, "SizeInBytes"), original.size())
                  || !checksum.isEmpty() && !checksum.equalsIgnoreCase(original.md5()))
              || !original.contentType().isEmpty() && !sameMedia(field(metadata, "Content-Type"), original.contentType())) continue;
          predecessors.put(source, record);
          Integer owner = owners.get(source);
          if (owner == null) {
            Integer current = source;
            while (current != null) {
              int next = predecessors.get(current);
              Integer previous = matches.put(next, current); owners.put(current, next); current = previous;
            }
            assigned = true; break;
          }
          if (visited.add(owner)) pending.add(owner);
        }
      }
    }
    return matches;
  }

  private static <T> Map<String, List<T>> index(List<T> values, java.util.function.Function<T, String> key) {
    Map<String, List<T>> result = new java.util.HashMap<>();
    for (T value : values) result.computeIfAbsent(key.apply(value), ignored -> new ArrayList<>()).add(value);
    return result;
  }

  private static String basename(String value) {
    value = value.replace('\\', '/');
    return value.substring(value.lastIndexOf('/') + 1);
  }

  private static final class SourceIndex {
    final java.util.LinkedHashSet<Source> active;
    final Map<String, List<Source>> byId, bySize;
    final Map<Source, Integer> order = new java.util.IdentityHashMap<>();
    SourceIndex(List<Source> sources) {
      active = new java.util.LinkedHashSet<>(sources);
      byId = index(sources, source -> normal(source.nativeCore() == null ? header(source.message(), "Message-ID")
          : source.nativeCore().text().getOrDefault("messageid", "")));
      bySize = index(sources, source -> Long.toString(source.file().size()));
      for (int i = 0; i < sources.size(); i++) order.put(sources.get(i), i);
    }
    void remove(Source source) { active.remove(source); }
    List<Source> candidates(Element email, boolean legacy) {
      if (legacy) return active.isEmpty() ? List.of() : List.of(active.getFirst());
      Set<Source> eligible = new HashSet<>();
      String id = normal(field(email, "messageid")), size = field(email, "SizeInBytes");
      if (!field(email, "messageid").isEmpty()) eligible.addAll(byId.getOrDefault(id, List.of()));
      if (nonnegative(size)) eligible.addAll(bySize.getOrDefault(new BigInteger(size.strip()).toString(), List.of()));
      int best = 39;
      List<Source> matches = new ArrayList<>();
      for (Source source : eligible) if (active.contains(source)) {
        int score = sourceScore(email, source);
        if (score > best) { best = score; matches.clear(); }
        if (score == best) matches.add(source);
      }
      matches.sort(java.util.Comparator.comparingInt(order::get));
      return matches;
    }
  }

  private static int sourceScore(Element email, Source source) {
    int score = 0;
    if (source.nativeCore() != null) {
      String id = field(email, "messageid");
      if (!id.isEmpty() && normal(id).equals(normal(source.nativeCore().text().getOrDefault("messageid", "")))) score += 100;
      if (integerEquals(field(email, "SizeInBytes"), source.file().size())) score += 50;
      return score;
    }
    String id = normal(field(email, "messageid"));
    if (!id.isEmpty() && id.equals(normal(header(source.message(), "Message-ID")))) score += 100;
    if (integerEquals(field(email, "SizeInBytes"), source.file().size())) score += 50;
    try {
      if (!field(email, "Subject").isEmpty() && normal(SourceHeaders.decoded(field(email, "Subject"))).equals(
          normal(SourceHeaders.decoded(SourceHeaders.originalText(header(source.message(), "Subject")))))) score += 10;
      if (source.message().getSentDate() != null && java.time.OffsetDateTime.parse(field(email, "Sent")).toInstant().equals(source.message().getSentDate().toInstant())) score += 10;
    } catch (Exception ignored) { /* Unknown normalization supplies no match evidence. */ }
    if (!field(email, "Raw-From").isEmpty() && normal(field(email, "Raw-From")).equals(normal(header(source.message(), "From")))) score += 5;
    return score;
  }

  private record NativeAttachment(List<String> names, String contentType, long size, String md5, Map<String, String> nestedContent) {
    NativeAttachment(List<String> names, String contentType, long size, String md5) { this(names, contentType, size, md5, Map.of()); }
  }

  private List<NativeAttachment> originalAttachments(Source source) throws Exception {
    if (source.nativeCore() != null) {
      if (source.nativeCore().attachments() == null) throw new java.io.IOException(source.nativeCore().attachmentError());
      return source.nativeCore().attachments().stream().map(a -> new NativeAttachment(a.names(), a.contentType(), a.size(), a.md5(), a.nestedContent())).toList();
    }
    if (source.outlook() != null && source.outlook().attachments() == null) throw new java.io.IOException(source.outlook().attachmentError());
    List<NativeAttachment> inventory = attachmentInventories.get(source);
    if (inventory == null) {
      inventory = new ArrayList<>();
      if (source.outlook() == null) inspectMime(source.message(), inventory, 0);
      else for (var attachment : source.outlook().attachments()) inventory.add(new NativeAttachment(attachment.names(), attachment.contentType(), attachment.size(), attachment.md5(), attachment.nestedContent()));
      attachmentInventories.put(source, List.copyOf(inventory));
    }
    return inventory;
  }

  String sourceChecksum(Element attachment, Element owner) {
    if (fieldElement(attachment, "CheckSum") != null || owner == null || !sourceBindings.containsKey(owner)) return null;
    try {
      Set<String> digests = new HashSet<>();
      for (NativeAttachment item : originalAttachments(sourceBindings.get(owner)))
        if (item.names().contains(field(attachment, "Filename")) && integerEquals(field(attachment, "SizeInBytes"), item.size())) digests.add(item.md5());
      return digests.size() == 1 ? digests.iterator().next() : null;
    } catch (Exception unavailable) { return null; }
  }

  boolean hasInspectableSources() { return inspectableSourceCount > 0; }
  boolean hasSource(Element owner) { return sourceBindings.containsKey(owner); }
  List<String> sourceHeader(Element owner, String header) throws Exception {
    if (sourceBindings.get(owner).nativeCore() != null) throw new java.io.IOException("No stored transport header: " + header);
    String[] values = sourceBindings.get(owner).message().getHeader(header);
    if (values == null) return List.of();
    List<String> decoded = new ArrayList<>();
    for (String value : values) decoded.add(SourceHeaders.originalText(value));
    return decoded;
  }
  long sourceCount(Element owner, String property) throws Exception {
    Source source = sourceBindings.get(owner);
    return "Mail_SizeInBytes".equals(property) ? source.file().size() : originalAttachments(source).size();
  }
  private void inspectMime(Part part, List<NativeAttachment> out, int depth) throws Exception {
    String contentType = part.getContentType();
    String base = baseMedia(contentType);
    boolean attachment = "attachment".equalsIgnoreCase(part.getDisposition()) || Set.of("message/rfc822", "message/global").contains(base)
        || part.getFileName() != null && !Set.of("text/plain", "text/html").contains(base);
    if (attachment) {
      List<String> names = new ArrayList<>();
      if (part.getFileName() != null) names.add(part.getFileName());
      try {
        var type = new jakarta.mail.internet.ContentType(contentType);
        if (type.getParameter("name") != null && !names.contains(type.getParameter("name"))) names.add(type.getParameter("name"));
      } catch (jakarta.mail.internet.ParseException malformed) {
        // Preserve a complete quoted name preceding a malformed parameter; keep the raw type for comparison.
        var parameter = java.util.regex.Pattern.compile("(?i);[ \\t]*name[ \\t]*=[ \\t]*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(contentType);
        if (parameter.find()) {
          String name = parameter.group(1).replaceAll("\\\\(.)", "$1");
          if (!names.contains(name)) names.add(name);
        }
      }
      var digest = java.security.MessageDigest.getInstance("MD5");
      long size = 0;
      // Jakarta Mail can ignore transfer encodings for other message subtypes
      // too (for example message/delivery-status from a real Outlook export).
      boolean encodedMessage = base.startsWith("message/");
      String encoding = part instanceof jakarta.mail.internet.MimeBodyPart body ? body.getEncoding()
          : part instanceof MimeMessage message ? message.getEncoding() : null;
      try (var raw = encodedMessage && part instanceof jakarta.mail.internet.MimeBodyPart body ? body.getRawInputStream()
              : encodedMessage && part instanceof MimeMessage message ? message.getRawInputStream() : part.getInputStream();
           var input = encodedMessage && encoding != null ? MimeUtility.decode(raw, encoding) : raw) {
        byte[] buffer = new byte[128 * 1024]; int count;
        while ((count = input.read(buffer)) != -1) {
          size += count; inspectedMimeBytes += count;
          if (inspectedMimeBytes > 64L * 1024 * 1024 * 1024) throw new java.io.IOException("Original MIME payload inventory exceeds its byte limit");
          digest.update(buffer, 0, count);
        }
      }
      out.add(new NativeAttachment(List.copyOf(names), contentType, size, java.util.HexFormat.of().formatHex(digest.digest())));
    } else if (part.isMimeType("multipart/*")) {
      if (!(part.getContent() instanceof Multipart multipart)) throw new java.io.IOException("Original multipart content cannot be inspected");
      for (int index = 0; index < multipart.getCount(); index++) inspectMime(multipart.getBodyPart(index), out, depth + 1);
    }
  }

  static boolean sameMedia(String left, String right) {
    if (left == null || right == null) return false;
    if (left.equals(right)) return true;
    try {
      var a = new jakarta.mail.internet.ContentType(left); var b = new jakarta.mail.internet.ContentType(right);
      if (!a.getBaseType().equalsIgnoreCase(b.getBaseType())) return false;
      Map<String, String> ap = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER), bp = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
      if (a.getParameterList() != null) for (var names = a.getParameterList().getNames(); names.hasMoreElements();) { String name = names.nextElement(); ap.put(name, a.getParameter(name)); }
      if (b.getParameterList() != null) for (var names = b.getParameterList().getNames(); names.hasMoreElements();) { String name = names.nextElement(); bp.put(name, b.getParameter(name)); }
      ap.computeIfPresent("charset", (key, value) -> value.toLowerCase(Locale.ROOT));
      bp.computeIfPresent("charset", (key, value) -> value.toLowerCase(Locale.ROOT));
      return ap.equals(bp);
    } catch (Exception error) { return false; }
  }

  private void foaf(Element email, String property) {
    var agents = MailboxChecks.read(email, property);
    if (!agents.present()) return;
    var containers = children(valueElement(fieldElement(email, property)));
    boolean ordered = containers.size() == 1 && is(containers.getFirst(), RDF, "Seq")
        && children(containers.getFirst()).stream().allMatch(item -> is(item, RDF, "li") && !item.hasAttributeNS(RDF, "resource")
            && ("Resource".equals(item.getAttributeNS(RDF, "parseType")) || !children(item).isEmpty() || item.hasAttributeNS(FOAF, "mbox")));
    report.check("MESSAGE_" + property.toUpperCase(Locale.ROOT) + "_XMP", ordered,
        "A supplied address field must be an ordered sequence of FOAF Agent containers.", Map.of("field", property));
    report.check("MESSAGE_" + property.toUpperCase(Locale.ROOT) + "_FOAF", agents.valid(),
        "Supplied FOAF mailbox values must have valid mailbox syntax.", Map.of("field", property));
  }

  private static InternetAddress[] sourceAddresses(String raw) throws jakarta.mail.internet.AddressException {
    try { return InternetAddress.parseHeader(raw, true); }
    catch (jakarta.mail.internet.AddressException malformed) { return InternetAddress.parseHeader(raw, false); }
  }

  private void compareAddresses(Element email, MimeMessage message, String property) {
    var agents = MailboxChecks.read(email, property);
    String presence = "MESSAGE_" + property.toUpperCase(Locale.ROOT) + "_XMP";
    String code = "MESSAGE_" + property.toUpperCase(Locale.ROOT) + "_FOAF";
    try { SourceHeaders.originalText(header(message, property)); }
    catch (IllegalArgumentException undecodable) {
      sourceUnavailable(presence, "Source address interpretation is unavailable for undecodable mailbox octets.");
      sourceUnavailable(code, "Source mailbox comparison is unavailable for undecodable octets."); return;
    }
    if (!agents.valid()) {
      if (agents.declaredCount() > 0) try {
        int known = 0; boolean nonSmtp = false;
        for (InternetAddress address : sourceAddresses(SourceHeaders.originalText(header(message, property)))) {
          InternetAddress[] members = address.isGroup() ? address.getGroup(true) : new InternetAddress[]{address};
          for (InternetAddress member : members) if (MailboxChecks.valid(member.getAddress())) known++; else nonSmtp = true;
        }
        if (nonSmtp && agents.declaredCount() > known)
          sourceUnavailable(code, "Additional SMTP mailbox declarations cannot be verified against non-SMTP source tokens without normalization evidence.");
      } catch (Exception unavailable) { sourceUnavailable(code, "Source mailbox normalization could not be established."); }
      if (!header(message, property).isEmpty() && agents.present()) sourceCheck(presence, true, "The address property is supplied; its invalid FOAF type is reported separately.", null);
      return;
    }
    String raw = header(message, property);
    List<String> expected = new ArrayList<>();
    boolean nonSmtp = false;
    try {
      raw = SourceHeaders.originalText(raw);
      for (InternetAddress address : sourceAddresses(raw)) {
        InternetAddress[] members = address.isGroup() ? address.getGroup(true) : new InternetAddress[]{address};
        for (InternetAddress member : members) {
          String value = member.getAddress();
          if (value.regionMatches(true, 0, "mailto:", 0, 7)) value = value.substring(7);
          if (MailboxChecks.valid(value)) expected.add(value); else nonSmtp = true;
        }
      }
    } catch (Exception error) {
      sourceUnavailable(presence, "The source address header could not be interpreted: " + property);
      sourceUnavailable(code, "Source mailbox comparison is unavailable for " + property); return;
    }
    if (raw.isEmpty() && !agents.present() && emails.size() > 1) return;
    if (nonSmtp) {
      String saved = field(email, "Raw-" + property);
      if (!saved.isEmpty() && normal(saved).equals(normal(raw))) sourceCheck(presence, true, "Non-SMTP source addresses are retained in their raw field.", null);
      else sourceUnavailable(presence, "Non-SMTP source addresses require an exact raw header.");
      if (!saved.isEmpty() && normal(saved).equals(normal(raw)) && agents.mailboxes().isEmpty() && expected.isEmpty()) {
        if (emails.size() <= 1) sourceCheck(code, true, "No SMTP mailbox agents require a value comparison.", null);
        return;
      }
      if (!saved.isEmpty() && normal(saved).equals(normal(raw)) && agents.mailboxes().size() <= expected.size()) {
        // The valid SMTP subset can still establish a required missing agent or a value comparison.
      } else sourceUnavailable(code, "Non-SMTP source normalization requires independent evidence.");
      if (expected.isEmpty()) return;
    }
    if (!expected.isEmpty()) sourceCheck(presence, !agents.mailboxes().isEmpty(), "A nonempty source address field requires its XMP agents.", Map.of("field", property));
    else if (agents.mailboxes().isEmpty()) {
      sourceCheck(presence, true, "The source and XMP field contain no addresses.", null);
      sourceCheck(code, true, "No source or metadata mailbox requires a value comparison.", null); return;
    }
    if (agents.mailboxes().isEmpty()) {
      if (emails.size() <= 1) sourceCheck(code, true, "No supplied mailbox agent requires a value comparison; required presence is checked separately.", null);
      return;
    }
    boolean mismatch = expected.size() != agents.mailboxes().size(), unknown = false;
    for (int index = 0; index < Math.min(expected.size(), agents.mailboxes().size()); index++) {
      var a = MailboxChecks.key(expected.get(index)); var b = MailboxChecks.key(agents.mailboxes().get(index));
      if (a == null || b == null) { unknown = true; continue; }
      if (!a.local().equals(b.local())) mismatch = true;
      if (!a.domain().equals(b.domain())) {
        if ((a.domain() + b.domain()).codePoints().anyMatch(point -> point > 127)) unknown = true;
        else mismatch = true;
      }
    }
    if (!mismatch && unknown) sourceUnavailable(code, "Mailbox domain normalization needs additional evidence.");
    else sourceCheck(code, !mismatch, "FOAF mailbox values must match the source, preserving local-part case and quoted data.", Map.of("field", property, "actual", agents.mailboxes(), "expected", expected));
  }

  private static List<String> replyIds(String value) {
    return java.util.regex.Pattern.compile("<[^>]*>|[^\\s]+").matcher(normal(value)).results().map(java.util.regex.MatchResult::group).sorted().toList();
  }

  static boolean dateTime(String text) {
    if (text == null || !text.strip().matches("-?[0-9]{4,}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](?:\\.[0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})?")) return false;
    String year = text.strip().split("-", 3)[text.strip().startsWith("-") ? 1 : 0];
    if (year.matches("0+") || year.length() > 4 && year.startsWith("0")) return false;
    try { return javax.xml.datatype.DatatypeFactory.newInstance().newXMLGregorianCalendar(text.strip()).isValid(); }
    catch (Exception error) { return false; }
  }

  private void checkInteger(Element record, String field, String code) {
    report.check(code, simple(fieldElement(record, field)) && nonnegative(field(record, field)), "The supplied size or count must be a nonnegative XMP integer.", Map.of("field", field, "value", field(record, field)));
  }
  static boolean integerEquals(String text, long expected) { return nonnegative(text) && new BigInteger(text.strip()).equals(BigInteger.valueOf(expected)); }
  static String baseMedia(String text) { return text.split(";", 2)[0].strip().toLowerCase(Locale.ROOT); }
  static boolean mediaType(String text) {
    if (!baseMedia(text).matches("[!#$%&'*+.^_`|~0-9a-z-]+/[!#$%&'*+.^_`|~0-9a-z-]+")) return false;
    return true; // EA-PDF preserves original parameters, including potential source errors.
  }
  static boolean namesMatch(String left, String right) {
    left = left.replace('\\', '/'); right = right.replace('\\', '/');
    return left.substring(left.lastIndexOf('/') + 1).equals(right.substring(right.lastIndexOf('/') + 1));
  }
}
