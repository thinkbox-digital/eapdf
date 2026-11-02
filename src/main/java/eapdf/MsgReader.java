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

  static void recoverTransportAgents(List<MailAgent> agents, String header) {
    if (Common.nz(header).isBlank()) return;
    List<MailAgent> transport = Common.internetAddressListLenient(header);
    if (agents.isEmpty()) {
      agents.addAll(transport);
      return;
    }
    // Keep native names and mailbox values, but retain the original header order
    // when both representations contain exactly the same recipient occurrences.
    if (transport.size() != agents.size()) return;
    List<MailAgent> remaining = new ArrayList<>(agents);
    List<MailAgent> ordered = new ArrayList<>();
    Map<MailAgent, String> suppliedMailboxes = new IdentityHashMap<>();
    for (MailAgent supplied : transport) {
      MailAgent match = remaining.stream().filter(agent -> Common.nz(agent.email).equals(Common.nz(supplied.email))).findFirst().orElse(null);
      if (match == null) {
        // Outlook's native rows can change casing. Preserve the actual saved
        // header spelling, using a unique native occurrence only for its name.
        List<MailAgent> folded = remaining.stream().filter(agent -> Common.nz(agent.email).equalsIgnoreCase(Common.nz(supplied.email))).toList();
        if (folded.size() != 1) return;
        match = folded.get(0);
      }
      remaining.remove(match);
      ordered.add(match);
      suppliedMailboxes.put(match, supplied.email);
    }
    suppliedMailboxes.forEach((agent, mailbox) -> agent.email = mailbox);
    agents.clear();
    agents.addAll(ordered);
  }

  static EmailRecord readMsg(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    ProcessingLimits.checkMessage(Files.size(Paths.get(path)), path);
    checkCompressedRtf(Paths.get(path));
    OutlookMessageParser p = new OutlookMessageParser();
    OutlookMessage m = p.parseMsg(new File(path));
    EmailRecord r = new EmailRecord();
    r.sourceType = "msg";
    r.sourcePath = path;
    r.folderPath = "";
    r.subject = decodeMsgSubject(Paths.get(path), m, r).replace("\0", "");
    String fromEmail = senderAddress(Paths.get(path), m);
    String fromName = senderName(Paths.get(path), m, r, fromEmail);
    Map<String, String> recipientNames = decodedRecipientNames(Paths.get(path), m, r);
    Common.addAgent(r.fromAgents, fromName, fromEmail);
    for (OutlookRecipient rcpt : m.getToRecipients()) Common.addAgent(r.toAgents, recipientName(rcpt, recipientNames), Common.nz(rcpt.getAddress()));
    for (OutlookRecipient rcpt : m.getCcRecipients()) Common.addAgent(r.ccAgents, recipientName(rcpt, recipientNames), Common.nz(rcpt.getAddress()));
    for (OutlookRecipient rcpt : m.getBccRecipients()) Common.addAgent(r.bccAgents, recipientName(rcpt, recipientNames), Common.nz(rcpt.getAddress()));
    r.from = Common.joinAddr(fromName, fromEmail);
    r.to = displayAddress(r.toAgents, decodeMsgString(Paths.get(path), m, r, 0x0e04, "display To", m.getDisplayTo()));
    r.cc = displayAddress(r.ccAgents, decodeMsgString(Paths.get(path), m, r, 0x0e03, "display Cc", m.getDisplayCc()));
    r.bcc = displayAddress(r.bccAgents, decodeMsgString(Paths.get(path), m, r, 0x0e02, "display Bcc", m.getDisplayBcc()));
    r.rawFrom = rawAddressHeader(m, "From", r.fromAgents, r.from);
    r.rawTo = rawAddressHeader(m, "To", r.toAgents, r.to);
    r.rawCc = rawAddressHeader(m, "Cc", r.ccAgents, r.cc);
    r.rawBcc = rawAddressHeader(m, "Bcc", r.bccAgents, r.bcc);
    r.rawReplyTo = headerValue(m, "Reply-To");
    // Some MSG exports retain transport addresses without MAPI recipient rows.
    // Use those supplied addresses when the native agent list is unavailable.
    recoverTransportAgents(r.fromAgents, headerValue(m, "From"));
    recoverTransportAgents(r.toAgents, headerValue(m, "To"));
    recoverTransportAgents(r.ccAgents, headerValue(m, "Cc"));
    recoverTransportAgents(r.bccAgents, headerValue(m, "Bcc"));
    r.from = displayAddress(r.fromAgents, r.from);
    r.to = displayAddress(r.toAgents, r.to);
    r.cc = displayAddress(r.ccAgents, r.cc);
    r.bcc = displayAddress(r.bccAgents, r.bcc);
    r.messageId = Common.nz(m.getMessageId());
    if (!headerValue(m, "In-Reply-To").isBlank()) r.inReplyTo.add(headerValue(m, "In-Reply-To"));
    if (!headerValue(m, "References").isBlank()) r.references.add(headerValue(m, "References"));
    recoverNativeThreadHeaders(Paths.get(path), m, r);
    r.archiveGlobalId = Common.nz(globalId);
    r.archiveEmails.addAll(archiveEmails);
    String storedSent = headerValue(m, "Date").isBlank() ? nativeSentXmp(Paths.get(path)) : "";
    Date sentDate = storedSent.isBlank() ? sentDate(m) : Date.from(java.time.Instant.parse(storedSent));
    r.date = Common.fmtDate(sentDate);
    r.sentXmp = storedSent.isBlank() ? Common.xmpDate(sentDate) : storedSent;
    r.rawDateHeader = !headerValue(m, "Date").isBlank() ? headerValue(m, "Date") : Common.rfc822Date(sentDate);
    Object nativeValue = m.getPropertyValue(0x1016);
    int nativeBody = nativeValue instanceof Number number ? number.intValue() : 0;
    Boolean rtfInSync = storageBoolean(Paths.get(path), 0x0e1f000b);
    String plainText = decodeMsgString(Paths.get(path), m, r, 0x1000, "plain-text body", m.getBodyText());
    populateMsgBody(m, r, nativeBody, rtfInSync, plainText);

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
    // fetchTrueAttachments uses a HashSet internally. Keep the parser's source
    // order rather than allowing object hash codes to reorder displayed files.
    Set<OutlookFileAttachment> selectedAttachments = Collections.newSetFromMap(new IdentityHashMap<>());
    selectedAttachments.addAll(m.fetchTrueAttachments());
    for (var attachment : m.getOutlookAttachments()) {
      if (!(attachment instanceof OutlookFileAttachment af0) || !selectedAttachments.remove(af0)) continue;
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

    collectAttachedMessages(Paths.get(path), r, debug, m);
    EmlReader.preferReadableAlternative(r, "text/plain");
    Common.finalizeRecordMetadata(r);

    if (debug) {
      Common.dbg(r, "MSG subject=" + r.subject);
      Common.dbg(r, "MSG text len=" + r.textBody.length() + " html len=" + r.htmlBody.length());
    }

    return r;
  }

  static void checkCompressedRtf(Path path) throws Exception {
    try (var storage = new POIFSFileSystem(path.toFile(), true)) {
      var pending = new java.util.ArrayDeque<java.util.Map.Entry<org.apache.poi.poifs.filesystem.DirectoryEntry, Integer>>();
      pending.add(java.util.Map.entry(storage.getRoot(), 0)); int count = 0;
      while (!pending.isEmpty()) {
        var item = pending.removeFirst();
        if (item.getValue() > 32) throw Common.fail("MSG storage nesting exceeds the processing limit");
        for (var entry : item.getKey()) {
          if (++count > 10000) throw Common.fail("MSG storage entry count exceeds the processing limit");
          if (entry instanceof org.apache.poi.poifs.filesystem.DirectoryEntry directory)
            pending.add(java.util.Map.entry(directory, item.getValue() + 1));
          else if (entry instanceof org.apache.poi.poifs.filesystem.DocumentEntry document
              && "__substg1.0_10090102".equalsIgnoreCase(entry.getName())) {
            ProcessingLimits.checkMessage(document.getSize(), "compressed MSG RTF");
            try (var input = new org.apache.poi.poifs.filesystem.DocumentInputStream(document)) {
              com.pff.EaPdfPstProperties.checkRtfHeader(input.readNBytes(16), document.getSize(), ProcessingLimits.bodyCharacters());
            }
          }
        }
      }
    }
  }

  static void recoverNativeThreadHeaders(Path path, OutlookMessage message, EmailRecord record) throws Exception {
    if (!record.inReplyTo.isEmpty() && !record.references.isEmpty()) return;
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      DirectoryNode root = storage.getRoot();
      if (record.inReplyTo.isEmpty()) {
        String reply = decodeMsgString(root, message, record, 0x1042, "In-Reply-To", storageString(root, "1042"));
        if (!reply.isBlank()) record.inReplyTo.add(reply);
      }
      if (record.references.isEmpty()) {
        String references = decodeMsgString(root, message, record, 0x1039, "References", storageString(root, "1039"));
        if (!references.isBlank()) record.references.add(references);
      }
    }
  }

  static String decodeMsgSubject(Path path, OutlookMessage message, EmailRecord record) throws Exception {
    return decodeMsgString(path, message, record, 0x0037, "Subject", Common.nz(message.getSubject()));
  }

  static String senderAddress(Path path, OutlookMessage message) throws IOException {
    String parsed = Common.nz(message.getFromEmail());
    if (!parsed.isBlank()) return parsed;
    // The parser omits Exchange addresses and does not read their SMTP counterparts.
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      DirectoryNode root = storage.getRoot();
      String representedSmtp = storageString(root, "5D02");
      if (Common.smtpMailbox(representedSmtp)) return representedSmtp;
      String represented = storageString(root, "0065");
      if (Common.smtpMailbox(represented)) return represented;
      String sender = storageString(root, "0C1F");
      // A delegate's SMTP address must not be assigned to the represented author.
      if (!represented.isBlank() && !represented.equalsIgnoreCase(sender)) return parsed;
      String senderSmtp = storageString(root, "5D01");
      return Common.smtpMailbox(senderSmtp) ? senderSmtp : parsed;
    }
  }

  static String senderName(Path path, OutlookMessage message, EmailRecord record, String email) throws Exception {
    String represented = decodeMsgString(path, message, record, 0x0042, "sender name", Common.nz(message.getFromName()));
    if (!represented.isBlank()) return represented;
    String transport = headerValue(message, "From");
    for (MailAgent author : Common.internetAddressListLenient(transport)) {
      if (email.equalsIgnoreCase(Common.nz(author.email)) && !Common.nz(author.name).isBlank()) return author.name;
    }
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      DirectoryNode root = storage.getRoot();
      String actual = storageString(root, "0C1F");
      String actualSmtp = storageString(root, "5D01");
      boolean sameMailbox = !email.isBlank() && (email.equalsIgnoreCase(actual) || email.equalsIgnoreCase(actualSmtp));
      boolean ownSender = transport.isBlank() && storageString(root, "0065").isBlank() && storageString(root, "5D02").isBlank();
      // The sender's name is safe only when it belongs to the selected author.
      if (sameMailbox || ownSender) return decodeMsgString(root, message, record, 0x0c1a, "sender name", storageString(root, "0C1A"));
    }
    return represented;
  }

  static Date sentDate(OutlookMessage message) {
    try {
      Date transportDate = message.getDate();
      if (transportDate != null) return transportDate;
    } catch (NullPointerException absentTransportDate) {
      // Parser 1.16.2 clones a null Date when transport headers are absent.
      // The native submission timestamp remains available independently.
    }
    return message.getClientSubmitTime();
  }

  static String nativeSentXmp(Path path) throws IOException {
    String fallback = "";
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      if (!storage.getRoot().hasEntry("__properties_version1.0")) return fallback;
      try (InputStream input = storage.getRoot().createDocumentInputStream("__properties_version1.0")) {
        if (input.readNBytes(32).length != 32) return fallback;
        Long ticks = null;
        byte[] entry; long scanned = 32;
        while ((entry = input.readNBytes(16)).length > 0) {
          scanned += entry.length;
          if (scanned > ProcessingLimits.metadataBytes()) throw new IOException("MSG property table exceeds the metadata budget");
          if (entry.length != 16) {
            for (byte padding : entry) if (padding != 0) return fallback;
            break;
          }
          var value = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN);
          if (value.getInt(0) == 0x00390040) {
            if (ticks != null) return fallback;
            ticks = value.getLong(8);
          }
        }
        if (ticks != null && ticks > 0) try {
          var instant = java.time.Instant.ofEpochSecond(ticks / 10000000 - 11644473600L, (ticks % 10000000) * 100);
          // The parser's generic Date can be a modification date, and its Date
          // representation loses submission fractions. Keep the stored instant.
          return instant.toString();
        } catch (java.time.DateTimeException | ArithmeticException unsupported) { /* Keep the existing date when the native value cannot be represented. */ }
      }
    }
    return fallback;
  }

  static String decodeMsgString(Path path, OutlookMessage message, EmailRecord record,
                                int propertyId, String field, String parsed) throws Exception {
    Object declared = message.getPropertyValue(0x3ffd);
    if (!(declared instanceof Number codePage) || codePage.intValue() <= 0) return parsed;
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      return decodeMsgString(storage.getRoot(), message, record, propertyId, field, parsed);
    }
  }

  static String decodeMsgString(DirectoryNode root, OutlookMessage message, EmailRecord record,
                                int propertyId, String field, String parsed) throws Exception {
    Object declared = message.getPropertyValue(0x3ffd);
    if (!(declared instanceof Number codePage) || codePage.intValue() <= 0) return parsed;
    String unicodeEntry = String.format(Locale.ROOT, "__substg1.0_%04X001F", propertyId);
    String ansiEntry = String.format(Locale.ROOT, "__substg1.0_%04X001E", propertyId);
    if (root.hasEntry(unicodeEntry) || !root.hasEntry(ansiEntry)) return parsed;
    Charset charset;
    try {
      charset = Charset.forName(CodePageUtil.codepageToEncoding(codePage.intValue()));
    } catch (UnsupportedEncodingException | IllegalArgumentException unsupported) {
      Common.warn(record, ConversionDiagnostic.Code.TEXT_RECOVERED, "MSG " + field + " code page " + codePage.intValue() + " is unsupported; using parser recovery.");
      return parsed;
    }
    byte[] bytes;
    try (InputStream input = root.createDocumentInputStream(ansiEntry)) {
      bytes = ProcessingLimits.read(input, Common.nz(record.sourcePath).isBlank() ? field : record.sourcePath);
    }
    try {
      return Common.stripInvalidXml(charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
    } catch (CharacterCodingException invalidText) {
      Common.warn(record, ConversionDiagnostic.Code.TEXT_RECOVERED, "MSG " + field + " contains invalid bytes for " + charset.name() + "; replacement characters were used.");
      return Common.stripInvalidXml(new String(bytes, charset));
    }
  }

  static String recipientNameKey(String address, String name) {
    return Common.nz(address).trim().toLowerCase(Locale.ROOT) + "\0" + Common.nz(name).trim();
  }

  static String recipientName(OutlookRecipient recipient, Map<String, String> names) {
    return names.getOrDefault(recipientNameKey(recipient.getAddress(), recipient.getName()), Common.nz(recipient.getName()));
  }

  static Map<String, String> decodedRecipientNames(Path path, OutlookMessage message, EmailRecord record) throws Exception {
    Map<String, String> names = new HashMap<>();
    Object declared = message.getPropertyValue(0x3ffd);
    if (!(declared instanceof Number codePage) || codePage.intValue() <= 0) return names;
    try (POIFSFileSystem storage = new POIFSFileSystem(path.toFile(), true)) {
      for (Entry entry : storage.getRoot()) {
        if (!(entry instanceof DirectoryNode recipient) || !entry.getName().startsWith("__recip_version1.0_")) continue;
        if (recipient.hasEntry("__substg1.0_3001001F") || !recipient.hasEntry("__substg1.0_3001001E")) continue;
        String parsed = storageString(recipient, "3001", java.nio.charset.StandardCharsets.ISO_8859_1);
        String address = storageString(recipient, "39FE", java.nio.charset.StandardCharsets.ISO_8859_1);
        if (address.isBlank()) address = storageString(recipient, "3003", java.nio.charset.StandardCharsets.ISO_8859_1);
        String decoded = decodeMsgString(recipient, message, record, 0x3001, "recipient name", parsed);
        names.put(recipientNameKey(address, parsed), decoded);
      }
    }
    return names;
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
      Common.warn(record, ConversionDiagnostic.Code.TEXT_RECOVERED, "MSG HTML code page " + codePage.intValue() + " is unsupported; using parser recovery.");
      return parsed;
    }
    try {
      return charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException invalidText) {
      Common.warn(record, ConversionDiagnostic.Code.TEXT_RECOVERED, "MSG HTML contains invalid bytes for " + charset.name() + "; replacement characters were used.");
      return new String(bytes, charset);
    }
  }

  static void populateMsgBody(OutlookMessage message, EmailRecord record, int nativeBody, Boolean rtfInSync, String plainText) {
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
        Common.warn(record, ConversionDiagnostic.Code.BODY_FALLBACK, warning);
      }
    }
    record.textBody = Common.normalizeBody(plainText);
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
          if (entry.length != 16) {
            // Some otherwise readable MSG exports pad their property stream
            // with zeros. Padding does not supply the optional Boolean flag.
            for (byte padding : entry) {
              if (padding != 0) throw new IOException("Invalid MSG property-table entry");
            }
            return null;
          }
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

  static void recoverAttachmentFilename(DirectoryNode attachment, OutlookMessage parent, EmailRecord record, Path path,
                                         Map<AssociatedFile, String> parsedNames) throws Exception {
    String property = "3707";
    String parsed = storageString(attachment, property, java.nio.charset.StandardCharsets.ISO_8859_1);
    if (parsed.isBlank()) {
      property = "3704";
      parsed = storageString(attachment, property, java.nio.charset.StandardCharsets.ISO_8859_1);
    }
    if (parsed.isBlank()) return;
    String decoded = Common.normalizeAttachmentFilename(decodeMsgString(attachment, parent, record,
        Integer.parseInt(property, 16), "attachment filename", parsed));
    if (decoded.equals(parsed) || !attachment.hasEntry("__substg1.0_37010102")) return;
    byte[] bytes;
    try (InputStream input = attachment.createDocumentInputStream("__substg1.0_37010102")) {
      bytes = ProcessingLimits.read(input, path.toString());
    }
    String checksum = Common.sha256Hex(bytes);
    for (AssociatedFile file : record.associatedFiles) {
      if (!"Source".equals(file.afRelationship) && parsed.equals(parsedNames.get(file))
          && checksum.equals(file.checksumSha256) && Arrays.equals(bytes, file.bytes)) file.filename = decoded;
    }
  }

  static void collectAttachedMessages(Path path, EmailRecord record, boolean debug, OutlookMessage parent) throws Exception {
    try (POIFSFileSystem original = new POIFSFileSystem(path.toFile(), true)) {
      Map<String, AssociatedFile> payloads = new LinkedHashMap<>();
      Map<AssociatedFile, String> parsedNames = new IdentityHashMap<>();
      for (AssociatedFile file : record.associatedFiles) {
        if (file.bytes != null && !"Source".equals(file.afRelationship)) {
          payloads.putIfAbsent(Common.sha256Hex(file.bytes), file);
          parsedNames.put(file, file.filename);
        }
      }
      Map<String, AssociatedFile> filenameResources = new LinkedHashMap<>();
      int sequence = 0;
      for (Entry entry : original.getRoot()) {
        if (!(entry instanceof DirectoryNode attachment) || !entry.getName().startsWith("__attach_version1.0_")) continue;
        recoverAttachmentFilename(attachment, parent, record, path, parsedNames);
        String location = storageString(attachment, "3713");
        if (location.isBlank() && attachment.hasEntry("__substg1.0_37010102")) {
          byte[] bytes;
          try (InputStream input = attachment.createDocumentInputStream("__substg1.0_37010102")) {
            bytes = ProcessingLimits.read(input, path.toString());
          }
          AssociatedFile resource = payloads.get(Common.sha256Hex(bytes));
          if (resource != null && Arrays.equals(resource.bytes, bytes)
              && Common.nz(resource.mime).startsWith("image/") && !Common.nz(resource.filename).isBlank()) {
            // Outlook HTML can refer to an embedded image by its native filename.
            // Bind only the exact stored payload; never resolve a file on disk.
            String alias = new java.net.URI(null, null, resource.filename, null).toASCIIString();
            if (!filenameResources.containsKey(alias)) filenameResources.put(alias, resource);
            else {
              AssociatedFile previous = filenameResources.get(alias);
              if (previous != null && !Arrays.equals(previous.bytes, bytes)) {
                filenameResources.put(alias, null);
                Common.warn(record, ConversionDiagnostic.Code.ATTACHMENT_REFERENCE_UNRESOLVED, "MSG attachments contain different payloads for one image filename; inline lookup was omitted.");
              }
            }
          }
        }
        if (!location.isBlank() && attachment.hasEntry("__substg1.0_37010102")) {
          String base = storageString(attachment, "3711");
          try {
            location = new java.net.URI(base).resolve(new java.net.URI(location)).toString();
          } catch (java.net.URISyntaxException invalidLocation) {
            Common.warn(record, ConversionDiagnostic.Code.ATTACHMENT_REFERENCE_UNRESOLVED, "MSG attachment has an invalid Content-Location; inline lookup was omitted.");
            location = "";
          }
          if (!location.isBlank()) {
            byte[] bytes;
            try (InputStream input = attachment.createDocumentInputStream("__substg1.0_37010102")) {
              bytes = ProcessingLimits.read(input, path.toString());
            }
            AssociatedFile resource = payloads.get(Common.sha256Hex(bytes));
            if (resource == null || !Arrays.equals(resource.bytes, bytes)) {
              Common.warn(record, ConversionDiagnostic.Code.ATTACHMENT_REFERENCE_UNRESOLVED, "MSG location resource could not be bound to a parsed attachment.");
            } else if (!record.nativeLocationResources.containsKey(location)) {
              record.nativeLocationResources.put(location, resource);
            } else {
              AssociatedFile previous = record.nativeLocationResources.get(location);
              if (previous != null && !Arrays.equals(previous.bytes, bytes)) {
                record.nativeLocationResources.put(location, null);
                Common.warn(record, ConversionDiagnostic.Code.ATTACHMENT_REFERENCE_UNRESOLVED, "MSG attachments contain different payloads for one Content-Location; inline lookup was omitted.");
              }
            }
          }
        }
        if (!attachment.hasEntry("__substg1.0_3701000D")) continue;
        Entry embedded = attachment.getEntry("__substg1.0_3701000D");
        if (!(embedded instanceof DirectoryNode message)) throw new IOException("Embedded MSG message storage is not a directory");
        AssociatedFile file = new AssociatedFile();
        file.filename = decodeMsgString(attachment, parent, record, 0x3707, "attached message filename", storageString(attachment, "3707"));
        if (file.filename.isBlank()) file.filename = decodeMsgString(attachment, parent, record, 0x3704, "attached message filename", storageString(attachment, "3704"));
        if (file.filename.isBlank()) file.filename = "attached_message_" + String.format("%03d", ++sequence) + ".msg";
        file.filename = Common.normalizeAttachmentFilename(file.filename);
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
      for (var resource : filenameResources.entrySet()) {
        if (!record.nativeLocationResources.containsKey(resource.getKey()))
          record.nativeLocationResources.put(resource.getKey(), resource.getValue());
      }
    }
  }

  static String storageString(DirectoryNode storage, String propertyId) throws IOException {
    return storageString(storage, propertyId, java.nio.charset.Charset.forName("windows-1252"));
  }

  static String storageString(DirectoryNode storage, String propertyId, Charset ansiCharset) throws IOException {
    for (String type : List.of("001F", "001E")) {
      String name = "__substg1.0_" + propertyId + type;
      if (!storage.hasEntry(name)) continue;
      try (InputStream input = storage.createDocumentInputStream(name)) {
        var charset = type.equals("001F") ? java.nio.charset.StandardCharsets.UTF_16LE : ansiCharset;
        return Common.stripInvalidXml(new String(input.readAllBytes(), charset)).trim();
      }
    }
    return "";
  }
}
