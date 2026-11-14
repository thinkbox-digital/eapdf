package eapdf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Assertions;

final class TestSupport {
  static final List<String> INPUT_FIXTURES = List.of("sample.eml", "sample.mbox", "test.pst");

  private TestSupport() {}

  static String fontPath() {
    return Common.resolveFontPath(null);
  }

  static String iccPath() {
    return Common.resolveIccPath(null);
  }

  static Path firstInputWithExtension(String extension) throws IOException {
    for (String filename : INPUT_FIXTURES) {
      if (filename.toLowerCase(java.util.Locale.ROOT).endsWith(extension.toLowerCase(java.util.Locale.ROOT))) {
        return fixturePath(filename);
      }
    }
    throw new IOException("No classpath input fixture found for extension " + extension);
  }

  static Path fixturePath(String filename) throws IOException {
    var resource = TestSupport.class.getResource("/eapdf/email/" + filename);
    if (resource == null) throw new IOException("Missing test resource: eapdf/email/" + filename);
    if ("file".equals(resource.getProtocol())) {
      try {
        return Path.of(resource.toURI());
      } catch (java.net.URISyntaxException exception) {
        throw new IOException(exception);
      }
    }
    Path extracted = Files.createTempFile("eapdf-fixture-", "-" + filename);
    extracted.toFile().deleteOnExit();
    try (var input = resource.openStream()) {
      Files.copy(input, extracted, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    return extracted;
  }

  static String metadataXml(PDDocument doc) throws IOException {
    PDDocumentCatalog cat = doc.getDocumentCatalog();
    PDMetadata metadata = cat.getMetadata();
    if (metadata == null) return "";
    return new String(metadata.toByteArray(), StandardCharsets.UTF_8);
  }

  static org.w3c.dom.Document parseMetadata(String xml) throws Exception {
    var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  static Set<String> embeddedFileNames(PDDocument doc) throws IOException {
    Set<String> names = new LinkedHashSet<>();
    PDDocumentNameDictionary dict = doc.getDocumentCatalog().getNames();
    if (dict == null || dict.getEmbeddedFiles() == null || dict.getEmbeddedFiles().getNames() == null) return names;
    for (Map.Entry<String, PDComplexFileSpecification> entry : dict.getEmbeddedFiles().getNames().entrySet()) {
      names.add(entry.getKey());
    }
    return names;
  }

  static List<String> folderNames(PDDocument doc) {
    COSDictionary collection = collectionDictionary(doc);
    COSBase foldersBase = collection == null ? null : collection.getDictionaryObject(COSName.getPDFName("Folders"));
    COSDictionary root = derefDict(foldersBase);
    List<String> names = new ArrayList<>();
    if (root == null) return names;
    COSDictionary child = derefDict(root.getDictionaryObject(COSName.getPDFName("Child")));
    while (child != null) {
      names.add(child.getString(COSName.NAME));
      child = derefDict(child.getDictionaryObject(COSName.NEXT));
    }
    return names;
  }

  static COSDictionary collectionDictionary(PDDocument doc) {
    return derefDict(doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("Collection")));
  }

  static COSDictionary derefDict(COSBase base) {
    if (base instanceof COSObject obj) base = obj.getObject();
    return base instanceof COSDictionary dict ? dict : null;
  }

  static List<String> schemaFieldNames(PDDocument doc) {
    COSDictionary collection = collectionDictionary(doc);
    COSDictionary schema = collection == null ? null : derefDict(collection.getDictionaryObject(COSName.getPDFName("Schema")));
    if (schema == null) return List.of();
    List<String> fields = new ArrayList<>();
    for (COSName key : schema.keySet()) fields.add(key.getName());
    return fields;
  }

  static void assertOutlineDestinationsResolve(PDDocument doc) throws IOException {
    PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
    Assertions.assertNotNull(outline, "document outline should be present");
    PDOutlineItem item = outline.getFirstChild();
    int count = 0;
    while (item != null) {
      count += assertOutlineItemResolves(doc, item);
      item = item.getNextSibling();
    }
    Assertions.assertTrue(count > 0, "expected at least one outline item");
  }

  private static int assertOutlineItemResolves(PDDocument doc, PDOutlineItem item) throws IOException {
    int count = 1;
    var page = item.findDestinationPage(doc);
    Assertions.assertNotNull(page, "outline item should resolve: " + item.getTitle());
    int pageNumber = doc.getPages().indexOf(page);
    Assertions.assertTrue(pageNumber >= 0 && pageNumber < doc.getNumberOfPages(),
        "outline page number out of range for " + item.getTitle() + ": " + pageNumber);
    PDOutlineItem child = item.getFirstChild();
    while (child != null) {
      count += assertOutlineItemResolves(doc, child);
      child = child.getNextSibling();
    }
    return count;
  }

  static void assertPdfInfoBlank(PDDocument doc) {
    PDDocumentInformation info = doc.getDocumentInformation();
    Assertions.assertTrue(info.getCOSObject().size() == 0, "document info dictionary should be cleared");
  }

  static void assertCollectionHasNoDefaultDocument(PDDocument doc) {
    COSDictionary collection = collectionDictionary(doc);
    Assertions.assertNotNull(collection, "collection dictionary should exist");
    Assertions.assertNull(collection.getDictionaryObject(COSName.getPDFName("D")), "Collection.D must be absent");
  }

  static void assertAfPresent(PDDocument doc) {
    COSArray af = (COSArray) doc.getDocumentCatalog().getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
    Assertions.assertNotNull(af, "catalog AF array should exist");
    Assertions.assertTrue(af.size() > 0, "catalog AF array should not be empty");
  }
}
