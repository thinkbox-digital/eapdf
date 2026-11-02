package eapdf;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MboxReadInventoryTests {
  static final Path INPUTS = Path.of("tests/fixtures/eapdf/batch13");
  @TempDir Path directory;

  @Test void ambiguousRecognizedSeparatorsCannotSilentlyBecomeBodyText() throws Exception {
    for (String file : List.of("truncated-final-entry.mbox", "ambiguous-header.mbox", "blank-header.mbox", "missing-header-separator.mbox")) {
      byte[] bytes = Files.readAllBytes(INPUTS.resolve(file));
      for (String format : List.of("auto", "mboxrd", "mboxo")) {
        List<byte[]> delivered = new ArrayList<>();
        Assertions.assertThrows(Common.UserFacingException.class,
            () -> MboxParser.read(new ByteArrayInputStream(bytes), format, delivered::add), file + ": " + format);
      }
    }
  }

  @Test void readerCannotReturnASuccessfulPartialMailbox() throws Exception {
    Assertions.assertThrows(Common.UserFacingException.class, () -> MboxReader.readMbox(
        INPUTS.resolve("truncated-final-entry.mbox").toString(), "", List.of(), false));
  }

  @Test void ambiguousInputCannotReplaceAnExistingCollection() throws Exception {
    Path output = directory.resolve("protected.pdf");
    byte[] previous = "previous archive bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(output, previous);
    Assertions.assertThrows(Common.UserFacingException.class, () -> EmailToPDFA.runArchive(
        List.of(INPUTS.resolve("truncated-final-entry.mbox").toString()), directory.toString(), output.toString(),
        TestSupport.fontPath(), TestSupport.iccPath(), "", List.of(), false, directory.toString(), "c"));
    Assertions.assertArrayEquals(previous, Files.readAllBytes(output));
  }

  JsonArray cases() throws Exception {
    return new Gson().fromJson(Files.readString(INPUTS.resolve("inputs.json")), JsonArray.class);
  }

  MboxParser.ReadResult parse(JsonObject item, List<byte[]> messages) throws Exception {
    return MboxParser.read(new ByteArrayInputStream(Files.readAllBytes(INPUTS.resolve(item.get("input").getAsString()))),
        item.get("format").getAsString(), messages::add);
  }

  List<EmailRecord> read(JsonObject item) throws Exception {
    String previous = System.getProperty("eapdf.mboxFormat");
    try {
      System.setProperty("eapdf.mboxFormat", item.get("format").getAsString());
      return MboxReader.readMbox(INPUTS.resolve(item.get("input").getAsString()).toString(), "", List.of(), false);
    } finally {
      if (previous == null) System.clearProperty("eapdf.mboxFormat");
      else System.setProperty("eapdf.mboxFormat", previous);
    }
  }

  @Test void acceptedByteSpansAndExportsMatchAuthoredOracles() throws Exception {
    for (var value : cases()) {
      JsonObject item = value.getAsJsonObject();
      List<byte[]> messages = new ArrayList<>();
      MboxParser.ReadResult result = parse(item, messages);
      JsonObject actual = new Gson().toJsonTree(result).getAsJsonObject();
      Assertions.assertEquals(item.get("format"), actual.get("requestedFormat"));
      Assertions.assertEquals(item.get("containerSize"), actual.get("containerSize"));
      Assertions.assertEquals(item.get("containerSha256"), actual.get("containerSha256"));
      JsonArray expected = item.getAsJsonArray("entries");
      Assertions.assertEquals(expected.size(), messages.size());
      for (int index = 0; index < expected.size(); index++) {
        JsonObject row = expected.get(index).getAsJsonObject();
        JsonObject observed = actual.getAsJsonArray("entries").get(index).getAsJsonObject();
        for (String key : List.of("ordinal", "envelopeOffset", "messageOffset", "rawSize", "rawSha256", "exportedSize",
            "exportedSha256", "paddingSize", "framing", "quoting")) Assertions.assertEquals(row.get(key), observed.get(key), item.get("id") + ": " + key);
        Assertions.assertArrayEquals(Files.readAllBytes(INPUTS.resolve(row.get("exportFile").getAsString())), messages.get(index));
      }
      String location = System.getProperty("eapdf.mboxReaderReports");
      if (location != null) {
        Path output = Path.of(location); Files.createDirectories(output);
        Files.writeString(output.resolve(item.get("id").getAsString() + ".json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result));
      }
    }
  }

  @Test void stagedReaderRecordsCarryVerifiedContainerAndEntryProvenance() throws Exception {
    try (Common.JobReport job = Common.beginJob()) {
      for (var value : cases()) {
        JsonObject item = value.getAsJsonObject();
        if (!item.get("reader").getAsBoolean()) continue;
        List<EmailRecord> records = read(item);
        Assertions.assertEquals(item.getAsJsonArray("entries").size(), records.size());
        for (int index = 0; index < records.size(); index++) {
          EmailRecord record = records.get(index);
          Assertions.assertTrue(record.payloadStaged);
          Assertions.assertNull(record.sourceBytes);
          Assertions.assertEquals(item.get("containerSha256").getAsString(), record.mboxProvenance.containerSha256());
          Assertions.assertEquals(index + 1, record.mboxProvenance.entry().ordinal());
          AssociatedFile source = record.associatedFiles.stream().filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow();
          Assertions.assertTrue(source.description.contains("container SHA-256=" + record.mboxProvenance.containerSha256()));
          Assertions.assertTrue(source.description.contains("entry=" + (index + 1) + "/" + records.size()));
        }
      }
    }
  }

  @Test void lostAndReorderedIdenticalReaderEntriesCannotReconcile() throws Exception {
    JsonObject item = cases().asList().stream().map(value -> value.getAsJsonObject())
        .filter(value -> value.get("id").getAsString().equals("identical")).findFirst().orElseThrow();
    MboxParser.ReadResult expected = parse(item, new ArrayList<>());
    List<EmailRecord> records = read(item);
    Assertions.assertThrows(Common.UserFacingException.class, () -> MboxReadInventory.verify(expected, records.subList(0, 1)));
    java.util.Collections.reverse(records);
    Assertions.assertThrows(Common.UserFacingException.class, () -> MboxReadInventory.verify(expected, records));
  }

  @Test void changedStagedExportCannotReconcileEvenWithUpdatedChecksums() throws Exception {
    JsonObject item = cases().get(0).getAsJsonObject();
    MboxParser.ReadResult expected = parse(item, new ArrayList<>());
    try (Common.JobReport job = Common.beginJob()) {
      List<EmailRecord> records = read(item);
      AssociatedFile source = records.get(0).associatedFiles.stream().filter(file -> "Source".equals(file.afRelationship)).findFirst().orElseThrow();
      Files.writeString(source.dataPath, "changed exported bytes");
      source.storedSize = Math.toIntExact(Files.size(source.dataPath));
      source.checksumMd5 = Common.digestHex(source.dataPath, "MD5");
      source.checksumSha256 = Common.digestHex(source.dataPath, "SHA-256");
      Assertions.assertThrows(Common.UserFacingException.class, () -> MboxReadInventory.verify(expected, records));
    }
  }
}
