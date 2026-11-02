package eapdf;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.text.PDFTextStripper;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSigningTests {
  @TempDir Path directory;
  static final char[] PASSWORD = "temporary-test-password".toCharArray();
  record Identity(KeyPair pair, X509Certificate certificate) {}

  static Identity identity(String algorithm, Instant expires, int usage) throws Exception {
    KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
    generator.initialize("RSA".equals(algorithm) ? 2048 : 256);
    KeyPair pair = generator.generateKeyPair();
    X500Name name = new X500Name("CN=EA-PDF TEST ONLY");
    var builder = new JcaX509v3CertificateBuilder(name, new BigInteger(120, new SecureRandom()),
        Date.from(Instant.now().minusSeconds(86400)), Date.from(expires), name, pair.getPublic());
    builder.addExtension(Extension.keyUsage, true, new KeyUsage(usage));
    var certificate = builder.build(new JcaContentSignerBuilder("RSA".equals(algorithm) ? "SHA256withRSA" : "SHA256withECDSA").build(pair.getPrivate()));
    return new Identity(pair, new JcaX509CertificateConverter().getCertificate(certificate));
  }

  EapdfConfig.SigningConfig store(Identity identity, String filename, boolean duplicate) throws Exception {
    KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, PASSWORD);
    store.setKeyEntry("test", identity.pair().getPrivate(), PASSWORD, new java.security.cert.Certificate[]{identity.certificate()});
    if (duplicate) store.setKeyEntry("second", identity.pair().getPrivate(), PASSWORD, new java.security.cert.Certificate[]{identity.certificate()});
    Path path = directory.resolve(filename);
    try (OutputStream output = Files.newOutputStream(path)) { store.store(output, PASSWORD); }
    var config = new EapdfConfig.SigningConfig(); config.enabled = true; config.pkcs12Path = path.toString();
    return config;
  }

  @Test void rsaAndEcSignRealPdfWithoutChangingPagesMetadataOrAttachments() throws Exception {
    for (String algorithm : List.of("RSA", "EC")) {
      Identity identity = identity(algorithm, Instant.now().plusSeconds(86400), KeyUsage.digitalSignature);
      var config = store(identity, algorithm + ".p12", false);
      Path input = Path.of("examples/eml/eml_Agenda_and_timeline_slides_archive_1c.pdf");
      Path signed = directory.resolve(algorithm + ".pdf"); Files.copy(input, signed);
      var signer = PdfSigning.load(config, PASSWORD);
      signer.signInPlace(signed); PdfSigning.verify(signed, identity.certificate());
      try (var before = Loader.loadPDF(input.toFile()); var after = Loader.loadPDF(signed.toFile())) {
        assertEquals(before.getNumberOfPages(), after.getNumberOfPages());
        assertEquals(new PDFTextStripper().getText(before), new PDFTextStripper().getText(after));
        assertArrayEquals(before.getDocumentCatalog().getMetadata().toByteArray(), after.getDocumentCatalog().getMetadata().toByteArray());
        var a = before.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
        var b = after.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
        assertEquals(a.keySet(), b.keySet());
        for (String filename : a.keySet()) assertArrayEquals(a.get(filename).getEmbeddedFile().toByteArray(), b.get(filename).getEmbeddedFile().toByteArray());
        assertEquals(1, after.getSignatureDictionaries().size());
        var widget = after.getSignatureFields().get(0).getWidgets().get(0);
        assertEquals(0, widget.getRectangle().getWidth()); assertEquals(0, widget.getRectangle().getHeight());
        assertFalse(after.getDocumentCatalog().getAcroForm(null).getCOSObject().containsKey(COSName.XFA));
      }
    }
  }

  @Test void signatureRangesHandleOffsetsBeyondTwoGiBWithoutBufferingTheFile() throws Exception {
    Path sparse = directory.resolve("large-range.bin");
    long offset = (1L << 31) + 100;
    try (var file = new RandomAccessFile(sparse.toFile(), "rw")) {
      file.write(new byte[]{1, 2}); file.seek(offset); file.write(new byte[]{3, 4});
    }
    try (var input = new PdfSigning.ByteRanges(sparse, new long[]{0, 2, offset, 2})) {
      assertArrayEquals(new byte[]{1, 2, 3, 4}, input.readAllBytes());
    }
  }

  @Test void changesAndTrailingBytesAreRejected() throws Exception {
    Identity identity = identity("RSA", Instant.now().plusSeconds(86400), KeyUsage.digitalSignature);
    var signer = PdfSigning.load(store(identity, "test.p12", false), PASSWORD);
    Path signed = directory.resolve("signed.pdf"); signer.sign(Path.of("examples/eml/eml_Agenda_and_timeline_slides_1s.pdf"), signed);
    byte[] valid = Files.readAllBytes(signed);
    byte[] changed = valid.clone(); changed[10] ^= 1; Files.write(signed, changed);
    assertThrows(Exception.class, () -> PdfSigning.verify(signed, identity.certificate()));
    Files.write(signed, valid); Files.writeString(signed, "\n% appended modification\n", StandardOpenOption.APPEND);
    assertThrows(GeneralSecurityException.class, () -> PdfSigning.verify(signed, identity.certificate()));
  }

  @Test void badCredentialsAndUnsuitableCertificatesStopSigning() throws Exception {
    Identity identity = identity("RSA", Instant.now().plusSeconds(86400), KeyUsage.digitalSignature);
    var cfg = store(identity, "valid.p12", true);
    assertThrows(IOException.class, () -> PdfSigning.load(cfg, "wrong".toCharArray()));
    assertThrows(Common.UserFacingException.class, () -> PdfSigning.load(cfg, PASSWORD));
    cfg.alias = "unknown"; assertThrows(Common.UserFacingException.class, () -> PdfSigning.load(cfg, PASSWORD));
    cfg.alias = "test"; assertNotNull(PdfSigning.load(cfg, PASSWORD));
    var expired = store(identity("RSA", Instant.now().minusSeconds(3600), KeyUsage.digitalSignature), "expired.p12", false);
    assertThrows(java.security.cert.CertificateExpiredException.class, () -> PdfSigning.load(expired, PASSWORD));
    var unsuitable = store(identity("RSA", Instant.now().plusSeconds(86400), KeyUsage.keyEncipherment), "unsuitable.p12", false);
    assertThrows(GeneralSecurityException.class, () -> PdfSigning.load(unsuitable, PASSWORD));
  }

  @Test void diskBackedCollectionChildStaysUnsignedWhenSigningIsEnabled() throws Exception {
    var settings = EapdfConfig.get().signing;
    boolean before = settings.enabled;
    settings.enabled = true;
    try {
      Path source = Path.of("in/Agenda and timeline slides.eml");
      var record = EmlReader.readRfc822Bytes(Files.readAllBytes(source), source.toString(), source.toString(), "", List.of(), false);
      Path child = directory.resolve("child.pdf");
      PdfMailWriter.buildSingleMessagePdf(child, record, TestSupport.fontPath(), TestSupport.iccPath(),
          false, directory, 1, "s", child);
      try (var document = Loader.loadPDF(child.toFile())) { assertTrue(document.getSignatureDictionaries().isEmpty()); }
    } finally { settings.enabled = before; }
  }

  @Test void failedSigningKeepsExistingFileAndRemovesTemporaryOutput() throws Exception {
    Identity identity = identity("RSA", Instant.now().plusSeconds(86400), KeyUsage.digitalSignature);
    var signer = PdfSigning.load(store(identity, "test.p12", false), PASSWORD);
    Path target = directory.resolve("existing.pdf"); Files.writeString(target, "previous output");
    assertThrows(IOException.class, () -> signer.signInPlace(target));
    assertEquals("previous output", Files.readString(target));
    try (var files = Files.list(directory)) { assertEquals(2, files.count()); }
    signer.sign(Path.of("examples/eml/eml_Agenda_and_timeline_slides_1s.pdf"), target);
    byte[] once = Files.readAllBytes(target);
    assertThrows(IOException.class, () -> signer.signInPlace(target));
    assertArrayEquals(once, Files.readAllBytes(target));
  }
}
