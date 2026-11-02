package eapdf;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreservationInventoryTests {
  @TempDir Path directory;

  EmailRecord record(String suffix) throws Exception {
    String raw = Files.readString(Path.of("tests/fixtures/eapdf/batch11/source.eml"), StandardCharsets.UTF_8)
        .replace("Child acceptance", "Inventory " + suffix)
        .replace("Original native attachment bytes", "Original payload " + suffix);
    return EmlReader.readRfc822Bytes(raw.getBytes(StandardCharsets.UTF_8), "source.eml", "source.eml", "", List.of(), false);
  }

  List<EmailRecord> records(String profile) throws Exception {
    return new ArrayList<>(profile.startsWith("s") ? List.of(record("A")) : List.of(record("A"), record("B"), record("C")));
  }

  PreservationInventory capture(List<EmailRecord> records, String profile) throws Exception {
    return PreservationInventory.capture(records, profile);
  }

  void checkedWrite(Path output, PreservationInventory inventory, byte[] bytes) throws Exception {
    Common.writePdfAtomically(output, candidate -> Files.write(candidate, bytes), inventory);
  }

  void retain(String id, PreservationInventory inventory, byte[] bytes, String status, String diagnostic) throws Exception {
    String location = System.getProperty("eapdf.inventoryCorpus");
    if (location == null) return;
    Path output = Path.of(location);
    Files.createDirectories(output);
    Files.write(output.resolve(id + ".pdf"), bytes);
    Files.writeString(output.resolve(id + ".expected-inputs.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(inventory));
    Files.writeString(output.resolve(id + ".creator.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(
        java.util.Map.of("status", status, "diagnostic", diagnostic,
            "existing_destination_check", status.equals("REJECT") ? "unchanged bytes asserted" : "not a rejection case")));
  }

  byte[] create(List<EmailRecord> records, String profile) throws Exception {
    Path output = directory.resolve("generated-" + profile + ".pdf");
    if (profile.startsWith("s")) return PdfMailWriter.buildSingleMessagePdf(output, records.get(0),
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, 1, profile).pdfBytes;
    if (profile.startsWith("m")) PdfMailWriter.writeEaPdfArchive(output, records,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
    else PdfMailWriter.writeEaPdfCollection(output, records,
        TestSupport.fontPath(), TestSupport.iccPath(), false, directory, profile);
    return Files.readAllBytes(output);
  }

  void rejectWithoutReplacement(String id, PreservationInventory inventory, byte[] candidate) throws Exception {
    Path output = directory.resolve("protected.pdf");
    byte[] previous = "existing destination bytes".getBytes(StandardCharsets.UTF_8);
    Files.write(output, previous);
    IOException failure = Assertions.assertThrows(IOException.class, () -> checkedWrite(output, inventory, candidate));
    Assertions.assertTrue(failure.getMessage().startsWith("Preservation inventory mismatch:"), failure.getMessage());
    Assertions.assertArrayEquals(previous, Files.readAllBytes(output));
    retain(id, inventory, candidate, "REJECT", failure.getMessage());
  }

  @Test void intactAllSixProfilesMatchExpectedInputs() throws Exception {
    for (String profile : List.of("s", "si", "m", "mi", "c", "ci")) {
      List<EmailRecord> records = records(profile);
      PreservationInventory inventory = capture(records, profile);
      byte[] bytes = create(records, profile);
      checkedWrite(directory.resolve("accepted-" + profile + ".pdf"), inventory, bytes);
      retain("valid-" + profile, inventory, bytes, "PASS", "Input inventory reconciled");
    }
  }

  @Test void missingMessageAndItsMetadataCannotDisappearTogether() throws Exception {
    for (String profile : List.of("m", "mi", "c", "ci")) {
      List<EmailRecord> records = records(profile);
      PreservationInventory inventory = capture(records, profile);
      records.remove(1);
      rejectWithoutReplacement("missing-message-" + profile, inventory, create(records, profile));
    }
  }

  @Test void missingAttachmentAndItsMetadataCannotDisappearTogether() throws Exception {
    for (String profile : List.of("s", "si", "m", "mi", "c", "ci")) {
      List<EmailRecord> records = records(profile);
      PreservationInventory inventory = capture(records, profile);
      records.get(0).associatedFiles.removeIf(file -> file.emailAttachment);
      rejectWithoutReplacement("missing-attachment-" + profile, inventory, create(records, profile));
    }
  }

  @Test void changedPayloadWithConsistentNewPdfMetadataIsRejected() throws Exception {
    List<EmailRecord> records = records("s");
    PreservationInventory inventory = capture(records, "s");
    AssociatedFile file = records.get(0).associatedFiles.stream().filter(asset -> asset.emailAttachment).findFirst().orElseThrow();
    file.bytes = "changed payload".getBytes(StandardCharsets.UTF_8);
    file.checksumMd5 = Common.md5Hex(file.bytes);
    file.checksumSha256 = Common.sha256Hex(file.bytes);
    rejectWithoutReplacement("changed-native-bytes", inventory, create(records, "s"));
  }

  @Test void sameFilenamePayloadsCannotBeSwappedBetweenOwners() throws Exception {
    List<EmailRecord> records = records("m");
    PreservationInventory inventory = capture(records, "m");
    AssociatedFile first = records.get(0).associatedFiles.stream().filter(asset -> asset.emailAttachment).findFirst().orElseThrow();
    AssociatedFile second = records.get(1).associatedFiles.stream().filter(asset -> asset.emailAttachment).findFirst().orElseThrow();
    byte[] previous = first.bytes;
    first.bytes = second.bytes;
    second.bytes = previous;
    for (AssociatedFile file : List.of(first, second)) {
      file.checksumMd5 = Common.md5Hex(file.bytes);
      file.checksumSha256 = Common.sha256Hex(file.bytes);
    }
    rejectWithoutReplacement("swapped-native-owners", inventory, create(records, "m"));
  }

  @Test void identicalOccurrencesCannotShareOneRetainedFile() throws Exception {
    List<EmailRecord> records = records("s");
    AssociatedFile duplicate = record("A").associatedFiles.stream().filter(asset -> asset.emailAttachment).findFirst().orElseThrow();
    records.get(0).associatedFiles.add(duplicate);
    PreservationInventory inventory = capture(records, "s");
    try (PDDocument document = Loader.loadPDF(create(records, "s"))) {
      org.apache.pdfbox.cos.COSBase removedFile = null;
      for (var page : document.getPages()) {
        var annotations = page.getAnnotations();
        for (int index = annotations.size() - 1; index >= 0; index--) {
          if (annotations.get(index) instanceof PDAnnotationFileAttachment attachment
              && "native.txt".equals(attachment.getFile().getFile())) {
            var file = attachment.getFile().getCOSObject();
            if (removedFile == null) removedFile = file;
            if (file == removedFile) annotations.remove(index);
          }
        }
        page.setAnnotations(annotations);
      }
      Assertions.assertNotNull(removedFile);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      document.save(bytes);
      rejectWithoutReplacement("reused-identical-occurrence", inventory, bytes.toByteArray());
    }
  }

  @Test void snapshotHashesStagedPayloadBytesBeforeTheyChange() throws Exception {
    List<EmailRecord> records = records("s");
    try (PayloadStore store = new PayloadStore()) {
      store.stage(records.get(0));
      PreservationInventory inventory = capture(records, "s");
      AssociatedFile file = records.get(0).associatedFiles.stream().filter(asset -> asset.emailAttachment).findFirst().orElseThrow();
      byte[] changed = "modified staged bytes".getBytes(StandardCharsets.UTF_8);
      Path changedFile = directory.resolve("modified-staged-payload.bin");
      Files.write(changedFile, changed);
      file.dataPath = changedFile; file.dataOffset = 0; file.dataSlice = true;
      file.storedSize = changed.length;
      file.checksumMd5 = Common.md5Hex(changed);
      file.checksumSha256 = Common.sha256Hex(changed);
      rejectWithoutReplacement("changed-staged-bytes", inventory, create(records, "s"));
    }
  }

  @Test void changedRetainedSourceWithUpdatedChecksumsIsRejected() throws Exception {
    List<EmailRecord> records = records("s");
    PreservationInventory inventory = capture(records, "s");
    AssociatedFile source = records.get(0).associatedFiles.stream().filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow();
    source.bytes = new String(source.bytes, StandardCharsets.UTF_8).replace("Email body.", "Changed body.").getBytes(StandardCharsets.UTF_8);
    source.checksumMd5 = Common.md5Hex(source.bytes);
    source.checksumSha256 = Common.sha256Hex(source.bytes);
    records.get(0).sourceBytes = source.bytes;
    rejectWithoutReplacement("changed-source-bytes", inventory, create(records, "s"));
  }

  @Test void isolatedOutputsDoNotRequireRetainedSources() throws Exception {
    for (String profile : List.of("si", "mi", "ci")) {
      List<EmailRecord> records = records(profile);
      for (EmailRecord record : records) {
        record.associatedFiles.removeIf(file -> "Source".equals(file.afRelationship));
        record.sourceBytes = null;
      }
      PreservationInventory inventory = capture(records, profile);
      byte[] bytes = create(records, profile);
      checkedWrite(directory.resolve("isolated-" + profile + ".pdf"), inventory, bytes);
      retain("source-omitted-" + profile, inventory, bytes, "PASS", "Input inventory reconciled; sources omitted by policy");
    }
  }

  @Test void missingCollectionChildCannotHideBehindParentMetadataOrOrphanObjects() throws Exception {
    List<EmailRecord> records = records("c");
    PreservationInventory inventory = capture(records, "c");
    try (PDDocument document = Loader.loadPDF(create(records, "c"))) {
      var names = document.getDocumentCatalog().getNames().getEmbeddedFiles();
      var files = new java.util.LinkedHashMap<>(names.getNames());
      var entry = files.entrySet().stream().filter(item -> item.getValue().getFilename().endsWith(".pdf")).findFirst().orElseThrow();
      files.remove(entry.getKey());
      names.setNames(files);
      var associated = (org.apache.pdfbox.cos.COSArray) document.getDocumentCatalog().getCOSObject()
          .getDictionaryObject(org.apache.pdfbox.cos.COSName.getPDFName("AF"));
      for (int index = associated.size() - 1; index >= 0; index--)
        if (associated.getObject(index) == entry.getValue().getCOSObject()) associated.remove(index);
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      document.save(bytes);
      rejectWithoutReplacement("missing-listed-child", inventory, bytes.toByteArray());
    }
  }
}
