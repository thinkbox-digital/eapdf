package eapdf;
import org.simplejavamail.outlookmessageparser.OutlookMessageParser;
import org.simplejavamail.outlookmessageparser.model.OutlookFileAttachment;
import org.simplejavamail.outlookmessageparser.model.OutlookMessage;
import org.simplejavamail.outlookmessageparser.model.OutlookRecipient;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.Entry;
import org.apache.poi.poifs.filesystem.EntryUtils;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import java.io.*;
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
    r.to = Common.nz(m.getDisplayTo());
    r.cc = Common.nz(m.getDisplayCc());
    r.bcc = Common.nz(m.getDisplayBcc());
    r.rawFrom = !Common.rfc822AddrList(r.fromAgents).isBlank() ? Common.rfc822AddrList(r.fromAgents) : (!headerValue(m, "From").isBlank() ? headerValue(m, "From") : r.from);
    r.rawTo = !Common.rfc822AddrList(r.toAgents).isBlank() ? Common.rfc822AddrList(r.toAgents) : (!headerValue(m, "To").isBlank() ? headerValue(m, "To") : r.to);
    r.rawCc = !Common.rfc822AddrList(r.ccAgents).isBlank() ? Common.rfc822AddrList(r.ccAgents) : (!headerValue(m, "Cc").isBlank() ? headerValue(m, "Cc") : r.cc);
    r.rawBcc = !Common.rfc822AddrList(r.bccAgents).isBlank() ? Common.rfc822AddrList(r.bccAgents) : (!headerValue(m, "Bcc").isBlank() ? headerValue(m, "Bcc") : r.bcc);
    r.messageId = Common.nz(m.getMessageId());
    if (!headerValue(m, "In-Reply-To").isBlank()) r.inReplyTo.add(headerValue(m, "In-Reply-To"));
    if (!headerValue(m, "References").isBlank()) r.references.add(headerValue(m, "References"));
    r.archiveGlobalId = Common.nz(globalId);
    r.archiveEmails.addAll(archiveEmails);
    Date sentDate = m.getDate() != null ? m.getDate() : m.getClientSubmitTime();
    r.date = Common.fmtDate(sentDate);
    r.sentXmp = Common.xmpDate(sentDate);
    r.rawDateHeader = !headerValue(m, "Date").isBlank() ? headerValue(m, "Date") : Common.rfc822Date(sentDate);
    String bodyText = Common.nz(m.getBodyText());
    String bodyHtml = Common.nz(m.getConvertedBodyHTML());
    r.bodyMime = !bodyHtml.isBlank() && !Common.nz(m.getBodyRTF()).isBlank() ? "text/rtf" : "text/html";
    if ("text/rtf".equals(r.bodyMime)) r.rtfBody = m.getBodyRTF();
    if (bodyHtml.isBlank()) {
      bodyHtml = Common.nz(m.getBodyHTML());
      r.bodyMime = bodyHtml.isBlank() ? "text/plain" : "text/html";
    }
    r.textBody = Common.normalizeBody(bodyText);
    r.htmlBody = !bodyHtml.isBlank() ? Common.normalizeHtml(bodyHtml) : Common.plainTextToHtml(r.textBody);

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
