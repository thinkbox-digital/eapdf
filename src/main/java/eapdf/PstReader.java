package eapdf;
import com.pff.*;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bbottema.rtftohtml.OutlookRtfToHtmlConverter;

class PstReader {
  static List<EmailRecord> readPst(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    List<EmailRecord> out = new ArrayList<>();
    forEach(path, globalId, archiveEmails, debug, out::add);
    return out;
  }

  @FunctionalInterface interface RecordConsumer { void accept(EmailRecord record) throws Exception; }

  static void forEach(String path, String globalId, List<String> archiveEmails, boolean debug, RecordConsumer consumer) throws Exception {
    PSTFile pst = new EaPdfPstFile(path);
    try {
      int[] counts = new int[2];
      walkPstFolder(pst.getRootFolder(), "", path, globalId, archiveEmails, consumer, debug, counts);
      if (counts[1] > 0) System.out.println("PST non-email items skipped: " + counts[1]);
    } finally { pst.close(); }
  }

  static void walkPstFolder(PSTFolder f, String prefix, String sourcePath, String globalId, List<String> archiveEmails, List<EmailRecord> out, boolean debug) throws Exception {
    int[] counts = new int[2];
    walkPstFolder(f, prefix, sourcePath, globalId, archiveEmails, out, debug, counts);
    if (counts[1] > 0) System.out.println("PST non-email items skipped: " + counts[1]);
  }

  static void walkPstFolder(PSTFolder f, String prefix, String sourcePath, String globalId, List<String> archiveEmails,
                            List<EmailRecord> out, boolean debug, int[] counts) throws Exception {
    walkPstFolder(f, prefix, sourcePath, globalId, archiveEmails, out::add, debug, counts);
  }

  static void walkPstFolder(PSTFolder f, String prefix, String sourcePath, String globalId, List<String> archiveEmails,
                            RecordConsumer out, boolean debug, int[] counts) throws Exception {
    String here = prefix.isEmpty() ? Common.nz(f.getDisplayName()) : (prefix + "/" + Common.nz(f.getDisplayName()));
    if (f.getContentCount() > 0) {
      PSTMessage m;
      while ((m = (PSTMessage)f.getNextChild()) != null) {
        collectPstMessage(m, here, sourcePath, globalId, archiveEmails, out, debug, counts);
      }
    }
    if (f.hasSubfolders()) for (PSTFolder sf : f.getSubFolders()) walkPstFolder(sf, here, sourcePath, globalId, archiveEmails, out, debug, counts);
  }

  static void collectPstMessage(PSTMessage message, String folder, String sourcePath, String globalId,
                                List<String> archiveEmails, List<EmailRecord> out, boolean debug, int[] counts) throws Exception {
    collectPstMessage(message, folder, sourcePath, globalId, archiveEmails, out::add, debug, counts);
  }

  static void collectPstMessage(PSTMessage message, String folder, String sourcePath, String globalId,
                                List<String> archiveEmails, RecordConsumer out, boolean debug, int[] counts) throws Exception {
    ProcessingLimits.checkCancelled();
    ProcessingLimits.checkCount((long) ++counts[0]);
    String itemClass = Common.stripInvalidXml(Common.nz(message.getMessageClass())).trim();
    if (Common.isNonEmailOutlookItem(itemClass)) {
      counts[1]++;
      System.out.println("skipped PST item: " + sourcePath + "#" + message.getDescriptorNodeId() + " (" + itemClass + ")");
      return;
    }
    out.accept(Common.stageRecord(readMessage(message, folder, sourcePath, globalId, archiveEmails, debug, 0)));
  }

  static EmailRecord readMessage(PSTMessage m, String here, String sourcePath, String globalId, List<String> archiveEmails, boolean debug, int depth) throws Exception {
    String itemClass = Common.stripInvalidXml(Common.nz(m.getMessageClass())).trim();
    if (Common.isNonEmailOutlookItem(itemClass)) {
      throw Common.fail("PST item is not an email (" + itemClass + "): " + sourcePath + "#" + m.getDescriptorNodeId());
    }
    EmailRecord r = new EmailRecord();
    r.sourceType = "pst";
    r.sourcePath = sourcePath;
    r.folderPath = here;
    r.folderMemberships.add(here);
    r.from = "";
    r.to = "";
    r.cc = "";
    r.bcc = "";
    r.rawFrom = "";
    r.rawTo = "";
    r.rawCc = "";
    r.rawBcc = "";
    r.rawDateHeader = "";
    r.sentXmp = "";
    r.messageId = "";
    r.subject = Common.nz(m.getSubject()).replace("\0", "");
    populatePstHeadersAndRecipients(m, r);
    if (r.fromAgents.isEmpty()) {
      // MAPI's represented sender is the From author; the actual sender may be a delegate.
      String name = Common.stripInvalidXml(Common.nz(m.getSentRepresentingName())).trim();
      String email = Common.stripInvalidXml(Common.nz(m.getSentRepresentingEmailAddress())).trim();
      // A display name alone cannot replace the existing sender address.
      if (email.isBlank()) {
        name = Common.nz(m.getSenderName());
        email = Common.nz(m.getSenderEmailAddress());
      }
      Common.addAgent(r.fromAgents, name, nativeMailbox(email));
    }
    populatePstRecipients(m, r);
    r.from = Common.joinAgents(r.fromAgents);
    if (r.from.isBlank()) r.from = Common.joinAddr(Common.nz(m.getSenderName()), Common.nz(m.getSenderEmailAddress()));
    r.to = Common.joinAgents(r.toAgents);
    r.cc = Common.joinAgents(r.ccAgents);
    r.bcc = Common.joinAgents(r.bccAgents);
    if (r.rawFrom.isBlank()) r.rawFrom = !Common.rfc822AddrList(r.fromAgents).isBlank() ? Common.rfc822AddrList(r.fromAgents) : r.from;
    if (r.rawTo.isBlank()) r.rawTo = !Common.rfc822AddrList(r.toAgents).isBlank() ? Common.rfc822AddrList(r.toAgents) : pstDisplayHeader(m.getDisplayTo());
    if (r.rawCc.isBlank()) r.rawCc = !Common.rfc822AddrList(r.ccAgents).isBlank() ? Common.rfc822AddrList(r.ccAgents) : pstDisplayHeader(m.getDisplayCC());
    if (r.rawBcc.isBlank()) r.rawBcc = !Common.rfc822AddrList(r.bccAgents).isBlank() ? Common.rfc822AddrList(r.bccAgents) : pstDisplayHeader(m.getDisplayBCC());
    populatePstSentDate(r, m.getClientSubmitTime(), m.getMessageDeliveryTime());
    preservePstMessageId(r, m.getInternetMessageId());
    if (r.inReplyTo.isEmpty() && !Common.nz(m.getInReplyToId()).isBlank()) r.inReplyTo.add(m.getInReplyToId());
    if (r.references.isEmpty()) {
      String references = Common.nz(EaPdfPstProperties.internetReferences(m));
      if (!references.isBlank()) r.references.add(references);
    }
    r.archiveGlobalId = Common.nz(globalId);
    r.archiveEmails.addAll(archiveEmails);

    String bodyText = Common.normalizeBody(m.getBody());
    String bodyHtml = Common.normalizeHtml(m.getBodyHTML());
    int nativeBody = m.getNativeBodyType();
    String bodyRtf = nativeBody == 2 || (bodyText.isBlank() && bodyHtml.isBlank())
        ? Common.nz(EaPdfPstProperties.boundedRtf(m, ProcessingLimits.messageBytes(), ProcessingLimits.bodyCharacters())) : "";
    populatePstBody(r, bodyText, bodyHtml, bodyRtf, nativeBody);

    collectPstAttachments(m, r, depth);
    r.sourceBytes = null;
    r.sourceFilename = "";
    r.sourceMime = "";
    r.afRelationship = "";
    normalizeExportedAddressHeaders(r);
    Common.finalizeRecordMetadata(r);

    if (debug) {
      Common.dbg(r, "PST subject=" + r.subject);
      Common.dbg(r, "PST text len=" + r.textBody.length() + " html len=" + r.htmlBody.length());
    }

    return r;
  }

  static void normalizeExportedAddressHeaders(EmailRecord record) throws Exception {
    // XMP compares with the reconstructed RFC822 source. Original native header bytes remain in the Supplement.
    record.rawFrom = Common.encodeAddressHeader(Common.nz(record.rawFrom));
    record.rawTo = Common.encodeAddressHeader(Common.nz(record.rawTo));
    record.rawCc = Common.encodeAddressHeader(Common.nz(record.rawCc));
    record.rawBcc = Common.encodeAddressHeader(Common.nz(record.rawBcc));
    record.rawReplyTo = Common.encodeAddressHeader(Common.nz(record.rawReplyTo));
  }

  static String pstDisplayHeader(String value) {
    String text = Common.nz(value);
    int end = text.length();
    // ANSI MAPI display strings may include their storage terminators.
    while (end > 0 && text.charAt(end - 1) == '\0') end--;
    return text.substring(0, end);
  }

  static String addressHeader(MimeMessage message, String field) throws jakarta.mail.MessagingException {
    // Outlook can store repeated recipient fields. The reconstructed email must
    // retain the same complete address list returned by getRecipients().
    return Common.nz(message.getHeader(field, ", "));
  }

  static void populatePstRecipients(PSTMessage m, EmailRecord r) throws Exception {
    if (!r.toAgents.isEmpty() && !r.ccAgents.isEmpty() && !r.bccAgents.isEmpty()) return;
    EmailRecord recovered = new EmailRecord();
    int n = m.getNumberOfRecipients();
    for (int i = 0; i < n; i++) {
      PSTRecipient rcpt = m.getRecipient(i);
      String email = Common.nz(rcpt.getSmtpAddress());
      if (email.isBlank()) email = Common.nz(rcpt.getEmailAddress());
      int type = rcpt.getRecipientType();
      if (type == PSTRecipient.MAPI_TO) addMissingRecipient(recovered.toAgents, Common.nz(rcpt.getDisplayName()), email);
      else if (type == PSTRecipient.MAPI_CC) addMissingRecipient(recovered.ccAgents, Common.nz(rcpt.getDisplayName()), email);
      else if (type == PSTRecipient.MAPI_BCC) addMissingRecipient(recovered.bccAgents, Common.nz(rcpt.getDisplayName()), email);
    }
    recoverMissingRecipientGroups(r, recovered);
  }

  static void recoverMissingRecipientGroups(EmailRecord record, EmailRecord recovered) {
    if (record.toAgents.isEmpty()) record.toAgents.addAll(recovered.toAgents);
    if (record.ccAgents.isEmpty()) record.ccAgents.addAll(recovered.ccAgents);
    if (record.bccAgents.isEmpty()) record.bccAgents.addAll(recovered.bccAgents);
  }

  static void addMissingRecipient(List<MailAgent> recipients, String name, String email) {
    email = nativeMailbox(email);
    for (MailAgent existing : recipients) {
      if (!email.isBlank() && email.equalsIgnoreCase(Common.nz(existing.email))) return;
      if (email.isBlank() && Common.nz(existing.email).isBlank() && name.equalsIgnoreCase(Common.nz(existing.name))) return;
    }
    Common.addAgent(recipients, name, email);
  }

  static String nativeMailbox(String value) {
    String original = Common.nz(value);
    // Native MAPI properties sometimes contain a full display-name address.
    // Use the same mailbox as the reconstructed RFC822 header, without
    // repairing malformed addresses or changing quoted local-part bytes.
    if (original.indexOf('<') >= 0 && original.indexOf('>') >= 0
        && original.indexOf('\r') < 0 && original.indexOf('\n') < 0) {
      try {
        var parsed = jakarta.mail.internet.InternetAddress.parse(original, true);
        if (parsed.length == 1 && !parsed[0].isGroup()) {
          parsed[0].validate();
          if (Common.smtpMailbox(parsed[0].getAddress())) return parsed[0].getAddress();
        }
      } catch (jakarta.mail.internet.AddressException malformed) {
        // Preserve unsupported source values for the existing diagnostics.
      }
    }
    return original;
  }

  static void populatePstSentDate(EmailRecord record, Date submitted, Date delivered) {
    Date sent = Common.parseMessageDate(record.rawDateHeader);
    boolean nativeDate = sent == null;
    if (sent == null) sent = submitted != null ? submitted : delivered;
    if (nativeDate && sent != null && Math.floorMod(sent.getTime(), 1000) != 0) {
      Common.warn(record, ConversionDiagnostic.Code.DATE_PRECISION_REDUCED, "Reduced date precision: native PST sent time " + sent.toInstant()
          + " is represented at whole-second precision in the reconstructed email and Sent metadata.");
    }
    record.date = Common.fmtDate(sent);
    record.sentXmp = Common.xmpDate(sent);
    if (Common.nz(record.rawDateHeader).isBlank()) record.rawDateHeader = Common.rfc822Date(sent);
  }

  static void preservePstMessageId(EmailRecord record, String dedicatedId) {
    String value = Common.nz(dedicatedId).trim();
    if (value.isBlank()) return;
    if (!Common.nz(record.messageId).isBlank() && !value.equals(record.messageId)) {
      String warning = "PST Message-ID differs from transport header; using dedicated property " + value + "; original header: " + record.messageId;
      Common.warn(record, ConversionDiagnostic.Code.HEADER_CONFLICT, warning);
    }
    record.messageId = value;
  }

  static void populatePstHeadersAndRecipients(PSTMessage m, EmailRecord r) throws Exception {
    String headers = Common.nz(m.getTransportMessageHeaders());
    if (headers.isBlank()) return;
    AssociatedFile originalHeaders = new AssociatedFile();
    originalHeaders.filename = "transport-headers.txt";
    originalHeaders.mime = "text/plain";
    originalHeaders.afRelationship = "Supplement";
    originalHeaders.sourceSetMember = true;
    originalHeaders.description = "Native PST transport message header property, stored as UTF-8 text";
    originalHeaders.bytes = headers.getBytes(StandardCharsets.UTF_8);
    ProcessingLimits.checkMessage(originalHeaders.bytes.length, "PST transport headers");
    r.associatedFiles.add(originalHeaders);
    byte[] rawHeaders = (headers + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
    Session s = EmlReader.mimeSession(rawHeaders);
    try (ByteArrayInputStream in = new ByteArrayInputStream(rawHeaders)) {
      MimeMessage mm = new MimeMessage(s, in);
      r.rawFrom = addressHeader(mm, "From");
      r.rawTo = addressHeader(mm, "To");
      r.rawCc = addressHeader(mm, "Cc");
      r.rawBcc = addressHeader(mm, "Bcc");
      r.rawReplyTo = addressHeader(mm, "Reply-To");
      r.rawDateHeader = Common.header1(mm, "Date");
      r.inReplyTo.addAll(Common.headerValues(mm, "In-Reply-To"));
      r.references.addAll(Common.headerValues(mm, "References"));
      if (!Common.nz(r.rawDateHeader).isBlank()) {
        try {
          Date sent = mm.getSentDate();
          if (sent != null) r.sentXmp = Common.xmpDate(sent);
        } catch (Exception ignored) {}
      }
      if (!Common.nz(Common.header1(mm, "Message-ID")).isBlank()) r.messageId = Common.header1(mm, "Message-ID");
      addHeaderAgents(r.fromAgents, r.rawFrom, () -> mm.getFrom());
      addHeaderAgents(r.toAgents, r.rawTo, () -> mm.getRecipients(jakarta.mail.Message.RecipientType.TO));
      addHeaderAgents(r.ccAgents, r.rawCc, () -> mm.getRecipients(jakarta.mail.Message.RecipientType.CC));
      addHeaderAgents(r.bccAgents, r.rawBcc, () -> mm.getRecipients(jakarta.mail.Message.RecipientType.BCC));
    }
  }

  interface AddressSupplier {
    jakarta.mail.Address[] get() throws Exception;
  }

  static void addHeaderAgents(List<MailAgent> out, String rawHeader, AddressSupplier supplier) {
    try {
      jakarta.mail.Address[] addrs = supplier.get();
      if (addrs != null) {
        out.addAll(Common.internetAddressList(addrs));
      }
      if (!out.isEmpty()) return;
    } catch (Exception ignored) {}

    String raw = Common.nz(rawHeader);
    if (raw.isBlank()) return;
    try {
      InternetAddress[] parsed = InternetAddress.parseHeader(raw, false);
      out.addAll(Common.internetAddressList(parsed));
      return;
    } catch (Exception ignored) {}

    for (String part : raw.split("\\s*,\\s*")) {
      String token = Common.nz(part).trim();
      if (token.isBlank()) continue;
      Common.addAgent(out, "", token);
    }
  }

  static void populatePstBody(EmailRecord record, String bodyText, String bodyHtml, String bodyRtf) {
    populatePstBody(record, bodyText, bodyHtml, bodyRtf, 0);
  }

  static void populatePstBody(EmailRecord record, String bodyText, String bodyHtml, String bodyRtf, int nativeBody) {
    ProcessingLimits.checkBody(bodyText, record.subject);
    ProcessingLimits.checkBody(bodyHtml, record.subject);
    ProcessingLimits.checkBody(bodyRtf, record.subject);
    boolean hasText = !Common.nz(bodyText).isBlank();
    boolean hasHtml = !Common.nz(bodyHtml).isBlank();
    boolean hasRtf = !Common.nz(bodyRtf).isBlank();
    int selected;
    if (nativeBody == 1 && hasText) selected = 1;
    else if (nativeBody == 2 && hasRtf) selected = 2;
    else if (nativeBody == 3 && hasHtml) selected = 3;
    else selected = hasHtml ? 3 : hasText ? 1 : hasRtf ? 2 : 1;
    if (nativeBody >= 1 && nativeBody <= 3 && nativeBody != selected) {
      Common.warn(record, ConversionDiagnostic.Code.BODY_FALLBACK, "Declared PST native body " + nativeBody + " is unavailable; rendering available body " + selected);
    }
    record.bodyMime = selected == 3 ? "text/html" : selected == 2 ? "text/rtf" : "text/plain";
    record.bodyMime += "; charset=UTF-8";
    if (selected == 1) bodyHtml = "";
    if (selected == 2) {
      record.rtfBody = bodyRtf;
      try {
        if (!bodyRtf.stripLeading().startsWith("{\\rtf")) throw new IllegalArgumentException("Invalid RTF body");
        bodyHtml = new OutlookRtfToHtmlConverter().toHtml(bodyRtf);
        if (Common.nz(bodyHtml).isBlank()) {
          // A real PST contains a valid fromhtml RTF wrapper with no body.
          // Independently decode text before deciding whether rendering lost it.
          if (bodyRtf.matches("(?s).*\\\\(?:pict|object)\\b.*"))
            throw new IllegalArgumentException("RTF image/object body could not be rendered");
          var editor = new javax.swing.text.rtf.RTFEditorKit();
          var document = editor.createDefaultDocument();
          editor.read(new java.io.StringReader(bodyRtf), document, 0);
          String recovered = document.getText(0, document.getLength());
          if (recovered.isBlank() && hasText) recovered = bodyText;
          bodyHtml = Common.plainTextToHtml(recovered);
          Common.warn(record, recovered.isBlank()
              ? "RTF source contains no visible text; original RTF retained."
              : "RTF HTML rendering was empty; recovered plain text displayed and original RTF retained.");
        }
        if (!hasText) bodyText = Common.stripHtml(bodyHtml);
      } catch (RuntimeException | java.io.IOException | javax.swing.text.BadLocationException error) {
        throw Common.fail("Cannot convert RTF body for PST email '" + Common.nz(record.subject) + "': " + error.getMessage());
      }
    }
    record.textBody = Common.normalizeBody(bodyText);
    record.htmlBody = !Common.nz(bodyHtml).isBlank() ? Common.normalizeHtml(bodyHtml) : Common.plainTextToHtml(record.textBody);
  }

  static void collectPstAttachments(PSTMessage m, EmailRecord r) throws Exception {
    collectPstAttachments(m, r, 0);
  }

  static void collectPstAttachments(PSTMessage m, EmailRecord r, int depth) throws Exception {
    if (depth > 32) throw Common.fail("PST attached-email nesting exceeds 32 levels");
    int count = m.getNumberOfAttachments();
    for (int i = 0; i < count; i++) {
      PSTAttachment a0 = m.getAttachment(i);
      AssociatedFile af = new AssociatedFile();
      String longFilename = Common.normalizeAttachmentFilename(a0.getLongFilename());
      af.filename = !longFilename.isBlank() ? longFilename : Common.normalizeAttachmentFilename(a0.getFilename());
      boolean unnamed = Common.nz(af.filename).isBlank();
      String cid = Common.nz(a0.getContentId());
      af.inline = !cid.isBlank() || a0.isAttachmentInvisibleInHtml() || a0.isAttachmentMhtmlRef();
      if (Common.nz(af.filename).isBlank()) af.filename = (af.inline ? "inline" : "attachment") + Common.extensionForMime(Common.nz(a0.getMimeTag()), af.filename);
      af.mime = Common.detectMimeType(af.filename, Common.nz(a0.getMimeTag()), null);
      af.rawContentType = Common.nz(a0.getMimeTag()).replace("\0", "").trim();
      af.afRelationship = af.inline ? "Supplement" : "Data";
      af.description = af.inline ? "Inline Outlook related content" : "Original PST attachment";
      af.contentId = cid;
      // Every native PST attachment is named in the reconstructed MIME source.
      // A Content-ID does not stop a named file from being an attachment (the
      // EML reader and both validators use the same attachment-count rule).
      af.emailAttachment = true;
      af.embedInPdf = true;
      int method = a0.getAttachMethod();
      if (method == PSTAttachment.ATTACHMENT_METHOD_EMBEDDED) {
        PSTMessage embedded = a0.getEmbeddedPSTMessage();
        if (embedded == null) {
          Common.sourceProblem(r, "Embedded PST email is missing: " + af.filename);
          continue;
        }
        EmailRecord child = readMessage(embedded, r.folderPath, r.sourcePath, r.archiveGlobalId, r.archiveEmails, false, depth + 1);
        Common.requireCreationDate(child);
        for (String problem : child.sourceProblems)
          Common.sourceProblem(r, "Attached email '" + af.filename + "': " + problem);
        af.bytes = Common.synthesizeEml(child);
        if (unnamed) af.filename = Common.safeFilename(Common.nz(child.subject).isBlank() ? "attached-email" : child.subject) + ".eml";
        if (!af.filename.toLowerCase(Locale.ROOT).endsWith(".eml")) af.filename = af.filename.replaceFirst("(?i)\\.msg$", "") + ".eml";
        af.mime = "message/rfc822";
        af.rawContentType = af.mime;
        af.inline = false;
        af.emailAttachment = true;
        af.afRelationship = "Data";
        af.description = "Attached PST email reconstructed as RFC 822 with headers, body, and nested attachments";
        for (AssociatedFile companion : child.associatedFiles) {
          if (!companion.sourceSetMember) continue;
          companion.filename = "attached-" + (i + 1) + "-" + companion.filename;
          companion.description += "; attached email: " + af.filename;
          r.associatedFiles.add(companion);
        }
      } else if (method == PSTAttachment.ATTACHMENT_METHOD_BY_VALUE || method == PSTAttachment.ATTACHMENT_METHOD_OLE) {
        if (!EaPdfPstProperties.hasAttachmentPayload(a0)) {
          Common.sourceProblem(r, "Attachment data could not be recovered from the original PST file: "
              + (unnamed ? "unnamed attachment" : af.filename) + " in " + r.sourcePath + "#" + m.getDescriptorNodeId()
              + " (attachment " + (i + 1) + "). This attachment could not be included.");
          continue;
        }
        try (InputStream in = a0.getFileInputStream()) {
          af.bytes = Common.readAllBytes(in);
        }
      } else {
        Common.sourceProblem(r, "PST attachment could not be extracted (method " + method + "): " + af.filename);
        continue;
      }
      if (af.bytes == null) throw Common.fail("Missing PST attachment data: " + af.filename);
      normalizeAttachmentContentType(r, af);
      long size = af.bytes.length;
      for (AssociatedFile existing : r.associatedFiles) size += existing.size();
      ProcessingLimits.checkMessage(size, r.subject);
      af.checksumMd5 = Common.md5Hex(af.bytes);
      af.checksumSha256 = Common.sha256Hex(af.bytes);
      r.associatedFiles.add(af);
    }
  }
  static void normalizeAttachmentContentType(EmailRecord record, AssociatedFile file) {
    String storedType = Common.sourceContentType(file);
    boolean opaque;
    try {
      opaque = new jakarta.mail.internet.ContentType(storedType).getBaseType()
          .toLowerCase(Locale.ROOT).startsWith("multipart/");
    } catch (jakarta.mail.internet.ParseException invalidType) {
      opaque = true;
    }
    if (!opaque) return;
    // Outlook can retain a wrapper or malformed type on an extracted payload.
    // Preserve the payload bytes and record the original label as evidence.
    file.mime = "application/octet-stream";
    file.rawContentType = file.mime;
    file.description += "; stored PST MIME type: " + storedType;
    Common.warn(record, ConversionDiagnostic.Code.ATTACHMENT_TYPE_RECOVERED, "PST attachment '" + file.filename + "' has stored MIME type " + storedType
        + "; exported as application/octet-stream with unchanged payload bytes.");
  }

}
