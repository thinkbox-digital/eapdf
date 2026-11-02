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
  private static final String ENCODED_WORD = "=\\?[^\\s?]+\\?[bBqQ]\\?[^?\\r\\n]*\\?=";
  private static final java.util.regex.Pattern ADJACENT_SUBJECT_WORDS = java.util.regex.Pattern.compile(
      "(" + ENCODED_WORD + ")(?=" + ENCODED_WORD + ")");

  static int headerEnd(byte[] raw) {
    int end = 0;
    while (end < raw.length) {
      int start = end;
      while (end < raw.length && raw[end] != '\r' && raw[end] != '\n') end++;
      if (end == start) break;
      if (end < raw.length && raw[end] == '\r') end++;
      if (end < raw.length && raw[end] == '\n') end++;
    }
    return end;
  }

  static Session mimeSession(byte[] raw) {
    int end = headerEnd(raw);
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

  static String decodedSubject(MimeMessage message) throws Exception {
    String decoded = Common.nz(message.getSubject());
    String raw = MimeUtility.unfold(Common.header1(message, "Subject"));
    var adjacent = ADJACENT_SUBJECT_WORDS.matcher(raw);
    if (!adjacent.find()) return decoded;
    // Some senders join encoded words without their required separating space.
    // Decode the original header once, preserving intentionally encoded literals.
    return MimeUtility.decodeText(adjacent.replaceAll("$1 "));
  }

  static EmailRecord readRfc822Bytes(byte[] raw, String sourcePath, String sourceFilename, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    ProcessingLimits.checkMessage(raw.length, sourcePath);
    Session s = mimeSession(raw);
    try (InputStream in = new ByteArrayInputStream(raw)) {
      MimeMessage mm = new MimeMessage(s, in);
      checkMimeStructure(mm, 0, new long[]{0, 0});
      EmailRecord r = new EmailRecord();
      r.mimePartScopes = new IdentityHashMap<>();
      assignLocationScopes(mm, "message", List.of(), r.mimePartScopes);
      r.sourceType = "eml";
      r.sourcePath = Common.nz(sourcePath);
      r.folderPath = "";
      r.subject = decodedSubject(mm);
      r.rawFrom = Common.header1(mm, "From");
      r.rawTo = Common.header1(mm, "To");
      r.rawCc = Common.header1(mm, "Cc");
      r.rawBcc = Common.header1(mm, "Bcc");
      r.rawReplyTo = Common.header1(mm, "Reply-To");
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
      r.bodyBaseUri = choice.baseUri;
      r.bodyLocationScopes = choice.locationScopes;
      r.htmlBody = !Common.nz(choice.html).isBlank() ? Common.normalizeHtml(choice.html) : Common.plainTextToHtml(r.textBody);

      r.sourceBytes = raw;
      r.sourceFilename = !Common.nz(sourceFilename).isBlank() ? sourceFilename : (Common.safeFileStem(Common.nz(r.subject).isBlank() ? "message" : r.subject) + ".eml");
      r.sourceMime = "message/rfc822";
      if (Boolean.parseBoolean(s.getProperty("mail.mime.allowutf8"))) {
        for (int i = 0, end = headerEnd(raw); i < end; i++) {
          if (raw[i] < 0) {
            r.sourceMime = "message/global";
            break;
          }
        }
      }
      r.afRelationship = "Source";

      AssociatedFile src = new AssociatedFile();
      src.filename = r.sourceFilename;
      src.mime = r.sourceMime;
      src.afRelationship = "Source";
      src.bytes = raw;
      src.description = r.sourceMime.equals("message/global")
          ? "Original international email source" : "Original RFC 822 source";
      src.checksumMd5 = Common.md5Hex(src.bytes);
      src.checksumSha256 = Common.sha256Hex(src.bytes);
      src.emailAttachment = false;
      r.associatedFiles.add(src);

      collectRfc822AssociatedFiles(mm, r.associatedFiles, r, debug);
      preferReadableAlternative(r, choice.plainMime);
      r.mimePartScopes = null;
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

  static void preferReadableAlternative(EmailRecord record, String plainMime) throws Exception {
    if (!"text/html".equals(Common.baseMimeType(record.bodyMime)) || plainMime.isBlank()
        || Common.nz(record.textBody).isBlank()) return;
    var document = org.jsoup.Jsoup.parse(record.htmlBody);
    boolean hasVisibleText = EmailStyles.visibleBodyText(document).codePoints().anyMatch(point ->
        !Character.isWhitespace(point) && !Character.isSpaceChar(point)
            && Character.getType(point) != Character.FORMAT);
    if (hasVisibleText
        || !document.select("svg,object,embed,canvas").isEmpty()
        || record.htmlBody.toLowerCase(Locale.ROOT).contains("data:image/")) return;
    // Styles can add visible words even when the HTML has no literal text.
    var generatedContent = java.util.regex.Pattern.compile("(?i)\\bcontent\\s*:");
    for (var element : document.select("style,[style]")) {
      String css = "style".equals(element.normalName()) ? element.data() : element.attr("style");
      if (generatedContent.matcher(css).find()) return;
    }
    var images = document.select("img");
    if (images.isEmpty()) return;
    for (var image : images) {
      String location = image.attr("src").trim();
      AssociatedFile nativeImage = record.nativeLocationResources.get(location);
      if (nativeImage != null && nativeImage.hasData() && nativeImage.size() > 0
          && Common.detectMimeType(nativeImage.filename, nativeImage.mime, nativeImage.head()).startsWith("image/")) return;
      String source = location.toLowerCase(Locale.ROOT);
      if (!source.startsWith("https://") && !source.startsWith("http://")) return;
    }
    // Only an image matching the HTML URL can satisfy it offline. An unrelated
    // photo attachment must not prevent the readable plain-text alternative.
    for (AssociatedFile file : record.associatedFiles) {
      if (file == null || !file.hasData() || file.size() == 0 || "Source".equals(file.afRelationship)
          || !Common.detectMimeType(file.filename, file.mime, file.head()).startsWith("image/")) continue;
      if (!record.bodyLocationScopes.isEmpty() && !record.bodyLocationScopes.contains(file.locationScope)) continue;
      String location = Common.nz(file.resolvedContentLocation).isBlank() ? file.contentLocation : file.resolvedContentLocation;
      for (var image : images) {
        if (image.attr("src").trim().equals(location)) return;
      }
    }
    record.htmlBody = Common.plainTextToHtml(record.textBody);
    record.bodyMime = plainMime;
    record.bodyBaseUri = "";
    record.bodyLocationScopes = List.of();
    record.contentSetDescription = "Plain-text alternative rendered because HTML images require external resources";
    Common.warn(record, ConversionDiagnostic.Code.BODY_FALLBACK, "Rendered the supplied plain-text alternative because HTML images require external resources.");
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
    } else if (part.isMimeType("message/rfc822") && part.getContent() instanceof Part nested) {
      checkMimeStructure(nested, depth + 1, totals);
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
      Charset decoderCharset;
      try {
        decoderCharset = Charset.forName(encoding);
      } catch (IllegalArgumentException unsupported) {
        // Keep the original MIME bytes; only the displayed recovery uses a guess.
        try {
          StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes));
          decoderCharset = StandardCharsets.UTF_8;
        } catch (CharacterCodingException notUtf8) {
          decoderCharset = Charset.forName("windows-1252");
        }
        Common.warn(record, ConversionDiagnostic.Code.TEXT_RECOVERED, "Unsupported MIME charset '" + charset + "'; displayed text recovered using "
            + decoderCharset.name() + ". Original source bytes are retained.");
      }
      try {
        text = decoderCharset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
      } catch (CharacterCodingException invalidText) {
        text = new String(bytes, decoderCharset);
        String notice = "MIME text could not be decoded completely as " + decoderCharset.name()
            + "; invalid byte sequences were replaced in recovered text (" + type.getBaseType() + ").";
        Common.warn(record, ConversionDiagnostic.Code.TEXT_RECOVERED, notice);
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
    for (String warning : out.renderWarnings) Common.warn(r, ConversionDiagnostic.Code.RENDERING_REDUCED, warning);
    if (debug) {
      Common.dbg(r, "selected text len=" + out.text.length());
      Common.dbg(r, "selected html len=" + out.html.length());
      Common.dbg(r, "selected text preview=" + Common.preview(out.text, 160));
      Common.dbg(r, "selected html preview=" + Common.preview(out.html, 220));
    }
    return out;
  }

  static void assignLocationScopes(Part part, String path, List<String> inherited,
                                   Map<Object, List<String>> scopes) throws Exception {
    List<String> current = inherited;
    if (part.isMimeType("multipart/related")) {
      List<String> nested = new ArrayList<>();
      nested.add(path);
      nested.addAll(inherited);
      current = List.copyOf(nested);
    }
    scopes.put(part, current);
    if (part.isMimeType("multipart/*")) {
      Multipart multipart = (Multipart) part.getContent();
      for (int i = 0; i < multipart.getCount(); i++) {
        assignLocationScopes(multipart.getBodyPart(i), path + "/" + i, current, scopes);
      }
    }
  }

  static String locationUri(Part part) throws Exception {
    return Common.decodeMimeWord(MimeUtility.unfold(Common.header1(part, "Content-Location")))
        .replaceAll("[\\r\\n\\t ]", "");
  }

  static String mimeBaseUri(Part part) throws Exception {
    while (part != null) {
      String location = locationUri(part);
      try {
        java.net.URI uri = new java.net.URI(location);
        if (uri.isAbsolute() && !uri.isOpaque()) return location;
      } catch (java.net.URISyntaxException invalidLocation) {
        // An invalid location cannot establish a base; check the enclosing part.
      }
      part = part instanceof BodyPart body && body.getParent() != null ? body.getParent().getParent() : null;
    }
    return "";
  }

  static void extractBodyChoiceInto(Part p, BodyChoice out, EmailRecord r, boolean debug, String indent) throws Exception {
    extractBodyChoiceInto(p, out, r, debug, indent, false);
  }

  static void extractBodyChoiceInto(Part p, BodyChoice out, EmailRecord r, boolean debug, String indent, boolean relatedRoot) throws Exception {
    if (!relatedRoot && Part.ATTACHMENT.equalsIgnoreCase(safeDisposition(p))) {
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
      out.renderWarnings.add(notice);
      return;
    }
    if (p.isMimeType("text/plain")) {
      if (out.text.isBlank()) {
        out.text = readText(p, r);
        out.mime = p.getContentType();
        out.plainMime = out.mime;
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
        out.baseUri = mimeBaseUri(p);
        out.locationScopes = r.mimePartScopes == null ? List.of() : r.mimePartScopes.getOrDefault(p, List.of());
        if (debug) Common.dbg(r, indent + "selected html len=" + out.html.length());
      }
      return;
    }
    if (p.isMimeType("multipart/*")) {
      Multipart mp = (Multipart)p.getContent();
      if (debug) Common.dbg(r, indent + "enter multipart count=" + mp.getCount() + " alternative=" + p.isMimeType("multipart/alternative"));
      if (p.isMimeType("multipart/signed")) {
        String protocol = Common.baseMimeType(new ContentType(p.getContentType()).getParameter("protocol"));
        if (mp.getCount() == 2 && !protocol.isBlank()
            && protocol.equals(Common.baseMimeType(mp.getBodyPart(1).getContentType()))) {
          extractBodyChoiceInto(mp.getBodyPart(0), out, r, debug, indent + "  ");
          Common.warn(r, ConversionDiagnostic.Code.SIGNATURE_UNVERIFIED, "Detached email signature was not verified.");
          return;
        }
        Common.warn(r, ConversionDiagnostic.Code.SIGNATURE_UNVERIFIED, "Malformed multipart/signed envelope; rendering available parts without signature verification.");
      }
      if (p.isMimeType("multipart/related")) {
        String start = Common.normalizeCidKey(new ContentType(p.getContentType()).getParameter("start"));
        for (int index = 0; index < mp.getCount(); index++) {
          BodyPart root = mp.getBodyPart(index);
          if (start.isBlank() || start.equals(Common.normalizeCidKey(Common.header1(root, "Content-ID")))) {
            extractBodyChoiceInto(root, out, r, debug, indent + "  ", true);
            return;
          }
        }
        if (!start.isBlank()) throw new MessagingException("multipart/related root Content-ID was not found: " + start);
        return;
      }
      BodyChoice alternative = new BodyChoice();
      BodyChoice unsupportedAlternative = null;
      String alternativeText = "";
      String alternativeTextMime = "";
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
          if (!section.text.isBlank()) {
            alternativeText = section.text;
            alternativeTextMime = section.plainMime;
          }
        } else {
          out.renderWarnings.addAll(section.renderWarnings);
          out.unsupported = out.text.isBlank() && out.html.isBlank() ? section.unsupported : out.unsupported && section.unsupported;
          String text = section.text.isBlank() ? Common.stripHtml(section.html) : section.text;
          String html = section.html.isBlank() ? Common.plainTextToHtml(section.text) : section.html;
          out.baseUri = out.html.isBlank() ? section.baseUri : "";
          out.locationScopes = out.html.isBlank() ? section.locationScopes : List.of();
          if (out.text.isBlank()) out.plainMime = section.plainMime;
          else if (!section.text.isBlank() && !out.plainMime.equals(section.plainMime)) out.plainMime = "";
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
        out.plainMime = alternative.text.isBlank() ? alternativeTextMime : alternative.plainMime;
        out.html = alternative.html;
        out.unsupported = alternative.unsupported;
        out.mime = alternative.mime;
        out.baseUri = alternative.baseUri;
        out.locationScopes = alternative.locationScopes;
        out.renderWarnings.addAll(alternative.renderWarnings);
      }
      return;
    }
    if (safeFileName(p).isBlank() && Common.header1(p, "Content-ID").isBlank()) {
      String notice = "Unsupported email body could not be rendered (" + mime + "). Retain the original email to access its contents.";
      out.text = notice;
      out.html = Common.plainTextToHtml(notice);
      out.unsupported = true;
      out.mime = p.getContentType();
      out.renderWarnings.add(notice);
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
    String location = Common.header1(p, "Content-Location");

    boolean hasFileName = !Common.nz(fn).isBlank();
    boolean inline = Part.INLINE.equalsIgnoreCase(disp);
    boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(disp);
    boolean cidPart = !Common.nz(cid).isBlank();
    boolean locationPart = !location.isBlank();
    boolean bodyText = p.isMimeType("text/plain") || p.isMimeType("text/html");

    if (!attachment && !(inline && (hasFileName || cidPart)) && !((cidPart || locationPart) && !bodyText) && !(hasFileName && !bodyText)) return;

    byte[] bytes;
    if (ct.startsWith("message/")) bytes = attachedMessageBytes(p, r.sourcePath);
    else try (InputStream in = p.getInputStream()) {
      bytes = ProcessingLimits.read(in, r.sourcePath);
    }

    if (bytes == null) return;

    AssociatedFile af = new AssociatedFile();
    af.filename = hasFileName ? fn : ("part_" + String.format("%03d", ++seq[0]) + Common.extensionForMime(ct, fn));
    af.mime = ct;
    af.rawContentType = p.getContentType();
    af.bytes = bytes;
    boolean treatAsInline = !attachment && (inline || cidPart || locationPart);
    af.contentId = cid;
    af.contentLocation = location;
    if (locationPart) {
      List<String> scopes = r.mimePartScopes == null ? List.of() : r.mimePartScopes.getOrDefault(p, List.of());
      af.locationScope = scopes.isEmpty() ? "" : scopes.get(0);
      try {
        String base = mimeBaseUri(p);
        af.resolvedContentLocation = new java.net.URI(base.isBlank() ? "thismessage:/" : base)
            .resolve(new java.net.URI(locationUri(p))).toString();
      } catch (java.net.URISyntaxException invalidLocation) {
        // Retain the original header without inventing a resolved resource identity.
      }
    }
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
