package eapdf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class MboxReader {
  static List<EmailRecord> readMbox(String path, String globalId, List<String> archiveEmails, boolean debug) throws Exception {
    List<EmailRecord> out = new ArrayList<>();
    String sourceName = Paths.get(path).getFileName().toString();
    String stem = Common.safeFileStem(sourceName.replaceFirst("\\.[^.]+$", ""));
    int[] sequence = {0};
    String format = System.getProperty("eapdf.mboxFormat", System.getenv().getOrDefault("EAPDF_MBOX_FORMAT", "auto"));
    try (var input = Files.newInputStream(Paths.get(path))) {
      MboxParser.ReadResult inventory = MboxParser.read(input, format, messageBytes -> {
      int seq = ++sequence[0];
      String sourceFilename = stem + "_" + String.format("%06d", seq) + ".eml";
      EmailRecord r = EmlReader.readRfc822Bytes(messageBytes, path + "#" + seq, sourceFilename, globalId, archiveEmails, debug);
      r.sourceType = "mbox";
      for (AssociatedFile file : r.associatedFiles) {
        if ("Source".equals(file.afRelationship)) file.description = Common.sourceDescription(r);
      }
      if (Common.nz(r.folderPath).isBlank()) {
        boolean utf8 = Boolean.parseBoolean(EmlReader.mimeSession(messageBytes).getProperty("mail.mime.allowutf8"));
        String message = new String(messageBytes, 0, EmlReader.headerEnd(messageBytes),
            utf8 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
        String labels = Common.decodeMimeWord(headerValue(message, 0, message.length(), "X-Gmail-Labels"));
        if (!Common.nz(labels).isBlank()) r.folderPath = labels;
      }
      if (Common.nz(r.folderPath).isBlank()) r.folderPath = stem;
      out.add(Common.stageRecord(r));
      });
      MboxReadInventory.attach(inventory, out);
    }
    return out;
  }

  static int lineEnd(String text, int start) {
    int n = text.length();
    int i = start;
    while (i < n) {
      char ch = text.charAt(i);
      if (ch == '\n' || ch == '\r') break;
      i++;
    }
    return i;
  }

  static int nextLineStart(String text, int lineEnd) {
    int n = text.length();
    if (lineEnd >= n) return n;
    if (text.charAt(lineEnd) == '\r') lineEnd++;
    if (lineEnd < n && text.charAt(lineEnd) == '\n') lineEnd++;
    return lineEnd;
  }

  static byte[] normalizeMessageBytes(String message) {
    return MboxParser.unquote(Common.nz(message), "mboxo");
  }

  static String headerValue(String text, int start, int end, String headerName) {
    String target = headerName.toLowerCase(Locale.ROOT);
    int pos = start;
    String currentName = "";
    StringBuilder currentValue = new StringBuilder();
    while (pos < end) {
      int lineEnd = lineEnd(text, pos);
      String line = text.substring(pos, lineEnd);
      pos = nextLineStart(text, lineEnd);
      if (line.isEmpty()) break;
      if ((line.startsWith(" ") || line.startsWith("\t")) && !currentName.isBlank()) {
        currentValue.append(' ').append(line.trim());
        continue;
      }
      if (!currentName.isBlank() && currentName.equals(target)) return currentValue.toString().trim();
      int colon = line.indexOf(':');
      if (colon <= 0) {
        currentName = "";
        currentValue.setLength(0);
        continue;
      }
      currentName = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      currentValue.setLength(0);
      currentValue.append(line.substring(colon + 1).trim());
    }
    if (!currentName.isBlank() && currentName.equals(target)) return currentValue.toString().trim();
    return "";
  }

}
