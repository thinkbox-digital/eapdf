package eapdf;

import static eapdf.EaPdfChecks.*;
import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

class AttachmentOwnerScopeTests {
  static final Path SAMPLE = Path.of("..", "tests/quality/fixtures/pst_native_attachment_scope_legacy.pdf");

  static List<Element> records(Element root, String field) {
    Element property = (Element) root.getElementsByTagNameNS(MAIL, field).item(0);
    return children(children(property).getFirst());
  }

  static JavaValidation.Report assess(PDDocument document, String changedField) throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
    var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document.getDocumentCatalog().getMetadata().toByteArray()));
    var emails = records(xml.getDocumentElement(), "email");
    var attachments = records(xml.getDocumentElement(), "attachments");
    if (changedField != null) {
      Element record = attachments.stream().filter(a -> "image001.gif".equals(field(a, "Filename"))).findFirst().orElseThrow();
      Element value = fieldElement(record, changedField);
      if (value == null) { value = xml.createElementNS(MAIL, "pdfmailmeta:" + changedField); record.appendChild(value); }
      value.setTextContent("CheckSum".equals(changedField) ? "0".repeat(32) : "1");
    }
    List<EaPdfChecks.Embedded> files = new ArrayList<>();
    var catalog = document.getDocumentCatalog().getCOSObject();
    java.util.Set<COSDictionary> specifications = identitySet();
    if (get(catalog, "AF") instanceof COSArray array) for (var item : array) if (resolve(item) instanceof COSDictionary spec) specifications.add(spec);
    for (PDPage page : document.getPages()) for (var annotation : page.getAnnotations()) {
      COSDictionary spec = dict(annotation.getCOSObject(), "FS");
      if (spec != null) specifications.add(spec);
    }
    for (var spec : specifications) {
      var ef = dict(spec, "EF");
      COSStream stream = (COSStream) (get(ef, "UF") == null ? get(ef, "F") : get(ef, "UF"));
      byte[] bytes; try (var input = stream.createInputStream()) { bytes = input.readAllBytes(); }
      String filename = string(get(spec, "UF")); if (filename.isEmpty()) filename = string(get(spec, "F"));
      files.add(new EaPdfChecks.Embedded("", spec, stream, bytes, filename, name(get(stream, "Subtype")),
          HexFormat.of().formatHex(java.security.MessageDigest.getInstance("MD5").digest(bytes)), name(get(spec, "AFRelationship"))));
    }
    var report = new JavaValidation.Report(SAMPLE.toString());
    new MetadataChecks(report, files, List.of(), emails, List.of(), attachments, "mi").nativeAttachments(document);
    return report;
  }

  static boolean has(JavaValidation.Report report, String code, String level) {
    return report.items.asList().stream().map(item -> item.getAsJsonObject()).anyMatch(item -> code.equals(item.get("code").getAsString()) && level.equals(item.get("level").getAsString()));
  }

  static PDPage urlPage(PDDocument document) throws Exception {
    for (PDPage page : document.getPages()) for (var annotation : page.getAnnotations()) {
      COSDictionary spec = dict(annotation.getCOSObject(), "FS");
      if (string(get(spec, "UF")).startsWith("_~DLNK")) return page;
    }
    throw new AssertionError("Missing real URL placeholder");
  }

  @Test void ordinaryIsolatedArchiveCompletesNativeComparisons() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      var report = assess(document, null);
      assertTrue(has(report, "ATTACHMENT_XMP_SIZE_MATCH", "PASS"));
      assertFalse(has(report, "ATTACHMENT_XMP_SIZE_MATCH", "FAIL"));
      assertFalse(has(report, "VALIDATION_INCOMPLETE", "WARN"));
    }
  }

  @Test void missingPageOwnerKeepsTheAssociationUnavailable() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      urlPage(document).getCOSObject().removeItem(COSName.getPDFName("DPart"));
      assertTrue(has(assess(document, null), "VALIDATION_INCOMPLETE", "WARN"));
    }
  }

  @Test void additionalUnknownReferencePreventsAFalseScopedPass() throws Exception {
    try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      PDPage source = urlPage(document);
      COSDictionary original = source.getAnnotations().stream().map(a -> dict(a.getCOSObject(), "FS"))
          .filter(spec -> string(get(spec, "UF")).startsWith("_~DLNK")).findFirst().orElseThrow();
      PDPage page = new PDPage(); document.addPage(page);
      var annotation = new PDAnnotationFileAttachment(); annotation.getCOSObject().setItem("FS", original);
      page.setAnnotations(List.of(annotation));
      assertTrue(has(assess(document, null), "VALIDATION_INCOMPLETE", "WARN"));
    }
  }

  @Test void incorrectSizeAndChecksumRemainFailures() throws Exception {
    for (String field : List.of("SizeInBytes", "CheckSum")) try (var document = Loader.loadPDF(SAMPLE.toFile())) {
      assertTrue(has(assess(document, field), "CheckSum".equals(field) ? "ATTACHMENT_XMP_CHECKSUM_MATCH" : "ATTACHMENT_XMP_SIZE_MATCH", "FAIL"), field);
    }
  }
}
