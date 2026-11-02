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
    PSTFile pst = new PSTFile(path);
    try {
      PSTFolder root = pst.getRootFolder();
      List<EmailRecord> out = new ArrayList<>();
      walkPstFolder(root, "", path, globalId, archiveEmails, out, debug);
      return out;
    } finally {
      pst.close();
    }
  }

  static void walkPstFolder(PSTFolder f, String prefix, String sourcePath, String globalId, List<String> archiveEmails, List<EmailRecord> out, boolean debug) throws Exception {
    String here = prefix.isEmpty() ? Common.nz(f.getDisplayName()) : (prefix + "/" + Common.nz(f.getDisplayName()));
    if (f.getContentCount() > 0) {
      PSTMessage m;
      while ((m = (PSTMessage)f.getNextChild()) != null) {
        ProcessingLimits.checkCount((long)out.size() + 1);
        out.add(Common.stageRecord(readMessage(m, here, sourcePath, globalId, archiveEmails, debug, 0)));
      }
    }
    if (f.hasSubfolders()) for (PSTFolder sf : f.getSubFolders()) walkPstFolder(sf, here, sourcePath, globalId, archiveEmails, out, debug);
  }

  static EmailRecord readMessage(PSTMessage m, String here, String sourcePath, String globalId, List<String> archiveEmails, boolean debug, int depth) throws Exception {
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
    if (r.fromAgents.isEmpty()) Common.addAgent(r.fromAgents, Common.nz(m.getSenderName()), Common.nz(m.getSenderEmailAddress()));
    populatePstRecipients(m, r);
    r.from = Common.joinAgents(r.fromAgents);
    if (r.from.isBlank()) r.from = Common.joinAddr(Common.nz(m.getSenderName()), Common.nz(m.getSenderEmailAddress()));
    r.to = Common.joinAgents(r.toAgents);
    r.cc = Common.joinAgents(r.ccAgents);
    r.bcc = Common.joinAgents(r.bccAgents);
    if (r.rawFrom.isBlank()) r.rawFrom = !Common.rfc822AddrList(r.fromAgents).isBlank() ? Common.rfc822AddrList(r.fromAgents) : r.from;
    if (r.rawTo.isBlank()) r.rawTo = !Common.rfc822AddrList(r.toAgents).isBlank() ? Common.rfc822AddrList(r.toAgents) : Common.nz(m.getDisplayTo());
    if (r.rawCc.isBlank()) r.rawCc = !Common.rfc822AddrList(r.ccAgents).isBlank() ? Common.rfc822AddrList(r.ccAgents) : Common.nz(m.getDisplayCC());
    if (r.rawBcc.isBlank()) r.rawBcc = !Common.rfc822AddrList(r.bccAgents).isBlank() ? Common.rfc822AddrList(r.bccAgents) : Common.nz(m.getDisplayBCC());
    populatePstSentDate(r, m.getClientSubmitTime(), m.getMessageDeliveryTime());
    preservePstMessageId(r, m.getInternetMessageId());
    if (r.inReplyTo.isEmpty() && !Common.nz(m.getInReplyToId()).isBlank()) r.inReplyTo.add(m.getInReplyToId());
    r.archiveGlobalId = Common.nz(globalId);
    r.archiveEmails.addAll(archiveEmails);

    String bodyText = Common.normalizeBody(m.getBody());
    String bodyHtml = Common.normalizeHtml(m.getBodyHTML());
    String bodyRtf = bodyText.isBlank() && bodyHtml.isBlank() ? Common.nz(m.getRTFBody()) : "";
    populatePstBody(r, bodyText, bodyHtml, bodyRtf);

    collectPstAttachments(m, r, depth);
    r.sourceBytes = null;
    r.sourceFilename = "";
    r.sourceMime = "";
    r.afRelationship = "";
    Common.finalizeRecordMetadata(r);

    if (debug) {
      Common.dbg(r, "PST subject=" + r.subject);
      Common.dbg(r, "PST text len=" + r.textBody.length() + " html len=" + r.htmlBody.length());
    }

    return r;
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
    for (MailAgent existing : recipients) {
      if (!email.isBlank() && email.equalsIgnoreCase(Common.nz(existing.email))) return;
      if (email.isBlank() && Common.nz(existing.email).isBlank() && name.equalsIgnoreCase(Common.nz(existing.name))) return;
    }
    Common.addAgent(recipients, name, email);
  }

  static void populatePstSentDate(EmailRecord record, Date submitted, Date delivered) {
    Date sent = Common.parseMessageDate(record.rawDateHeader);
    if (sent == null) sent = submitted != null ? submitted : delivered;
    record.date = Common.fmtDate(sent);
    record.sentXmp = Common.xmpDate(sent);
    if (Common.nz(record.rawDateHeader).isBlank()) record.rawDateHeader = Common.rfc822Date(sent);
  }

  static void preservePstMessageId(EmailRecord record, String dedicatedId) {
    String value = Common.nz(dedicatedId).trim();
    if (value.isBlank()) return;
    if (!Common.nz(record.messageId).isBlank() && !value.equals(record.messageId)) {
      String warning = "PST Message-ID differs from transport header; using dedicated property " + value + "; original header: " + record.messageId;
      record.conversionWarnings.add(warning);
      System.err.println("warning: " + warning);
    }
    record.messageId = value;
  }

  static void populatePstHeadersAndRecipients(PSTMessage m, EmailRecord r) throws Exception {
    String headers = Common.nz(m.getTransportMessageHeaders());
    if (headers.isBlank()) return;
    Session s = Session.getInstance(new Properties());
    try (ByteArrayInputStream in = new ByteArrayInputStream((headers + "\r\n\r\n").getBytes(StandardCharsets.UTF_8))) {
      MimeMessage mm = new MimeMessage(s, in);
      r.rawFrom = Common.header1(mm, "From");
      r.rawTo = Common.header1(mm, "To");
      r.rawCc = Common.header1(mm, "Cc");
      r.rawBcc = Common.header1(mm, "Bcc");
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
        for (var a : addrs) {
          if (a instanceof InternetAddress ia) Common.addAgent(out, Common.nz(ia.getPersonal()), Common.nz(ia.getAddress()));
          else Common.addAgent(out, "", Common.nz(a == null ? "" : a.toString()));
        }
      }
      if (!out.isEmpty()) return;
    } catch (Exception ignored) {}

    String raw = Common.nz(rawHeader);
    if (raw.isBlank()) return;
    try {
      InternetAddress[] parsed = InternetAddress.parseHeader(raw, false);
      for (InternetAddress ia : parsed) Common.addAgent(out, Common.nz(ia.getPersonal()), Common.nz(ia.getAddress()));
      if (!out.isEmpty()) return;
    } catch (Exception ignored) {}

    for (String part : raw.split("\\s*,\\s*")) {
      String token = Common.nz(part).trim();
      if (token.isBlank()) continue;
      Common.addAgent(out, "", token);
    }
  }

  static void populatePstBody(EmailRecord record, String bodyText, String bodyHtml, String bodyRtf) {
    ProcessingLimits.checkBody(bodyText, record.subject);
    ProcessingLimits.checkBody(bodyHtml, record.subject);
    ProcessingLimits.checkBody(bodyRtf, record.subject);
    record.bodyMime = !Common.nz(bodyHtml).isBlank() ? "text/html"
        : Common.nz(bodyText).isBlank() && !Common.nz(bodyRtf).isBlank() ? "text/rtf" : "text/plain";
    record.bodyMime += "; charset=UTF-8";
    if (Common.nz(bodyText).isBlank() && Common.nz(bodyHtml).isBlank() && !Common.nz(bodyRtf).isBlank()) {
      record.rtfBody = bodyRtf;
      try {
        if (!bodyRtf.stripLeading().startsWith("{\\rtf")) throw new IllegalArgumentException("Invalid RTF body");
        bodyHtml = new OutlookRtfToHtmlConverter().toHtml(bodyRtf);
        if (Common.nz(bodyHtml).isBlank()) throw new IllegalArgumentException("RTF conversion produced no body");
        bodyText = Common.stripHtml(bodyHtml);
      } catch (RuntimeException error) {
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
      af.emailAttachment = !af.inline;
      af.embedInPdf = true;
      int method = a0.getAttachMethod();
      if (method == PSTAttachment.ATTACHMENT_METHOD_EMBEDDED) {
        PSTMessage embedded = a0.getEmbeddedPSTMessage();
        if (embedded == null) throw Common.fail("Missing embedded PST email: " + af.filename);
        EmailRecord child = readMessage(embedded, r.folderPath, r.sourcePath, r.archiveGlobalId, r.archiveEmails, false, depth + 1);
        af.bytes = Common.synthesizeEml(child);
        if (unnamed) af.filename = Common.safeFilename(Common.nz(child.subject).isBlank() ? "attached-email" : child.subject) + ".eml";
        if (!af.filename.toLowerCase(Locale.ROOT).endsWith(".eml")) af.filename = af.filename.replaceFirst("(?i)\\.msg$", "") + ".eml";
        af.mime = "message/rfc822";
        af.rawContentType = af.mime;
        af.inline = false;
        af.emailAttachment = true;
        af.afRelationship = "Data";
        af.description = "Attached PST email reconstructed as RFC 822 with headers, body, and nested attachments";
      } else if (method == PSTAttachment.ATTACHMENT_METHOD_BY_VALUE || method == PSTAttachment.ATTACHMENT_METHOD_OLE) {
        try (InputStream in = a0.getFileInputStream()) {
          af.bytes = Common.readAllBytes(in);
        }
      } else {
        throw Common.fail("Unsupported PST attachment method " + method + ": " + af.filename + " in email '" + r.subject + "'");
      }
      if (af.bytes == null) throw Common.fail("Missing PST attachment data: " + af.filename);
      long size = af.bytes.length;
      for (AssociatedFile existing : r.associatedFiles) size += existing.size();
      ProcessingLimits.checkMessage(size, r.subject);
      af.checksumMd5 = Common.md5Hex(af.bytes);
      af.checksumSha256 = Common.sha256Hex(af.bytes);
      r.associatedFiles.add(af);
    }
  }
}
