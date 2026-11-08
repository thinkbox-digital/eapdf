package eapdf;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.interfaces.RSAKey;
import java.util.*;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.digitalsignature.*;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.cms.*;
import org.bouncycastle.asn1.ess.ESSCertIDv2;
import org.bouncycastle.asn1.ess.SigningCertificateV2;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaCertStore;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.*;
import org.bouncycastle.cms.jcajce.*;
import org.bouncycastle.operator.jcajce.*;

/** Optional detached signature, added only after the outer PDF is complete. */
final class PdfSigning {
  static final String PASSWORD_ENV = "EAPDF_SIGNING_PASSWORD";
  private static volatile PdfSigning configured;
  private final PrivateKey key;
  private final List<X509Certificate> chain;
  private final String algorithm;
  private final String reason;

  private PdfSigning(PrivateKey key, List<X509Certificate> chain, String reason) throws Exception {
    this.key = key;
    this.chain = List.copyOf(chain);
    this.reason = Common.nz(reason);
    if (key instanceof RSAKey rsa && rsa.getModulus().bitLength() >= 2048) algorithm = "SHA256withRSA";
    else if (key instanceof ECKey ec && ec.getParams().getOrder().bitLength() >= 256) algorithm = "SHA256withECDSA";
    else throw new GeneralSecurityException("Signing requires an RSA key of at least 2048 bits or an EC key of at least 256 bits.");
    checkCertificate();
  }

  private void checkCertificate() throws Exception {
    for (X509Certificate cert : chain) cert.checkValidity();
    boolean[] usage = chain.get(0).getKeyUsage();
    if (usage != null && !usage[0] && (usage.length < 2 || !usage[1]))
      throw new GeneralSecurityException("The certificate does not permit digital signatures.");
  }

  static synchronized void preflight() throws Exception {
    EapdfConfig.SigningConfig cfg = EapdfConfig.get().signing;
    if (!cfg.enabled || configured != null) return;
    String password = System.getenv(PASSWORD_ENV);
    if (password == null) throw Common.fail("Signing is enabled. Enter the certificate password in the GUI or set " + PASSWORD_ENV + ".");
    char[] chars = password.toCharArray();
    try { configured = load(cfg, chars); }
    finally { Arrays.fill(chars, '\0'); }
  }

  static PdfSigning load(EapdfConfig.SigningConfig cfg, char[] password) throws Exception {
    if (Common.nz(cfg.pkcs12Path).isBlank()) throw Common.fail("Signing is enabled but no .p12 or .pfx certificate file is selected.");
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (InputStream input = Files.newInputStream(Path.of(cfg.pkcs12Path))) {
      store.load(input, password);
    } catch (IOException e) {
      if (e.getCause() instanceof UnrecoverableKeyException)
        throw new IOException("Incorrect signing certificate password. Please try again.", e);
      throw new IOException("Cannot open the signing certificate. Check the file and its password.", e);
    }
    String alias = Common.nz(cfg.alias).trim();
    if (alias.isEmpty()) {
      List<String> candidates = new ArrayList<>();
      for (String name : Collections.list(store.aliases())) if (store.isKeyEntry(name)) candidates.add(name);
      if (candidates.size() != 1) throw Common.fail("The certificate file must contain one signing key, or a signing alias must be selected.");
      alias = candidates.get(0);
    }
    Key selected;
    try {
      selected = store.getKey(alias, password);
    } catch (UnrecoverableKeyException e) {
      throw new IOException("Incorrect signing certificate password. Please try again.", e);
    }
    java.security.cert.Certificate[] certificates = store.getCertificateChain(alias);
    if (!(selected instanceof PrivateKey privateKey) || certificates == null || certificates.length == 0)
      throw Common.fail("The selected alias has no private key and certificate chain.");
    List<X509Certificate> chain = new ArrayList<>();
    for (var certificate : certificates) {
      if (!(certificate instanceof X509Certificate x509)) throw Common.fail("Signing requires an X.509 certificate chain.");
      chain.add(x509);
    }
    for (int i = 0; i + 1 < chain.size(); i++) chain.get(i).verify(chain.get(i + 1).getPublicKey());
    PdfSigning signer = new PdfSigning(privateKey, chain, cfg.reason);
    // Detect a mismatched key before spending time converting a mailbox.
    byte[] challenge = new byte[32]; new SecureRandom().nextBytes(challenge);
    Signature proof = Signature.getInstance(signer.algorithm);
    proof.initSign(privateKey); proof.update(challenge); byte[] signed = proof.sign();
    proof.initVerify(chain.get(0)); proof.update(challenge);
    if (!proof.verify(signed)) throw new GeneralSecurityException("The signing key does not match its certificate.");
    return signer;
  }

  static void signConfigured(Path pdf) throws Exception {
    preflight();
    if (EapdfConfig.get().signing.enabled) configured.signInPlace(pdf);
  }

  void signInPlace(Path pdf) throws Exception {
    checkCertificate();
    // Keep the original intact if signing or verification fails.
    Common.writeAtomically(pdf, signed -> {
      sign(pdf, signed);
      ProcessingLimits.checkOutput(Files.size(signed));
      verify(signed, chain.get(0));
    });
  }

  void sign(Path input, Path output) throws Exception {
    checkCertificate();
    if (input.toAbsolutePath().normalize().equals(output.toAbsolutePath().normalize()))
      throw new IOException("Signing input and output must be different files.");
    long[] signingRange;boolean bindIndex;
    try (PDDocument document = IndexedPdfLoader.load(input, IOUtils.createTempFileOnlyStreamCache());
         SignatureOptions options = new SignatureOptions()) {
      if (document.isEncrypted() || !document.getSignatureDictionaries().isEmpty())
        throw new IOException("Expected an unsigned, unencrypted creator output.");
      var metadata=document.getDocumentCatalog().getMetadata();
      bindIndex=metadata!=null&&new String(metadata.toByteArray(),java.nio.charset.StandardCharsets.UTF_8).contains(FullTextSearchIndex.SIGNATURE_MARKER);
      if(bindIndex)FullTextSearchIndex.verifyUnsignedBinding(input);
      PDSignature signature = new PDSignature();
      signature.setFilter(PDSignature.FILTER_ADOBE_PPKLITE);
      signature.setSubFilter(PDSignature.SUBFILTER_ETSI_CADES_DETACHED);
      signature.setName(chain.get(0).getSubjectX500Principal().getName());
      if (!reason.isBlank()) signature.setReason(reason);
      signature.setSignDate(Calendar.getInstance());
      int certificateBytes = 0;
      for (X509Certificate certificate : chain) certificateBytes = Math.addExact(certificateBytes, certificate.getEncoded().length);
      options.setPreferredSignatureSize(Math.addExact(16384, certificateBytes));
      document.addSignature(signature, options);
      // A zero-size signature widget adds no visible mark to the email pages.
      for (var field : document.getSignatureFields()) field.setAlternateFieldName("Document signature");
      try (OutputStream out = Files.newOutputStream(output)) {
        ExternalSigningSupport external = document.saveIncrementalForExternalSigning(out);
        // PDFBox late external signing: finish the placeholder PDF before calculating CMS.
        // The index fingerprint must be sealed over this final revision before it is signed.
        external.setSignature(new byte[0]);
        var bytes=signature.getCOSObject().getCOSArray(org.apache.pdfbox.cos.COSName.BYTERANGE);
        signingRange=new long[4];
        for(int i=0;i<4;i++)signingRange[i]=((org.apache.pdfbox.cos.COSInteger)bytes.getObject(i)).longValue();
      }
    }
    if(bindIndex)FullTextSearchIndex.sealForSigning(output,signingRange);
    byte[] signature;
    try(var content=new ByteRanges(output,signingRange)){signature=cms(content);}
    if(signature.length>(signingRange[2]-signingRange[1]-2)/2)
      throw new IOException("Signature exceeds reserved Contents space");
    try(var file=new RandomAccessFile(output.toFile(),"rw")){
      file.seek(signingRange[1]+1);
      file.write(HexFormat.of().withUpperCase().formatHex(signature).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
  }

  private byte[] cms(InputStream content) throws Exception {
    CMSSignedDataStreamGenerator generator = new CMSSignedDataStreamGenerator();
    var signer = new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder().build());
    byte[] certHash = MessageDigest.getInstance("SHA-256").digest(chain.get(0).getEncoded());
    var certificateAttribute = new Attribute(PKCSObjectIdentifiers.id_aa_signingCertificateV2,
        new DERSet(new SigningCertificateV2(new ESSCertIDv2(certHash))));
    Hashtable<org.bouncycastle.asn1.ASN1ObjectIdentifier, Attribute> attributes = new Hashtable<>();
    attributes.put(certificateAttribute.getAttrType(), certificateAttribute);
    var defaults = new DefaultSignedAttributeTableGenerator(new AttributeTable(attributes));
    signer.setSignedAttributeGenerator(parameters -> defaults.getAttributes(parameters).remove(CMSAttributes.signingTime));
    generator.addSignerInfoGenerator(signer.build(new JcaContentSignerBuilder(algorithm).build(key), chain.get(0)));
    generator.addCertificates(new JcaCertStore(chain));
    ByteArrayOutputStream signature = new ByteArrayOutputStream();
    // Only the small CMS signature is buffered, never the complete PDF.
    try (OutputStream signedContent = generator.open(signature, false)) { content.transferTo(signedContent); }
    return org.bouncycastle.asn1.ASN1Primitive.fromByteArray(signature.toByteArray()).getEncoded("DER");
  }

  /** Checks integrity and full-file coverage, not issuer trust, revocation, or trusted time. */
  static void verify(Path pdf, X509Certificate expected) throws Exception {
    long[] range;
    byte[] cms;
    try (PDDocument document = IndexedPdfLoader.load(pdf, IOUtils.createTempFileOnlyStreamCache())) {
      List<PDSignature> signatures = document.getSignatureDictionaries();
      if (signatures.size() != 1) throw new GeneralSecurityException("Expected one outer PDF signature.");
      PDSignature signature = signatures.get(0);
      if (!PDSignature.SUBFILTER_ETSI_CADES_DETACHED.getName().equals(signature.getSubFilter()))
        throw new GeneralSecurityException("Unexpected signature format.");
      var values = signature.getCOSObject().getCOSArray(org.apache.pdfbox.cos.COSName.BYTERANGE);
      if (values == null || values.size() != 4) throw new GeneralSecurityException("Invalid signature byte ranges.");
      range = new long[4];
      for (int i = 0; i < 4; i++) {
        if (!(values.getObject(i) instanceof org.apache.pdfbox.cos.COSInteger number))
          throw new GeneralSecurityException("Invalid signature byte range offset.");
        range[i] = number.longValue();
      }
      cms = signature.getContents();
    }
    long length = Files.size(pdf);
    if (range == null || range.length != 4 || range[0] != 0 || range[1] <= 0
        || range[2] <= range[1] || range[3] < 0 || range[2] > length || range[3] != length - range[2])
      throw new GeneralSecurityException("The signature does not cover the entire output PDF.");
    try (InputStream bytes = new ByteRanges(pdf, range)) {
      CMSSignedDataParser parser = new CMSSignedDataParser(new JcaDigestCalculatorProviderBuilder().build(),
          new CMSTypedStream(bytes), cms);
      try {
        parser.getSignedContent().drain();
        var signers = parser.getSignerInfos().getSigners();
        if (signers.size() != 1) throw new GeneralSecurityException("Expected one CMS signer.");
        SignerInformation signer = signers.iterator().next();
        X509CertificateHolder certificate = new JcaX509CertificateHolder(expected);
        if (!signer.getSID().match(certificate)
            || !signer.verify(new JcaSimpleSignerInfoVerifierBuilder().build(expected)))
          throw new GeneralSecurityException("Output PDF signature verification failed.");
      } finally { parser.close(); }
    }
  }

  static final class ByteRanges extends InputStream {
    final RandomAccessFile file;
    final long[] range;
    int part = 0;
    long remaining;
    ByteRanges(Path path, long[] range) throws IOException {
      this.file = new RandomAccessFile(path.toFile(), "r"); this.range = range; remaining = range[1];
    }
    @Override public int read() throws IOException { byte[] one = new byte[1]; return read(one, 0, 1) < 0 ? -1 : one[0] & 255; }
    @Override public int read(byte[] bytes, int offset, int length) throws IOException {
      Objects.checkFromIndexSize(offset, length, bytes.length);
      if (length == 0) return 0;
      if (remaining == 0 && part == 0) { part = 1; file.seek(range[2]); remaining = range[3]; }
      if (remaining == 0) return -1;
      int count = file.read(bytes, offset, (int) Math.min(length, remaining));
      if (count < 0) throw new EOFException("Truncated signed PDF.");
      remaining -= count; return count;
    }
    @Override public void close() throws IOException { file.close(); }
  }
}
