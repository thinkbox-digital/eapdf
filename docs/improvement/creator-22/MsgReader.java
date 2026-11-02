package eapdf;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;
import org.simplejavamail.outlookmessageparser.model.OutlookRecipient;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.CodePageUtil;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.file.*;
import java.util.*;

class MsgReader {
  static String headerValue(OutlookMessage m, String name) {
    Map<String, Collection<String>> headers = m.getHeadersMap();
    if (headers == null) return "";
    for (Map.Entry<String, Collection<String>> e : headers.entrySet()) {
      if (!name.equalsIgnoreCase(Common.nz(e.getKey()))) continue;
      return String.join(", ", e.getValue());
    }
    return "";
  }

  static String displayAddress(List<MailAgent> agents, String fallback) {
    String addresses = Common.joinAgents(agents);
    return addresses.isBlank() ? Common.nz(fallback) : addresses;
  }

  static String rawAddressHeader(OutlookMessage message, String field, List<MailAgent> agents, String fallback) {
    String original = headerValue(message, field);
    if (!original.isBlank()) return original;
    String addresses = Common.rfc822AddrList(agents);
    return addresses.isBlank() ? fallback : addresses;
  }

  static EmailRecord readMsg(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    ProcessingLimits.checkMessage(Files.size(Paths.get(path)), path);
    OutlookMessageParser p = new OutlookMessageParser();
    OutlookMessage m = p.parseMsg(new File(path));
    EmailRecord r = new EmailRecord();
    r.sourceType = "msg";
    r.sourcePath = path;
    r.folderPath = "";
    r.subject = Common.nz(m.getSubject()).replace("\0", "");
    Common.addAgent(r.fromAgents, Common.nz(m.getFromName()), Common.nz(m.getFromEmail()));
    for (OutlookRecipient rcpt : m.getToRecipients()) Common.addAgent(r.toAgents, Common.nz(rcpt.getName()), Common.nz(rcpt.getAddress()));
    for (OutlookRecipient rcpt : m.getCcRecipients()) Common.addAgent(r.ccAgents, Common.nz(rcpt.getName()), Common.nz(rcpt.getAddress()));
    for (OutlookRecipient rcpt : m.getBccRecipients()) Common.addAgent(r.bccAgents, Common.nz(rcpt.getName()), Common.nz(rcpt.getAddress()));
    r.from = Common.joinAddr(Common.nz(m.getFromName()), Common.nz(m.getFromEmail()));
    r.to = displayAddress(r.toAgents, m.getDisplayTo());
    r.cc = displayAddress(r.ccAgents, m.getDisplayCc());
    r.bcc = displayAddress(r.bccAgents, m.getDisplayBcc());
    r.rawFrom = rawAddressHeader(m, "From", r.fromAgents, r.from);
    r.rawTo = rawAddressHeader(m, "To", r.toAgents, r.to);
    r.rawCc = rawAddressHeader(m, "Cc", r.ccAgents, r.cc);
    r.rawBcc = rawAddressHeader(m, "Bcc", r.bccAgents, r.bcc);
    r.messageId = Common.nz(m.getMessageId());
    if (!headerValue(m, "In-Reply-To").isBlank()) r.inReplyTo.add(headerValue(m, "In-Reply-To"));
    if (!headerValue(m, "References").isBlank()) r.references.add(headerValue(m, "References"));
    r.archiveGlobalId = Common.nz(globalId);
    r.archiveEmails.addAll(archiveEmails);
    Date sentDate = m.getDate() != null ? m.getDate() : m.getClientSubmitTime();
    r.date = Common.fmtDate(sentDate);
    r.sentXmp = Common.xmpDate(sentDate);
    r.rawDateHeader = !headerValue(m, "Date").isBlank() ? headerValue(m, "Date") : Common.rfc822Date(sentDate);
    Object nativeValue = m.getPropertyValue(0x1016);
    int nativeBody = nativeValue instanceof Number number ? number.intValue() : 0;
    Boolean rtfInSync = storageBoolean(Paths.get(path), 0x0e1f000b);
    populateMsgBody(m, r, nativeBody, rtfInSync);

    r.sourceBytes = Files.readAllBytes(Paths.get(path));
    r.sourceFilename = Paths.get(path).getFileName().toString();
    r.sourceMime = "application/vnd.ms-outlook";
    r.afRelationship = "Source";

    AssociatedFile msgSrc = new AssociatedFile();
    msgSrc.filename = r.sourceFilename;
    msgSrc.mime = r.sourceMime;
    msgSrc.afRelationship = "Source";
    msgSrc.bytes = r.sourceBytes;
    msgSrc.description = "Original Outlook MSG source";
    msgSrc.checksumMd5 = Common.md5Hex(msgSrc.bytes);
    msgSrc.checksumSha256 = Common.sha256Hex(msgSrc.bytes);
    msgSrc.emailAttachment = false;
    r.associatedFiles.add(msgSrc);

    Set<String> seenInline = new LinkedHashSet<>();
    for (OutlookFileAttachment af0 : m.fetchTrueAttachments()) {
      AssociatedFile af = new AssociatedFile();
      String longFilename = Common.normalizeAttachmentFilename(af0.getLongFilename());
      af.filename = !longFilename.isBlank() ? longFilename : Common.normalizeAttachmentFilename(af0.getFilename());
      if (Common.nz(af.filename).isBlank()) af.filename = "attachment" + Common.extensionForMime(Common.nz(af0.getMimeTag()), af.filename);
      af.mime = Common.detectMimeType(af.filename, Common.nz(af0.getMimeTag()), af0.getData());
      af.rawContentType = Common.nz(af0.getMimeTag()).replace("\0", "").trim();
      af.afRelationship = "Data";
      af.bytes = af0.getData();
      af.description = "Original Outlook attachment";
      af.contentId = Common.nz(af0.getContentId());
      af.inline = false;
      af.emailAttachment = af.bytes != null;
      if (af.bytes != null) {
        af.checksumMd5 = Common.md5Hex(af.bytes);
        af.checksumSha256 = Common.sha256Hex(af.bytes);
      }
      if (af.emailAttachment) r.associatedFiles.add(af);
      if (!Common.nz(af.contentId).isBlank()) seenInline.add(Common.nz(af.contentId));
    }

    for (Map.Entry<String, OutlookFileAttachment> e : m.fetchCIDMap().entrySet()) {
      OutlookFileAttachment af0 = e.getValue();
      String cid = Common.nz(af0.getContentId()).isBlank() ? Common.nz(e.getKey()) : Common.nz(af0.getContentId());
      if (seenInline.contains(cid)) continue;
      AssociatedFile af = new AssociatedFile();
      String longFilename = Common.normalizeAttachmentFilename(af0.getLongFilename());
      af.filename = !longFilename.isBlank() ? longFilename : Common.normalizeAttachmentFilename(af0.getFilename());
      if (Common.nz(af.filename).isBlank()) af.filename = "inline" + Common.extensionForMime(Common.nz(af0.getMimeTag()), af.filename);
      af.mime = Common.detectMimeType(af.filename, Common.nz(af0.getMimeTag()), af0.getData());
      af.rawContentType = Common.nz(af0.getMimeTag()).replace("\0", "").trim();
      af.afRelationship = "Supplement";
      af.bytes = af0.getData();
      af.description = "Inline Outlook related content";
      af.contentId = cid;
      af.inline = true;
      af.emailAttachment = false;
      if (af.bytes != null) {
        af.checksumMd5 = Common.md5Hex(af.bytes);
        af.checksumSha256 = Common.sha256Hex(af.bytes);
      }
      if (af.bytes != null && af.bytes.length > 0) r.associatedFiles.add(af);
    }

    collectAttachedMessages(Paths.get(path), r, debug);
    Common.finalizeRecordMetadata(r);

    if (debug) {
      Common.dbg(r, "MSG subject=" + r.subject);
      Common.dbg(r, "MSG text len=" + r.textBody.length() + " html len=" + r.htmlBody.length());
    }

    return r;
  }

  static String decodeMsgHtml(OutlookMessage message, EmailRecord record) {
    String parsed = Common.nz(message.getBodyHTML());
    Object raw = message.getPropertyValue(0x1013);
    Object declared = message.getPropertyValue(0x3fde);
    if (!(raw instanceof byte[] bytes) || !(declared instanceof Number codePage) || codePage.intValue() <= 0) return parsed;
    Charset charset;
    try {
      charset = Charset.forName(CodePageUtil.codepageToEncoding(codePage.intValue()));
    } catch (UnsupportedEncodingException | IllegalArgumentException unsupported) {
      Common.warn(record, "MSG HTML code page " + codePage.intValue() + " is unsupported; using parser recovery.");
      return parsed;
    }
    try {
      return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalidText) {
      Common.warn(record, "MSG HTML contains invalid bytes for " + charset.name() + "; replacement characters were used.");
      return new String(bytes, charset);
    }
  }

  static void populateMsgBody(OutlookMessage message, EmailRecord record, int nativeBody, Boolean rtfInSync) {
    String html = Common.nz(message.getBodyHTML());
    String convertedRtf = Common.nz(message.getConvertedBodyHTML());
    boolean hasRtf = !Common.nz(message.getBodyRTF()).isBlank() && !convertedRtf.isBlank();
    int selected;
    if (nativeBody == 1 && message.getBodyText() != null) selected = 1;
    else if (nativeBody == 2 && hasRtf) selected = 2;
    else if (nativeBody == 3 && message.getBodyHTML() != null) selected = 3;
    else {
      if (hasRtf && Boolean.TRUE.equals(rtfInSync)) selected = 2;
      else if (message.getBodyHTML() != null) selected = 3;
      else if (message.getBodyText() != null) selected = 1;
      else selected = hasRtf ? 2 : 1;
      if (nativeBody >= 1 && nativeBody <= 3) {
        String warning = "Declared MSG native body " + nativeBody + " is unavailable; rendering available body " + selected;
        record.conversionWarnings.add(warning);
        System.err.println("warning: " + warning);
      }
    }
    record.textBody = Common.normalizeBody(Common.nz(message.getBodyText()));
    record.bodyMime = selected == 2 ? "text/rtf" : selected == 3 ? "text/html" : "text/plain";
    if (selected == 2) record.rtfBody = message.getBodyRTF();
    if (selected == 3) html = decodeMsgHtml(message, record);
    String rendered = selected == 2 ? convertedRtf : selected == 3 ? html : "";
    record.htmlBody = selected == 1 ? Common.plainTextToHtml(record.textBody) : Common.normalizeHtml(rendered);
  }

  static Boolean storageBoolean(Path path, int propertyTag) throws IOException {
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      if (!storage.getRoot().hasEntry("__properties_version1.0")) return null;
      try (InputStream input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) {
        input.skipNBytes(32);
        long scanned = 32;
        byte[] entry;
        while ((entry = input.readNBytes(16)).length > 0) {
          scanned += entry.length;
          if (scanned > ProcessingLimits.metadataBytes()) throw new IOException("MSG property table exceeds the metadata budget");
          if (entry.length != 16) throw new IOException("Invalid MSG property-table entry");
          ByteBuffer value = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
          if (value.getInt(0) == propertyTag) {
            int flag = Short.toUnsignedInt(value.getShort(8));
            return flag == 0 ? Boolean.FALSE : flag == 1 ? Boolean.TRUE : null;
          }
        }
      }
    }
    return null;
  }

  static void collectAttachedMessages(Path path, EmailRecord record, boolean debug) throws Exception {
    try (POIFSFileSystem original = new POIFSFileSystem(path.toFile(), true)) {
      int sequence = 0;
      for (Entry entry : original.getRoot()) {
        if (!(entry instanceof DirectoryNode attachment) || !entry.getName().startsWith("__attach_version1.0_")) continue;
        if (!attachment.hasEntry("__substg1.0_3701000D")) continue;
        Entry embedded = attachment.getEntry("__substg1.0_3701000D");
        if (!(embedded instanceof DirectoryNode message)) throw new IOException("Embedded MSG message storage is not a directory");
        AssociatedFile file = new AssociatedFile();
        file.filename = storageString(attachment, "3707");
        if (file.filename.isBlank()) file.filename = storageString(attachment, "3704");
        if (file.filename.isBlank()) file.filename = "attached_message_" + String.format("%03d", ++sequence) + ".msg";
        file.filename = Common.safeFilename(file.filename);
        if (!file.filename.toLowerCase(Locale.ROOT).endsWith(".msg")) file.filename += ".msg";
        try (POIFSFileSystem extracted = new POIFSFileSystem(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
          EntryUtils.copyNodes(message, extracted.getRoot());
          extracted.getRoot().setStorageClsid(original.getRoot().getStorageClsid());
          if (!extracted.getRoot().hasEntry("__nameid_version1.0") && original.getRoot().hasEntry("__nameid_version1.0")) {
            EntryUtils.copyNodeRecursively(original.getRoot().getEntry("__nameid_version1.0"), extracted.getRoot());
          }
          if (extracted.getRoot().hasEntry("__properties_version1.0")) {
            byte[] properties;
            try (InputStream input = extracted.getRoot().createDocumentInputStream("__properties_version1.0")) {
              properties = input.readAllBytes();
            }
            if (properties.length < 24 || (properties.length - 24) % 16 != 0) {
              throw new IOException("Invalid embedded MSG property-table header");
            }
            byte[] standaloneProperties = new byte[properties.length + 8];
            System.arraycopy(properties, 0, standaloneProperties, 0, 24);
            System.arraycopy(properties, 24, standaloneProperties, 32, properties.length - 24);
            extracted.getRoot().createOrUpdateDocument("__properties_version1.0", new ByteArrayInputStream(standaloneProperties));
          }
          extracted.writeFilesystem(output);
          file.bytes = output.toByteArray();
        }
        file.mime = "application/vnd.ms-outlook";
        file.afRelationship = "Data";
        file.emailAttachment = true;
        file.description = "Complete attached Outlook message extracted from parent MSG storage";
        file.checksumMd5 = Common.md5Hex(file.bytes);
        file.checksumSha256 = Common.sha256Hex(file.bytes);
        record.associatedFiles.add(file);
        if (debug) Common.dbg(record, "associated attached MSG filename=" + file.filename + " bytes=" + file.bytes.length);
      }
    }
  }

  static String storageString(DirectoryNode storage, String propertyId) throws IOException {
    for (String type : List.of("001F", "001E")) {
      String name = "__substg1.0_" + propertyId + type;
      if (!storage.hasEntry(name)) continue;
      try (InputStream input = storage.createDocumentInputStream(name)) {
        var charset = type.equals("001F") ? java.nio.charset.StandardCharsets.UTF_16LE : java.nio.charset.Charset.forName("windows-1252");
        return Common.stripInvalidXml(new String(input.readAllBytes(), charset)).trim();
      }
    }
    return "";
  }
}
