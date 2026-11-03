package eapdf;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionChildAcceptanceTests {
  @TempDir Path directory;

  byte[] collection(String profile) throws Exception {
    Path output = directory.resolve("collection_" + profile + ".pdf");
    byte[] source = Files.readAllBytes(Path.of("tests/fixtures/eapdf/batch11/source.eml"));
    EmailRecord record = EmlReader.readRfc822Bytes(source, "source.eml", "source.eml", "", List.of(), false);
    PdfMailWriter.writeEaPdfCollection(output, List.of(record),
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
    return Files.readAllBytes(output);
  }

  static void retain(String name, byte[] bytes) throws Exception {
    String location = System.getProperty("eapdf.childAcceptanceCorpus");
    if (location == null) return;
    Path output = Path.of(location);
    Files.createDirectories(output);
    Files.write(output.resolve(name + ".pdf"), bytes);
  }

  static PDComplexFileSpecification childFile(PDDocument parent) throws Exception {
    return AttachmentProfilePolicyTests.files(parent).values().stream()
        .filter(file -> file.getFilename().endsWith(".pdf")).findFirst().orElseThrow();
  }

  static void replaceChild(PDDocument parent, PDComplexFileSpecification file, byte[] bytes) throws Exception {
    PDEmbeddedFile embedded = new PDEmbeddedFile(parent, new ByteArrayInputStream(bytes));
    embedded.setSubtype("application/pdf");
    embedded.setSize(bytes.length);
    embedded.getCOSObject().getCOSDictionary(COSName.PARAMS)
        .setItem(COSName.getPDFName("CheckSum"), new org.apache.pdfbox.cos.COSString(
            java.util.HexFormat.of().parseHex(Common.md5Hex(bytes))));
    file.setEmbeddedFile(embedded);
    file.setEmbeddedFileUnicode(embedded);
  }

  byte[] corrupt(byte[] original, String mutation) throws Exception {
    try (PDDocument parent = Loader.loadPDF(original)) {
      PDComplexFileSpecification file = childFile(parent);
      try (PDDocument child = Loader.loadPDF(AttachmentProfilePolicyTests.bytes(file))) {
        if (mutation.equals("missing-sent")) {
          String xml = TestSupport.metadataXml(child).replaceAll("<pdfmailmeta:Sent>[^<]*</pdfmailmeta:Sent>", "");
          child.getDocumentCatalog().getMetadata().importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
        } else if (mutation.equals("missing-dpart")) {
          child.getPage(0).getCOSObject().removeItem(COSName.getPDFName("DPart"));
        } else if (mutation.equals("bad-attachment-checksum")) {
          for (var page : child.getPages()) for (var annotation : page.getAnnotations()) {
            if (annotation instanceof org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment attachment) {
              PDComplexFileSpecification nativeFile = (PDComplexFileSpecification) attachment.getFile();
              if (!"native.txt".equals(nativeFile.getFilename())) continue;
              nativeFile.getEmbeddedFileUnicode().getCOSObject().getCOSDictionary(COSName.PARAMS)
                  .setItem(COSName.getPDFName("CheckSum"), new org.apache.pdfbox.cos.COSString(new byte[16]));
            }
          }
        } else throw new IllegalArgumentException(mutation);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        child.save(bytes);
        replaceChild(parent, file, bytes.toByteArray());
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      parent.save(bytes);
      return bytes.toByteArray();
    }
  }

  @Test void damagedSerializedChildrenCannotReplaceAnExistingCollection() throws Exception {
    for (String profile : List.of("c", "ci")) {
      byte[] valid = collection(profile);
      retain("valid-" + profile, valid);
      Path output = directory.resolve("protected-" + profile + ".pdf");
      Files.write(output, valid);
      for (String mutation : List.of("missing-sent", "missing-dpart", "bad-attachment-checksum")) {
        byte[] damaged = corrupt(valid, mutation);
        retain(mutation + "-" + profile, damaged);
        // The envelope and its checksum describe the actual replacement bytes.
        // Only checking that envelope cannot catch the invalid child.
        Assertions.assertThrows(IOException.class, () -> Common.writePdfAtomically(output,
            candidate -> Files.write(candidate, damaged)), profile + ": " + mutation);
        Assertions.assertArrayEquals(valid, Files.readAllBytes(output), profile + ": " + mutation);
      }
    }
  }

  @Test void finalCollectionValidationIncludesGrandchildren() throws Exception {
    byte[] valid = collection("c");
    byte[] damaged = corrupt(valid, "missing-sent");
    try (PDDocument parent = Loader.loadPDF(valid)) {
      replaceChild(parent, childFile(parent), valid);
      PdfCreationValidator.validate(parent);
      replaceChild(parent, childFile(parent), damaged);
      Assertions.assertThrows(IOException.class, () -> PdfCreationValidator.validate(parent));
    }
  }

  byte[] nest(byte[] parentBytes, byte[] childBytes) throws Exception {
    try (PDDocument parent = Loader.loadPDF(parentBytes)) {
      replaceChild(parent, childFile(parent), childBytes);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      parent.save(bytes);
      return bytes.toByteArray();
    }
  }

  @Test void nestedCollectionsBeyondFormerCutoffAreFullyChecked() throws Exception {
    byte[] seed = collection("c");
    byte[] nested = seed;
    for (int depth = 1; depth < 8; depth++) nested = nest(seed, nested);
    try (PDDocument parent = Loader.loadPDF(nested)) { PdfCreationValidator.validate(parent); }
    try (PDDocument parent = Loader.loadPDF(nest(seed, nested))) {
      PdfCreationValidator.validate(parent);
    }
  }

  @Test void processedChildrenDoNotConsumeLaterStagingAllowances() throws Exception {
    byte[] seed = collection("c");
    byte[] nested = nest(seed, seed);
    String previous = System.getProperty("eapdf.maxOutputBytes");
    try {
      System.setProperty("eapdf.maxOutputBytes", Long.toString(seed.length + 1L));
      try (PDDocument parent = Loader.loadPDF(nested)) {
        PdfCreationValidator.validate(parent);
      }
    } finally {
      if (previous == null) System.clearProperty("eapdf.maxOutputBytes");
      else System.setProperty("eapdf.maxOutputBytes", previous);
    }
  }

  @Test void legacyChildCountIsIgnoredButIndividualStagedBytesRemainBounded() throws Exception {
    byte[] seed = collection("c");
    byte[] nested = nest(seed, seed);
    for (String property : List.of("eapdf.maxMessages", "eapdf.maxOutputBytes")) {
      String previous = System.getProperty(property);
      try {
        System.setProperty(property, property.equals("eapdf.maxMessages") ? "1" : "16");
        try (PDDocument parent = Loader.loadPDF(nested)) {
          if (property.equals("eapdf.maxMessages")) PdfCreationValidator.validate(parent);
          else Assertions.assertThrows(Common.UserFacingException.class, () -> PdfCreationValidator.validate(parent), property);
        }
      } finally {
        if (previous == null) System.clearProperty(property);
        else System.setProperty(property, previous);
      }
    }
  }
}
