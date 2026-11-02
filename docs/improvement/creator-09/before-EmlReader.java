package eapdf;
import jakarta.mail.*;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeUtility;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class EmlReader {
  static Session mimeSession(byte[] raw) {
    int end = 0;
    while (end < raw.length) {
      int start = end;
      while (end < raw.length && raw[end] != '\r' && raw[end] != '\n') end++;
      if (end == start) break;
      if (end < raw.length && raw[end] == '\r') end++;
      if (end < raw.length && raw[end] == '\n') end++;
    }
    boolean utf8 = true;
    try {
      StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw, 0, end));
    } catch (CharacterCodingException legacyHeaders) {
      // Keep the existing byte mapping for headers that are not valid UTF-8.
      utf8 = false;
    }
    Properties properties = new Properties();
    properties.setProperty("mail.mime.allowutf8", Boolean.toString(utf8));
    return Session.getInstance(properties);
  }

  static EmailRecord readEml(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    ProcessingLimits.checkMessage(Files.size(Paths.get(path)), path);
    byte[] raw;
    try (InputStream input = Files.newInputStream(Paths.get(path))) {
      raw = ProcessingLimits.read(input, path);
    }
    String sourceName = Paths.get(path).getFileName().toString();
    return readRfc822Bytes(raw, path, sourceName, globalId, archiveEmails, debug);
  }

  static EmailRecord readRfc822Bytes(byte[] raw, String sourcePath, String sourceFilename, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    ProcessingLimits.checkMessage(raw.length, sourcePath);
    Session s = mimeSession(raw);
    try (InputStream in = new ByteArrayInputStream(raw)) {
      MimeMessage mm = new MimeMessage(s, in);
      checkMimeStructure(mm, 0, new long[]{0, 0});
      EmailRecord r = new EmailRecord();
      r.sourceType = "eml";
      r.sourcePath = Common.nz(sourcePath);
      r.folderPath = "";
      r.subject = Common.nz(mm.getSubject());
      r.rawFrom = Common.header1(mm, "From");
      r.rawTo = Common.header1(mm, "To");
      r.rawCc = Common.header1(mm, "Cc");
      r.rawBcc = Common.header1(mm, "Bcc");
      populateAddressMetadata(mm, r);
      r.rawDateHeader = Common.header1(mm, "Date");
      r.date = Common.fmtDate(mm.getSentDate());
      r.sentXmp = Common.xmpDate(mm.getSentDate());
      r.messageId = Common.header1(mm, "Message-ID");
      r.inReplyTo.addAll(Common.headerValues(mm, "In-Reply-To"));
      r.references.addAll(Common.headerValues(mm, "References"));
      r.archiveGlobalId = Common.nz(globalId);
      r.archiveEmails.addAll(archiveEmails);

      if (debug) dumpMimeStructure(mm, r, "");

      BodyChoice choice = extractBodyChoice(mm, r, debug);
      ProcessingLimits.checkBody(choice.text, sourcePath);
      ProcessingLimits.checkBody(choice.html, sourcePath);
      r.textBody = Common.normalizeBody(choice.text);
      r.bodyMime = choice.mime.isBlank() ? "text/plain" : choice.mime;
      r.htmlBody = !Common.nz(choice.html).isBlank() ? Common.normalizeHtml(choice.html) : Common.plainTextToHtml(r.textBody);

      r.sourceBytes = raw;
      r.sourceFilename = !Common.nz(sourceFilename).isBlank() ? sourceFilename : (Common.safeFileStem(Common.nz(r.subject).isBlank() ? "message" : r.subject) + ".eml");
      r.sourceMime = "message/rfc822";
      r.afRelationship = "Source";

      AssociatedFile src = new AssociatedFile();
      src.filename = r.sourceFilename;
      src.mime = r.sourceMime;
      src.afRelationship = "Source";
      src.bytes = raw;
      src.description = "Original RFC 822 source";
      src.checksumMd5 = Common.md5Hex(src.bytes);
      src.checksumSha256 = Common.sha256Hex(src.bytes);
      src.emailAttachment = false;
      r.associatedFiles.add(src);

      collectRfc822AssociatedFiles(mm, r.associatedFiles, r, debug);
      Common.finalizeRecordMetadata(r);

      if (debug) {
        Common.dbg(r, "RFC822 subject=" + r.subject);
        Common.dbg(r, "RFC822 raw date=" + r.rawDateHeader);
        Common.dbg(r, "RFC822 normalized text len=" + r.textBody.length());
        Common.dbg(r, "RFC822 normalized html len=" + r.htmlBody.length());
        Common.dbg(r, "RFC822 associated files=" + r.associatedFiles.size());
      }

      return r;
    }
  }

  static void populateAddressMetadata(MimeMessage mm, EmailRecord r) throws Exception {
    r.fromAgents = preferredAgents(r.rawFrom, () -> mm.getFrom());
    r.toAgents = preferredAgents(r.rawTo, () -> mm.getRecipients(Message.RecipientType.TO));
    r.ccAgents = preferredAgents(r.rawCc, () -> mm.getRecipients(Message.RecipientType.CC));
    r.bccAgents = preferredAgents(r.rawBcc, () -> mm.getRecipients(Message.RecipientType.BCC));

    if (Common.nz(r.rawFrom).isBlank()) r.rawFrom = Common.rfc822AddrList(r.fromAgents);
    if (Common.nz(r.rawTo).isBlank()) r.rawTo = Common.rfc822AddrList(r.toAgents);
    if (Common.nz(r.rawCc).isBlank()) r.rawCc = Common.rfc822AddrList(r.ccAgents);
    if (Common.nz(r.rawBcc).isBlank()) r.rawBcc = Common.rfc822AddrList(r.bccAgents);

    r.from = !Common.addrListLenient(r.rawFrom).isBlank() ? Common.addrListLenient(r.rawFrom) : Common.joinAgents(r.fromAgents);
    r.to = !Common.addrListLenient(r.rawTo).isBlank() ? Common.addrListLenient(r.rawTo) : Common.joinAgents(r.toAgents);
    r.cc = !Common.addrListLenient(r.rawCc).isBlank() ? Common.addrListLenient(r.rawCc) : Common.joinAgents(r.ccAgents);
    r.bcc = !Common.addrListLenient(r.rawBcc).isBlank() ? Common.addrListLenient(r.rawBcc) : Common.joinAgents(r.bccAgents);
  }

  interface AddressSupplier {
    Address[] get() throws Exception;
  }

  static List<MailAgent> preferredAgents(String rawHeader, AddressSupplier supplier) {
    List<MailAgent> out = new ArrayList<>();
    try {
      out = Common.internetAddressList(supplier.get());
    } catch (Exception ignored) {
    }
    if (!out.isEmpty()) return out;
    return Common.internetAddressListLenient(rawHeader);
  }

  static String safeDisposition(Part p) {
    try {
      return Common.nz(p.getDisposition());
    } catch (Exception ignored) {
      return "";
    }
  }

  static String safeFileName(Part p) {
    try {
      return Common.decodeMimeWord(p.getFileName());
    } catch (Exception ignored) {
      try {
        return Common.nz(p.getFileName());
      } catch (Exception ignoredAgain) {
        return "";
      }
    }
  }

  static void dumpMimeStructure(Part p, EmailRecord r, String indent) throws Exception {
    String ct = Common.baseMimeType(Common.nz(p.getContentType()));
    String disp = safeDisposition(p);
    String fn = safeFileName(p);
    String cid = Common.header1(p, "Content-ID");
    Common.dbg(r, indent + "part ct=" + ct + " disp=" + disp + " file=" + fn + " cid=" + cid);
    if (p.isMimeType("multipart/*")) {
      Multipart mp = (Multipart)p.getContent();
      for (int i = 0; i < mp.getCount(); i++) dumpMimeStructure(mp.getBodyPart(i), r, indent + "  ");
    }
  }

  static void checkMimeStructure(Part part, int depth, long[] totals) throws Exception {
    ProcessingLimits.checkMime(depth, ++totals[0]);
    if (part.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) part.getContent();
      ProcessingLimits.checkMime(depth, totals[0] + multipart.getCount());
      for (int index = 0; index < multipart.getCount(); index++) checkMimeStructure(multipart.getBodyPart(index), depth + 1, totals);
    } else {
      try (InputStream input = part.getInputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
          totals[1] += count;
          ProcessingLimits.checkMessage(totals[1], "decoded MIME contents");
        }
      }
    }
  }

  static String displayFlowedText(String source, boolean delSp) {
    String text = source.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder displayed = new StringBuilder(text.length());
    int start = 0;
    int previousDepth = -1;
    boolean previousFlowed = false;
    boolean first = true;
    while (start < text.length()) {
      int end = text.indexOf('\n', start);
      boolean terminated = end >= 0;
      if (!terminated) end = text.length();
      int quoteEnd = start;
      while (quoteEnd < end && text.charAt(quoteEnd) == '>') quoteEnd++;
      int depth = quoteEnd - start;
      int content = quoteEnd;
      if (content < end && text.charAt(content) == ' ') content++;
      boolean signature = end - content == 3 && text.startsWith("-- ", content);
      boolean flowed = terminated && end > content && text.charAt(end - 1) == ' ' && !signature;
      boolean joined = !first && previousFlowed && previousDepth == depth && !signature;
      if (!joined) {
        if (!first) displayed.append('\n');
        displayed.append(text, start, quoteEnd);
      }
      displayed.append(text, content, flowed && delSp ? end - 1 : end);
      first = false;
      previousDepth = depth;
      previousFlowed = flowed;
      start = terminated ? end + 1 : end;
      if (terminated && start == text.length()) displayed.append('\n');
    }
    return displayed.toString();
  }

  static String readText(Part part, EmailRecord record) throws Exception {
    ContentType type = new ContentType(part.getContentType());
    String charset = type.getParameter("charset");
    String encoding = jakarta.mail.internet.MimeUtility.javaCharset(charset == null ? "US-ASCII" : charset);
    try (InputStream input = part.getInputStream()) {
      byte[] bytes = ProcessingLimits.read(input, record.sourcePath);
      String text;
      Charset decoderCharset = Charset.forName(encoding);
      try {
        text = decoderCharset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException invalidText) {
        text = new String(bytes, encoding);
        String notice = "MIME text could not be decoded completely as " + decoderCharset.name()
            + "; invalid byte sequences were replaced in recovered text (" + type.getBaseType() + ").";
        record.conversionWarnings.add(notice);
        System.err.println("warning: " + notice);
      }
      ProcessingLimits.checkBody(text, record.sourcePath);
      if (type.match("text/plain") && "flowed".equalsIgnoreCase(type.getParameter("format"))) {
        text = displayFlowedText(text, "yes".equalsIgnoreCase(type.getParameter("delsp")));
      }
      return text;
    }
  }

  static BodyChoice extractBodyChoice(Part p, EmailRecord r, boolean debug) throws Exception {
    BodyChoice out = new BodyChoice();
    extractBodyChoiceInto(p, out, r, debug, "");
    if (out.html.isBlank() && !out.text.isBlank()) out.html = Common.plainTextToHtml(out.text);
    if (out.text.isBlank() && !out.html.isBlank()) out.text = Common.stripHtml(out.html);
    if (out.unsupported) r.contentSetDescription = out.text.startsWith("Encrypted") ? "Encrypted email body not rendered" : "Unsupported email body not rendered";
    if (debug) {
      Common.dbg(r, "selected text len=" + out.text.length());
      Common.dbg(r, "selected html len=" + out.html.length());
      Common.dbg(r, "selected text preview=" + Common.preview(out.text, 160));
      Common.dbg(r, "selected html preview=" + Common.preview(out.html, 220));
    }
    return out;
  }

  static void extractBodyChoiceInto(Part p, BodyChoice out, EmailRecord r, boolean debug, String indent) throws Exception {
    if (Part.ATTACHMENT.equalsIgnoreCase(safeDisposition(p))) {
      if (debug) Common.dbg(r, indent + "skip attachment part");
      return;
    }
    if (p instanceof BodyPart && (p.isMimeType("message/rfc822") || p.isMimeType("message/global"))) {
      if (debug) Common.dbg(r, indent + "skip attached message part");
      return;
    }
    String mime = Common.baseMimeType(p.getContentType());
    boolean encrypted = p.isMimeType("multipart/encrypted");
    if (p.isMimeType("application/pkcs7-mime") || p.isMimeType("application/x-pkcs7-mime")) {
      String smimeType = Common.nz(new ContentType(p.getContentType()).getParameter("smime-type"));
      encrypted = smimeType.isBlank() || smimeType.equalsIgnoreCase("enveloped-data") || smimeType.equalsIgnoreCase("authEnveloped-data");
    }
    if (encrypted) {
      String notice = "Encrypted email body could not be rendered (" + mime + "). No decryption was attempted. Retain the original email to access its contents.";
      out.text = notice;
      out.html = Common.plainTextToHtml(notice);
      out.unsupported = true;
      out.mime = p.getContentType();
      r.conversionWarnings.add(notice);
      System.err.println("warning: " + notice);
      return;
    }
    if (p.isMimeType("text/plain")) {
      if (out.text.isBlank()) {
        out.text = readText(p, r);
        out.mime = p.getContentType();
        if (debug) Common.dbg(r, indent + "selected text/plain len=" + out.text.length());
      }
      return;
    }
    if (p.isMimeType("text/html")) {
      String html = readText(p, r);
      if (debug) Common.dbg(r, indent + "seen text/html len=" + html.length());
      if (out.html.isBlank() || html.length() > out.html.length()) {
        out.html = html;
        out.mime = p.getContentType();
        if (debug) Common.dbg(r, indent + "selected html len=" + out.html.length());
      }
      return;
    }
    if (p.isMimeType("multipart/*")) {
      Multipart mp = (Multipart)p.getContent();
      if (debug) Common.dbg(r, indent + "enter multipart count=" + mp.getCount() + " alternative=" + p.isMimeType("multipart/alternative"));
      if (p.isMimeType("multipart/related")) {
        String start = Common.normalizeCidKey(new ContentType(p.getContentType()).getParameter("start"));
        for (int index = 0; index < mp.getCount(); index++) {
          BodyPart root = mp.getBodyPart(index);
          if (start.isBlank() || start.equals(Common.normalizeCidKey(Common.header1(root, "Content-ID")))) {
            extractBodyChoiceInto(root, out, r, debug, indent + "  ");
            return;
          }
        }
        if (!start.isBlank()) throw new MessagingException("multipart/related root Content-ID was not found: " + start);
        return;
      }
      BodyChoice alternative = new BodyChoice();
      BodyChoice unsupportedAlternative = null;
      String alternativeText = "";
      for (int i = 0; i < mp.getCount(); i++) {
        BodyPart bp = mp.getBodyPart(i);
        String disp = safeDisposition(bp);
        if (Part.ATTACHMENT.equalsIgnoreCase(disp)) {
          if (debug) Common.dbg(r, indent + "skip attachment part");
          continue;
        }
        BodyChoice section = new BodyChoice();
        extractBodyChoiceInto(bp, section, r, debug, indent + "  ");
        if (section.text.isBlank() && section.html.isBlank()) continue;
        if (p.isMimeType("multipart/alternative")) {
          if (section.unsupported) {
            unsupportedAlternative = section;
            continue;
          }
          alternative = section;
          if (!section.text.isBlank()) alternativeText = section.text;
        } else {
          out.unsupported = out.text.isBlank() && out.html.isBlank() ? section.unsupported : out.unsupported && section.unsupported;
          String text = section.text.isBlank() ? Common.stripHtml(section.html) : section.text;
          String html = section.html.isBlank() ? Common.plainTextToHtml(section.text) : section.html;
          out.text += (out.text.isBlank() ? "" : "\n") + text;
          out.html += (out.html.isBlank() ? "" : "\n") + html;
          ProcessingLimits.checkBody(out.text, r.sourcePath);
          ProcessingLimits.checkBody(out.html, r.sourcePath);
          if (!"text/html".equalsIgnoreCase(Common.baseMimeType(out.mime))) out.mime = section.mime;
        }
      }
      if (p.isMimeType("multipart/alternative")) {
        if (alternative.text.isBlank() && alternative.html.isBlank() && unsupportedAlternative != null) alternative = unsupportedAlternative;
        out.text = alternative.text.isBlank() ? alternativeText : alternative.text;
        out.html = alternative.html;
        out.unsupported = alternative.unsupported;
        out.mime = alternative.mime;
      }
      return;
    }
    if (safeFileName(p).isBlank() && Common.header1(p, "Content-ID").isBlank()) {
      String notice = "Unsupported email body could not be rendered (" + mime + "). Retain the original email to access its contents.";
      out.text = notice;
      out.html = Common.plainTextToHtml(notice);
      out.unsupported = true;
      out.mime = p.getContentType();
      r.conversionWarnings.add(notice);
      System.err.println("warning: " + notice);
    }
  }

  static void collectRfc822AssociatedFiles(Part p, List<AssociatedFile> out, EmailRecord r, boolean debug) throws Exception {
    collectRfc822AssociatedFiles(p, out, r, debug, new int[]{0});
  }

  static byte[] attachedMessageBytes(Part part, String sourcePath) throws Exception {
    InputStream raw;
    String encoding;
    if (part instanceof MimeBodyPart body) {
      encoding = body.getEncoding();
      raw = body.getRawInputStream();
    } else if (part instanceof MimeMessage message) {
      encoding = message.getEncoding();
      raw = message.getRawInputStream();
    } else {
      try (InputStream input = part.getInputStream()) {
        return ProcessingLimits.read(input, sourcePath);
      }
    }
    try (InputStream stored = raw;
         InputStream decoded = encoding == null ? stored : MimeUtility.decode(stored, encoding)) {
      return ProcessingLimits.read(decoded, sourcePath);
    }
  }

  static void collectRfc822AssociatedFiles(Part p, List<AssociatedFile> out, EmailRecord r, boolean debug, int[] seq) throws Exception {
    if (p.isMimeType("multipart/*")) {
      Multipart mp = (Multipart)p.getContent();
      for (int i = 0; i < mp.getCount(); i++) collectRfc822AssociatedFiles(mp.getBodyPart(i), out, r, debug, seq);
      return;
    }

    if (p.isMimeType("message/rfc822") || p.isMimeType("message/global")) {
      AssociatedFile attachedMessage = new AssociatedFile();
      attachedMessage.filename = safeFileName(p);
      if (attachedMessage.filename.isBlank()) attachedMessage.filename = "attached_message_" + String.format("%03d", ++seq[0]) + ".eml";
      attachedMessage.mime = Common.baseMimeType(p.getContentType());
      attachedMessage.rawContentType = p.getContentType();
      attachedMessage.afRelationship = "Data";
      attachedMessage.emailAttachment = true;
      attachedMessage.description = "Complete attached " + (p.isMimeType("message/global") ? "international" : "RFC 822")
          + " message; parent email: " + Common.nz(r.messageId);
      attachedMessage.bytes = attachedMessageBytes(p, r.sourcePath);
      attachedMessage.checksumMd5 = Common.md5Hex(attachedMessage.bytes);
      attachedMessage.checksumSha256 = Common.sha256Hex(attachedMessage.bytes);
      out.add(attachedMessage);
      if (debug) Common.dbg(r, "associated attached message filename=" + attachedMessage.filename + " bytes=" + attachedMessage.bytes.length);
      return;
    }

    String disp = safeDisposition(p);
    String ct = Common.baseMimeType(Common.nz(p.getContentType()));
    String cid = Common.header1(p, "Content-ID");
    String fn = safeFileName(p);

    boolean hasFileName = !Common.nz(fn).isBlank();
    boolean inline = Part.INLINE.equalsIgnoreCase(disp);
    boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disp);
    boolean cidPart = !Common.nz(cid).isBlank();
    boolean bodyText = p.isMimeType("text/plain") || p.isMimeType("text/html");

    if (!attachment && !(inline && (hasFileName || cidPart)) && !(cidPart && !bodyText) && !(hasFileName && !bodyText)) return;

    byte[] bytes;
    try (InputStream in = p.getInputStream()) {
      bytes = ProcessingLimits.read(in, r.sourcePath);
    }

    if (bytes == null) return;

    AssociatedFile af = new AssociatedFile();
    af.filename = hasFileName ? fn : ("part_" + String.format("%03d", ++seq[0]) + Common.extensionForMime(ct, fn));
    af.mime = ct;
    af.rawContentType = p.getContentType();
    af.bytes = bytes;
    boolean treatAsInline = !attachment && (inline || cidPart);
    af.contentId = cid;
    af.inline = treatAsInline;
    // Filename-bearing non-body parts still count as attachments for EA-PDF/XMP,
    // even when they also carry a Content-ID and may be renderable inline.
    af.emailAttachment = attachment || (hasFileName && !bodyText);
    af.description = attachment ? "Original email attachment" : (treatAsInline ? "Inline related MIME part" : "Associated MIME part");
    af.afRelationship = treatAsInline ? "Supplement" : "Data";
    af.checksumMd5 = Common.md5Hex(bytes);
    af.checksumSha256 = Common.sha256Hex(bytes);
    out.add(af);

    if (debug) Common.dbg(r, "associated attachment filename=" + af.filename + " mime=" + af.mime + " disposition=" + disp + " cid=" + cid + " bytes=" + bytes.length);
  }
}
