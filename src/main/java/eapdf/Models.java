package eapdf;
import java.util.*;

class MailAgent {
  String name;
  String email;
}

class AssociatedFile {
  String filename;
  String mime;
  String rawContentType;
  String afRelationship;
  String launchToken;
  byte[] bytes;
  java.nio.file.Path dataPath;
  long dataOffset;
  boolean dataSlice;
  int storedSize;
  String description;
  String checksumMd5;
  String checksumSha256;
  String contentId;
  String contentLocation;
  String resolvedContentLocation;
  String locationScope = "";
  boolean inline;
  boolean emailAttachment;
  boolean sourceSetMember;
  boolean embedInPdf = true;

  int size() {
    return bytes != null ? bytes.length : storedSize;
  }

  boolean hasData() {
    return bytes != null || dataPath != null;
  }

  byte[] data() {
    if (bytes != null || dataPath == null) return bytes;
    try {
      try (var input = openData()) {
        byte[] value = input.readAllBytes();
        if (dataSlice && value.length != storedSize) throw new java.io.IOException("Truncated staged payload: " + filename);
        return value;
      }
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  java.io.InputStream openData() throws java.io.IOException {
    if (dataPath == null) return new java.io.ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
    if (!dataSlice) return java.nio.file.Files.newInputStream(dataPath);
    return PayloadStore.slice(dataPath, dataOffset, storedSize);
  }

  /** Fill missing fingerprints in one bounded pass without materializing staged payloads. */
  synchronized void ensureChecksums() throws java.io.IOException {
    ProcessingLimits.checkCancelled();
    boolean needMd5 = Common.nz(checksumMd5).isBlank();
    boolean needSha256 = Common.nz(checksumSha256).isBlank();
    if (!needMd5 && !needSha256) return;
    if (!hasData()) throw new java.io.IOException("Attachment payload is unavailable: " + filename);
    try {
      var md5 = needMd5 ? java.security.MessageDigest.getInstance("MD5") : null;
      var sha256 = needSha256 ? java.security.MessageDigest.getInstance("SHA-256") : null;
      long length = 0;
      try (var input = openData()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          ProcessingLimits.checkCancelled();
          length += count;
          if (md5 != null) md5.update(buffer, 0, count);
          if (sha256 != null) sha256.update(buffer, 0, count);
        }
      }
      if (dataSlice && length != storedSize)
        throw new java.io.IOException("Truncated staged payload: " + filename);
      // Publish neither new fingerprint until the complete stream has been read and closed.
      if (md5 != null) checksumMd5 = HexFormat.of().formatHex(md5.digest());
      if (sha256 != null) checksumSha256 = HexFormat.of().formatHex(sha256.digest());
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("Required payload digest algorithm is unavailable", error);
    }
  }

  byte[] head() throws java.io.IOException {
    try (java.io.InputStream input = openData()) {
      return input.readNBytes(512);
    }
  }
}

class EmailRecord {
  String sourceType, sourcePath, folderPath, messageId, subject, from, to, cc, bcc, date, rawDateHeader;
  String rawFrom, rawTo, rawCc, rawBcc, rawReplyTo;
  List<String> inReplyTo = new ArrayList<>();
  List<String> references = new ArrayList<>();
  List<String> conversionWarnings = new ArrayList<>();
  final String diagnosticIdentity = UUID.randomUUID().toString();
  Map<String, ConversionDiagnostic.Code> diagnosticCodes = new LinkedHashMap<>();
  List<String> sourceProblems = new ArrayList<>();
  List<String> folderMemberships = new ArrayList<>();
  String sentXmp;
  String textBody, htmlBody, rtfBody;
  String bodyMime;
  String bodyBaseUri = "";
  List<String> bodyLocationScopes = List.of();
  transient Map<Object, List<String>> mimePartScopes;
  transient Map<String, AssociatedFile> nativeLocationResources = new LinkedHashMap<>();
  String headerTagMarker;
  java.nio.file.Path textDataPath, htmlDataPath, rtfDataPath;
  long textDataOffset, htmlDataOffset, rtfDataOffset;
  int textDataLength, htmlDataLength, rtfDataLength;
  long stagedMetadataSize;
  boolean payloadStaged;
  MboxReadInventory.Provenance mboxProvenance;
  byte[] sourceBytes;
  String sourceFilename;
  String sourceMime;
  String afRelationship;
  String archiveGlobalId;
  String mailGuid;
  int renderContentWidthPx;
  int renderedBodyPages;
  int attachmentCount;
  boolean embedSourceMessage = true;
  String contentSetDescription;
  List<MailAgent> fromAgents = new ArrayList<>();
  List<MailAgent> toAgents = new ArrayList<>();
  List<MailAgent> ccAgents = new ArrayList<>();
  List<MailAgent> bccAgents = new ArrayList<>();
  List<String> archiveEmails = new ArrayList<>();
  List<AssociatedFile> associatedFiles = new ArrayList<>();
  Map<String, byte[]> renderAssets = new HashMap<>();
  String renderBaseUri = "";
  List<String> debugLog = new ArrayList<>();
}

class BodyChoice {
  String text = "";
  String html = "";
  String mime = "";
  String plainMime = "";
  String baseUri = "";
  List<String> locationScopes = List.of();
  boolean unsupported;
  List<String> renderWarnings = new ArrayList<>();
}
