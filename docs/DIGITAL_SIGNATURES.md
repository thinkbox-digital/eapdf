# Signing EA-PDF output

The creator can sign each outer EA-PDF with a certificate and private key from a
password-protected PKCS#12 (`.p12` or `.pfx`) file. Signing is off by default.
Embedded email PDFs, original attachments, and finding-aid sidecars are not signed
separately. The outer signature covers the embedded bytes as part of the PDF.
No visible signature stamp is added to email pages.

## GUI

1. Open **Signing settings...** and select your `.p12` or `.pfx` file.
2. Leave the key alias blank if the file contains just one private key. Otherwise,
   enter the alias of the signing key. The reason is optional.
3. Select **Sign output**.
4. Start conversion and enter the certificate password when prompted.

Keep the private key and its password on your own machine. The password is not
written to JSON, logs, or command arguments. The GUI passes it to its conversion
process through `EAPDF_SIGNING_PASSWORD`, clears the GUI password buffer, and
removes its temporary configuration after the run. As with other environment
secrets, the operating system and sufficiently privileged local processes can
access it during the run. Signing settings selected in the GUI last for that GUI
session; put non-secret settings in your configuration to retain them.

## Configuration

Add this object to your existing `EAPDF_CONFIG` JSON file:

```json
"signing": {
  "enabled": true,
  "pkcs12Path": "/absolute/path/to/signing-certificate.p12",
  "alias": "",
  "reason": ""
}
```

Set `EAPDF_SIGNING_PASSWORD` in the launching environment for a command-line
conversion. It is required even for an empty password. Do not put the password in
the JSON file or command arguments. Existing conversion arguments are unchanged.
The same setting applies to local inputs, Gmail output, single messages, archives,
and collections. Missing or incorrect credentials stop the job; the creator does
not silently publish unsigned output when signing was requested.

## What is checked

The creator checks certificate validity dates, signing key usage, alias selection,
and that the private key matches the certificate before normal CLI/GUI conversion
work begins. RSA keys must be at least 2048 bits; EC keys must be at least 256 bits.
The key and keystore must use the same password. Multiple private keys require an
explicit alias.

Signing occurs after all PDF repairs and preservation checks, as an incremental
PDF update using SHA-256 and detached CMS (`ETSI.CAdES.detached`). The signature
includes the certificate chain supplied by the PKCS#12 file and a SHA-256 binding
to the signing certificate. Signed data is streamed rather than copied into a
whole-file byte array. The PDF object graph still needs memory.

Before publication, the creator verifies the cryptographic signature and that its
byte ranges cover the whole output, then repeats its creation/preservation checks.
A failure leaves an existing destination unchanged. The standalone Java validator
still provides the full PDF/A and EA-PDF conformance checks; these checks do not
establish certificate trust or signature validity.

## Current limits

This is an integrity signature. It does not check issuer trust or revocation,
contact a timestamp service, or embed long-term validation evidence. Its signing
time comes from the computer clock. This implementation is not a claim of
certified PAdES conformance or a qualified electronic signature.

The supplied example uses a temporary self-signed **EA-PDF TEST ONLY** certificate.
A reader can verify its integrity but will normally report an unknown issuer.
For production, obtain a document-signing certificate and its private key in
PKCS#12 form. Hardware tokens and cloud signing services need separate integration.
No production certificate or private key is included in the software.

Implementation references: [Apache PDFBox signing examples](https://github.com/apache/pdfbox/tree/3.0.8/examples/src/main/java/org/apache/pdfbox/examples/signature)
and [Bouncy Castle Java documentation](https://www.bouncycastle.org/documentation/documentation-java/).
