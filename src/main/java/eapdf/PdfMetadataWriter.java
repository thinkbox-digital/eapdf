package eapdf;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.XMPSchema;
import org.apache.xmpbox.type.*;
import org.apache.xmpbox.xml.XmpSerializer;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Maps EA-PDF records to XMPBox; PDF publication remains with PdfMailWriter. */
final class PdfMetadataWriter {
  private static final String RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
  private static final String MAIL = "http://www.pdfa.org/eapdf/ns/meta/";
  private static final String FOAF = "http://xmlns.com/foaf/0.1/";
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

  String chooseEaPdfConformance(EmailRecord record) { return chooseEaPdfConformance(record, null); }
  String chooseEaPdfConformance(EmailRecord record, String override) {
    return !Common.nz(override).isBlank() ? override : record != null && !record.embedSourceMessage ? "si" : "s";
  }

  String buildXmpMetadata(EmailRecord record) throws Exception { return buildXmpMetadata(record, null); }
  String buildXmpMetadata(EmailRecord record, String override) throws Exception {
    return buildMetadata(List.of(record), chooseEaPdfConformance(record, override), false);
  }
  String buildXmpMetadata(List<EmailRecord> records, String profile) throws Exception {
    return buildMetadata(records, profile, true);
  }

  private String buildMetadata(List<EmailRecord> records, String profile, boolean archive) throws Exception {
    for (EmailRecord record : records) {
      Common.finalizeRecordMetadata(record);
      Common.requireCreationDate(record);
    }
    EmailRecord first = records.isEmpty() ? null : records.getFirst();
    String title = archive ? buildArchiveTitle(records) : Common.nz(first.subject).isBlank() ? "Email" : first.subject;
    String description = archive ? buildArchiveDescription(records, profile) : buildDescription(first);
    XMPMetadata metadata = standardMetadata(records, profile, title, description);
    XMPSchema mail = metadata.createAndAddDefaultSchema("pdfmailmeta", MAIL);
    if (archive) {
      ArrayProperty guids = array(mail, "Mail_GUID", Cardinality.Seq);
      ArrayProperty identifiers = array(mail, "messageid", Cardinality.Seq);
      for (EmailRecord record : records) {
        item(guids, record.mailGuid);
        item(identifiers, record.messageId);
      }
    }
    ArrayProperty emails = array(mail, "email", Cardinality.Bag);
    for (EmailRecord record : records) {
      AbstractStructuredType email = resource(emails);
      text(email, "Subject", record.subject);
      text(email, "Raw-From", record.rawFrom);
      text(email, "Raw-To", record.rawTo);
      text(email, "Raw-Cc", record.rawCc);
      text(email, "Raw-Bcc", record.rawBcc);
      text(email, "Sent", record.sentXmp);
      if (shouldEmitRawSent(record)) text(email, "Raw-Sent", !Common.nz(record.rawDateHeader).isBlank() ? record.rawDateHeader : record.date);
      text(email, "SizeInBytes", Integer.toString(messageMetadataSize(record)));
      text(email, "NumberAttachments", Integer.toString(Math.max(record.attachmentCount, 0)));
      text(email, "Mail_GUID", record.mailGuid);
      text(email, "messageid", record.messageId);
      if (!record.inReplyTo.isEmpty()) {
        ArrayProperty replies = array(email, "In-Reply-To", Cardinality.Bag);
        for (String identifier : record.inReplyTo) item(replies, identifier);
      }
      agents(email, "From", record.fromAgents);
      agents(email, "To", record.toAgents);
      agents(email, "Cc", record.ccAgents);
      agents(email, "Bcc", record.bccAgents);
    }
    ArrayProperty sources = array(mail, "assets", Cardinality.Bag);
    if (archive) {
      if (!"ci".equals(profile)) for (Asset asset : assets.archive(records)) {
        if (!"Source".equals(asset.relationship) && !asset.sourceSetMember) continue;
        AbstractStructuredType source = resource(sources);
        text(source, "Filename", asset.filename);
        text(source, "SizeInBytes", Integer.toString(asset.size));
        text(source, "ContentType", asset.mime);
        text(source, "Checksum", asset.checksumMd5);
        if ("Source".equals(asset.relationship)) text(source, "NumberMessages", "1");
      }
    } else for (AssociatedFile file : first.associatedFiles) {
      if (!assets.include(first, file)) continue;
      AbstractStructuredType source = resource(sources);
      text(source, "Filename", file.filename);
      text(source, "SizeInBytes", Integer.toString(assets.size(first, file)));
      text(source, "ContentType", assets.mime(file, file.filename));
      text(source, "Checksum", file.checksumMd5);
    }
    ArrayProperty attachments = array(mail, "attachments", Cardinality.Bag);
    for (EmailRecord record : records) for (AssociatedFile file : record.associatedFiles) {
      if (!file.emailAttachment) continue;
      AbstractStructuredType attachment = resource(attachments);
      text(attachment, "Filename", file.filename);
      text(attachment, "SizeInBytes", Integer.toString(assets.size(record, file)));
      text(attachment, "Content-Type", Common.sourceContentType(file));
      if (record.embedSourceMessage) text(attachment, "CheckSum", file.checksumMd5);
      text(attachment, "Mail_GUID", record.mailGuid);
      text(attachment, "Description", assets.description(record, file));
    }
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    new XmpSerializer() {
      @Override public void serializeFields(Document document, Element parent, List<AbstractField> fields,
                                            String namespace, String prefix, boolean wrap) {
        if (fields.stream().noneMatch(field -> field instanceof FoafAgent)) {
          super.serializeFields(document, parent, fields, namespace, prefix, wrap);
          return;
        }
        // Retain EA-PDF's existing rdf:Description/FOAF attribute representation.
        for (AbstractField field : fields) {
          MailAgent agent = ((FoafAgent) field).agent;
          Element item = document.createElementNS(RDF, "rdf:li");
          Element person = document.createElementNS(RDF, "rdf:Description");
          person.setAttributeNS(FOAF, "foaf:mbox", Common.mailto(agent.email));
          if (!Common.nz(agent.name).isBlank()) person.setAttributeNS(FOAF, "foaf:name", Common.normalizeBody(agent.name).replace('\n', ' ').replace('\t', ' '));
          item.appendChild(person);
          parent.appendChild(item);
        }
      }
    }.serialize(metadata, output, true);
    return output.toString(StandardCharsets.UTF_8);
  }

  private XMPMetadata standardMetadata(List<EmailRecord> records, String profile, String title, String description) {
    XMPMetadata metadata = XMPMetadata.createXMPMetadata();
    metadata.setEndXPacket("w");
    var dc = metadata.createAndAddDublinCoreSchema();
    dc.addCreator(Common.normalizeBody(CONFIG.requireCreator()));
    dc.setFormat("application/pdf");
    dc.setTitle(Common.normalizeBody(title));
    dc.setDescription(Common.normalizeBody(description));
    String tool = Common.productLabel(), now = Common.xmpDateNowUtc();
    var pdf = metadata.createAndAddAdobePDFSchema();
    pdf.setProducer(tool + " via OpenHTMLtoPDF + Apache PDFBox");
    pdf.setKeywords("EA-PDF");
    var basic = metadata.createAndAddXMPBasicSchema();
    text(basic, "CreateDate", now);
    text(basic, "ModifyDate", now);
    text(basic, "MetadataDate", now);
    basic.setCreatorTool(tool);
    XMPSchema pdfa = metadata.createAndAddDefaultSchema("pdfaid", "http://www.aiim.org/pdfa/ns/id/");
    text(pdfa, "part", "4"); text(pdfa, "rev", "2020"); text(pdfa, "conformance", "F");
    XMPSchema identification = metadata.createAndAddDefaultSchema("pdfmailid", "http://www.pdfa.org/eapdf/ns/id/");
    identification.addNamespace("http://www.pdfa.org/eapdf/", "pdfmail");
    text(identification, "version", "1"); text(identification, "rev", "2024"); text(identification, "conformance", profile);
    if (records.stream().anyMatch(record -> !record.sourceProblems.isEmpty())) {
      XMPSchema conversion = metadata.createAndAddDefaultSchema("conversion", "https://thinkbox.digital/ns/eapdf/conversion/1.0/");
      text(conversion, "status", "INCOMPLETE");
      ArrayProperty problems = array(conversion, "problems", Cardinality.Seq);
      for (EmailRecord record : records) for (String problem : record.sourceProblems)
        item(problems, record.mailGuid + " | " + record.subject + " | " + problem);
    }
    if (pdfUaFeaturesEnabled()) {
      XMPSchema ua = metadata.createAndAddDefaultSchema("pdfuaid", "http://www.aiim.org/pdfua/ns/id/");
      text(ua, "part", "2"); text(ua, "rev", "2024");
    }
    XMPSchema declarations = metadata.createAndAddDefaultSchema("pdfd", "http://pdfa.org/declarations/");
    AbstractStructuredType declaration = resource(array(declarations, "declarations", Cardinality.Bag));
    text(declaration, "conformsTo", "https://pdfa.org/declarations#iso32005");
    AbstractStructuredType claim = resource(array(declaration, "claimData", Cardinality.Bag));
    text(claim, "claimBy", tool); text(claim, "claimDate", LocalDate.now(ZoneOffset.UTC).toString());
    var management = metadata.createAndAddXMPMediaManagementSchema();
    management.setDocumentID("uuid:" + UUID.randomUUID());
    management.setInstanceID("uuid:" + UUID.randomUUID());
    ResourceEventType event = new ResourceEventType(metadata);
    event.setAction("converted"); event.setSoftwareAgent(tool);
    text(event, "when", now);
    event.setParameters("Converted " + records.size() + (records.size() == 1 ? " email" : " emails") + " to PDF/mail-1" + profile + ".");
    array(management, "History", Cardinality.Seq).addProperty(event);
    return metadata;
  }

  private static void text(AbstractStructuredType parent, String name, String value) {
    if (!Common.nz(value).isBlank()) parent.addProperty(parent.createTextType(name, Common.normalizeBody(value)));
  }
  private static ArrayProperty array(AbstractStructuredType parent, String name, Cardinality kind) {
    ArrayProperty result = parent.createArrayProperty(name, kind);
    parent.addProperty(result);
    return result;
  }
  private static AbstractStructuredType resource(ArrayProperty parent) {
    var result = new DefinedStructuredType(parent.getMetadata(), parent.getNamespace(), parent.getPrefix(), "li");
    parent.addProperty(result);
    return result;
  }
  private static void item(ArrayProperty parent, String value) {
    if (!Common.nz(value).isBlank()) parent.addProperty(new TextType(parent.getMetadata(), RDF, "rdf", "li", Common.normalizeBody(value)));
  }
  private static final class FoafAgent extends DefinedStructuredType {
    final MailAgent agent;
    FoafAgent(XMPMetadata metadata, MailAgent agent) { super(metadata, FOAF, "foaf", "li"); this.agent = agent; }
  }
  private static void agents(AbstractStructuredType parent, String property, List<MailAgent> agents) {
    if (agents == null) return;
    List<MailAgent> selected = agents.stream().filter(agent -> Common.smtpMailbox(agent.email)).toList();
    if (selected.isEmpty()) return;
    ArrayProperty sequence = array(parent, property, Cardinality.Seq);
    for (MailAgent agent : selected) sequence.addProperty(new FoafAgent(parent.getMetadata(), agent));
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

}
