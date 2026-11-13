package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.verapdf.xmp.tools.SecureXML;
import org.xml.sax.SAXException;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamXmlSecurityTests {
  @TempDir Path directory;

  @Test void upstreamXmpParserRejectsDoctypeAndExternalEntities() throws Exception {
    var secret = directory.resolve("private.txt");
    Files.writeString(secret, "must not appear in parsed XML");
    var xml = "<!DOCTYPE root [<!ENTITY external SYSTEM '" + secret.toUri() + "'>]><root>&external;</root>";
    var builder = SecureXML.newSafeDocumentBuilder();
    assertThrows(SAXException.class, () -> builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
  }

  @Test void upstreamXmpParserRetainsNamespaceAwareNormalXml() throws Exception {
    var xml = "<x:root xmlns:x='urn:eapdf:security-test'><x:value>safe</x:value></x:root>";
    var document = SecureXML.newSafeDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    assertEquals("urn:eapdf:security-test", document.getDocumentElement().getNamespaceURI());
    assertEquals("safe", document.getDocumentElement().getTextContent());
  }
}
