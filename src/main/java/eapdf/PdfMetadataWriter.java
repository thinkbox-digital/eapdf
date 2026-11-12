package eapdf;

import java.util.*;
import java.time.*;

/** Builds metadata from records; PDF publication remains with PdfMailWriter. */
final class PdfMetadataWriter {
  record Asset(String filename, int size, String mime, String checksumMd5, String relationship, boolean sourceSetMember) {}
  interface Assets {
    boolean include(EmailRecord record, AssociatedFile file);
    int size(EmailRecord record, AssociatedFile file);
    String mime(AssociatedFile file, String filename) throws Exception;
    String description(EmailRecord record, AssociatedFile file);
    List<Asset> archive(List<EmailRecord> records) throws Exception;
    int folders(List<EmailRecord> records);
  }
  private final EapdfConfig CONFIG;
  private final Assets assets;
  PdfMetadataWriter(EapdfConfig config, Assets assets) { this.CONFIG=config; this.assets=assets; }

boolean pdfUaFeaturesEnabled() {
  return CONFIG.accessibility != null && CONFIG.accessibility.addPdfUaFeatures;
}

void appendSourceProblems(StringBuilder x, List<EmailRecord> records) {
  if (records.stream().noneMatch(r -> !r.sourceProblems.isEmpty())) return;
  x.append("<rdf:Description rdf:about=\"\" xmlns:conversion=\"https://thinkbox.digital/ns/eapdf/conversion/1.0/\">")
      .append("<conversion:status>INCOMPLETE</conversion:status><conversion:problems><rdf:Seq>");
  for (EmailRecord record : records) for (String problem : record.sourceProblems)
    x.append("<rdf:li>").append(Common.xml(record.mailGuid + " | " + record.subject + " | " + problem)).append("</rdf:li>");
  x.append("</rdf:Seq></conversion:problems></rdf:Description>");
}

String pdfUaIdentification() {
  if (!pdfUaFeaturesEnabled()) return "";
  return "<rdf:Description rdf:about=\"\" xmlns:pdfuaid=\"http://www.aiim.org/pdfua/ns/id/\">"
      + "<pdfuaid:part>2</pdfuaid:part><pdfuaid:rev>2024</pdfuaid:rev></rdf:Description>";
}

String chooseEaPdfConformance(EmailRecord r) {
  return chooseEaPdfConformance(r, null);
}

String chooseEaPdfConformance(EmailRecord r, String profileOverride) {
  if (!Common.nz(profileOverride).isBlank()) return profileOverride;
  if (r != null && !r.embedSourceMessage) return "si";
  return "s";
}

String buildXmpMetadata(EmailRecord r) throws Exception {
  return buildXmpMetadata(r, null);
}

String buildXmpMetadata(EmailRecord r, String profileOverride) throws Exception {
  Common.finalizeRecordMetadata(r);
  Common.requireCreationDate(r);
  String now = Common.xmpDateNowUtc();
  String claimDate = LocalDate.now(ZoneOffset.UTC).toString();
  String title = Common.stripInvalidXml(Common.nz(r.subject).isBlank() ? "Email" : Common.nz(r.subject));
  String description = buildDescription(r);
  String producer = Common.productLabel() + " via OpenHTMLtoPDF + Apache PDFBox";
  String creatorTool = Common.productLabel();
  String conformance = chooseEaPdfConformance(r, profileOverride);
  String mailGuid = Common.nz(r.mailGuid).isBlank() ? buildMailGuid(r) : Common.nz(r.mailGuid);
  StringBuilder x = new StringBuilder();

  x.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>");
  x.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">");
  x.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"");
  x.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"");
  x.append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"");
  x.append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"");
  x.append(" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"");
  x.append(" xmlns:pdfd=\"http://pdfa.org/declarations/\"");
  x.append(" xmlns:foaf=\"http://xmlns.com/foaf/0.1/\"");
  x.append(" xmlns:pdfmail=\"http://www.pdfa.org/eapdf/\"");
  x.append(" xmlns:pdfmailid=\"http://www.pdfa.org/eapdf/ns/id/\"");
  x.append(" xmlns:pdfmailmeta=\"http://www.pdfa.org/eapdf/ns/meta/\">");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<dc:creator><rdf:Seq><rdf:li>").append(Common.xml(CONFIG.requireCreator())).append("</rdf:li></rdf:Seq></dc:creator>");
  x.append("<dc:format>application/pdf</dc:format>");
  x.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(title)).append("</rdf:li></rdf:Alt></dc:title>");
  x.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(description)).append("</rdf:li></rdf:Alt></dc:description>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdf:Producer>").append(Common.xml(producer)).append("</pdf:Producer>");
  x.append("<pdf:Keywords>EA-PDF</pdf:Keywords>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<xmp:CreateDate>").append(Common.xml(now)).append("</xmp:CreateDate>");
  x.append("<xmp:ModifyDate>").append(Common.xml(now)).append("</xmp:ModifyDate>");
  x.append("<xmp:MetadataDate>").append(Common.xml(now)).append("</xmp:MetadataDate>");
  x.append("<xmp:CreatorTool>").append(Common.xml(creatorTool)).append("</xmp:CreatorTool>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfaid:part>4</pdfaid:part>");
  x.append("<pdfaid:rev>2020</pdfaid:rev>");
  x.append("<pdfaid:conformance>F</pdfaid:conformance>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailid:version>1</pdfmailid:version>");
  x.append("<pdfmailid:rev>2024</pdfmailid:rev>");
  x.append("<pdfmailid:conformance>").append(Common.xml(conformance)).append("</pdfmailid:conformance>");
  x.append("</rdf:Description>");

  appendSourceProblems(x, List.of(r));
  x.append(pdfUaIdentification());
  appendIsoTs32005Declaration(x, creatorTool, claimDate);
  appendConversionHistory(x, creatorTool, now, conformance, 1);

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailmeta:email><rdf:Bag><rdf:li rdf:parseType=\"Resource\">");
  appendStringProp(x, "Subject", r.subject);
  appendStringProp(x, "Raw-From", r.rawFrom);
  appendStringProp(x, "Raw-To", r.rawTo);
  appendStringProp(x, "Raw-Cc", r.rawCc);
  appendStringProp(x, "Raw-Bcc", r.rawBcc);
  if (!Common.nz(r.sentXmp).isBlank()) appendStringProp(x, "Sent", r.sentXmp);
  if (shouldEmitRawSent(r)) appendStringProp(x, "Raw-Sent", !Common.nz(r.rawDateHeader).isBlank() ? r.rawDateHeader : r.date);
  appendStringProp(x, "SizeInBytes", Integer.toString(messageMetadataSize(r)));
  appendStringProp(x, "NumberAttachments", Integer.toString(Math.max(r.attachmentCount, 0)));
  appendStringProp(x, "Mail_GUID", mailGuid);
  appendStringProp(x, "messageid", r.messageId);
  appendReplyMetadata(x, r);
  appendFoafSeq(x, "From", r.fromAgents);
  appendFoafSeq(x, "To", r.toAgents);
  appendFoafSeq(x, "Cc", r.ccAgents);
  appendFoafSeq(x, "Bcc", r.bccAgents);
  x.append("</rdf:li></rdf:Bag></pdfmailmeta:email>");

  x.append("<pdfmailmeta:assets><rdf:Bag>");
  for (AssociatedFile af : r.associatedFiles) {
    if (!assets.include(r, af)) continue;
    x.append("<rdf:li rdf:parseType=\"Resource\">");
    appendStringProp(x, "Filename", Common.nz(af.filename));
    appendStringProp(x, "SizeInBytes", Integer.toString(assets.size(r, af)));
    appendStringProp(x, "ContentType", assets.mime(af, af.filename));
    appendStringProp(x, "Checksum", Common.nz(af.checksumMd5));
    x.append("</rdf:li>");
  }
  x.append("</rdf:Bag></pdfmailmeta:assets>");

  x.append("<pdfmailmeta:attachments><rdf:Bag>");
  for (AssociatedFile af : r.associatedFiles) {
    if (!af.emailAttachment) continue;
    x.append("<rdf:li rdf:parseType=\"Resource\">");
    appendStringProp(x, "Filename", Common.nz(af.filename));
    appendStringProp(x, "SizeInBytes", Integer.toString(assets.size(r, af)));
    appendStringProp(x, "Content-Type", Common.sourceContentType(af));
    if (r.embedSourceMessage && !Common.nz(af.checksumMd5).isBlank()) appendStringProp(x, "CheckSum", Common.nz(af.checksumMd5));
    appendStringProp(x, "Mail_GUID", mailGuid);
    appendStringProp(x, "Description", assets.description(r, af));
    x.append("</rdf:li>");
  }
  x.append("</rdf:Bag></pdfmailmeta:attachments>");

  x.append("</rdf:Description>");
  x.append("</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>");
  return x.toString();
}

String buildXmpMetadata(List<EmailRecord> records, String archiveProfile) throws Exception {
  for (EmailRecord r : records) {
    Common.finalizeRecordMetadata(r);
    Common.requireCreationDate(r);
  }
  String now = Common.xmpDateNowUtc();
  String claimDate = LocalDate.now(ZoneOffset.UTC).toString();
  String title = Common.stripInvalidXml(buildArchiveTitle(records));
  String description = buildArchiveDescription(records, archiveProfile);
  String producer = Common.productLabel() + " via OpenHTMLtoPDF + Apache PDFBox";
  String creatorTool = Common.productLabel();
  StringBuilder x = new StringBuilder();

  x.append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>");
  x.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">");
  x.append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"");
  x.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"");
  x.append(" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\"");
  x.append(" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\"");
  x.append(" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"");
  x.append(" xmlns:pdfd=\"http://pdfa.org/declarations/\"");
  x.append(" xmlns:foaf=\"http://xmlns.com/foaf/0.1/\"");
  x.append(" xmlns:pdfmail=\"http://www.pdfa.org/eapdf/\"");
  x.append(" xmlns:pdfmailid=\"http://www.pdfa.org/eapdf/ns/id/\"");
  x.append(" xmlns:pdfmailmeta=\"http://www.pdfa.org/eapdf/ns/meta/\">");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<dc:creator><rdf:Seq><rdf:li>").append(Common.xml(CONFIG.requireCreator())).append("</rdf:li></rdf:Seq></dc:creator>");
  x.append("<dc:format>application/pdf</dc:format>");
  x.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(title)).append("</rdf:li></rdf:Alt></dc:title>");
  x.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">").append(Common.xml(description)).append("</rdf:li></rdf:Alt></dc:description>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdf:Producer>").append(Common.xml(producer)).append("</pdf:Producer>");
  x.append("<pdf:Keywords>EA-PDF</pdf:Keywords>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<xmp:CreateDate>").append(Common.xml(now)).append("</xmp:CreateDate>");
  x.append("<xmp:ModifyDate>").append(Common.xml(now)).append("</xmp:ModifyDate>");
  x.append("<xmp:MetadataDate>").append(Common.xml(now)).append("</xmp:MetadataDate>");
  x.append("<xmp:CreatorTool>").append(Common.xml(creatorTool)).append("</xmp:CreatorTool>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfaid:part>4</pdfaid:part>");
  x.append("<pdfaid:rev>2020</pdfaid:rev>");
  x.append("<pdfaid:conformance>F</pdfaid:conformance>");
  x.append("</rdf:Description>");

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailid:version>1</pdfmailid:version>");
  x.append("<pdfmailid:rev>2024</pdfmailid:rev>");
  x.append("<pdfmailid:conformance>").append(Common.xml(archiveProfile)).append("</pdfmailid:conformance>");
  x.append("</rdf:Description>");

  appendSourceProblems(x, records);
  x.append(pdfUaIdentification());
  appendIsoTs32005Declaration(x, creatorTool, claimDate);
  appendConversionHistory(x, creatorTool, now, archiveProfile, records.size());

  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfmailmeta:Mail_GUID><rdf:Seq>");
  for (EmailRecord r : records) {
    if (!Common.nz(r.mailGuid).isBlank()) {
      x.append("<rdf:li>").append(Common.xml(r.mailGuid)).append("</rdf:li>");
    }
  }
  x.append("</rdf:Seq></pdfmailmeta:Mail_GUID>");
  x.append("<pdfmailmeta:messageid><rdf:Seq>");
  for (EmailRecord r : records) {
    if (!Common.nz(r.messageId).isBlank()) {
      x.append("<rdf:li>").append(Common.xml(r.messageId)).append("</rdf:li>");
    }
  }
  x.append("</rdf:Seq></pdfmailmeta:messageid>");

  x.append("<pdfmailmeta:email><rdf:Bag>");
  for (EmailRecord r : records) {
    x.append("<rdf:li rdf:parseType=\"Resource\">");
    appendStringProp(x, "Subject", r.subject);
    appendStringProp(x, "Raw-From", r.rawFrom);
    appendStringProp(x, "Raw-To", r.rawTo);
    appendStringProp(x, "Raw-Cc", r.rawCc);
    appendStringProp(x, "Raw-Bcc", r.rawBcc);
    if (!Common.nz(r.sentXmp).isBlank()) appendStringProp(x, "Sent", r.sentXmp);
    if (shouldEmitRawSent(r)) appendStringProp(x, "Raw-Sent", !Common.nz(r.rawDateHeader).isBlank() ? r.rawDateHeader : r.date);
    appendStringProp(x, "SizeInBytes", Integer.toString(messageMetadataSize(r)));
    appendStringProp(x, "NumberAttachments", Integer.toString(Math.max(r.attachmentCount, 0)));
    appendStringProp(x, "Mail_GUID", r.mailGuid);
    appendStringProp(x, "messageid", r.messageId);
    appendReplyMetadata(x, r);
    appendFoafSeq(x, "From", r.fromAgents);
    appendFoafSeq(x, "To", r.toAgents);
    appendFoafSeq(x, "Cc", r.ccAgents);
    appendFoafSeq(x, "Bcc", r.bccAgents);
    x.append("</rdf:li>");
  }
  x.append("</rdf:Bag></pdfmailmeta:email>");

  x.append("<pdfmailmeta:assets><rdf:Bag>");
  if (!"ci".equals(archiveProfile)) {
    for (Asset plan : assets.archive(records)) {
      if (!"Source".equals(plan.relationship) && !plan.sourceSetMember) continue;
      x.append("<rdf:li rdf:parseType=\"Resource\">");
      appendStringProp(x, "Filename", plan.filename);
      appendStringProp(x, "SizeInBytes", Integer.toString(plan.size));
      appendStringProp(x, "ContentType", plan.mime);
      appendStringProp(x, "Checksum", plan.checksumMd5);
      if ("Source".equals(plan.relationship)) appendStringProp(x, "NumberMessages", "1");
      x.append("</rdf:li>");
    }
  }
  x.append("</rdf:Bag></pdfmailmeta:assets>");

  x.append("<pdfmailmeta:attachments><rdf:Bag>");
  for (EmailRecord r : records) {
    for (AssociatedFile af : r.associatedFiles) {
      if (!af.emailAttachment) continue;
      x.append("<rdf:li rdf:parseType=\"Resource\">");
      appendStringProp(x, "Filename", Common.nz(af.filename));
      appendStringProp(x, "SizeInBytes", Integer.toString(assets.size(r, af)));
      appendStringProp(x, "Content-Type", Common.sourceContentType(af));
      if (r.embedSourceMessage && !Common.nz(af.checksumMd5).isBlank()) appendStringProp(x, "CheckSum", Common.nz(af.checksumMd5));
      appendStringProp(x, "Mail_GUID", Common.nz(r.mailGuid));
      appendStringProp(x, "Description", assets.description(r, af));
      x.append("</rdf:li>");
    }
  }
  x.append("</rdf:Bag></pdfmailmeta:attachments>");

  x.append("</rdf:Description>");
  x.append("</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>");
  return x.toString();
}

String buildDescription(EmailRecord r) {
  StringBuilder d = new StringBuilder("EA-PDF email archive");
  if (!Common.nz(r.archiveGlobalId).isBlank()) d.append("; archiveGlobalId=").append(Common.nz(r.archiveGlobalId));
  if (!Common.nz(r.messageId).isBlank()) d.append("; messageId=").append(Common.nz(r.messageId));
  if (!Common.nz(r.mailGuid).isBlank()) d.append("; mailGuid=").append(Common.nz(r.mailGuid));
  if (!r.archiveEmails.isEmpty()) d.append("; archiveEmails=").append(String.join(", ", r.archiveEmails));
  if (!Common.sourceDerivation(r).isBlank()) d.append("; ").append(Common.sourceDerivation(r));
  return d.toString();
}

void appendReplyMetadata(StringBuilder metadata, EmailRecord record) {
  if (record.inReplyTo.isEmpty()) return;
  metadata.append("<pdfmailmeta:In-Reply-To><rdf:Bag>");
  for (String identifier : record.inReplyTo) metadata.append("<rdf:li>").append(Common.xml(identifier)).append("</rdf:li>");
  metadata.append("</rdf:Bag></pdfmailmeta:In-Reply-To>");
}

String buildArchiveDescription(List<EmailRecord> records, String archiveProfile) {
  StringBuilder d = new StringBuilder("EA-PDF email archive");
  d.append("; messages=").append(records.size());
  d.append("; folders=").append(assets.folders(records));
  d.append("; attachments=").append(records.stream().mapToLong(r -> Math.max(r.attachmentCount, 0)).sum());
  d.append("; conformance=").append(archiveProfile);
  String archiveGlobalId = "";
  List<String> archiveEmails = new ArrayList<>();
  for (EmailRecord r : records) {
    if (archiveGlobalId.isBlank() && !Common.nz(r.archiveGlobalId).isBlank()) archiveGlobalId = Common.nz(r.archiveGlobalId);
    for (String email : r.archiveEmails) {
      if (!archiveEmails.contains(email)) archiveEmails.add(email);
    }
  }
  if (!archiveGlobalId.isBlank()) d.append("; archiveGlobalId=").append(archiveGlobalId);
  if (!archiveEmails.isEmpty()) d.append("; archiveEmails=").append(String.join(", ", archiveEmails));
  for (EmailRecord record : records) {
    if (!Common.sourceDerivation(record).isBlank()) d.append("; Mail_GUID=").append(record.mailGuid).append(": ").append(Common.sourceDerivation(record));
  }
  return d.toString();
}

String buildArchiveTitle(List<EmailRecord> records) {
  if (records.size() == 1) {
    String subject = Common.nz(records.get(0).subject);
    return subject.isBlank() ? "Email Archive" : subject;
  }
  return "Email Archive (" + records.size() + " messages)";
}

String buildMailGuid(EmailRecord r) throws Exception {
  Common.finalizeRecordMetadata(r);
  return Common.nz(r.mailGuid);
}

void appendStringProp(StringBuilder x, String prop, String value) {
  if (Common.nz(value).isBlank()) return;
  x.append("<pdfmailmeta:").append(prop).append(">")
   .append(Common.xml(value))
   .append("</pdfmailmeta:").append(prop).append(">");
}

void appendFoafSeq(StringBuilder x, String prop, List<MailAgent> agents) {
  if (agents == null || agents.isEmpty()) return;
  agents = agents.stream().filter(agent -> Common.smtpMailbox(agent.email)).toList();
  if (agents.isEmpty()) return;
  x.append("<pdfmailmeta:").append(prop).append("><rdf:Seq>");
  for (MailAgent a : agents) {
    String email = Common.nz(a.email);
    if (email.isBlank()) continue;
    x.append("<rdf:li><rdf:Description");
    x.append(" foaf:mbox=\"").append(Common.xmlAttr(Common.mailto(email))).append("\"");
    if (!Common.nz(a.name).isBlank()) x.append(" foaf:name=\"").append(Common.xmlAttr(a.name)).append("\"");
    x.append("/></rdf:li>");
  }
  x.append("</rdf:Seq></pdfmailmeta:").append(prop).append(">");
}

boolean shouldEmitRawSent(EmailRecord r) {
  String raw = Common.nz(r.rawDateHeader).trim();
  if (raw.isBlank()) return false;
  return Common.sourceDateNeedsRaw(raw);
}

int messageMetadataSize(EmailRecord r) {
  if (r == null) return 0;
  for (AssociatedFile af : r.associatedFiles) {
    if (!"Source".equals(Common.nz(af.afRelationship))) continue;
    if ("message/rfc822".equalsIgnoreCase(Common.nz(af.mime)) || Common.nz(af.filename).toLowerCase(Locale.ROOT).endsWith(".eml")) {
      if (af.hasData() && af.size() > 0) return af.size();
    }
  }
  if (r.sourceBytes != null && r.sourceBytes.length > 0) return r.sourceBytes.length;
  if (r.payloadStaged) {
    for (AssociatedFile file : r.associatedFiles) {
      if ("Source".equals(file.afRelationship)) return file.size();
    }
  }
  try {
    byte[] synthesized = Common.synthesizeEml(r);
    return synthesized.length;
  } catch (Exception ignored) {
    return 0;
  }
}

void appendIsoTs32005Declaration(StringBuilder x, String claimBy, String claimDate) {
  x.append("<rdf:Description rdf:about=\"\">");
  x.append("<pdfd:declarations><rdf:Bag><rdf:li rdf:parseType=\"Resource\">");
  x.append("<pdfd:conformsTo>https://pdfa.org/declarations#iso32005</pdfd:conformsTo>");
  x.append("<pdfd:claimData><rdf:Bag><rdf:li rdf:parseType=\"Resource\">");
  x.append("<pdfd:claimBy>").append(Common.xml(claimBy)).append("</pdfd:claimBy>");
  x.append("<pdfd:claimDate>").append(Common.xml(claimDate)).append("</pdfd:claimDate>");
  x.append("</rdf:li></rdf:Bag></pdfd:claimData>");
  x.append("</rdf:li></rdf:Bag></pdfd:declarations>");
  x.append("</rdf:Description>");
}

void appendConversionHistory(StringBuilder x, String tool, String timestamp, String profile, int messages) {
  String documentId = "uuid:" + UUID.randomUUID();
  String instanceId = "uuid:" + UUID.randomUUID();
  x.append("<rdf:Description rdf:about=\"\" xmlns:xmpMM=\"http://ns.adobe.com/xap/1.0/mm/\"");
  x.append(" xmlns:stEvt=\"http://ns.adobe.com/xap/1.0/sType/ResourceEvent#\">");
  x.append("<xmpMM:DocumentID>").append(documentId).append("</xmpMM:DocumentID>");
  x.append("<xmpMM:InstanceID>").append(instanceId).append("</xmpMM:InstanceID>");
  x.append("<xmpMM:History><rdf:Seq><rdf:li rdf:parseType=\"Resource\">");
  x.append("<stEvt:action>converted</stEvt:action>");
  x.append("<stEvt:softwareAgent>").append(Common.xml(tool)).append("</stEvt:softwareAgent>");
  x.append("<stEvt:when>").append(Common.xml(timestamp)).append("</stEvt:when>");
  x.append("<stEvt:parameters>Converted ").append(messages).append(messages == 1 ? " email" : " emails");
  x.append(" to PDF/mail-1").append(Common.xml(profile)).append(".</stEvt:parameters>");
  x.append("</rdf:li></rdf:Seq></xmpMM:History></rdf:Description>");
}
}
