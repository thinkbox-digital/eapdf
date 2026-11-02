package eapdf;

import static eapdf.Common.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reconstructs portable MIME messages, preserving attachment payload bytes. */
final class MimeMessageWriter {
  private MimeMessageWriter() {}
  /** UTF-8 generated MIME text with byte-exact encapsulated messages. */
  private static final class MimeBytes extends ByteArrayOutputStream {
    MimeBytes append(Object text) {
      writeBytes(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
      return this;
    }
  }

  static byte[] synthesizeEml(EmailRecord r) throws Exception {
    MimeBytes sb = new MimeBytes();
    String rawFrom = !nz(r.rawFrom).isBlank() ? nz(r.rawFrom) : nz(r.from);
    String rawTo = !nz(r.rawTo).isBlank() ? nz(r.rawTo) : nz(r.to);
    String rawCc = !nz(r.rawCc).isBlank() ? nz(r.rawCc) : nz(r.cc);
    String rawBcc = !nz(r.rawBcc).isBlank() ? nz(r.rawBcc) : nz(r.bcc);
    if (!rawFrom.isBlank()) sb.append("From: ").append(encodeAddressHeader(rawFrom)).append("\r\n");
    if (!rawTo.isBlank()) sb.append("To: ").append(encodeAddressHeader(rawTo)).append("\r\n");
    if (!rawCc.isBlank()) sb.append("Cc: ").append(encodeAddressHeader(rawCc)).append("\r\n");
    if (!rawBcc.isBlank()) sb.append("Bcc: ").append(encodeAddressHeader(rawBcc)).append("\r\n");
    if (!nz(r.rawReplyTo).isBlank()) sb.append("Reply-To: ").append(encodeAddressHeader(r.rawReplyTo)).append("\r\n");
    if (!nz(r.subject).isBlank()) sb.append("Subject: ").append(MimeUtility.encodeText(r.subject, "UTF-8", null)).append("\r\n");
    if (!nz(r.rawDateHeader).isBlank()) sb.append("Date: ").append(nz(r.rawDateHeader)).append("\r\n");
    else if (!nz(r.date).isBlank()) sb.append("Date: ").append(nz(r.date)).append("\r\n");
    if (!nz(r.messageId).isBlank()) sb.append("Message-ID: ").append(nz(r.messageId)).append("\r\n");
    for (String value : r.inReplyTo) sb.append("In-Reply-To: ").append(value).append("\r\n");
    for (String value : r.references) sb.append("References: ").append(value).append("\r\n");
    sb.append("MIME-Version: 1.0\r\n");
    List<AssociatedFile> attachments = new ArrayList<>();
    List<AssociatedFile> inlineFiles = new ArrayList<>();
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null || !af.hasData()) continue;
      if ("Source".equals(nz(af.afRelationship)) || af.sourceSetMember) continue;
      if (af.inline) inlineFiles.add(af);
      else attachments.add(af);
    }
    // Encapsulated messages retain arbitrary original bytes. Label the enclosing
    // multipart accordingly rather than claiming a narrower transport encoding.
    if (r.associatedFiles.stream().anyMatch(af -> af != null && af.hasData()
        && !"Source".equals(nz(af.afRelationship)) && !af.sourceSetMember
        && "message/rfc822".equals(baseMimeType(sourceContentType(af))))) {
      sb.append("Content-Transfer-Encoding: binary\r\n");
    }
    boolean hasHtml = !htmlBody(r).isBlank();
    boolean hasAttachments = !attachments.isEmpty();
    if (!hasHtml && !hasAttachments && inlineFiles.isEmpty()) {
      sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: 8bit\r\n");
      sb.append("\r\n");
      sb.append(textBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
      sb.append("\r\n");
      return sb.toByteArray();
    }

    String mixedBoundary = stableMimeBoundary("Mixed", r);
    String altBoundary = stableMimeBoundary("Alt", r);
    String relatedBoundary = stableMimeBoundary("Related", r);
    if (hasAttachments) {
      sb.append("Content-Type: multipart/mixed; boundary=\"").append(mixedBoundary).append("\"\r\n\r\n");
      if (inlineFiles.isEmpty()) {
        appendTextOrAltPart(sb, r, mixedBoundary, altBoundary, hasHtml);
      } else {
        sb.append("--").append(mixedBoundary).append("\r\n");
        sb.append("Content-Type: multipart/related; boundary=\"").append(relatedBoundary).append("\"\r\n");
        if (inlineFiles.stream().anyMatch(af -> "message/rfc822".equals(baseMimeType(sourceContentType(af))))) {
          sb.append("Content-Transfer-Encoding: binary\r\n");
        }
        sb.append("\r\n");
        appendRelatedParts(sb, r, relatedBoundary, altBoundary, hasHtml, inlineFiles);
      }
      for (AssociatedFile af : attachments) appendAttachmentPart(sb, mixedBoundary, af);
      sb.append("--").append(mixedBoundary).append("--\r\n");
    } else if (!inlineFiles.isEmpty()) {
      sb.append("Content-Type: multipart/related; boundary=\"").append(relatedBoundary).append("\"\r\n\r\n");
      appendRelatedParts(sb, r, relatedBoundary, altBoundary, hasHtml, inlineFiles);
    } else {
      sb.append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n");
      appendTextAndHtml(sb, r, altBoundary, hasHtml);
      sb.append("--").append(altBoundary).append("--\r\n");
    }
    return sb.toByteArray();
  }

  static String encodeAddressHeader(String rawHeader) throws Exception {
    if (rawHeader.chars().allMatch(character -> character < 128)) return rawHeader;
    InternetAddress[] addresses = InternetAddress.parseHeader(rawHeader, false);
    for (InternetAddress address : addresses) {
      if (address.getPersonal() != null) address.setPersonal(address.getPersonal(), "UTF-8");
    }
    return InternetAddress.toString(addresses);
  }

  static void appendRelatedParts(MimeBytes builder, EmailRecord record, String relatedBoundary, String altBoundary,
                                 boolean hasHtml, List<AssociatedFile> inlineFiles) throws Exception {
    appendTextOrAltPart(builder, record, relatedBoundary, altBoundary, hasHtml);
    for (AssociatedFile inlineFile : inlineFiles) appendAttachmentPart(builder, relatedBoundary, inlineFile);
    builder.append("--").append(relatedBoundary).append("--\r\n");
  }

  static String stableMimeBoundary(String prefix, EmailRecord r) {
    StringBuilder key = new StringBuilder();
    key.append(prefix).append('|');
    key.append(nz(r.messageId)).append('|');
    key.append(nz(r.subject)).append('|');
    key.append(nz(r.rawDateHeader)).append('|');
    key.append(nz(r.from)).append('|');
    key.append(nz(r.to)).append('|');
    key.append(nz(r.cc)).append('|');
    key.append(nz(r.bcc)).append('|');
    key.append(textBody(r)).append('|');
    key.append(htmlBody(r)).append('|');
    for (AssociatedFile af : r.associatedFiles) {
      if (af == null || !af.hasData() || af.size() == 0) continue;
      if ("Source".equals(nz(af.afRelationship)) || af.sourceSetMember) continue;
      key.append(nz(af.filename)).append(':').append(af.size()).append(':').append(nz(af.mime)).append('|');
    }
    int hash = key.toString().hashCode();
    return "=_" + prefix + "_" + Integer.toUnsignedString(hash, 16);
  }

  static void appendTextOrAltPart(MimeBytes sb, EmailRecord r, String mixedBoundary, String altBoundary, boolean hasHtml) {
    sb.append("--").append(mixedBoundary).append("\r\n");
    if (hasHtml) {
      sb.append("Content-Type: multipart/alternative; boundary=\"").append(altBoundary).append("\"\r\n\r\n");
      appendTextAndHtml(sb, r, altBoundary, true);
      sb.append("--").append(altBoundary).append("--\r\n");
    } else {
      sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
      sb.append(textBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
      sb.append("\r\n");
    }
  }

  static void appendTextAndHtml(MimeBytes sb, EmailRecord r, String altBoundary, boolean hasHtml) {
    sb.append("--").append(altBoundary).append("\r\n");
    sb.append("Content-Type: text/plain; charset=UTF-8\r\n");
    sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
    sb.append(textBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
    sb.append("\r\n");
    if (hasHtml) {
      sb.append("--").append(altBoundary).append("\r\n");
      String rtf = rtfBody(r);
      if (!rtf.isBlank()) {
        sb.append("Content-Type: text/rtf; charset=UTF-8\r\n");
        sb.append("Content-Transfer-Encoding: base64\r\n\r\n");
        sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
            .encodeToString(rtf.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        sb.append("--").append(altBoundary).append("\r\n");
      }
      sb.append("Content-Type: text/html; charset=UTF-8\r\n");
      sb.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
      sb.append(htmlBody(r).replace("\r\n","\n").replace("\r","\n").replace("\n","\r\n"));
      sb.append("\r\n");
    }
  }

  static void appendAttachmentPart(MimeBytes sb, String mixedBoundary, AssociatedFile af) throws Exception {
    sb.append("--").append(mixedBoundary).append("\r\n");
    String originalContentType = sourceContentType(af);
    ContentType contentType = new ContentType(originalContentType.isBlank() ? "application/octet-stream" : originalContentType);
    if (contentType.getParameterList() == null) contentType.setParameterList(new ParameterList());
    ParameterList dispositionParameters = new ParameterList();
    if (!nz(af.filename).isBlank()) {
      dispositionParameters.set("filename", af.filename, "UTF-8");
    }
    sb.append("Content-Type: ").append(contentType).append("\r\n");
    // RFC 2046 section 5.2.1 forbids base64 on message/rfc822 itself.
    boolean encapsulated = "message/rfc822".equalsIgnoreCase(contentType.getBaseType());
    sb.append("Content-Transfer-Encoding: ").append(encapsulated ? "binary" : "base64").append("\r\n");
    // A native Outlook text attachment can carry a Content-ID assigned by a
    // mail filter. Keep it explicitly attached, rather than making it a body.
    boolean inlineBodyType = af.emailAttachment && Set.of("text/plain", "text/html").contains(baseMimeType(originalContentType));
    sb.append("Content-Disposition: ").append(new ContentDisposition(af.inline && !inlineBodyType ? Part.INLINE : Part.ATTACHMENT, dispositionParameters)).append("\r\n");
    if (!nz(af.contentId).isBlank()) sb.append("Content-ID: <").append(normalizeCidKey(af.contentId)).append(">\r\n");
    if (!nz(af.contentLocation).isBlank()) sb.append("Content-Location: ").append(af.contentLocation).append("\r\n");
    sb.append("\r\n");
    byte[] payload = af.data();
    if (encapsulated) sb.writeBytes(payload);
    else sb.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(payload));
    sb.append("\r\n");
  }

}
