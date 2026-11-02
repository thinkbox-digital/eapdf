package eapdf;

import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.xml.sax.InputSource;

class PremisBuilder {
  static byte[] buildCollection(Path out, List<EmailRecord> records, String profile,
                                 List<PdfMailWriter.CollectionMember> members) throws Exception {
    String aggregate = new String(buildAggregate(out, records, profile), StandardCharsets.UTF_8);
    StringBuilder childObjects = new StringBuilder();
    StringBuilder childEvents = new StringBuilder();
    StringBuilder links = new StringBuilder();
    var described = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<byte[], Boolean>());
    for (PdfMailWriter.CollectionMember member : members) {
      if (member.premisBytes == null || !described.add(member.premisBytes)) continue;
      String identifier = "child:" + member.folderId + ":" + member.filename;
      String child = new String(member.premisBytes, StandardCharsets.UTF_8);
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(child)));
      var elements = document.getDocumentElement().getChildNodes();
      for (int index = 0; index < elements.getLength(); index++) {
        var element = elements.item(index);
        if (!(element instanceof org.w3c.dom.Element node)) continue;
        boolean outputObject = "object".equals(node.getLocalName())
            && node.getElementsByTagNameNS("http://www.loc.gov/premis/v3", "objectIdentifierValue").item(0)
                .getTextContent().equals("object:" + member.filename);
        if (!outputObject && !"event".equals(node.getLocalName())) continue;
        var values = node.getElementsByTagName("*");
        for (int valueIndex = 0; valueIndex < values.getLength(); valueIndex++) {
          var value = values.item(valueIndex);
          if (value.getChildNodes().getLength() != 1 || value.getFirstChild().getNodeType() != org.w3c.dom.Node.TEXT_NODE) continue;
          if (value.getTextContent().equals("object:" + member.filename)) value.setTextContent("object:" + identifier);
          if (value.getTextContent().equals("event:conversion:" + member.filename)) value.setTextContent("event:conversion:" + identifier);
        }
        TransformerFactory transformers = TransformerFactory.newInstance();
        transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        Transformer transformer = transformers.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        var serialized = new java.io.StringWriter();
        transformer.transform(new DOMSource(node), new StreamResult(serialized));
        if (outputObject) childObjects.append(serialized);
        else childEvents.append(serialized);
      }
      appendLinkingIdentifier(links, "premis:linkingObjectIdentifier", "local", "object:" + identifier);
    }
    aggregate = aggregate.replace("</premis:event>", links + "</premis:event>");
    aggregate = aggregate.replace("<premis:event>", childObjects + "<premis:event>");
    aggregate = aggregate.replace("<premis:agent>", childEvents + "<premis:agent>");
    return prettyPrintXml(aggregate);
  }

  static byte[] buildSingle(Path out, EmailRecord record, String profile) throws Exception {
    Common.finalizeRecordMetadata(record);
    List<EmailRecord> records = List.of(record);
    return build(out, records, profile);
  }

  static byte[] buildAggregate(Path out, List<EmailRecord> records, String profile) throws Exception {
    for (EmailRecord record : records) {
      if (record != null) Common.finalizeRecordMetadata(record);
    }
    return build(out, records, profile);
  }

  static byte[] build(Path out, List<EmailRecord> records, String profile) throws Exception {
    String now = Common.xmpDateNowUtc();
    String outputName = out == null || out.getFileName() == null ? "output.pdf" : out.getFileName().toString();
    String objectId = "object:" + outputName;
    String eventId = "event:conversion:" + outputName;
    String agentId = "agent:EmailToPDFA";

    StringBuilder x = new StringBuilder();
    x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    x.append("<premis:premis xmlns:premis=\"http://www.loc.gov/premis/v3\"");
    x.append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"");
    x.append(" version=\"3.0\">");

    appendPdfObject(x, objectId, outputName, profile);
    appendRecordObjects(x, records);
    appendConversionEvent(x, eventId, now, profile, objectId, records, agentId);
    appendSoftwareAgent(x, agentId);

    x.append("</premis:premis>");
    return prettyPrintXml(x.toString());
  }

  static byte[] prettyPrintXml(String xml) throws Exception {
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    dbf.setNamespaceAware(true);
    dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

    TransformerFactory tf = TransformerFactory.newInstance();
    tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    try {
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
    } catch (IllegalArgumentException ignored) {}

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    transformer.transform(
        new DOMSource(dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)))),
        new StreamResult(out));
    return out.toByteArray();
  }

  static void appendPdfObject(StringBuilder x, String objectId, String outputName, String profile) {
    x.append("<premis:object xsi:type=\"premis:file\">");
    appendIdentifier(x, "premis:objectIdentifier", "local", objectId);
    x.append("<premis:objectCharacteristics>");
    x.append("<premis:compositionLevel>0</premis:compositionLevel>");
    x.append("<premis:format>");
    x.append("<premis:formatDesignation>");
    x.append("<premis:formatName>PDF</premis:formatName>");
    x.append("<premis:formatVersion>2.0</premis:formatVersion>");
    x.append("</premis:formatDesignation>");
    x.append("</premis:format>");
    x.append("</premis:objectCharacteristics>");
    x.append("<premis:originalName>").append(Common.xml(outputName)).append("</premis:originalName>");
    x.append("</premis:object>");
  }

  static void appendRecordObjects(StringBuilder x, List<EmailRecord> records) throws Exception {
    if (records == null) return;
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (EmailRecord record : records) {
      if (record == null) continue;
      String recordId = "record:" + record.mailGuid;
      if (seen.add(recordId)) {
        x.append("<premis:object xsi:type=\"premis:file\">");
        appendIdentifier(x, "premis:objectIdentifier", "local", recordId);
        if (!Common.nz(record.messageId).isBlank()) appendIdentifier(x, "premis:objectIdentifier", "Message-ID", record.messageId);
        x.append("<premis:objectCharacteristics>");
        x.append("<premis:compositionLevel>0</premis:compositionLevel>");
        x.append("<premis:format><premis:formatDesignation><premis:formatName>")
            .append(Common.xml(Common.nz(record.sourceMime).isBlank() ? "message/rfc822" : record.sourceMime))
            .append("</premis:formatName></premis:formatDesignation></premis:format>");
        x.append("</premis:objectCharacteristics>");
        if (!Common.nz(record.subject).isBlank()) {
          x.append("<premis:originalName>").append(Common.xml(record.subject)).append("</premis:originalName>");
        }
        x.append("</premis:object>");
      }

      List<AssociatedFile> attachments = PdfMailWriter.pdfEmbeddedFiles(record);
      for (AssociatedFile af : attachments) {
        if (af == null || !af.hasData()) continue;
        String afId = "embedded:" + record.mailGuid + ":" + (Common.nz(af.checksumSha256).isBlank() ? Common.sha256Hex(af.data()) : af.checksumSha256) + ":" + Common.nz(af.filename);
        if (!seen.add(afId)) continue;
        String mime = Common.detectMimeType(af.filename, af.mime, af.data());
        String sha256 = Common.nz(af.checksumSha256).isBlank() ? Common.sha256Hex(af.data()) : Common.nz(af.checksumSha256);
        x.append("<premis:object xsi:type=\"premis:file\">");
        appendIdentifier(x, "premis:objectIdentifier", "local", afId);
        x.append("<premis:objectCharacteristics>");
        x.append("<premis:compositionLevel>0</premis:compositionLevel>");
        x.append("<premis:fixity>");
        x.append("<premis:messageDigestAlgorithm>SHA-256</premis:messageDigestAlgorithm>");
        x.append("<premis:messageDigest>").append(Common.xml(sha256)).append("</premis:messageDigest>");
        x.append("</premis:fixity>");
        x.append("<premis:size>").append(af.size()).append("</premis:size>");
        x.append("<premis:format><premis:formatDesignation><premis:formatName>")
            .append(Common.xml(mime))
            .append("</premis:formatName></premis:formatDesignation></premis:format>");
        x.append("</premis:objectCharacteristics>");
        if (!Common.nz(af.filename).isBlank()) {
          x.append("<premis:originalName>").append(Common.xml(af.filename)).append("</premis:originalName>");
        }
        x.append("</premis:object>");
      }
    }
  }

  static void appendConversionEvent(StringBuilder x, String eventId, String when, String profile, String outputObjectId,
                                    List<EmailRecord> records, String agentId) {
    x.append("<premis:event>");
    appendIdentifier(x, "premis:eventIdentifier", "local", eventId);
    x.append("<premis:eventType>normalization</premis:eventType>");
    x.append("<premis:eventDateTime>").append(Common.xml(when)).append("</premis:eventDateTime>");
    x.append("<premis:eventDetailInformation><premis:eventDetail>Prepared EA-PDF ")
        .append(Common.xml(profile)).append(" content for serialization; this event does not attest final publication or independent conformance validation.");
    if (records != null) for (EmailRecord record : records) {
      if (record != null && !Common.sourceDerivation(record).isBlank()) x.append(" Mail_GUID=")
          .append(Common.xml(record.mailGuid)).append(": ").append(Common.xml(Common.sourceDerivation(record)));
    }
    x.append("</premis:eventDetail></premis:eventDetailInformation>");
    appendLinkingIdentifier(x, "premis:linkingAgentIdentifier", "local", agentId);
    appendLinkingIdentifier(x, "premis:linkingObjectIdentifier", "local", outputObjectId);
    if (records != null) {
      for (EmailRecord record : records) {
        if (record == null) continue;
        String recordId = "record:" + record.mailGuid;
        appendLinkingIdentifier(x, "premis:linkingObjectIdentifier", "local", recordId);
      }
    }
    x.append("</premis:event>");
  }

  static void appendSoftwareAgent(StringBuilder x, String agentId) {
    x.append("<premis:agent>");
    appendIdentifier(x, "premis:agentIdentifier", "local", agentId);
    x.append("<premis:agentName>EmailToPDFA</premis:agentName>");
    x.append("<premis:agentType>software</premis:agentType>");
    x.append("</premis:agent>");
  }

  static void appendIdentifier(StringBuilder x, String element, String type, String value) {
    x.append("<").append(element).append(">");
    String prefix = element.substring(0, element.indexOf(':'));
    String local = element.substring(element.indexOf(':') + 1);
    x.append("<").append(prefix).append(":").append(local).append("Type>").append(Common.xml(type)).append("</").append(prefix).append(":").append(local).append("Type>");
    x.append("<").append(prefix).append(":").append(local).append("Value>").append(Common.xml(value)).append("</").append(prefix).append(":").append(local).append("Value>");
    x.append("</").append(element).append(">");
  }

  static void appendLinkingIdentifier(StringBuilder x, String element, String type, String value) {
    String prefix = element.substring(0, element.indexOf(':'));
    String local = element.substring(element.indexOf(':') + 1);
    x.append("<").append(element).append(">");
    x.append("<").append(prefix).append(":").append(local).append("Type>").append(Common.xml(type)).append("</").append(prefix).append(":").append(local).append("Type>");
    x.append("<").append(prefix).append(":").append(local).append("Value>").append(Common.xml(value)).append("</").append(prefix).append(":").append(local).append("Value>");
    x.append("</").append(element).append(">");
  }
}
