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

  /** Mask only the two fixed-width index binding values, preserving every other XMP byte. */
  static byte[] metadataWithoutBindingValues(byte[] metadata) {
    var charset = java.nio.charset.StandardCharsets.ISO_8859_1;
    String text = new String(metadata, charset);
    for (var field : List.of(Map.entry("fileDigest", "[0-9a-f]{64}"),
        Map.entry("signatureContents", "[0-9]{20}:[0-9]{20}"))) {
      var matcher = java.util.regex.Pattern.compile("<eapdfindex:" + field.getKey() + ">(" + field.getValue()
          + ")</eapdfindex:" + field.getKey() + ">").matcher(text);
      assertTrue(matcher.find(), "Missing or malformed index binding: " + field.getKey());
      int start = matcher.start(1), end = matcher.end(1);
      assertFalse(matcher.find(), "Duplicate index binding: " + field.getKey());
      text = text.substring(0, start) + "0".repeat(end - start) + text.substring(end);
    }
    return text.getBytes(charset);
  }

  static void assertSignedIndexBinding(Path signed, long[] range) throws Exception {
    byte[] bytes = Files.readAllBytes(signed);
    int slot = Math.toIntExact(FullTextSearchIndex.findMarker(signed, FullTextSearchIndex.MARKER));
    int descriptor = Math.toIntExact(FullTextSearchIndex.findMarker(signed, FullTextSearchIndex.SIGNATURE_MARKER));
    assertTrue(slot >= 0 && descriptor >= 0);
    var ascii = java.nio.charset.StandardCharsets.US_ASCII;
    assertEquals(String.format(Locale.ROOT, "%020d:%020d", range[1], range[2]),
        new String(bytes, descriptor, FullTextSearchIndex.EMPTY_SIGNATURE_RANGE.length(), ascii));
    String actual = new String(bytes, slot, 64, ascii);
    Arrays.fill(bytes, slot, slot + 64, (byte) '0');
    var hash = MessageDigest.getInstance("SHA-256");
    hash.update(bytes, 0, Math.toIntExact(range[1]));
    hash.update(bytes, Math.toIntExact(range[2]), Math.toIntExact(range[3]));
    assertEquals(actual, HexFormat.of().formatHex(hash.digest()));
  }

  @Test void rsaAndEcSignRealPdfWithoutChangingPagesMetadataOrAttachments() throws Exception {
    for (String algorithm : List.of("RSA", "EC")) {
      Identity identity = identity(algorithm, Instant.now().plusSeconds(86400), KeyUsage.digitalSignature);
      var config = store(identity, algorithm + ".p12", false);
      Path input = Path.of("examples/eml/eml_Agenda_and_timeline_slides_archive_1c.pdf");
      FullTextSearchIndex.verifyUnsignedBinding(input);
      Path signed = directory.resolve(algorithm + ".pdf"); Files.copy(input, signed);
      var signer = PdfSigning.load(config, PASSWORD);
      signer.signInPlace(signed); PdfSigning.verify(signed, identity.certificate());
      try (var before = Loader.loadPDF(input.toFile()); var after = Loader.loadPDF(signed.toFile())) {
        assertEquals(before.getNumberOfPages(), after.getNumberOfPages());
        assertEquals(new PDFTextStripper().getText(before), new PDFTextStripper().getText(after));
        assertArrayEquals(metadataWithoutBindingValues(before.getDocumentCatalog().getMetadata().toByteArray()),
            metadataWithoutBindingValues(after.getDocumentCatalog().getMetadata().toByteArray()));
        assertSignedIndexBinding(signed, Arrays.stream(after.getSignatureDictionaries().get(0).getByteRange()).asLongStream().toArray());
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

  @Test void indexedArchivesRemainBoundAndSignedAcrossAllFourProfiles() throws Exception {
    Identity identity=identity("RSA",Instant.now().plusSeconds(86400),KeyUsage.digitalSignature);
    var signer=PdfSigning.load(store(identity,"index.p12",false),PASSWORD);
    var config=EapdfConfig.get();boolean enabled=config.searchIndex.enabled,full=config.searchIndex.fullTextEnabled,signing=config.signing.enabled;
    try {
      config.searchIndex.enabled=true;config.searchIndex.fullTextEnabled=true;config.signing.enabled=true;
      for(String profile:List.of("1m","1mi","1c","1ci")){
        Path source=Path.of("browser-viewer/tests/fulltext-index/mbox_sample_"+profile+".pdf");
        Path indexed=directory.resolve("indexed-"+profile+".pdf"),signed=directory.resolve("signed-"+profile+".pdf");
        try(var pdf=Loader.loadPDF(source.toFile())){
          var metadata=pdf.getDocumentCatalog().getMetadata();
          String xmp=new String(metadata.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
          // Retain Phase 1 metadata indexing so child opening occurs on result selection.
          for(String field:List.of("fulltext","fileDigest","signatureContents"))
            xmp=xmp.replaceAll("<eapdfindex:"+field+">.*?</eapdfindex:"+field+">","");
          metadata.importXMPMetadata(xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          PdfFontMetadata.save(pdf,indexed.toFile());
        }
        FullTextSearchIndex.add(indexed);
        String before;
        try(var pdf=Loader.loadPDF(indexed.toFile())){
          before=new String(pdf.getDocumentCatalog().getMetadata().toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
          assertTrue(before.contains("<eapdfindex:fulltext>"));
          assertTrue(before.contains(FullTextSearchIndex.EMPTY_SIGNATURE_RANGE));
        }
        FullTextSearchIndex.verifyUnsignedBinding(indexed);
        signer.sign(indexed,signed);PdfSigning.verify(signed,identity.certificate());
        Files.writeString(indexed,"\n% changed after indexing\n",StandardOpenOption.APPEND);
        byte[] stale=Files.readAllBytes(indexed);
        assertThrows(IOException.class,()->signer.signInPlace(indexed));
        assertArrayEquals(stale,Files.readAllBytes(indexed));
        long[] range;
        try(var original=Loader.loadPDF(source.toFile());var pdf=Loader.loadPDF(signed.toFile())){
          assertEquals(new PDFTextStripper().getText(original),new PDFTextStripper().getText(pdf));
          var a=original.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
          var b=pdf.getDocumentCatalog().getNames().getEmbeddedFiles().getNames();
          assertEquals(a.keySet(),b.keySet());
          for(String name:a.keySet())assertArrayEquals(a.get(name).getEmbeddedFile().toByteArray(),b.get(name).getEmbeddedFile().toByteArray());
          int[] r=pdf.getSignatureDictionaries().get(0).getByteRange();range=new long[]{r[0],r[1],r[2],r[3]};
          String after=new String(pdf.getDocumentCatalog().getMetadata().toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
          assertArrayEquals(metadataWithoutBindingValues(before.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              metadataWithoutBindingValues(after.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertSignedIndexBinding(signed, range);
        assertThrows(IOException.class,()->FullTextSearchIndex.sealForSigning(signed,range));
        String fixtures=System.getProperty("eapdf.signedIndexFixtures");
        if(fixtures!=null){Path destination=Path.of(fixtures);Files.createDirectories(destination);Files.copy(signed,destination.resolve("mbox_sample_"+profile+".pdf"),StandardCopyOption.REPLACE_EXISTING);}
        Path revised=directory.resolve("revised-"+profile+".pdf");
        try(var pdf=Loader.loadPDF(signed.toFile());var output=Files.newOutputStream(revised)){
          pdf.getDocumentInformation().setSubject("Subsequent metadata revision");
          pdf.getDocumentInformation().getCOSObject().setNeedToBeUpdated(true);
          pdf.saveIncremental(output);
        }
        assertTrue(Files.size(revised)>Files.size(signed));
        assertThrows(GeneralSecurityException.class,()->PdfSigning.verify(revised,identity.certificate()));
        if(fixtures!=null){Path destination=Path.of(fixtures,"revisions");Files.createDirectories(destination);Files.copy(revised,destination.resolve("mbox_sample_"+profile+".pdf"),StandardCopyOption.REPLACE_EXISTING);}
      }
    } finally {config.searchIndex.enabled=enabled;config.searchIndex.fullTextEnabled=full;config.signing.enabled=signing;}
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
    assertEquals("Incorrect signing certificate password. Please try again.",
        assertThrows(IOException.class, () -> PdfSigning.load(cfg, "wrong".toCharArray())).getMessage());
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
