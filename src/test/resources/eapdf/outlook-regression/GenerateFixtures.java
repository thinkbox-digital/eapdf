import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Synthetic property-level regressions; these are not recovered original emails. */
class GenerateFixtures {
  static void properties(DirectoryEntry directory, int headerSize, int recipients,
      Map<Integer, String> strings, Map<Integer, Long> scalars) throws Exception {
    var table = ByteBuffer.allocate(headerSize + 16 * (strings.size() + scalars.size()))
        .order(ByteOrder.LITTLE_ENDIAN);
    if (headerSize == 32) table.putInt(8, recipients).putInt(16, recipients);
    table.position(headerSize);
    for (var entry : strings.entrySet()) {
      byte[] bytes = (entry.getValue() + "\0").getBytes(StandardCharsets.UTF_16LE);
      directory.createDocument(String.format("__substg1.0_%08X", entry.getKey()), new ByteArrayInputStream(bytes));
      table.putInt(entry.getKey()).putInt(6).putInt(bytes.length).putInt(0);
    }
    for (var entry : scalars.entrySet()) table.putInt(entry.getKey()).putInt(6).putLong(entry.getValue());
    directory.createDocument("__properties_version1.0", new ByteArrayInputStream(table.array()));
  }

  static void write(Path path, boolean transport) throws Exception {
    var strings = new LinkedHashMap<Integer, String>();
    strings.put(0x001a001f, "IPM.Note");
    strings.put(0x0037001f, "Synthetic Outlook regression");
    strings.put(0x1035001f, "<synthetic-outlook@example.org>");
    strings.put(0x1000001f, "Synthetic message for timestamp, threading and address regression tests.");
    strings.put(0x0c1a001f, "Delegate");
    strings.put(0x0c1e001f, "SMTP");
    strings.put(0x0c1f001f, "delegate@example.org");
    // The represented author is an unresolved Exchange identity, different from the delegate.
    strings.put(0x0042001f, "Represented author");
    strings.put(0x0064001f, "EX");
    strings.put(0x0065001f, "/O=Synthetic/OU=Regression/CN=Represented");
    strings.put(0x1042001f, "<parent@example.org>");
    strings.put(0x1039001f, "<parent@example.org>");
    strings.put(0x0e04001f, transport ? "Native second; Native first" : "Native first");
    if (transport) strings.put(0x007d001f,
        "From: Header sender <sender@example.org>\r\n"
        + "To: Header first <first@example.org>, Header second <second@example.org>\r\n"
        + "Date: Thu, 22 Oct 2026 12:00:00 +0000\r\n"
        + "Message-ID: <transport-outlook@example.org>\r\n");
    Instant sent = Instant.parse("2019-10-08T14:07:48.3287079Z");
    long ticks = (sent.getEpochSecond() + 11644473600L) * 10000000L + sent.getNano() / 100;
    var scalars = new LinkedHashMap<Integer, Long>();
    scalars.put(0x00390040, ticks);
    scalars.put(0x10160003, 1L);
    try (var storage = new POIFSFileSystem()) {
      properties(storage.getRoot(), 32, transport ? 2 : 1, strings, scalars);
      for (int index = 0; index < (transport ? 2 : 1); index++) {
        String label = transport && index == 0 ? "second" : "first";
        var recipient = new LinkedHashMap<Integer, String>();
        recipient.put(0x3001001f, "Native " + label);
        recipient.put(0x3002001f, "SMTP");
        recipient.put(0x3003001f, label + "@example.org");
        properties(storage.getRoot().createDirectory(String.format("__recip_version1.0_#%08X", index)),
            8, 0, recipient, Map.of(0x0c150003, 1L));
      }
      try (var output = Files.newOutputStream(path)) { storage.writeFilesystem(output); }
    }
  }

  public static void main(String[] args) throws Exception {
    Path directory = Path.of(args[0]);
    Files.createDirectories(directory);
    write(directory.resolve("native-reply.msg"), false);
    write(directory.resolve("transport-recipient-order.msg"), true);
  }
}
