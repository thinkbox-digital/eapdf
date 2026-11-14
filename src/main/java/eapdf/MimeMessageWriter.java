package eapdf;

import static eapdf.Common.*;
import jakarta.activation.DataHandler;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reconstructs portable MIME messages, preserving attachment payload bytes. */
final class MimeMessageWriter {
  private MimeMessageWriter() {}

  static byte[] synthesizeEml(EmailRecord record) throws Exception {
    // A reconstruction must retain the source identifier, including its absence.
    MimeMessage message = new MimeMessage(Session.getInstance(new Properties())) {
      @Override protected void updateMessageID() {}
    };
    for (String[] header : new String[][] {
        {"From", !nz(record.rawFrom).isBlank() ? record.rawFrom : record.from},
        {"To", !nz(record.rawTo).isBlank() ? record.rawTo : record.to},
        {"Cc", !nz(record.rawCc).isBlank() ? record.rawCc : record.cc},
        {"Bcc", !nz(record.rawBcc).isBlank() ? record.rawBcc : record.bcc},
        {"Reply-To", record.rawReplyTo}}) {
      if (!nz(header[1]).isBlank()) message.setHeader(header[0], encodeAddressHeader(header[1]));
    }
    if (!nz(record.subject).isBlank()) message.setSubject(record.subject, "UTF-8");
    String date = !nz(record.rawDateHeader).isBlank() ? record.rawDateHeader : nz(record.date);
    if (!date.isBlank()) message.setHeader("Date", date);
    if (!nz(record.messageId).isBlank()) message.setHeader("Message-ID", record.messageId);
    for (String value : record.inReplyTo) message.addHeader("In-Reply-To", value);
    for (String value : record.references) message.addHeader("References", value);

    MimeBodyPart body = textPart(textBody(record), "plain");
    if (!htmlBody(record).isBlank()) {
      List<MimeBodyPart> alternatives = new ArrayList<>();
      alternatives.add(body);
      if (!rtfBody(record).isBlank()) {
        MimeBodyPart rtf = new MimeBodyPart();
        rtf.setDataHandler(new DataHandler(new ByteArrayDataSource(rtfBody(record).getBytes(StandardCharsets.UTF_8),
            "text/rtf; charset=UTF-8")));
        rtf.setHeader("Content-Transfer-Encoding", "base64");
        alternatives.add(rtf);
      }
      alternatives.add(textPart(htmlBody(record), "html"));
      body = multipartPart("alternative", "Alt", record, alternatives);
    }
    List<MimeBodyPart> inline = new ArrayList<>(), attachments = new ArrayList<>();
    for (AssociatedFile file : record.associatedFiles) {
      if (file == null || !file.hasData() || "Source".equals(nz(file.afRelationship)) || file.sourceSetMember) continue;
      (file.inline ? inline : attachments).add(attachmentPart(file));
    }
    if (!inline.isEmpty()) {
      inline.add(0, body);
      body = multipartPart("related", "Related", record, inline);
    }
    if (!attachments.isEmpty()) {
      attachments.add(0, body);
      body = multipartPart("mixed", "Mixed", record, attachments);
    }
    message.setDataHandler(body.getDataHandler());
    for (Enumeration<Header> headers = body.getAllHeaders(); headers.hasMoreElements();) {
      Header header = headers.nextElement();
      message.setHeader(header.getName(), header.getValue());
    }
    message.saveChanges();
    // Jakarta Mail normally supplies today's date for an undated new message.
    if (date.isBlank()) message.removeHeader("Date");
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    message.writeTo(output);
    if (!body.isMimeType("multipart/*")) output.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
    return output.toByteArray();
  }

  static String encodeAddressHeader(String rawHeader) throws Exception {
    if (rawHeader.chars().allMatch(character -> character < 128)) return rawHeader;
    InternetAddress[] addresses = InternetAddress.parseHeader(rawHeader, false);
    for (InternetAddress address : addresses) {
      if (address.getPersonal() != null) address.setPersonal(address.getPersonal(), "UTF-8");
    }
    return InternetAddress.toString(addresses);
  }

  private static MimeBodyPart textPart(String value, String subtype) throws MessagingException {
    MimeBodyPart part = new MimeBodyPart();
    part.setText(value.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n"), "UTF-8", subtype);
    part.setHeader("Content-Transfer-Encoding", "8bit");
    return part;
  }

  private static MimeBodyPart multipartPart(String subtype, String prefix, EmailRecord record,
                                           List<MimeBodyPart> parts) throws MessagingException {
    MimeMultipart multipart = new MimeMultipart(subtype) {
      { ContentType type = new ContentType(contentType);
        type.setParameter("boundary", stableMimeBoundary(prefix, record)); contentType = type.toString(); }
    };
    boolean binary = false;
    for (MimeBodyPart part : parts) {
      multipart.addBodyPart(part);
      binary |= "binary".equalsIgnoreCase(part.getEncoding());
    }
    MimeBodyPart result = new MimeBodyPart();
    result.setContent(multipart);
    if (binary) result.setHeader("Content-Transfer-Encoding", "binary");
    return result;
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

  private static MimeBodyPart attachmentPart(AssociatedFile file) throws Exception {
    String originalContentType = sourceContentType(file);
    ContentType type = new ContentType(originalContentType.isBlank() ? "application/octet-stream" : originalContentType);
    String encoding = type.match("message/rfc822") ? "binary" : "base64";
    // Treat attachments as opaque, including malformed message/multipart data.
    // PreencodedMimeBodyPart also avoids Mail dropping composite base64 encoding.
    MimeBodyPart part = new PreencodedMimeBodyPart(encoding) {
      @Override protected void updateHeaders() {}
    };
    byte[] payload = file.data();
    if (encoding.equals("base64")) payload = Base64.getMimeEncoder(76,
        "\r\n".getBytes(StandardCharsets.US_ASCII)).encode(payload);
    part.setDataHandler(new DataHandler(new ByteArrayDataSource(payload, type.toString())));
    part.setHeader("Content-Type", type.toString());
    // RFC 2046 section 5.2.1 forbids base64 on message/rfc822 itself.
    part.setHeader("Content-Transfer-Encoding", encoding);
    ParameterList parameters = new ParameterList();
    if (!nz(file.filename).isBlank()) parameters.set("filename", file.filename, "UTF-8");
    // A native Outlook text attachment with a Content-ID is still an attachment.
    boolean bodyType = file.emailAttachment && Set.of("text/plain", "text/html").contains(baseMimeType(originalContentType));
    part.setHeader("Content-Disposition", new ContentDisposition(file.inline && !bodyType ? Part.INLINE : Part.ATTACHMENT, parameters).toString());
    if (!nz(file.contentId).isBlank()) part.setContentID("<" + normalizeCidKey(file.contentId) + ">");
    if (!nz(file.contentLocation).isBlank()) part.setHeader("Content-Location", file.contentLocation);
    return part;
  }
}
