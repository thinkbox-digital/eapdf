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
  int storedSize;
  String description;
  String checksumMd5;
  String checksumSha256;
  String contentId;
  String contentLocation;
  String resolvedContentLocation;
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
      return java.nio.file.Files.readAllBytes(dataPath);
    } catch (java.io.IOException exception) {
      throw new java.io.UncheckedIOException(exception);
    }
  }

  java.io.InputStream openData() throws java.io.IOException {
    return dataPath != null ? java.nio.file.Files.newInputStream(dataPath)
        : new java.io.ByteArrayInputStream(bytes == null ? new byte[0] : bytes);
  }

  byte[] head() throws java.io.IOException {
    try (java.io.InputStream input = openData()) {
      return input.readNBytes(512);
    }
  }
}

class EmailRecord {
  String sourceType, sourcePath, folderPath, messageId, subject, from, to, cc, bcc, date, rawDateHeader;
  String rawFrom, rawTo, rawCc, rawBcc;
  List<String> inReplyTo = new ArrayList<>();
  List<String> references = new ArrayList<>();
  List<String> conversionWarnings = new ArrayList<>();
  List<String> folderMemberships = new ArrayList<>();
  String sentXmp;
  String textBody, htmlBody, rtfBody;
  String bodyMime;
  String bodyBaseUri = "";
  String headerTagMarker;
  java.nio.file.Path textDataPath, htmlDataPath, rtfDataPath;
  boolean payloadStaged;
  MboxReadInventory.Provenance mboxProvenance;
  byte[] sourceBytes;
  String sourceFilename;
  String sourceMime;
  String afRelationship;
  String archiveGlobalId;
  String mailGuid;
  int renderContentWidthPx;
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
  String baseUri = "";
  boolean unsupported;
}
