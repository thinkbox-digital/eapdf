package eapdf;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/** Reconcile accepted MBOX entry spans with the normalized reader result. */
final class MboxReadInventory {
  record Provenance(String requestedFormat, long containerSize, String containerSha256, int entryCount,
                    MboxParser.Entry entry) {}

  static void verify(MboxParser.ReadResult result, List<EmailRecord> records) throws Exception {
    require(result.entries().size() == records.size(), "Message count differs from accepted mailbox entries");
    long end = 0;
    for (int index = 0; index < records.size(); index++) {
      MboxParser.Entry entry = result.entries().get(index);
      require(entry.ordinal() == index + 1 && entry.envelopeOffset() == end && entry.messageOffset() > end
          && entry.rawSize() >= 0 && entry.paddingSize() >= 0, "Mailbox entry spans are missing, repeated or unordered");
      end = Math.addExact(Math.addExact(entry.messageOffset(), entry.rawSize()), entry.paddingSize());
      EmailRecord record = records.get(index);
      require("mbox".equals(record.sourceType) && Common.nz(record.sourcePath).endsWith("#" + entry.ordinal())
          && Common.nz(record.sourceFilename).endsWith(String.format("_%06d.eml", entry.ordinal())),
          "Normalized record has a different mailbox entry identity");
      List<AssociatedFile> sources = record.associatedFiles.stream().filter(file -> "Source".equals(file.afRelationship)).toList();
      require(sources.size() == 1 && sources.get(0).hasData(), "Mailbox entry has no unique readable exported Source");
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      long size = 0;
      byte[] buffer = new byte[8192];
      try (InputStream input = sources.get(0).openData()) {
        int count;
        while ((count = input.read(buffer)) != -1) {
          size = Math.addExact(size, count);
          ProcessingLimits.checkMessage(size, "exported mailbox entry");
          digest.update(buffer, 0, count);
        }
      }
      require(size == entry.exportedSize() && HexFormat.of().formatHex(digest.digest()).equals(entry.exportedSha256()),
          "Exported source bytes differ from accepted mailbox entry " + entry.ordinal());
    }
    require(end == result.containerSize(), "Mailbox inventory does not cover the complete input byte range");
  }

  static void attach(MboxParser.ReadResult result, List<EmailRecord> records) throws Exception {
    verify(result, records);
    for (int index = 0; index < records.size(); index++) {
      EmailRecord record = records.get(index);
      record.mboxProvenance = new Provenance(result.requestedFormat(), result.containerSize(), result.containerSha256(),
          result.entries().size(), result.entries().get(index));
      for (AssociatedFile file : record.associatedFiles)
        if ("Source".equals(file.afRelationship)) file.description = Common.sourceDescription(record);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw Common.fail("MBOX reader inventory mismatch: " + message);
  }
}
